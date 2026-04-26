package com.vrtmv.app.data.camera

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.vrtmv.app.util.YuvToBitmapConverter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * ARCore 기반 [FrameSource] 구현.
 *
 * - [Session] 이 카메라를 점유하고 매 프레임 외부 OES 텍스처에 카메라 영상을 기록
 * - GL thread 의 [onDrawFrame] 에서 [Session.update] → 백그라운드 렌더링 → CPU 이미지 추출
 * - 추출한 YUV 이미지를 ARGB Bitmap 으로 변환하여 별도 워커 스레드에서 리스너에 디스패치
 *   (GL thread 블로킹 방지 — 워커가 바쁘면 해당 프레임은 드롭)
 *
 * 외부에서 anchor hit-test 를 위해 [latestFrame] 으로 최신 프레임에 접근 가능.
 *
 * 라이프사이클: [start] 에서 LifecycleObserver 를 등록하여 onResume/onPause 에서 Session 을 자동 resume/pause.
 */
class ArCoreFrameSource(private val context: Context) : FrameSource, GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "ArCoreFrameSrc"
        private const val DRAW_LOG_EVERY_N = 120  // ~4초마다 1번 GL loop 생존 확인
    }

    private var drawFrameCounter: Int = 0

    private val glSurfaceView: GLSurfaceView = GLSurfaceView(context).apply {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 0, 16, 0)
        // SurfaceView 기본 z-order 는 Activity 윈도우 뒤 — Compose 윈도우가 opaque 하게 덮어 GL 영역이 검정으로 보이는
        // 레이어링 문제 회피. 미디어 오버레이로 올려 Compose(TextureView 계층) 위에 렌더되도록.
        setZOrderMediaOverlay(true)
    }

    /**
     * Session 생성은 생성자에서 즉시 시도 — 실패 시 [UnavailableException] 또는 RuntimeException
     * 이 그대로 전파되어 호출자(CameraScreen)가 CameraX 폴백을 선택하게 한다.
     */
    private val session: Session = Session(context).also { s ->
        val config = Config(s).apply {
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            focusMode = Config.FocusMode.AUTO
        }
        s.configure(config)
        Log.i(TAG, "ARCore Session 생성 완료")
    }

    init {
        glSurfaceView.setRenderer(this)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    override val view: View get() = glSurfaceView

    private val backgroundRenderer = BackgroundRenderer()
    private val listeners = CopyOnWriteArrayList<FrameListener>()

    @Volatile private var closed: Boolean = false
    @Volatile private var sessionRequiresResume: Boolean = true

    /** 표면 사이즈 — Session.setDisplayGeometry 에 전달. */
    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0
    private var displayRotation: Int = Surface.ROTATION_0

    /** 외부에서 hit-test 등을 위해 최신 프레임에 접근하기 위함. GL thread 만 쓰기. */
    @Volatile var latestFrame: Frame? = null
        private set

    /**
     * GL thread 의 매 프레임 호출 — anchor 투영 등 ARCore 전용 후처리 훅.
     * 콜백 내부에서 빠르게 반환할 것 (GL 렌더 thread 점유).
     */
    @Volatile var arFrameCallback: ArFrameCallback? = null

    /** 최신 프레임의 뷰포트 크기 (anchor 투영 시 필요). */
    val viewportSize: Pair<Int, Int> get() = viewportWidth to viewportHeight

    /**
     * 임의 [Pose] 위치에 월드 앵커를 생성한다. 호출자가 [Anchor.detach] 책임.
     * Session 닫힘/실패 시 null.
     */
    fun createAnchor(pose: Pose): Anchor? = try {
        if (closed) null else session.createAnchor(pose)
    } catch (e: Exception) {
        Log.w(TAG, "createAnchor 실패", e)
        null
    }

    /** GL thread 블로킹 방지용 워커 — 큐 0, 바쁜 프레임은 즉시 reject. */
    private val dispatchExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        ThreadPoolExecutor.AbortPolicy()
    )

    private var lifecycleOwner: LifecycleOwner? = null
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> resumeInternal()
            Lifecycle.Event.ON_PAUSE -> pauseInternal()
            Lifecycle.Event.ON_DESTROY -> close()
            else -> Unit
        }
    }

    /** 라이프사이클에 바인딩 — onResume/onPause 에서 자동 resume/pause. */
    override fun start(lifecycleOwner: LifecycleOwner) {
        if (closed) {
            Log.w(TAG, "이미 close 된 소스 — start 무시")
            return
        }
        this.lifecycleOwner = lifecycleOwner
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        // 이미 RESUMED 라면 즉시 resume
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            resumeInternal()
        }
    }

    private fun resumeInternal() {
        try {
            session.resume()
            glSurfaceView.onResume()
            sessionRequiresResume = false
            Log.i(TAG, "ARCore Session resume 완료")
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "ARCore 카메라 사용 불가", e)
        }
    }

    private fun pauseInternal() {
        try {
            glSurfaceView.onPause()
            session.pause()
            sessionRequiresResume = true
            Log.i(TAG, "ARCore Session pause 완료")
        } catch (e: Exception) {
            Log.w(TAG, "ARCore pause 오류", e)
        }
    }

    override fun stop() {
        pauseInternal()
        lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
        lifecycleOwner = null
    }

    override fun addListener(listener: FrameListener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: FrameListener) {
        listeners.remove(listener)
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        try {
            session.close()
        } catch (e: Exception) {
            Log.w(TAG, "Session close 오류", e)
        }
        latestFrame = null
        listeners.clear()
        try {
            dispatchExecutor.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "dispatchExecutor shutdown 오류", e)
        }
    }

    // ──────── GLSurfaceView.Renderer ────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
        // Session 이 아직 없을 수도 있지만, 여기서 텍스처를 만들어 두고 onDrawFrame 에서 매번 setCameraTextureName.
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        @Suppress("DEPRECATION")
        displayRotation = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (closed) return
        if (sessionRequiresResume) return
        if (viewportWidth == 0 || viewportHeight == 0) return

        try {
            session.setCameraTextureName(backgroundRenderer.textureName)
            session.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)

            val frame = session.update()
            latestFrame = frame

            backgroundRenderer.updateDisplayGeometry(frame)
            backgroundRenderer.draw()

            if ((drawFrameCounter++ % DRAW_LOG_EVERY_N) == 0) {
                Log.d(TAG, "onDrawFrame #$drawFrameCounter viewport=${viewportWidth}x$viewportHeight camTS=${frame.camera.trackingState}")
            }

            // tracking 미초기화 시에도 카메라 배경/프레임 추출은 가능. anchor 만 불가.

            // ARCore 전용 후처리 (anchor 투영 등) — VM 가 등록한 콜백 실행
            try {
                arFrameCallback?.onArFrame(frame, viewportWidth, viewportHeight)
            } catch (e: Exception) {
                Log.e(TAG, "arFrameCallback 오류", e)
            }

            dispatchListenerFrame(frame)
        } catch (e: Throwable) {
            Log.e(TAG, "onDrawFrame 오류", e)
        }
    }

    /** 카메라 이미지를 추출해 워커에 디스패치. 워커가 바쁘면 즉시 드롭 (frame skip 효과). */
    private fun dispatchListenerFrame(frame: Frame) {
        if (listeners.isEmpty()) return
        val image = try {
            frame.acquireCameraImage()
        } catch (e: Throwable) {
            // NotYetAvailableException 등 — 첫 몇 프레임은 정상적으로 발생
            return
        }
        val ts = System.currentTimeMillis()
        try {
            dispatchExecutor.execute {
                var bitmap: android.graphics.Bitmap? = null
                try {
                    bitmap = YuvToBitmapConverter.convert(image, rotationDegrees = 90)
                    if (bitmap != null) {
                        for (l in listeners) {
                            try {
                                l.onFrame(bitmap, ts)
                            } catch (e: Exception) {
                                Log.e(TAG, "리스너 처리 실패", e)
                            }
                        }
                    }
                } finally {
                    image.close()
                    bitmap?.recycle()
                }
            }
        } catch (e: RejectedExecutionException) {
            // 워커 바쁨 — 이 프레임은 드롭
            image.close()
        }
    }
}

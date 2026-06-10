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
 * ARCore [Session] 기반 [FrameSource]. GL thread 의 [onDrawFrame] 에서 Session.update →
 * 카메라 배경 렌더링 → CPU 이미지를 워커 스레드로 디스패치 (워커 바쁘면 드롭하여 GL 블로킹 방지).
 * 외부에서는 [latestFrame] 으로 hit-test 등 ARCore 전용 작업 가능.
 */
class ArCoreFrameSource(private val context: Context) : FrameSource, GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "ArCoreFrameSrc"
        private const val DRAW_LOG_EVERY_N = 120  // ~4초마다 GL loop 생존 확인
    }

    private var drawFrameCounter: Int = 0

    private val glSurfaceView: GLSurfaceView = GLSurfaceView(context).apply {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 0, 16, 0)
        // SurfaceView 기본 z-order 는 Activity 윈도우 뒤 — Compose 가 opaque 로 덮어 GL 영역이
        // 검정으로 보이는 레이어링 회피를 위해 미디어 오버레이로 올림.
        setZOrderMediaOverlay(true)
    }

    // 생성자에서 즉시 시도 — 실패는 호출자(CameraScreen) 의 CameraX 폴백 트리거.
    private val session: Session = Session(context).also { s ->
        val config = Config(s).apply {
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            focusMode = Config.FocusMode.AUTO
            // 평면이 없는 영역(전체 이미지 모드의 화면 중앙 등)에서도 즉시 추종 가능한 anchor 생성용.
            instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
        }
        s.configure(config)
        Log.i(TAG, "ARCore Session 생성 완료 (InstantPlacement=LOCAL_Y_UP)")
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

    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0
    private var displayRotation: Int = Surface.ROTATION_0

    @Volatile var latestFrame: Frame? = null
        private set

    @Volatile var arFrameCallback: ArFrameCallback? = null

    val viewportSize: Pair<Int, Int> get() = viewportWidth to viewportHeight

    fun createAnchor(pose: Pose): Anchor? = try {
        if (closed) null else session.createAnchor(pose)
    } catch (e: Exception) {
        Log.w(TAG, "createAnchor 실패", e)
        null
    }

    // 큐 0 의 SynchronousQueue + AbortPolicy — 워커 바쁘면 즉시 reject 되어 호출자가 프레임 드롭.
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

    override fun start(lifecycleOwner: LifecycleOwner) {
        if (closed) {
            Log.w(TAG, "이미 close 된 소스 — start 무시")
            return
        }
        this.lifecycleOwner = lifecycleOwner
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
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
            image.close()
        }
    }
}

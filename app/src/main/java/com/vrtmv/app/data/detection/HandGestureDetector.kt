package com.vrtmv.app.data.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.vrtmv.app.domain.model.AssetRegistry
import com.vrtmv.app.util.AssetPathResolver
import java.nio.ByteBuffer
import kotlin.math.hypot
import kotlin.math.max

/**
 * MediaPipe GestureRecognizer 기반 손 포인팅 검출.
 *
 * 동작 방식:
 * 1) 카메라 프레임을 연속 수신 (LIVE_STREAM 모드)
 * 2) 검지 손가락이 확장된 포인팅 자세 감지 (방향 무관, 거리 기반 판별)
 * 3) 손가락 끝(INDEX_FINGER_TIP, 랜드마크 8번) 좌표가 [HOLD_DURATION_MS] 동안 유지되면
 *    [onPointingConfirmed] 콜백 호출 (정규화 0~1 좌표)
 * 4) 프레임에서 포인팅이 감지되지 않으면 즉시 [onPointingLost] 콜백으로 UI 포인트를 숨김
 *
 * 앵커는 롤링 평균으로 따라가므로 손이 천천히 움직여도 홀드가 유지된다.
 */
class HandGestureDetector(
    private val context: Context,
    private val assetPathResolver: AssetPathResolver,
    private val onPointingUpdate: (normX: Float, normY: Float, progress: Float) -> Unit,
    private val onPointingConfirmed: (normX: Float, normY: Float) -> Unit,
    private val onPointingLost: () -> Unit
) {
    companion object {
        private const val TAG = "HandGesture"
        private const val INDEX_FINGER_TIP = 8

        // 2프레임당 1회 처리로 부하 절반 (30fps → ~15fps).
        // 홀드는 시간 기반이므로 fps 변동과 무관하게 동작.
        private const val FRAME_SKIP = 2

        // 홀드 지속 시간 (ms) — wall clock. fps와 무관.
        private const val HOLD_DURATION_MS = 500L

        // 정규화 좌표 드리프트 허용. 6% = 1080p에서 약 65px.
        // 3%(과거)는 손떨림에도 리셋되어 끊김 원인이었음.
        private const val DRIFT_THRESHOLD = 0.06f

        // 앵커 롤링 평균 가중치 — 0.7 * 기존 + 0.3 * 신규 (저역 필터).
        private const val ANCHOR_ALPHA = 0.3f

        private const val MIN_CONFIDENCE = 0.4f
    }

    private var recognizer: GestureRecognizer? = null
    @Volatile
    var paused: Boolean = false

    // 홀드 시작 시각 (0 = 홀드 없음)
    private var holdStartMs: Long = 0L
    private var anchorX = 0f
    private var anchorY = 0f
    private var frameCounter: Long = 0  // 단조증가 타임스탬프 (LIVE_STREAM 요구사항)
    private var frameSkipCounter = 0

    // UI 포인트 가시성 상태 — 중복 onPointingLost 호출을 방지하기 위한 로컬 플래그
    private var pointVisible: Boolean = false

    init {
        setup()
    }

    private fun setup() {
        val absolutePath = assetPathResolver.findAssetPath(AssetRegistry.GESTURE.fileName)
        if (absolutePath == null) {
            Log.w(TAG, "${AssetRegistry.GESTURE.fileName} 없음 — 제스처 비활성화 (Intro 다운로드 미완료?)")
            return
        }

        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(absolutePath)
                .setDelegate(Delegate.CPU)
                .build()

            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(MIN_CONFIDENCE)
                .setMinTrackingConfidence(MIN_CONFIDENCE)
                .setResultListener(::onResult)
                .setErrorListener { e -> Log.e(TAG, "제스처 인식 오류", e) }
                .build()

            recognizer = GestureRecognizer.createFromOptions(context, options)
            Log.i(TAG, "HandGestureDetector 초기화 완료: $absolutePath")
        } catch (e: Exception) {
            Log.e(TAG, "HandGestureDetector 초기화 실패", e)
        }
    }

    /** Analyzer 스레드에서 호출. ImageProxy는 호출자가 닫아야 함. */
    fun process(imageProxy: ImageProxy) {
        if (paused) return
        val rec = recognizer ?: return

        if (frameSkipCounter++ % FRAME_SKIP != 0) return

        try {
            val rawBitmap = imageProxyToBitmap(imageProxy) ?: return
            val rotation = imageProxy.imageInfo.rotationDegrees

            val upright = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                if (rotated != rawBitmap) rawBitmap.recycle()
                rotated
            } else {
                rawBitmap
            }

            // 입력은 CameraX에서 이미 640x480으로 설정되어 있어 heap 부담 낮음 —
            // 추가 다운스케일 없이 그대로 전달 (MediaPipe 내부가 전처리 담당).
            val mpImage = BitmapImageBuilder(upright).build()
            rec.recognizeAsync(mpImage, ++frameCounter)
        } catch (e: Exception) {
            Log.e(TAG, "제스처 프레임 처리 실패", e)
        }
    }

    private fun onResult(
        result: GestureRecognizerResult,
        @Suppress("UNUSED_PARAMETER") image: com.google.mediapipe.framework.image.MPImage
    ) {
        if (paused) {
            hidePoint()
            return
        }

        val landmarks = result.landmarks().firstOrNull()
        val gesture = result.gestures().firstOrNull()?.firstOrNull()
        val isPointing = gesture?.categoryName() == "Pointing_Up" || isPointingFromLandmarks(landmarks)

        if (landmarks == null || !isPointing) {
            // 손가락 미검출/비포인팅 — 즉시 UI 포인트 숨김
            hidePoint()
            return
        }

        val tip = landmarks.getOrNull(INDEX_FINGER_TIP) ?: run {
            hidePoint()
            return
        }
        val x = tip.x()
        val y = tip.y()
        val now = System.currentTimeMillis()

        if (holdStartMs == 0L) {
            // 홀드 시작
            holdStartMs = now
            anchorX = x
            anchorY = y
        } else {
            val drift = max(kotlin.math.abs(x - anchorX), kotlin.math.abs(y - anchorY))
            if (drift > DRIFT_THRESHOLD) {
                // 큰 움직임 — 앵커와 타이머 모두 리셋하지만 UI 포인트는 유지
                holdStartMs = now
                anchorX = x
                anchorY = y
            } else {
                // 소폭 드리프트 — 롤링 평균으로 앵커가 손을 따라감
                anchorX = anchorX * (1f - ANCHOR_ALPHA) + x * ANCHOR_ALPHA
                anchorY = anchorY * (1f - ANCHOR_ALPHA) + y * ANCHOR_ALPHA
            }
        }

        val elapsed = now - holdStartMs
        val progress = (elapsed.toFloat() / HOLD_DURATION_MS).coerceIn(0f, 1f)
        onPointingUpdate(x, y, progress)
        pointVisible = true

        if (elapsed >= HOLD_DURATION_MS) {
            onPointingConfirmed(x, y)
            resetHold()
        }
    }

    /**
     * 검지 펴짐 + 나머지 접힘을 거리 기반으로 판정한다.
     * Y축 비교(구버전)는 수평 포인팅을 놓쳤기 때문에 방향 무관한 길이 비율로 전환.
     * - 편 손가락: TIP이 MCP로부터 PIP보다 훨씬 멀리 뻗음
     * - 접힌 손가락: TIP이 MCP 근방으로 말려 들어가 PIP보다 가까움
     */
    private fun isPointingFromLandmarks(landmarks: List<NormalizedLandmark>?): Boolean {
        if (landmarks == null || landmarks.size < 21) return false

        fun dist(a: Int, b: Int): Float {
            val dx = landmarks[a].x() - landmarks[b].x()
            val dy = landmarks[a].y() - landmarks[b].y()
            return hypot(dx, dy)
        }

        // 검지: TIP(8)이 MCP(5)로부터 PIP(6) 대비 1.5배 이상 멀면 편 상태
        val indexExtended = dist(8, 5) > dist(6, 5) * 1.5f

        // 나머지 세 손가락: TIP이 MCP 대비 PIP의 1.1배 이하면 접힌 상태로 간주
        val middleFolded = dist(12, 9) < dist(10, 9) * 1.1f
        val ringFolded = dist(16, 13) < dist(14, 13) * 1.1f
        val pinkyFolded = dist(20, 17) < dist(18, 17) * 1.1f

        return indexExtended && middleFolded && ringFolded && pinkyFolded
    }

    /** 손가락 미검출 시 호출. 내부 홀드 리셋 + UI 포인트 숨김(중복 방지). */
    private fun hidePoint() {
        resetHold()
        if (pointVisible) {
            pointVisible = false
            onPointingLost()
        }
    }

    private fun resetHold() {
        holdStartMs = 0L
    }

    fun close() {
        try {
            recognizer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "제스처 인식기 해제 오류", e)
        }
        recognizer = null
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val planes = imageProxy.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * imageProxy.width

            val bitmap = Bitmap.createBitmap(
                imageProxy.width + rowPadding / pixelStride,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, imageProxy.width, imageProxy.height)
                if (cropped != bitmap) bitmap.recycle()
                cropped
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "ImageProxy→Bitmap 변환 실패", e)
            null
        }
    }
}

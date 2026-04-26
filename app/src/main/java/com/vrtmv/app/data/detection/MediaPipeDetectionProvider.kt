package com.vrtmv.app.data.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.vrtmv.app.domain.model.DetectedObject

/**
 * MediaPipe EfficientDet-Lite2 기반 온디맨드 객체 검출기.
 *
 * 카메라에서 매 프레임을 버퍼링하고(updateFrame),
 * 사용자가 터치할 때만 검출을 실행한다(detectNow).
 *
 * 프레임은 [com.vrtmv.app.data.camera.FrameSource] 에서 이미 upright 상태로 들어오므로
 * 회전 처리는 하지 않는다. EfficientDet-Lite2 모델은 COCO 80 카테고리를 다룬다.
 */
class MediaPipeDetectionProvider(private val context: Context) : DetectionProvider {

    companion object {
        private const val TAG = "MediaPipeDet"
        private const val MODEL_FILE = "efficientdet_lite2.tflite"  // assets에 번들된 모델
        private const val MAX_RESULTS = 10      // 최대 검출 객체 수
        private const val SCORE_THRESHOLD = 0.3f // 최소 신뢰도 임계값
        private const val FRAME_SKIP = 3         // 30fps 입력을 ~10fps로 다운샘플링 (배터리)
    }

    private var detector: ObjectDetector? = null  // MediaPipe 검출기 인스턴스
    private var latestBitmap: Bitmap? = null       // 최신 프레임 버퍼 (upright)
    private val bitmapLock = Any()                 // latestBitmap 동기화 락

    private var frameSkipCounter = 0               // 프레임 스킵 카운터 (배터리 최적화)
    @Volatile override var paused: Boolean = false  // 추론 중 프레임 처리 중단 플래그

    init {
        setupDetector()
    }

    /** MediaPipe ObjectDetector를 초기화한다 */
    private fun setupDetector() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_FILE)
                .setDelegate(Delegate.CPU) // CPU 추론 (GPU 미지원 기기 호환)
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE) // 단일 이미지 모드 (온디맨드)
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(SCORE_THRESHOLD)
                .build()

            detector = ObjectDetector.createFromOptions(context, options)
            Log.d(TAG, "ObjectDetector 초기화 완료: $MODEL_FILE")
        } catch (e: Exception) {
            Log.e(TAG, "ObjectDetector 초기화 실패", e)
        }
    }

    /**
     * 최신 카메라 프레임을 버퍼에 저장한다.
     * 검출은 수행하지 않는다 — detectNow()에서만 실행.
     */
    override fun updateFrame(bitmap: Bitmap, timestampMs: Long) {
        // 추론 중이면 프레임 처리 완전 중단 (배터리 절약)
        if (paused) return

        // 3프레임마다 1회만 처리 (~10fps, 배터리 최적화)
        if (frameSkipCounter++ % FRAME_SKIP != 0) return

        try {
            // FrameSource는 콜백 직후 비트맵을 recycle하므로 사본 보존 필수
            val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

            synchronized(bitmapLock) {
                latestBitmap?.recycle()
                latestBitmap = copy
            }
        } catch (e: Exception) {
            Log.e(TAG, "프레임 업데이트 실패", e)
        }
    }

    /**
     * 현재 버퍼된 프레임에서 즉시 검출을 실행한다.
     * 사용자 터치 시 호출. 바운딩박스는 upright 비트맵의 좌표계.
     */
    override fun detectNow(): DetectionResult? {
        val det = detector ?: return null

        // 동기화 블록 내에서 복사본 생성 (원본 recycle 방지)
        val frameCopy = synchronized(bitmapLock) {
            val bitmap = latestBitmap ?: return null
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        }

        return try {
            val mpImage = BitmapImageBuilder(frameCopy).build()
            val result = det.detect(mpImage)

            // MediaPipe 검출 결과를 도메인 모델로 변환
            val detectedObjects = result.detections().map { detection ->
                val box = detection.boundingBox()
                val category = detection.categories().firstOrNull()
                DetectedObject(
                    boundingBox = RectF(box.left, box.top, box.right, box.bottom),
                    label = category?.categoryName() ?: "unknown",
                    confidence = category?.score() ?: 0f
                )
            }

            Log.d(TAG, "${detectedObjects.size}개 객체 검출 (${frameCopy.width}x${frameCopy.height})")

            DetectionResult(
                objects = detectedObjects,
                bitmap = frameCopy,
                imageWidth = frameCopy.width,
                imageHeight = frameCopy.height
            )
        } catch (e: Exception) {
            Log.e(TAG, "검출 실패", e)
            null
        }
    }

    /** 최신 프레임 사본만 반환 — 검출 미수행. */
    override fun captureFrame(): Bitmap? {
        return synchronized(bitmapLock) {
            val bitmap = latestBitmap ?: return null
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        }
    }

    /** 리소스 해제. DisposableEffect에서 호출 */
    override fun close() {
        detector?.close()
        detector = null
        synchronized(bitmapLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
    }
}

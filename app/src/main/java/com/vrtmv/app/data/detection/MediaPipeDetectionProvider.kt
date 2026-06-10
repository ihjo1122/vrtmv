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

class MediaPipeDetectionProvider(private val context: Context) : DetectionProvider {

    companion object {
        private const val TAG = "MediaPipeDet"
        private const val MODEL_FILE = "efficientdet_lite2.tflite"
        private const val MAX_RESULTS = 10
        private const val SCORE_THRESHOLD = 0.3f
        // 30fps 입력을 ~10fps 로 다운샘플링 — 배터리/발열 절감
        private const val FRAME_SKIP = 3
    }

    private var detector: ObjectDetector? = null
    private var latestBitmap: Bitmap? = null
    private val bitmapLock = Any()

    private var frameSkipCounter = 0
    @Volatile override var paused: Boolean = false

    init {
        setupDetector()
    }

    private fun setupDetector() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_FILE)
                .setDelegate(Delegate.CPU)  // GPU 미지원 기기 호환
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(SCORE_THRESHOLD)
                .build()

            detector = ObjectDetector.createFromOptions(context, options)
            Log.d(TAG, "ObjectDetector 초기화 완료: $MODEL_FILE")
        } catch (e: Exception) {
            Log.e(TAG, "ObjectDetector 초기화 실패", e)
        }
    }

    override fun updateFrame(bitmap: Bitmap, timestampMs: Long) {
        if (paused) return
        if (frameSkipCounter++ % FRAME_SKIP != 0) return

        try {
            // FrameSource 가 콜백 직후 원본을 recycle 하므로 사본 보존 필수
            val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

            synchronized(bitmapLock) {
                latestBitmap?.recycle()
                latestBitmap = copy
            }
        } catch (e: Exception) {
            Log.e(TAG, "프레임 업데이트 실패", e)
        }
    }

    override fun detectNow(): DetectionResult? {
        val det = detector ?: return null

        // 동기화 블록 내에서 사본 — 외부에서 latestBitmap recycle 경합 방지
        val frameCopy = synchronized(bitmapLock) {
            val bitmap = latestBitmap ?: return null
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        }

        return try {
            val mpImage = BitmapImageBuilder(frameCopy).build()
            val result = det.detect(mpImage)

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

    override fun detectOnBitmap(bitmap: Bitmap): List<DetectedObject> {
        val det = detector ?: return emptyList()
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = det.detect(mpImage)
            result.detections().map { detection ->
                val box = detection.boundingBox()
                val category = detection.categories().firstOrNull()
                DetectedObject(
                    boundingBox = RectF(box.left, box.top, box.right, box.bottom),
                    label = category?.categoryName() ?: "unknown",
                    confidence = category?.score() ?: 0f
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "ROI 검출 실패", e)
            emptyList()
        }
    }

    override fun captureFrame(): Bitmap? {
        return synchronized(bitmapLock) {
            val bitmap = latestBitmap ?: return null
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        }
    }

    override fun close() {
        detector?.close()
        detector = null
        synchronized(bitmapLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
    }
}

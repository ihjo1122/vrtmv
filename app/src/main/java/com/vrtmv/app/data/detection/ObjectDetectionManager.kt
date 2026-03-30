package com.vrtmv.app.data.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.vrtmv.app.domain.model.DetectedObject
import java.nio.ByteBuffer

/**
 * 온디맨드 객체 검출 관리자.
 *
 * 카메라에서 매 프레임을 버퍼링하고(updateFrame),
 * 사용자가 터치할 때만 검출을 실행한다(detectNow).
 *
 * 프레임은 저장 시점에 회전이 적용되어 항상 upright portrait 상태로 유지.
 * MediaPipe EfficientDet-Lite2 모델 사용 (COCO 80 카테고리).
 */
class ObjectDetectionManager(private val context: Context) {

    companion object {
        private const val TAG = "ObjectDetection"
        private const val MODEL_FILE = "efficientdet_lite2.tflite"  // assets에 번들된 모델
        private const val MAX_RESULTS = 10      // 최대 검출 객체 수
        private const val SCORE_THRESHOLD = 0.3f // 최소 신뢰도 임계값
    }

    private var detector: ObjectDetector? = null  // MediaPipe 검출기 인스턴스
    private var latestBitmap: Bitmap? = null       // 최신 프레임 버퍼 (upright)
    private val bitmapLock = Any()                 // latestBitmap 동기화 락

    private var frameSkipCounter = 0               // 프레임 스킵 카운터 (배터리 최적화)
    @Volatile var paused: Boolean = false           // 추론 중 프레임 처리 중단 플래그

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
     * 센서 회전을 적용하여 upright portrait로 변환 후 저장.
     * 검출은 수행하지 않는다 — detectNow()에서만 실행.
     */
    fun updateFrame(imageProxy: ImageProxy) {
        // 추론 중이면 프레임 처리 완전 중단 (배터리 절약)
        if (paused) {
            imageProxy.close()
            return
        }

        // 3프레임마다 1회만 처리 (~10fps, 배터리 최적화)
        if (frameSkipCounter++ % 3 != 0) {
            imageProxy.close()
            return
        }

        try {
            val rawBitmap = imageProxyToBitmap(imageProxy) ?: return
            val rotation = imageProxy.imageInfo.rotationDegrees

            // 센서 회전 적용 (대부분의 Android 기기에서 90도)
            val upright = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                if (rotated != rawBitmap) rawBitmap.recycle()
                rotated
            } else {
                rawBitmap
            }

            // 이전 프레임 해제 후 새 프레임 저장 (동기화)
            synchronized(bitmapLock) {
                latestBitmap?.recycle()
                latestBitmap = upright
            }
        } catch (e: Exception) {
            Log.e(TAG, "프레임 업데이트 실패", e)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * 검출 결과 데이터 클래스.
     * @param objects 검출된 객체 목록
     * @param bitmap 검출에 사용된 프레임 (크롭용으로 보관)
     * @param imageWidth 이미지 너비
     * @param imageHeight 이미지 높이
     */
    data class DetectionResult(
        val objects: List<DetectedObject>,
        val bitmap: Bitmap,
        val imageWidth: Int,
        val imageHeight: Int
    )

    /**
     * 현재 버퍼된 프레임에서 즉시 검출을 실행한다.
     * 사용자 터치 시 호출. 바운딩박스는 upright 비트맵의 좌표계.
     *
     * @return 검출 결과, 또는 프레임/검출기가 없으면 null
     */
    fun detectNow(): DetectionResult? {
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

    /**
     * CameraX ImageProxy를 ARGB_8888 Bitmap으로 변환한다.
     * RGBA_8888 출력 포맷의 ImageProxy에서 직접 픽셀 버퍼를 복사.
     */
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

            // row padding이 있으면 실제 이미지 영역만 잘라냄
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

    /** 리소스 해제. DisposableEffect에서 호출 */
    fun close() {
        detector?.close()
        detector = null
        synchronized(bitmapLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
    }
}

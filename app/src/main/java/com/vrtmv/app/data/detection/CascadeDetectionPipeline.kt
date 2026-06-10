package com.vrtmv.app.data.detection

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.vrtmv.app.domain.model.DetectedObject
import com.vrtmv.app.domain.model.DetectorKind
import com.vrtmv.app.util.CoordinateMapper
import com.vrtmv.app.util.GazeTargetResolver
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MediaPipe(1차 후보) → 선택 객체 영역 → YOLO(라벨 재확인) 직렬 결합.
 * MediaPipe 결과로 박스/리스트를 UI 에 표시하고, 선택 객체 한정으로 YOLO 를 ROI 추론에 사용해
 * confidence 가 더 높으면 라벨을 교체한다. boundingBox 자체는 MediaPipe 원본 유지 — 크롭/앵커 일관성.
 */
@Singleton
class CascadeDetectionPipeline @Inject constructor(
    private val registry: DetectionProviderRegistry
) {

    companion object {
        private const val TAG = "Cascade"
        // 선택 객체 박스를 YOLO 에 넘기기 전 확장 비율. 작은 박스는 컨텍스트가 부족해 라벨 신뢰도가 낮음.
        private const val YOLO_ROI_PADDING_RATIO = 0.20f
        private const val MIN_REGION_PX = 8
    }

    data class Result(
        val mediaPipeResult: DetectionResult?,
        val mediaPipeMs: Long,
        val selected: DetectedObject?,        // YOLO 라벨 재확인이 반영된 최종
        val yoloMs: Long,                     // 0 if YOLO 미실행
        val cascadeUsedYolo: Boolean
    )

    fun runForTap(tapPoint: Offset, viewWidth: Float, viewHeight: Float): Result {
        val mp = registry.get(DetectorKind.MEDIAPIPE)
        val yolo = registry.get(DetectorKind.YOLO)

        val tMp0 = System.currentTimeMillis()
        val mpResult = mp.detectNow()
        val mediaPipeMs = System.currentTimeMillis() - tMp0
        if (mpResult == null) {
            return Result(null, mediaPipeMs, null, 0L, false)
        }

        val mapper = CoordinateMapper(mpResult.imageWidth, mpResult.imageHeight, viewWidth, viewHeight)
        val resolved = GazeTargetResolver.resolve(tapPoint, mpResult.objects, mapper)
        if (resolved == null) {
            return Result(mpResult, mediaPipeMs, null, 0L, false)
        }

        return refineWithYolo(mpResult, mediaPipeMs, resolved, yolo)
    }

    /**
     * 이미 검출된 객체 1개에 대해서 YOLO 재확인만 수행. 제스처 경로에서 사용.
     */
    fun refineSelection(mpResult: DetectionResult, mediaPipeMs: Long, selected: DetectedObject): Result {
        val yolo = registry.get(DetectorKind.YOLO)
        return refineWithYolo(mpResult, mediaPipeMs, selected, yolo)
    }

    private fun refineWithYolo(
        mpResult: DetectionResult,
        mediaPipeMs: Long,
        resolved: DetectedObject,
        yolo: DetectionProvider
    ): Result {
        val frame = mpResult.bitmap
        val expanded = expandBox(resolved.boundingBox, YOLO_ROI_PADDING_RATIO, frame.width, frame.height)
        val regionW = (expanded.right - expanded.left).roundToInt()
        val regionH = (expanded.bottom - expanded.top).roundToInt()
        if (regionW < MIN_REGION_PX || regionH < MIN_REGION_PX) {
            return Result(mpResult, mediaPipeMs, resolved, 0L, false)
        }

        var regionBitmap: Bitmap? = null
        return try {
            regionBitmap = Bitmap.createBitmap(
                frame,
                expanded.left.roundToInt(),
                expanded.top.roundToInt(),
                regionW,
                regionH
            )

            val tY0 = System.currentTimeMillis()
            val yoloObjs = yolo.detectOnBitmap(regionBitmap)
            val yoloMs = System.currentTimeMillis() - tY0

            val yoloBest = yoloObjs.maxByOrNull { it.confidence }
            val (finalSelected, usedYolo) = if (yoloBest != null && yoloBest.confidence > resolved.confidence) {
                Log.d(
                    TAG,
                    "YOLO 라벨 채택: ${resolved.label}(%.2f) → ${yoloBest.label}(%.2f)".format(
                        resolved.confidence,
                        yoloBest.confidence
                    )
                )
                DetectedObject(
                    boundingBox = resolved.boundingBox,
                    label = yoloBest.label,
                    confidence = yoloBest.confidence
                ) to true
            } else {
                resolved to false
            }

            Result(mpResult, mediaPipeMs, finalSelected, yoloMs, usedYolo)
        } catch (e: Exception) {
            Log.w(TAG, "YOLO 재확인 실패 — MediaPipe 결과 유지", e)
            Result(mpResult, mediaPipeMs, resolved, 0L, false)
        } finally {
            regionBitmap?.recycle()
        }
    }

    private fun expandBox(box: RectF, paddingRatio: Float, frameW: Int, frameH: Int): RectF {
        val padW = box.width() * paddingRatio
        val padH = box.height() * paddingRatio
        val left = max(0f, box.left - padW)
        val top = max(0f, box.top - padH)
        val right = min(frameW.toFloat(), box.right + padW)
        val bottom = min(frameH.toFloat(), box.bottom + padH)
        return RectF(left, top, right, bottom)
    }
}

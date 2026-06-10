package com.vrtmv.app.util

import androidx.compose.ui.geometry.Offset
import com.vrtmv.app.domain.model.DetectedObject
import kotlin.math.sqrt

/**
 * 터치 좌표 → 객체 매칭. 우선순위:
 *   1) 좌표를 포함하는 박스 중 가장 작은 것 (가장 구체적인 객체)
 *   2) 없으면 중심이 가장 가까운 객체 — [MAX_DISTANCE_THRESHOLD] 이내 한정
 */
object GazeTargetResolver {

    // FHD+ 화면 기준 ~7%
    private const val MAX_DISTANCE_THRESHOLD = 150f

    fun resolve(
        gazePoint: Offset,
        detectedObjects: List<DetectedObject>,
        coordinateMapper: CoordinateMapper
    ): DetectedObject? {
        if (detectedObjects.isEmpty()) return null

        val containingObjects = detectedObjects.filter { obj ->
            val viewRect = coordinateMapper.mapToView(obj.boundingBox)
            viewRect.contains(gazePoint)
        }

        if (containingObjects.isNotEmpty()) {
            return containingObjects.minByOrNull { obj ->
                val viewRect = coordinateMapper.mapToView(obj.boundingBox)
                viewRect.width * viewRect.height
            }
        }

        val nearest = detectedObjects.minByOrNull { obj ->
            val viewRect = coordinateMapper.mapToView(obj.boundingBox)
            val center = Offset(viewRect.left + viewRect.width / 2, viewRect.top + viewRect.height / 2)
            distance(gazePoint, center)
        }

        if (nearest != null) {
            val viewRect = coordinateMapper.mapToView(nearest.boundingBox)
            val center = Offset(viewRect.left + viewRect.width / 2, viewRect.top + viewRect.height / 2)
            if (distance(gazePoint, center) <= MAX_DISTANCE_THRESHOLD) {
                return nearest
            }
        }

        return null
    }

    private fun distance(a: Offset, b: Offset): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}

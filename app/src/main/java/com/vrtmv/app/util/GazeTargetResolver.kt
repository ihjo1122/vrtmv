package com.vrtmv.app.util

import androidx.compose.ui.geometry.Offset
import com.vrtmv.app.domain.model.DetectedObject
import kotlin.math.sqrt

/**
 * 터치/시선 좌표 기반 객체 선택 알고리즘.
 *
 * 선택 우선순위:
 * 1. 터치 좌표를 포함하는 바운딩박스 중 가장 작은(구체적인) 것 선택
 * 2. 포함하는 박스가 없으면 중심점이 가장 가까운 객체 선택 (임계값 이내)
 * 3. 임계값 초과 시 null 반환 (선택 없음)
 */
object GazeTargetResolver {

    /** 최근접 탐색 시 최대 허용 거리 (픽셀) */
    private const val MAX_DISTANCE_THRESHOLD = 150f

    /**
     * 터치 좌표에 해당하는 가장 적합한 객체를 선택한다.
     *
     * @param gazePoint 터치/시선 좌표 (화면 좌표계)
     * @param detectedObjects 검출된 객체 목록
     * @param coordinateMapper 이미지↔화면 좌표 변환기
     * @return 선택된 객체, 또는 매칭 없으면 null
     */
    fun resolve(
        gazePoint: Offset,
        detectedObjects: List<DetectedObject>,
        coordinateMapper: CoordinateMapper
    ): DetectedObject? {
        if (detectedObjects.isEmpty()) return null

        // 1단계: 터치 좌표를 포함하는 모든 바운딩박스 찾기
        val containingObjects = detectedObjects.filter { obj ->
            val viewRect = coordinateMapper.mapToView(obj.boundingBox)
            viewRect.contains(gazePoint)
        }

        if (containingObjects.isNotEmpty()) {
            // 가장 작은 바운딩박스 선택 (가장 구체적인 객체)
            return containingObjects.minByOrNull { obj ->
                val viewRect = coordinateMapper.mapToView(obj.boundingBox)
                viewRect.width * viewRect.height
            }
        }

        // 2단계: 포함하는 박스 없음 → 중심점이 가장 가까운 객체 탐색
        val nearest = detectedObjects.minByOrNull { obj ->
            val viewRect = coordinateMapper.mapToView(obj.boundingBox)
            val center = Offset(viewRect.left + viewRect.width / 2, viewRect.top + viewRect.height / 2)
            distance(gazePoint, center)
        }

        // 임계값 이내일 때만 선택
        if (nearest != null) {
            val viewRect = coordinateMapper.mapToView(nearest.boundingBox)
            val center = Offset(viewRect.left + viewRect.width / 2, viewRect.top + viewRect.height / 2)
            if (distance(gazePoint, center) <= MAX_DISTANCE_THRESHOLD) {
                return nearest
            }
        }

        return null
    }

    /** 두 점 사이의 유클리드 거리 계산 */
    private fun distance(a: Offset, b: Offset): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}

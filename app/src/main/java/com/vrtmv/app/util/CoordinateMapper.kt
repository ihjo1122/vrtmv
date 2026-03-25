package com.vrtmv.app.util

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/**
 * 이미지 좌표 ↔ 화면 좌표 변환기.
 *
 * ObjectDetectionManager에서 이미 회전이 적용된 upright 비트맵을 사용하므로
 * 회전 처리는 불필요하며, 스케일과 오프셋만 계산한다.
 * PreviewView의 FILL_CENTER 스케일 모드에 맞춰 매핑.
 *
 * @param imageWidth 분석 이미지의 너비 (회전 적용 후)
 * @param imageHeight 분석 이미지의 높이 (회전 적용 후)
 * @param viewWidth 화면 뷰의 너비
 * @param viewHeight 화면 뷰의 높이
 */
class CoordinateMapper(
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val viewWidth: Float,
    private val viewHeight: Float
) {
    private val scale: Float    // 이미지→화면 스케일 배율
    private val offsetX: Float  // 수평 오프셋 (FILL_CENTER 크롭 보정)
    private val offsetY: Float  // 수직 오프셋

    init {
        val scaleX = viewWidth / imageWidth.toFloat()
        val scaleY = viewHeight / imageHeight.toFloat()
        // FILL_CENTER: 화면을 가득 채우도록 큰 쪽 스케일 사용 (넘치는 부분은 크롭)
        scale = maxOf(scaleX, scaleY)

        // 중앙 정렬을 위한 오프셋 계산
        offsetX = (viewWidth - imageWidth * scale) / 2f
        offsetY = (viewHeight - imageHeight * scale) / 2f
    }

    /**
     * 이미지 픽셀 좌표의 바운딩박스를 화면 좌표로 변환한다.
     * 검출 결과를 오버레이에 그릴 때 사용.
     */
    fun mapToView(box: RectF): Rect {
        return Rect(
            left = box.left * scale + offsetX,
            top = box.top * scale + offsetY,
            right = box.right * scale + offsetX,
            bottom = box.bottom * scale + offsetY
        )
    }

    /**
     * 화면 좌표(터치 포인트)를 이미지 픽셀 좌표로 변환한다.
     * 터치 위치에서 어떤 객체가 있는지 판별할 때 사용.
     */
    fun mapToImage(viewOffset: Offset): Offset {
        return Offset(
            x = (viewOffset.x - offsetX) / scale,
            y = (viewOffset.y - offsetY) / scale
        )
    }

    /** 화면 뷰 크기 반환 */
    fun getViewSize(): Size = Size(viewWidth, viewHeight)
}

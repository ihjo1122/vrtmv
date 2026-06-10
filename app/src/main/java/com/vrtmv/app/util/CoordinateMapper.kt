package com.vrtmv.app.util

import android.graphics.RectF
import androidx.compose.ui.geometry.Rect

/**
 * upright 비트맵의 이미지 좌표 → PreviewView FILL_CENTER 모드의 화면 좌표 변환.
 * 회전 처리는 FrameSource 가 이미 끝낸 상태로 가정.
 */
class CoordinateMapper(
    imageWidth: Int,
    imageHeight: Int,
    private val viewWidth: Float,
    private val viewHeight: Float
) {
    private val scale: Float
    private val offsetX: Float
    private val offsetY: Float

    init {
        val scaleX = viewWidth / imageWidth.toFloat()
        val scaleY = viewHeight / imageHeight.toFloat()
        // FILL_CENTER: 화면을 가득 채우도록 큰 쪽 사용 (넘치는 부분은 크롭, 오프셋이 음수일 수 있음).
        scale = maxOf(scaleX, scaleY)
        offsetX = (viewWidth - imageWidth * scale) / 2f
        offsetY = (viewHeight - imageHeight * scale) / 2f
    }

    fun mapToView(box: RectF): Rect {
        return Rect(
            left = (box.left * scale + offsetX).coerceIn(0f, viewWidth),
            top = (box.top * scale + offsetY).coerceIn(0f, viewHeight),
            right = (box.right * scale + offsetX).coerceIn(0f, viewWidth),
            bottom = (box.bottom * scale + offsetY).coerceIn(0f, viewHeight)
        )
    }
}

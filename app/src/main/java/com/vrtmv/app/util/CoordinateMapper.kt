package com.vrtmv.app.util

import android.graphics.RectF
import androidx.compose.ui.geometry.Rect

/**
 * 이미지 좌표 → 화면 좌표 변환기.
 *
 * 각 `DetectionProvider.updateFrame`이 회전을 적용한 upright 비트맵을 넘기므로
 * 회전 계산은 생략하고 스케일·오프셋만 처리한다. PreviewView의 FILL_CENTER 모드에 맞춘다.
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
        // FILL_CENTER: 화면을 가득 채우도록 큰 쪽 스케일 사용 (넘치는 부분은 크롭)
        scale = maxOf(scaleX, scaleY)

        // FILL_CENTER에서는 오프셋이 음수가 될 수 있다 — 넘치는 영역을 화면 밖으로 밀어내는 정상 동작
        offsetX = (viewWidth - imageWidth * scale) / 2f
        offsetY = (viewHeight - imageHeight * scale) / 2f
    }

    /** 이미지 픽셀 바운딩박스 → 화면 좌표. 검출 결과를 오버레이에 그릴 때 사용. */
    fun mapToView(box: RectF): Rect {
        return Rect(
            left = (box.left * scale + offsetX).coerceIn(0f, viewWidth),
            top = (box.top * scale + offsetY).coerceIn(0f, viewHeight),
            right = (box.right * scale + offsetX).coerceIn(0f, viewWidth),
            bottom = (box.bottom * scale + offsetY).coerceIn(0f, viewHeight)
        )
    }

    // 디버그 로그 전용 — 운영 경로에서는 호출되지 않음
    fun debugScale(): Float = scale
    fun debugOffsetX(): Float = offsetX
    fun debugOffsetY(): Float = offsetY
}

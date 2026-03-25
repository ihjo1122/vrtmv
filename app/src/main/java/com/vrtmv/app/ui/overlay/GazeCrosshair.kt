package com.vrtmv.app.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

/** 크로스헤어 색상 (노란색) */
private val CrosshairColor = Color(0xFFFFEB3B)

/** 크로스헤어 외곽 원 반지름 */
private const val CROSSHAIR_RADIUS = 24f

/** 십자선 길이 */
private const val CROSSHAIR_LINE_LENGTH = 12f

/** 선 두께 */
private const val CROSSHAIR_STROKE_WIDTH = 2f

/**
 * 터치 위치를 표시하는 크로스헤어(조준점) 오버레이.
 * 노란색 원 + 중앙 점 + 상하좌우 십자선으로 구성.
 * 사용자가 터치한 위치에 표시되어 탐지 포인트를 시각적으로 알려준다.
 *
 * @param position 크로스헤어 표시 위치 (화면 좌표)
 */
@Composable
fun GazeCrosshair(
    position: Offset,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        // 외곽 원
        drawCircle(
            color = CrosshairColor,
            radius = CROSSHAIR_RADIUS,
            center = position,
            style = Stroke(width = CROSSHAIR_STROKE_WIDTH)
        )

        // 중앙 점
        drawCircle(
            color = CrosshairColor,
            radius = 3f,
            center = position
        )

        // 십자선 (원 바깥쪽에 간격을 두고 그림)
        val gap = CROSSHAIR_RADIUS + 4f
        val end = gap + CROSSHAIR_LINE_LENGTH

        // 상
        drawLine(CrosshairColor, Offset(position.x, position.y - gap), Offset(position.x, position.y - end), CROSSHAIR_STROKE_WIDTH)
        // 하
        drawLine(CrosshairColor, Offset(position.x, position.y + gap), Offset(position.x, position.y + end), CROSSHAIR_STROKE_WIDTH)
        // 좌
        drawLine(CrosshairColor, Offset(position.x - gap, position.y), Offset(position.x - end, position.y), CROSSHAIR_STROKE_WIDTH)
        // 우
        drawLine(CrosshairColor, Offset(position.x + gap, position.y), Offset(position.x + end, position.y), CROSSHAIR_STROKE_WIDTH)
    }
}

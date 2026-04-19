package com.vrtmv.app.ui.overlay

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import com.vrtmv.app.ui.theme.ArCyan
import com.vrtmv.app.ui.theme.CrosshairGold
import kotlin.math.cos
import kotlin.math.sin

private const val CROSSHAIR_RADIUS = 24f
private const val CROSSHAIR_LINE_LENGTH = 12f
private const val CROSSHAIR_STROKE_WIDTH = 2f
private const val DIAGONAL_LINE_LENGTH = 7f

@Composable
fun GazeCrosshair(
    position: Offset,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "crosshair")
    val pulseRadius by transition.animateFloat(
        initialValue = CROSSHAIR_RADIUS,
        targetValue = CROSSHAIR_RADIUS + 4f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "pulseRadius"
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    val color = CrosshairGold

    Canvas(modifier = modifier) {
        // Outer radar ring (larger, transparent)
        drawCircle(
            color = color.copy(alpha = pulseAlpha * 0.15f),
            radius = CROSSHAIR_RADIUS * 1.6f,
            center = position,
            style = Stroke(width = 1f)
        )

        // Main ring (pulsing)
        drawCircle(
            color = color.copy(alpha = pulseAlpha),
            radius = pulseRadius,
            center = position,
            style = Stroke(width = CROSSHAIR_STROKE_WIDTH)
        )

        // Center dot
        drawCircle(
            color = color,
            radius = 3f,
            center = position
        )

        // Cardinal crosshair lines
        val gap = CROSSHAIR_RADIUS + 4f
        val end = gap + CROSSHAIR_LINE_LENGTH

        drawLine(color, Offset(position.x, position.y - gap), Offset(position.x, position.y - end), CROSSHAIR_STROKE_WIDTH)
        drawLine(color, Offset(position.x, position.y + gap), Offset(position.x, position.y + end), CROSSHAIR_STROKE_WIDTH)
        drawLine(color, Offset(position.x - gap, position.y), Offset(position.x - end, position.y), CROSSHAIR_STROKE_WIDTH)
        drawLine(color, Offset(position.x + gap, position.y), Offset(position.x + end, position.y), CROSSHAIR_STROKE_WIDTH)

        // Diagonal tick marks (45, 135, 225, 315 degrees)
        val diagGap = CROSSHAIR_RADIUS + 6f
        val diagEnd = diagGap + DIAGONAL_LINE_LENGTH
        val diagColor = color.copy(alpha = 0.6f)
        val angles = listOf(45.0, 135.0, 225.0, 315.0)
        for (angle in angles) {
            val rad = Math.toRadians(angle)
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            drawLine(
                diagColor,
                Offset(position.x + cosA * diagGap, position.y + sinA * diagGap),
                Offset(position.x + cosA * diagEnd, position.y + sinA * diagEnd),
                CROSSHAIR_STROKE_WIDTH * 0.8f
            )
        }
    }
}

/**
 * 포인팅 홀드 진행률 링.
 * 사용자가 검지로 포인팅하는 동안 0~1초 동안 차오르는 원호로 피드백 제공.
 */
@Composable
fun PointingProgressRing(
    position: Offset,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val color = ArCyan
    val radius = 34f
    val stroke = 4f

    Canvas(modifier = modifier) {
        // 배경 링 (반투명)
        drawCircle(
            color = color.copy(alpha = 0.25f),
            radius = radius,
            center = position,
            style = Stroke(width = stroke)
        )

        // 진행 아크 — 12시 방향에서 시작하여 시계방향
        if (progress > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = progress * 360f,
                useCenter = false,
                topLeft = Offset(position.x - radius, position.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(width = stroke)
            )
        }

        // 중앙 점
        drawCircle(
            color = color,
            radius = 4f,
            center = position
        )
    }
}

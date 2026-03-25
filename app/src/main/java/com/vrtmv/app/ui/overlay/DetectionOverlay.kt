package com.vrtmv.app.ui.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.vrtmv.app.domain.model.DetectedObject
import com.vrtmv.app.domain.model.InferenceState
import com.vrtmv.app.util.CoordinateMapper

// AR 스타일 색상
private val AccentCyan = Color(0xFF00E5FF)
private val AccentCyanDim = Color(0xFF00E5FF).copy(alpha = 0.4f)
private val AccentCyanFill = Color(0xFF00E5FF).copy(alpha = 0.08f)
private val UnselectedColor = Color.White.copy(alpha = 0.5f)
private val TagBackground = Color(0xCC000000) // 80% black
private val TagBackgroundSelected = Color(0xE6001529) // dark blue-black

@Composable
fun DetectionOverlay(
    detectedObjects: List<DetectedObject>,
    selectedObject: DetectedObject?,
    inferenceState: InferenceState = InferenceState.Idle,
    coordinateMapper: CoordinateMapper,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val infiniteTransition = rememberInfiniteTransition(label = "ar_pulse")

    // Scanning line animation for selected object
    val scanProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanLine"
    )

    // Pulse for corner brackets
    val cornerPulse by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cornerPulse"
    )

    Canvas(modifier = modifier) {
        for (obj in detectedObjects) {
            val viewRect = coordinateMapper.mapToView(obj.boundingBox)
            val isSelected = obj == selectedObject

            if (isSelected) {
                drawSelectedObject(
                    obj = obj,
                    left = viewRect.left,
                    top = viewRect.top,
                    width = viewRect.width,
                    height = viewRect.height,
                    scanProgress = scanProgress,
                    cornerPulse = cornerPulse,
                    inferenceState = inferenceState,
                    textMeasurer = textMeasurer
                )
            } else {
                drawUnselectedObject(
                    obj = obj,
                    left = viewRect.left,
                    top = viewRect.top,
                    width = viewRect.width,
                    height = viewRect.height,
                    textMeasurer = textMeasurer
                )
            }
        }
    }
}

private fun DrawScope.drawSelectedObject(
    obj: DetectedObject,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    scanProgress: Float,
    cornerPulse: Float,
    inferenceState: InferenceState,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    val cornerLen = minOf(width, height) * 0.2f
    val strokeW = 3f

    // Semi-transparent fill
    drawRoundRect(
        color = AccentCyanFill,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(4f, 4f)
    )

    // Dashed border
    drawRoundRect(
        color = AccentCyanDim,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(4f, 4f),
        style = Stroke(
            width = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        )
    )

    // Corner brackets (AR targeting style)
    val c = AccentCyan.copy(alpha = cornerPulse)
    // Top-left
    drawLine(c, Offset(left, top), Offset(left + cornerLen, top), strokeW)
    drawLine(c, Offset(left, top), Offset(left, top + cornerLen), strokeW)
    // Top-right
    drawLine(c, Offset(left + width, top), Offset(left + width - cornerLen, top), strokeW)
    drawLine(c, Offset(left + width, top), Offset(left + width, top + cornerLen), strokeW)
    // Bottom-left
    drawLine(c, Offset(left, top + height), Offset(left + cornerLen, top + height), strokeW)
    drawLine(c, Offset(left, top + height), Offset(left, top + height - cornerLen), strokeW)
    // Bottom-right
    drawLine(c, Offset(left + width, top + height), Offset(left + width - cornerLen, top + height), strokeW)
    drawLine(c, Offset(left + width, top + height), Offset(left + width, top + height - cornerLen), strokeW)

    // Scanning line (horizontal sweep)
    val scanY = top + height * scanProgress
    drawLine(
        color = AccentCyan.copy(alpha = 0.6f),
        start = Offset(left + 2f, scanY),
        end = Offset(left + width - 2f, scanY),
        strokeWidth = 1.5f
    )

    // --- AR Tag (floating label above object) ---
    val padding = 10f
    val tagGap = 8f // gap between box and tag
    val connectorLen = 20f

    // Label text
    val labelText = obj.label.uppercase()
    val labelResult = textMeasurer.measure(
        text = labelText,
        style = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = AccentCyan
        )
    )

    // Confidence text
    val confText = "${(obj.confidence * 100).toInt()}%"
    val confResult = textMeasurer.measure(
        text = confText,
        style = TextStyle(fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
    )

    // Description text (from inference)
    val descText = when (inferenceState) {
        is InferenceState.Success -> inferenceState.text
        is InferenceState.Loading -> "분석 중..."
        else -> null
    }
    val descResult = descText?.let {
        textMeasurer.measure(
            text = it,
            style = TextStyle(fontSize = 11.sp, color = Color.White.copy(alpha = 0.9f)),
            constraints = Constraints(maxWidth = 500),
            overflow = TextOverflow.Ellipsis,
            maxLines = 2
        )
    }

    // Calculate tag dimensions
    val tagContentWidth = maxOf(
        labelResult.size.width + 8f + confResult.size.width,
        descResult?.size?.width?.toFloat() ?: 0f
    )
    val tagWidth = tagContentWidth + padding * 2
    val tagHeight = labelResult.size.height + padding * 2 +
        (descResult?.let { it.size.height + 6f } ?: 0f)

    // Tag position: above the bounding box, centered
    val tagLeft = (left + width / 2 - tagWidth / 2).coerceIn(4f, size.width - tagWidth - 4f)
    val tagBottom = top - tagGap
    val tagTop = tagBottom - tagHeight

    // Connector line from tag to box
    val connectorX = left + width / 2
    drawLine(
        color = AccentCyan.copy(alpha = 0.5f),
        start = Offset(connectorX, tagBottom),
        end = Offset(connectorX, top),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
    )

    // Small diamond at connector top
    val diamondSize = 4f
    val diamondPath = Path().apply {
        moveTo(connectorX, tagBottom)
        lineTo(connectorX - diamondSize, tagBottom + diamondSize)
        lineTo(connectorX, tagBottom + diamondSize * 2)
        lineTo(connectorX + diamondSize, tagBottom + diamondSize)
        close()
    }
    drawPath(diamondPath, AccentCyan)

    // Tag background
    drawRoundRect(
        color = TagBackgroundSelected,
        topLeft = Offset(tagLeft, tagTop),
        size = Size(tagWidth, tagHeight),
        cornerRadius = CornerRadius(6f, 6f)
    )

    // Tag border
    drawRoundRect(
        color = AccentCyan.copy(alpha = 0.4f),
        topLeft = Offset(tagLeft, tagTop),
        size = Size(tagWidth, tagHeight),
        cornerRadius = CornerRadius(6f, 6f),
        style = Stroke(width = 1f)
    )

    // Accent bar on left side of tag
    drawRoundRect(
        color = AccentCyan,
        topLeft = Offset(tagLeft, tagTop + 4f),
        size = Size(3f, tagHeight - 8f),
        cornerRadius = CornerRadius(2f, 2f)
    )

    // Draw label text
    drawText(
        textLayoutResult = labelResult,
        topLeft = Offset(tagLeft + padding + 4f, tagTop + padding)
    )

    // Draw confidence next to label
    drawText(
        textLayoutResult = confResult,
        topLeft = Offset(
            tagLeft + padding + 4f + labelResult.size.width + 8f,
            tagTop + padding + (labelResult.size.height - confResult.size.height)
        )
    )

    // Draw description if available
    if (descResult != null) {
        drawText(
            textLayoutResult = descResult,
            topLeft = Offset(
                tagLeft + padding + 4f,
                tagTop + padding + labelResult.size.height + 6f
            )
        )
    }
}

private fun DrawScope.drawUnselectedObject(
    obj: DetectedObject,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    val cornerLen = minOf(width, height) * 0.15f
    val strokeW = 1.5f
    val c = UnselectedColor

    // Corner brackets only (minimal, non-intrusive)
    // Top-left
    drawLine(c, Offset(left, top), Offset(left + cornerLen, top), strokeW)
    drawLine(c, Offset(left, top), Offset(left, top + cornerLen), strokeW)
    // Top-right
    drawLine(c, Offset(left + width, top), Offset(left + width - cornerLen, top), strokeW)
    drawLine(c, Offset(left + width, top), Offset(left + width, top + cornerLen), strokeW)
    // Bottom-left
    drawLine(c, Offset(left, top + height), Offset(left + cornerLen, top + height), strokeW)
    drawLine(c, Offset(left, top + height), Offset(left, top + height - cornerLen), strokeW)
    // Bottom-right
    drawLine(c, Offset(left + width, top + height), Offset(left + width - cornerLen, top + height), strokeW)
    drawLine(c, Offset(left + width, top + height), Offset(left + width, top + height - cornerLen), strokeW)

    // Small label at top-left corner
    val labelText = obj.label
    val textResult = textMeasurer.measure(
        text = labelText,
        style = TextStyle(fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
    )

    val chipPad = 3f
    drawRoundRect(
        color = TagBackground,
        topLeft = Offset(left, top - textResult.size.height - chipPad * 2),
        size = Size(textResult.size.width + chipPad * 2, textResult.size.height + chipPad * 2),
        cornerRadius = CornerRadius(3f, 3f)
    )
    drawText(
        textLayoutResult = textResult,
        topLeft = Offset(left + chipPad, top - textResult.size.height - chipPad)
    )
}

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
    tapPoint: Offset? = null,
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

        // 객체 미선택 + 터치 좌표 있을 때 → 터치 위치에 장면 AR 태그
        if (selectedObject == null && tapPoint != null &&
            inferenceState !is InferenceState.Idle
        ) {
            drawSceneTag(
                tapX = tapPoint.x,
                tapY = tapPoint.y,
                cornerPulse = cornerPulse,
                inferenceState = inferenceState,
                textMeasurer = textMeasurer
            )
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
    drawCornerBrackets(left, top, width, height, cornerLen, strokeW, AccentCyan.copy(alpha = cornerPulse))

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
    val accentBarInset = 4f // accent bar 오른쪽 여백

    // 화면 비례 동적 크기
    val screenWidth = size.width
    val screenHeight = size.height
    val maxTagWidth = screenWidth * 0.75f
    val minTagWidth = 160f
    val innerPadding = padding + accentBarInset // 텍스트 왼쪽 총 여백

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

    // Label row width
    val labelRowWidth = labelResult.size.width + 8f + confResult.size.width

    // Description text (from inference)
    val descText = when (inferenceState) {
        is InferenceState.Success -> inferenceState.text
        is InferenceState.Loading -> "분석 중..."
        else -> null
    }
    val descMaxWidth = (maxTagWidth - innerPadding - padding).toInt().coerceAtLeast(200)
    val descResult = descText?.let {
        textMeasurer.measure(
            text = it,
            style = TextStyle(fontSize = 11.sp, color = Color.White.copy(alpha = 0.9f)),
            constraints = Constraints(maxWidth = descMaxWidth),
            overflow = TextOverflow.Ellipsis,
            maxLines = 4
        )
    }

    // Calculate tag dimensions (동적)
    val tagContentWidth = maxOf(
        labelRowWidth,
        descResult?.size?.width?.toFloat() ?: 0f
    )
    val tagWidth = (tagContentWidth + innerPadding + padding)
        .coerceIn(minTagWidth, maxTagWidth)
    val descSpacing = 6f
    val tagHeight = labelResult.size.height + padding * 2 +
        (descResult?.let { it.size.height + descSpacing } ?: 0f)

    // Tag position: above the bounding box, or below if not enough space
    val tagLeft = (left + width / 2 - tagWidth / 2).coerceIn(4f, screenWidth - tagWidth - 4f)
    val showBelow = (top - tagGap - tagHeight) < 4f
    val tagTop: Float
    val connectorStart: Float
    val connectorEnd: Float
    if (showBelow) {
        tagTop = (top + height + tagGap).coerceAtMost(screenHeight - tagHeight - 4f)
        connectorStart = top + height
        connectorEnd = tagTop
    } else {
        tagTop = (top - tagGap - tagHeight).coerceAtLeast(4f)
        connectorStart = tagTop + tagHeight
        connectorEnd = top
    }
    val tagBottom = tagTop + tagHeight

    // Connector line from tag to box
    val connectorX = left + width / 2
    drawLine(
        color = AccentCyan.copy(alpha = 0.5f),
        start = Offset(connectorX, connectorStart),
        end = Offset(connectorX, connectorEnd),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
    )

    // Small diamond at connector joint
    val diamondSize = 4f
    val diamondY = if (showBelow) tagTop else tagBottom
    val diamondPath = Path().apply {
        moveTo(connectorX, diamondY)
        lineTo(connectorX - diamondSize, diamondY + diamondSize)
        lineTo(connectorX, diamondY + diamondSize * 2)
        lineTo(connectorX + diamondSize, diamondY + diamondSize)
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

    drawCornerBrackets(left, top, width, height, cornerLen, strokeW, c)

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

/** 터치 좌표에 장면 설명 AR 태그를 그린다. */
private fun DrawScope.drawSceneTag(
    tapX: Float,
    tapY: Float,
    cornerPulse: Float,
    inferenceState: InferenceState,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    val padding = 10f
    val accentBarInset = 4f
    val screenWidth = size.width
    val screenHeight = size.height
    val maxTagWidth = screenWidth * 0.75f
    val minTagWidth = 160f
    val innerPadding = padding + accentBarInset

    // 타이틀
    val titleText = when (inferenceState) {
        is InferenceState.Loading -> "장면 분석 중..."
        is InferenceState.Success -> "SCENE"
        is InferenceState.Error -> "분석 실패"
        else -> return
    }
    val titleResult = textMeasurer.measure(
        text = titleText,
        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
    )

    // 설명 텍스트
    val descMaxWidth = (maxTagWidth - innerPadding - padding).toInt().coerceAtLeast(200)
    val descText = when (inferenceState) {
        is InferenceState.Success -> inferenceState.text
        is InferenceState.Loading -> "VLM이 이미지를 분석하고 있습니다..."
        is InferenceState.Error -> inferenceState.message ?: "추론 실패"
        else -> return
    }
    val descResult = textMeasurer.measure(
        text = descText,
        style = TextStyle(fontSize = 11.sp, color = Color.White.copy(alpha = 0.9f)),
        constraints = Constraints(maxWidth = descMaxWidth),
        overflow = TextOverflow.Ellipsis,
        maxLines = 4
    )

    val descSpacing = 6f
    val tagContentWidth = maxOf(titleResult.size.width.toFloat(), descResult.size.width.toFloat())
    val tagWidth = (tagContentWidth + innerPadding + padding).coerceIn(minTagWidth, maxTagWidth)
    val tagHeight = titleResult.size.height + padding * 2 + descResult.size.height + descSpacing

    // 태그 위치: 터치 좌표 위쪽, 화면 밖으로 안 나가게
    val tagLeft = (tapX - tagWidth / 2).coerceIn(4f, screenWidth - tagWidth - 4f)
    val tagGap = 24f
    val showBelow = (tapY - tagGap - tagHeight) < 4f
    val tagTop = if (showBelow) {
        (tapY + tagGap).coerceAtMost(screenHeight - tagHeight - 4f)
    } else {
        (tapY - tagGap - tagHeight).coerceAtLeast(4f)
    }
    val tagBottom = tagTop + tagHeight

    // 커넥터 라인
    val connectorStartY = if (showBelow) tapY else tagBottom
    val connectorEndY = if (showBelow) tagTop else tapY
    drawLine(
        color = AccentCyan.copy(alpha = 0.5f),
        start = Offset(tapX, connectorStartY),
        end = Offset(tapX, connectorEndY),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
    )

    // 다이아몬드
    val diamondSize = 4f
    val diamondY = if (showBelow) tagTop else tagBottom
    val diamondPath = Path().apply {
        moveTo(tapX, diamondY)
        lineTo(tapX - diamondSize, diamondY + diamondSize)
        lineTo(tapX, diamondY + diamondSize * 2)
        lineTo(tapX + diamondSize, diamondY + diamondSize)
        close()
    }
    drawPath(diamondPath, AccentCyan)

    // 터치 포인트 원형 펄스
    drawCircle(
        color = AccentCyan.copy(alpha = cornerPulse * 0.3f),
        radius = 30f,
        center = Offset(tapX, tapY)
    )
    drawCircle(
        color = AccentCyan.copy(alpha = cornerPulse),
        radius = 6f,
        center = Offset(tapX, tapY)
    )

    // 태그 배경
    drawRoundRect(
        color = TagBackgroundSelected,
        topLeft = Offset(tagLeft, tagTop),
        size = Size(tagWidth, tagHeight),
        cornerRadius = CornerRadius(6f, 6f)
    )
    // 태그 테두리
    drawRoundRect(
        color = AccentCyan.copy(alpha = 0.4f),
        topLeft = Offset(tagLeft, tagTop),
        size = Size(tagWidth, tagHeight),
        cornerRadius = CornerRadius(6f, 6f),
        style = Stroke(width = 1f)
    )
    // Accent bar
    drawRoundRect(
        color = AccentCyan,
        topLeft = Offset(tagLeft, tagTop + 4f),
        size = Size(3f, tagHeight - 8f),
        cornerRadius = CornerRadius(2f, 2f)
    )

    // 타이틀
    drawText(
        textLayoutResult = titleResult,
        topLeft = Offset(tagLeft + innerPadding, tagTop + padding)
    )
    // 설명
    drawText(
        textLayoutResult = descResult,
        topLeft = Offset(tagLeft + innerPadding, tagTop + padding + titleResult.size.height + descSpacing)
    )
}

/** 4개 코너에 L자형 브래킷을 그린다. */
private fun DrawScope.drawCornerBrackets(
    left: Float, top: Float, width: Float, height: Float,
    cornerLen: Float, strokeW: Float, color: Color
) {
    // Top-left
    drawLine(color, Offset(left, top), Offset(left + cornerLen, top), strokeW)
    drawLine(color, Offset(left, top), Offset(left, top + cornerLen), strokeW)
    // Top-right
    drawLine(color, Offset(left + width, top), Offset(left + width - cornerLen, top), strokeW)
    drawLine(color, Offset(left + width, top), Offset(left + width, top + cornerLen), strokeW)
    // Bottom-left
    drawLine(color, Offset(left, top + height), Offset(left + cornerLen, top + height), strokeW)
    drawLine(color, Offset(left, top + height), Offset(left, top + height - cornerLen), strokeW)
    // Bottom-right
    drawLine(color, Offset(left + width, top + height), Offset(left + width - cornerLen, top + height), strokeW)
    drawLine(color, Offset(left + width, top + height), Offset(left + width, top + height - cornerLen), strokeW)
}

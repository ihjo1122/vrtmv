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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.vrtmv.app.domain.model.DetectedObject
import com.vrtmv.app.domain.model.InferenceState
import com.vrtmv.app.ui.theme.ArTeal
import com.vrtmv.app.ui.theme.OverlayCyanBright
import com.vrtmv.app.ui.theme.OverlayCyanDim
import com.vrtmv.app.ui.theme.OverlayCyanFill
import com.vrtmv.app.ui.theme.OverlayTagBg
import com.vrtmv.app.ui.theme.OverlayTagBgSelected
import com.vrtmv.app.ui.theme.OverlayUnselected
import com.vrtmv.app.ui.theme.StatusError
import com.vrtmv.app.util.CoordinateMapper

private val AccentCyan = OverlayCyanBright
private val AccentCyanDim = OverlayCyanDim
private val AccentCyanFill = OverlayCyanFill
private val UnselectedColor = OverlayUnselected
private val TagBackground = OverlayTagBg
private val TagBgSelected = OverlayTagBgSelected

// 로딩바 크기
private const val LOADING_BAR_WIDTH = 180f
private const val LOADING_BAR_HEIGHT = 4f

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

    val scanProgress by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "scanLine"
    )

    val cornerPulse by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
        label = "cornerPulse"
    )

    // 로딩바 shimmer sweep
    val shimmerProgress by infiniteTransition.animateFloat(
        initialValue = -0.3f, targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer"
    )

    // 점 3개 순차 깜박임 (0~1 cycle, 각 점마다 오프셋)
    val dotCycle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "dotCycle"
    )

    Canvas(modifier = modifier) {
        for (obj in detectedObjects) {
            val viewRect = coordinateMapper.mapToView(obj.boundingBox)
            val isSelected = obj == selectedObject

            if (isSelected) {
                drawSelectedObject(
                    obj = obj,
                    left = viewRect.left, top = viewRect.top,
                    width = viewRect.width, height = viewRect.height,
                    scanProgress = scanProgress,
                    cornerPulse = cornerPulse,
                    inferenceState = inferenceState,
                    textMeasurer = textMeasurer,
                    shimmerProgress = shimmerProgress,
                    dotCycle = dotCycle
                )
            } else {
                drawUnselectedObject(
                    obj = obj,
                    left = viewRect.left, top = viewRect.top,
                    width = viewRect.width, height = viewRect.height,
                    cornerPulse = cornerPulse,
                    textMeasurer = textMeasurer
                )
            }
        }

        // 객체 미선택 + 터치 좌표 → 장면 추론 태그
        if (selectedObject == null && tapPoint != null &&
            inferenceState !is InferenceState.Idle
        ) {
            drawSceneTag(
                tapX = tapPoint.x, tapY = tapPoint.y,
                cornerPulse = cornerPulse,
                inferenceState = inferenceState,
                textMeasurer = textMeasurer,
                shimmerProgress = shimmerProgress,
                dotCycle = dotCycle
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 선택된 객체
// ════════════════════════════════════════════════════════════════

private fun DrawScope.drawSelectedObject(
    obj: DetectedObject,
    left: Float, top: Float, width: Float, height: Float,
    scanProgress: Float, cornerPulse: Float,
    inferenceState: InferenceState,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    shimmerProgress: Float,
    dotCycle: Float
) {
    val cornerLen = minOf(width, height) * 0.2f

    // Fill
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
        style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
    )

    // Corner brackets + glow
    val bracketColor = AccentCyan.copy(alpha = cornerPulse)
    drawCornerBrackets(left, top, width, height, cornerLen, 3f, bracketColor)
    drawCornerBrackets(left, top, width, height, cornerLen, 7f, bracketColor.copy(alpha = cornerPulse * 0.12f))

    // Scan line
    val scanY = top + height * scanProgress
    drawLine(
        color = AccentCyan.copy(alpha = 0.5f),
        start = Offset(left + 2f, scanY),
        end = Offset(left + width - 2f, scanY),
        strokeWidth = 1.5f
    )

    // ── 로딩바 (화면 정중앙) ──
    if (inferenceState is InferenceState.Loading) {
        drawLoadingBar(size.width / 2, size.height / 2, shimmerProgress, textMeasurer, dotCycle)
    }

    // ── AR 결과 태그 ──
    if (inferenceState is InferenceState.Success || inferenceState is InferenceState.Idle) {
        drawArResultTag(
            anchorX = left + width / 2,
            anchorTop = top,
            anchorBottom = top + height,
            obj = obj,
            inferenceState = inferenceState,
            textMeasurer = textMeasurer
        )
    }
}

// ════════════════════════════════════════════════════════════════
// 미선택 객체
// ════════════════════════════════════════════════════════════════

private fun DrawScope.drawUnselectedObject(
    obj: DetectedObject,
    left: Float, top: Float, width: Float, height: Float,
    cornerPulse: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    val cornerLen = minOf(width, height) * 0.15f
    val c = UnselectedColor.copy(alpha = cornerPulse * 0.7f)

    drawRoundRect(
        color = Color.White.copy(alpha = 0.03f),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(4f, 4f)
    )
    drawCornerBrackets(left, top, width, height, cornerLen, 1.5f, c)

    // Label chip
    val chipText = "${obj.label}  ${(obj.confidence * 100).toInt()}%"
    val textResult = textMeasurer.measure(
        text = chipText,
        style = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.White.copy(alpha = 0.7f))
    )
    val chipPadH = 6f
    val chipPadV = 3f
    val chipW = textResult.size.width + chipPadH * 2
    val chipH = textResult.size.height + chipPadV * 2
    val chipTop = top - chipH - 2f

    drawRoundRect(color = TagBackground, topLeft = Offset(left, chipTop), size = Size(chipW, chipH), cornerRadius = CornerRadius(4f, 4f))
    drawRoundRect(color = UnselectedColor.copy(alpha = 0.2f), topLeft = Offset(left, chipTop), size = Size(chipW, chipH), cornerRadius = CornerRadius(4f, 4f), style = Stroke(0.5f))
    drawText(textLayoutResult = textResult, topLeft = Offset(left + chipPadH, chipTop + chipPadV))
}

// ════════════════════════════════════════════════════════════════
// 장면 태그 (객체 미검출 시 탭 위치)
// ════════════════════════════════════════════════════════════════

private fun DrawScope.drawSceneTag(
    tapX: Float, tapY: Float,
    cornerPulse: Float,
    inferenceState: InferenceState,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    shimmerProgress: Float,
    dotCycle: Float
) {
    // 터치 포인트 원형 펄스
    drawCircle(color = AccentCyan.copy(alpha = cornerPulse * 0.15f), radius = 30f, center = Offset(tapX, tapY))
    drawCircle(color = AccentCyan.copy(alpha = cornerPulse * 0.8f), radius = 5f, center = Offset(tapX, tapY))

    // ── 로딩바 (화면 정중앙) ──
    if (inferenceState is InferenceState.Loading) {
        drawLoadingBar(size.width / 2, size.height / 2, shimmerProgress, textMeasurer, dotCycle)
        return
    }

    // ── 결과 태그 ──
    val titleText: String
    val titleColor: Color
    when (inferenceState) {
        is InferenceState.Success -> { titleText = "SCENE"; titleColor = AccentCyan }
        is InferenceState.Error -> { titleText = "ERROR"; titleColor = StatusError }
        else -> return
    }
    val descText = when (inferenceState) {
        is InferenceState.Success -> inferenceState.text
        is InferenceState.Error -> inferenceState.message ?: "추론 실패"
        else -> return
    }

    drawResultPanel(
        anchorX = tapX,
        anchorY = tapY,
        title = titleText,
        titleColor = titleColor,
        description = descText,
        textMeasurer = textMeasurer,
        useConnector = true
    )
}

// ════════════════════════════════════════════════════════════════
// AR 결과 태그 (선택 객체용)
// ════════════════════════════════════════════════════════════════

private fun DrawScope.drawArResultTag(
    anchorX: Float, anchorTop: Float, anchorBottom: Float,
    obj: DetectedObject,
    inferenceState: InferenceState,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    // Idle 상태에선 라벨+신뢰도만 표시
    val title = "${obj.label.uppercase()}  ${(obj.confidence * 100).toInt()}%"
    val desc = when (inferenceState) {
        is InferenceState.Success -> inferenceState.text
        else -> null
    }

    val padding = 12f
    val screenWidth = size.width
    val screenHeight = size.height
    val maxTagWidth = screenWidth * 0.8f

    // ── 타이틀 측정 ──
    val titleResult = textMeasurer.measure(
        text = title,
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = AccentCyan)
    )

    // ── 설명 측정 ──
    val descMaxW = (maxTagWidth - padding * 2 - 8f).toInt().coerceAtLeast(200)
    val descResult = desc?.let {
        textMeasurer.measure(
            text = it,
            style = TextStyle(fontSize = 12.sp, color = Color.White.copy(alpha = 0.92f), lineHeight = 18.sp),
            constraints = Constraints(maxWidth = descMaxW),
            overflow = TextOverflow.Ellipsis,
            maxLines = 5
        )
    }

    // ── 크기 계산 ──
    val separatorH = if (descResult != null) 8f else 0f
    val lineH = if (descResult != null) 1f else 0f
    val contentW = maxOf(titleResult.size.width.toFloat(), descResult?.size?.width?.toFloat() ?: 0f)
    val tagWidth = (contentW + padding * 2 + 8f).coerceIn(140f, maxTagWidth) // 8f = 좌측 accent 영역
    val tagHeight = padding + titleResult.size.height + separatorH + lineH +
        (descResult?.let { it.size.height + 8f } ?: 0f) + padding

    // ── 위치 결정 ──
    val tagLeft = (anchorX - tagWidth / 2).coerceIn(4f, screenWidth - tagWidth - 4f)
    val tagGap = 10f
    val showBelow = (anchorTop - tagGap - tagHeight) < 4f
    val tagTop: Float
    val connStart: Float
    val connEnd: Float
    if (showBelow) {
        tagTop = (anchorBottom + tagGap).coerceAtMost(screenHeight - tagHeight - 4f)
        connStart = anchorBottom
        connEnd = tagTop
    } else {
        tagTop = (anchorTop - tagGap - tagHeight).coerceAtLeast(4f)
        connStart = tagTop + tagHeight
        connEnd = anchorTop
    }

    // ── 커넥터 라인 ──
    drawLine(
        color = AccentCyan.copy(alpha = 0.3f),
        start = Offset(anchorX, connStart),
        end = Offset(anchorX, connEnd),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
    )

    // ── 태그 본체 ──
    drawResultTagBody(tagLeft, tagTop, tagWidth, tagHeight, titleResult, descResult, padding, separatorH)
}

// ════════════════════════════════════════════════════════════════
// 결과 패널 (장면 추론 결과용)
// ════════════════════════════════════════════════════════════════

private fun DrawScope.drawResultPanel(
    anchorX: Float, anchorY: Float,
    title: String, titleColor: Color, description: String,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    useConnector: Boolean
) {
    val padding = 12f
    val screenWidth = size.width
    val screenHeight = size.height
    val maxTagWidth = screenWidth * 0.8f

    val titleResult = textMeasurer.measure(
        text = title,
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = titleColor)
    )
    val descMaxW = (maxTagWidth - padding * 2 - 8f).toInt().coerceAtLeast(200)
    val descResult = textMeasurer.measure(
        text = description,
        style = TextStyle(fontSize = 12.sp, color = Color.White.copy(alpha = 0.92f), lineHeight = 18.sp),
        constraints = Constraints(maxWidth = descMaxW),
        overflow = TextOverflow.Ellipsis,
        maxLines = 5
    )

    val contentW = maxOf(titleResult.size.width.toFloat(), descResult.size.width.toFloat())
    val tagWidth = (contentW + padding * 2 + 8f).coerceIn(140f, maxTagWidth)
    val tagHeight = padding + titleResult.size.height + 8f + 1f + descResult.size.height + 8f + padding

    val tagLeft = (anchorX - tagWidth / 2).coerceIn(4f, screenWidth - tagWidth - 4f)
    val tagGap = 48f
    val showBelow = (anchorY - tagGap - tagHeight) < 4f
    val tagTop = if (showBelow) {
        (anchorY + tagGap).coerceAtMost(screenHeight - tagHeight - 4f)
    } else {
        (anchorY - tagGap - tagHeight).coerceAtLeast(4f)
    }
    val tagBottom = tagTop + tagHeight

    if (useConnector) {
        val cStart = if (showBelow) anchorY + 16f else tagBottom
        val cEnd = if (showBelow) tagTop else anchorY - 16f
        drawLine(
            color = titleColor.copy(alpha = 0.3f),
            start = Offset(anchorX, cStart), end = Offset(anchorX, cEnd),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
        )
    }

    drawResultTagBody(tagLeft, tagTop, tagWidth, tagHeight, titleResult, descResult, padding, 8f)
}

// ════════════════════════════════════════════════════════════════
// 태그 본체 렌더링 (공통)
// ════════════════════════════════════════════════════════════════

private fun DrawScope.drawResultTagBody(
    tagLeft: Float, tagTop: Float, tagWidth: Float, tagHeight: Float,
    titleResult: androidx.compose.ui.text.TextLayoutResult,
    descResult: androidx.compose.ui.text.TextLayoutResult?,
    padding: Float, separatorH: Float
) {
    val innerLeft = tagLeft + 8f // accent bar + gap

    // ── 배경: 글래스 효과 (이중 레이어) ──
    // 외부 글로우
    drawRoundRect(
        color = AccentCyan.copy(alpha = 0.04f),
        topLeft = Offset(tagLeft - 2f, tagTop - 2f),
        size = Size(tagWidth + 4f, tagHeight + 4f),
        cornerRadius = CornerRadius(10f, 10f)
    )
    // 메인 배경
    drawRoundRect(
        color = Color(0xE6101520),
        topLeft = Offset(tagLeft, tagTop),
        size = Size(tagWidth, tagHeight),
        cornerRadius = CornerRadius(8f, 8f)
    )
    // 상단 하이라이트 (그라디언트)
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(AccentCyan.copy(alpha = 0.08f), Color.Transparent),
            startY = tagTop,
            endY = tagTop + tagHeight * 0.4f
        ),
        topLeft = Offset(tagLeft, tagTop),
        size = Size(tagWidth, tagHeight),
        cornerRadius = CornerRadius(8f, 8f)
    )
    // 테두리
    drawRoundRect(
        color = AccentCyan.copy(alpha = 0.2f),
        topLeft = Offset(tagLeft, tagTop),
        size = Size(tagWidth, tagHeight),
        cornerRadius = CornerRadius(8f, 8f),
        style = Stroke(width = 1f)
    )

    // ── 좌측 accent bar (그라디언트) ──
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(AccentCyan, ArTeal)),
        topLeft = Offset(tagLeft + 1f, tagTop + 6f),
        size = Size(3f, tagHeight - 12f),
        cornerRadius = CornerRadius(2f, 2f)
    )

    // ── 타이틀 ──
    drawText(
        textLayoutResult = titleResult,
        topLeft = Offset(innerLeft + padding, tagTop + padding)
    )

    // ── 구분선 + 설명 ──
    if (descResult != null && separatorH > 0f) {
        val lineY = tagTop + padding + titleResult.size.height + separatorH / 2
        drawLine(
            color = AccentCyan.copy(alpha = 0.15f),
            start = Offset(innerLeft + padding, lineY),
            end = Offset(tagLeft + tagWidth - padding, lineY),
            strokeWidth = 0.5f
        )
        drawText(
            textLayoutResult = descResult,
            topLeft = Offset(innerLeft + padding, lineY + separatorH / 2 + 4f)
        )
    }
}

// ════════════════════════════════════════════════════════════════
// 로딩바 (탭 위치 아래 수평 프로그레스)
// ════════════════════════════════════════════════════════════════

/**
 * 화면 정중앙에 표시되는 로딩 인디케이터.
 * 타원형 백배경 + "분석 중" + 순차 깜박이는 점 3개 + shimmer 프로그레스 바.
 */
private fun DrawScope.drawLoadingBar(
    cx: Float, cy: Float,
    shimmerProgress: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    dotCycle: Float
) {
    val barWidth = 220f
    val barHeight = 5f

    // "분석 중" 텍스트 (점 제외)
    val labelResult = textMeasurer.measure(
        text = "분석 중",
        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, color = AccentCyan)
    )

    // 점 3개 각각 측정 (alpha 다르게 그리기 위해)
    val dotResults = (0..2).map { i ->
        // 각 점이 순차적으로 밝아짐: 0번→1번→2번
        val phase = (dotCycle - i * 0.25f).mod(1f)
        val alpha = if (phase < 0.4f) 0.3f + (phase / 0.4f) * 0.7f else 1f - ((phase - 0.4f) / 0.6f) * 0.7f
        val result = textMeasurer.measure(
            text = ".",
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = AccentCyan.copy(alpha = alpha.coerceIn(0.2f, 1f)))
        )
        result
    }
    val dotsWidth = dotResults.sumOf { it.size.width }

    val totalTextW = labelResult.size.width + dotsWidth + 2f

    // Pill 크기
    val pillPadH = 28f
    val pillPadV = 14f
    val innerGap = 10f
    val pillContentW = maxOf(barWidth, totalTextW.toFloat())
    val pillW = pillContentW + pillPadH * 2
    val pillH = labelResult.size.height + innerGap + barHeight + pillPadV * 2
    val pillRadius = pillH / 2

    val pillLeft = cx - pillW / 2
    val pillTop = cy - pillH / 2

    // ── 타원형 배경 ──
    // 외부 글로우
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.3f),
        topLeft = Offset(pillLeft - 4f, pillTop - 4f),
        size = Size(pillW + 8f, pillH + 8f),
        cornerRadius = CornerRadius(pillRadius + 4f, pillRadius + 4f)
    )
    // 메인 배경
    drawRoundRect(
        color = Color(0xE60A0E18),
        topLeft = Offset(pillLeft, pillTop),
        size = Size(pillW, pillH),
        cornerRadius = CornerRadius(pillRadius, pillRadius)
    )
    // 상단 하이라이트
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(AccentCyan.copy(alpha = 0.06f), Color.Transparent),
            startY = pillTop, endY = pillTop + pillH * 0.5f
        ),
        topLeft = Offset(pillLeft, pillTop),
        size = Size(pillW, pillH),
        cornerRadius = CornerRadius(pillRadius, pillRadius)
    )
    // 테두리
    drawRoundRect(
        color = AccentCyan.copy(alpha = 0.3f),
        topLeft = Offset(pillLeft, pillTop),
        size = Size(pillW, pillH),
        cornerRadius = CornerRadius(pillRadius, pillRadius),
        style = Stroke(width = 1f)
    )

    // ── "분석 중" + 순차 깜박이는 점 ──
    val textStartX = pillLeft + (pillW - totalTextW) / 2
    val textY = pillTop + pillPadV
    drawText(textLayoutResult = labelResult, topLeft = Offset(textStartX, textY))

    var dotX = textStartX + labelResult.size.width + 2f
    for (dotResult in dotResults) {
        drawText(textLayoutResult = dotResult, topLeft = Offset(dotX, textY))
        dotX += dotResult.size.width
    }

    // ── 프로그레스 바 ──
    val barLeft = pillLeft + (pillW - barWidth) / 2
    val barY = textY + labelResult.size.height + innerGap

    // 트랙
    drawRoundRect(
        color = AccentCyan.copy(alpha = 0.12f),
        topLeft = Offset(barLeft, barY),
        size = Size(barWidth, barHeight),
        cornerRadius = CornerRadius(barHeight / 2, barHeight / 2)
    )

    // Shimmer sweep
    val shimmerW = barWidth * 0.3f
    val shimmerStart = barLeft + barWidth * shimmerProgress - shimmerW / 2
    val clampedStart = shimmerStart.coerceIn(barLeft, barLeft + barWidth - shimmerW)
    val clampedEnd = (shimmerStart + shimmerW).coerceIn(barLeft, barLeft + barWidth)
    val actualW = clampedEnd - clampedStart

    if (actualW > 0f) {
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, AccentCyan.copy(alpha = 0.9f), ArTeal.copy(alpha = 0.7f), Color.Transparent),
                startX = clampedStart, endX = clampedEnd
            ),
            topLeft = Offset(clampedStart, barY),
            size = Size(actualW, barHeight),
            cornerRadius = CornerRadius(barHeight / 2, barHeight / 2)
        )
    }
}

// ════════════════════════════════════════════════════════════════
// 공통 헬퍼
// ════════════════════════════════════════════════════════════════

private fun DrawScope.drawCornerBrackets(
    left: Float, top: Float, width: Float, height: Float,
    cornerLen: Float, strokeW: Float, color: Color
) {
    drawLine(color, Offset(left, top), Offset(left + cornerLen, top), strokeW)
    drawLine(color, Offset(left, top), Offset(left, top + cornerLen), strokeW)
    drawLine(color, Offset(left + width, top), Offset(left + width - cornerLen, top), strokeW)
    drawLine(color, Offset(left + width, top), Offset(left + width, top + cornerLen), strokeW)
    drawLine(color, Offset(left, top + height), Offset(left + cornerLen, top + height), strokeW)
    drawLine(color, Offset(left, top + height), Offset(left, top + height - cornerLen), strokeW)
    drawLine(color, Offset(left + width, top + height), Offset(left + width - cornerLen, top + height), strokeW)
    drawLine(color, Offset(left + width, top + height), Offset(left + width, top + height - cornerLen), strokeW)
}

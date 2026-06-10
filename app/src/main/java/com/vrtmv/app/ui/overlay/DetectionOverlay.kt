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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
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
import com.vrtmv.app.ui.theme.StatusError
import com.vrtmv.app.util.CoordinateMapper

private val AccentCyan = OverlayCyanBright
private val AccentCyanDim = OverlayCyanDim
private val AccentCyanFill = OverlayCyanFill

@Composable
fun DetectionOverlay(
    detectedObjects: List<DetectedObject>,
    selectedObject: DetectedObject?,
    inferenceState: InferenceState = InferenceState.Idle,
    coordinateMapper: CoordinateMapper,
    tapPoint: Offset? = null,
    /**
     * ARCore 앵커가 뷰프러스텀 안일 때 매 프레임 갱신되는 화면 좌표. 프러스텀 밖이면 null.
     * [arAnchorActive] 와 함께 사용 — null+active=true 면 "앵커 존재하지만 화면 밖" → 태그 숨김.
     */
    anchoredTagPosition: Offset? = null,
    /** ARCore anchor 생성·유지 상태. true 면 태그는 오직 anchor 를 따르며, 없으면 숨김(정적 박스 폴백 없음). */
    arAnchorActive: Boolean = false,
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

    // 글래스 카드 대각선 specular sweep (6초 주기, -0.2→1.2 로 양끝 off-card 진출)
    val specularSweep by infiniteTransition.animateFloat(
        initialValue = -0.2f, targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "specularSweep"
    )

    Canvas(modifier = modifier) {
        // 박스 시각요소는 항상 정적 좌표(coordinateMapper) 기반으로 그리고,
        // 결과 태그만 ARCore anchor 가 활성이면 그 위치를 추종한다.
        selectedObject?.let { obj ->
            val viewRect = coordinateMapper.mapToView(obj.boundingBox)
            drawSelectedObject(
                obj = obj,
                left = viewRect.left, top = viewRect.top,
                width = viewRect.width, height = viewRect.height,
                scanProgress = scanProgress,
                cornerPulse = cornerPulse,
                inferenceState = inferenceState,
                textMeasurer = textMeasurer,
                shimmerProgress = shimmerProgress,
                dotCycle = dotCycle,
                specularSweep = specularSweep,
                anchoredTagPosition = if (arAnchorActive) anchoredTagPosition else null,
                // 분석 중에만 박스 시각요소 노출. 결과 노출 직후에는 결과 태그만 남기고 박스는 숨김.
                showBoundary = inferenceState is InferenceState.Loading
            )
            return@Canvas
        }

        // selectedObject 가 없을 때 — 전체 이미지 모드 / 검출 fallback.
        // anchor 가 활성이고 프러스텀 안이면 anchor 추종, 그 외엔(anchor 미생성/프러스텀 밖) tapPoint 정적 표시.
        val sceneAnchor: Offset? = when {
            arAnchorActive && anchoredTagPosition != null -> anchoredTagPosition
            else -> tapPoint
        }
        if (sceneAnchor != null && inferenceState !is InferenceState.Idle) {
            drawSceneTag(
                tapX = sceneAnchor.x, tapY = sceneAnchor.y,
                cornerPulse = cornerPulse,
                inferenceState = inferenceState,
                textMeasurer = textMeasurer,
                shimmerProgress = shimmerProgress,
                dotCycle = dotCycle,
                specularSweep = specularSweep
            )
            return@Canvas
        }

        // 폴백: anchor/tapPoint 모두 없는데 로딩 상태이면 화면 중앙에 로딩바.
        if (inferenceState is InferenceState.Loading) {
            drawLoadingBar(size.width / 2, size.height / 2, shimmerProgress, textMeasurer, dotCycle, specularSweep)
        }
    }
}

private fun DrawScope.drawSelectedObject(
    obj: DetectedObject,
    left: Float, top: Float, width: Float, height: Float,
    scanProgress: Float, cornerPulse: Float,
    inferenceState: InferenceState,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    shimmerProgress: Float,
    dotCycle: Float,
    specularSweep: Float,
    anchoredTagPosition: Offset?,
    /** false 면 fill/border/brackets/scanLine 등 박스 시각요소를 모두 숨기고 결과 태그만 그린다 (VLM ON). */
    showBoundary: Boolean
) {
    val cornerLen = minOf(width, height) * 0.2f

    if (showBoundary) {
        drawRoundRect(
            color = AccentCyanFill,
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = CornerRadius(4f, 4f)
        )

        drawRoundRect(
            color = AccentCyanDim,
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = CornerRadius(4f, 4f),
            style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        )

        val bracketColor = AccentCyan.copy(alpha = cornerPulse)
        drawCornerBrackets(left, top, width, height, cornerLen, 3f, bracketColor)
        drawCornerBrackets(left, top, width, height, cornerLen, 7f, bracketColor.copy(alpha = cornerPulse * 0.12f))

        val scanY = top + height * scanProgress
        drawLine(
            color = AccentCyan.copy(alpha = 0.5f),
            start = Offset(left + 2f, scanY),
            end = Offset(left + width - 2f, scanY),
            strokeWidth = 1.5f
        )
    }

    if (inferenceState is InferenceState.Loading) {
        drawLoadingBar(size.width / 2, size.height / 2, shimmerProgress, textMeasurer, dotCycle, specularSweep)
    }

    // ARCore anchor 활성이면 태그가 그 좌표를 추종, 아니면 정적 박스 위/아래.
    if (inferenceState is InferenceState.Success || inferenceState is InferenceState.Idle) {
        val tagAnchorX: Float
        val tagAnchorTop: Float
        val tagAnchorBottom: Float
        if (anchoredTagPosition != null) {
            tagAnchorX = anchoredTagPosition.x
            tagAnchorTop = anchoredTagPosition.y - 10f
            tagAnchorBottom = anchoredTagPosition.y + 10f
        } else {
            tagAnchorX = left + width / 2
            tagAnchorTop = top
            tagAnchorBottom = top + height
        }
        drawArResultTag(
            anchorX = tagAnchorX,
            anchorTop = tagAnchorTop,
            anchorBottom = tagAnchorBottom,
            obj = obj,
            inferenceState = inferenceState,
            textMeasurer = textMeasurer,
            specularSweep = specularSweep
        )
    }
}

private fun DrawScope.drawSceneTag(
    tapX: Float, tapY: Float,
    cornerPulse: Float,
    inferenceState: InferenceState,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    shimmerProgress: Float,
    dotCycle: Float,
    specularSweep: Float
) {
    drawCircle(color = AccentCyan.copy(alpha = cornerPulse * 0.15f), radius = 30f, center = Offset(tapX, tapY))
    drawCircle(color = AccentCyan.copy(alpha = cornerPulse * 0.8f), radius = 5f, center = Offset(tapX, tapY))

    if (inferenceState is InferenceState.Loading) {
        drawLoadingBar(size.width / 2, size.height / 2, shimmerProgress, textMeasurer, dotCycle, specularSweep)
        return
    }

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
        useConnector = true,
        specularSweep = specularSweep
    )
}

private fun DrawScope.drawArResultTag(
    anchorX: Float, anchorTop: Float, anchorBottom: Float,
    obj: DetectedObject,
    inferenceState: InferenceState,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    specularSweep: Float
) {
    // 객체 라벨/신뢰도 대신 고정 타이틀 — 장면 태그와 동일 스타일 통일.
    val title = "VISION"
    val desc = when (inferenceState) {
        is InferenceState.Success -> inferenceState.text
        else -> null
    }

    val padding = 12f
    val screenWidth = size.width
    val screenHeight = size.height
    val maxTagWidth = screenWidth * 0.8f

    // 투명 배경 위 텍스트 가독성 확보용 그림자
    val textShadow = Shadow(
        color = Color.Black.copy(alpha = 0.65f),
        offset = Offset(0f, 1.5f),
        blurRadius = 3f
    )

    val titleResult = textMeasurer.measure(
        text = title,
        style = TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = AccentCyan,
            shadow = textShadow
        )
    )

    val descMaxW = (maxTagWidth - padding * 2 - 8f).toInt().coerceAtLeast(200)
    val descResult = desc?.let {
        textMeasurer.measure(
            text = it,
            style = TextStyle(
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.92f),
                lineHeight = 18.sp,
                shadow = textShadow
            ),
            constraints = Constraints(maxWidth = descMaxW),
            overflow = TextOverflow.Ellipsis,
            maxLines = 5
        )
    }

    val separatorH = if (descResult != null) 8f else 0f
    val lineH = if (descResult != null) 1f else 0f
    val contentW = maxOf(titleResult.size.width.toFloat(), descResult?.size?.width?.toFloat() ?: 0f)
    // 8f = 좌측 accent bar 영역
    val tagWidth = (contentW + padding * 2 + 8f).coerceIn(140f, maxTagWidth)
    val tagHeight = padding + titleResult.size.height + separatorH + lineH +
        (descResult?.let { it.size.height + 8f } ?: 0f) + padding

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

    drawLine(
        color = AccentCyan.copy(alpha = 0.3f),
        start = Offset(anchorX, connStart),
        end = Offset(anchorX, connEnd),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
    )

    drawResultTagBody(tagLeft, tagTop, tagWidth, tagHeight, titleResult, descResult, padding, separatorH, specularSweep)
}

private fun DrawScope.drawResultPanel(
    anchorX: Float, anchorY: Float,
    title: String, titleColor: Color, description: String,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    useConnector: Boolean,
    specularSweep: Float
) {
    val padding = 12f
    val screenWidth = size.width
    val screenHeight = size.height
    val maxTagWidth = screenWidth * 0.8f

    val textShadow = Shadow(
        color = Color.Black.copy(alpha = 0.65f),
        offset = Offset(0f, 1.5f),
        blurRadius = 3f
    )

    val titleResult = textMeasurer.measure(
        text = title,
        style = TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = titleColor,
            shadow = textShadow
        )
    )
    val descMaxW = (maxTagWidth - padding * 2 - 8f).toInt().coerceAtLeast(200)
    val descResult = textMeasurer.measure(
        text = description,
        style = TextStyle(
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.92f),
            lineHeight = 18.sp,
            shadow = textShadow
        ),
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

    drawResultTagBody(tagLeft, tagTop, tagWidth, tagHeight, titleResult, descResult, padding, 8f, specularSweep)
}

private fun DrawScope.drawResultTagBody(
    tagLeft: Float, tagTop: Float, tagWidth: Float, tagHeight: Float,
    titleResult: androidx.compose.ui.text.TextLayoutResult,
    descResult: androidx.compose.ui.text.TextLayoutResult?,
    padding: Float, separatorH: Float,
    specularSweep: Float
) {
    val innerLeft = tagLeft + 8f
    val cornerR = 8f

    drawGlassBackground(tagLeft, tagTop, tagWidth, tagHeight, cornerR, specularSweep)

    // 좌측 accent bar — 외부 glow halo 5f + 메인 3f
    drawRoundRect(
        color = AccentCyan.copy(alpha = 0.25f),
        topLeft = Offset(tagLeft, tagTop + 4f),
        size = Size(5f, tagHeight - 8f),
        cornerRadius = CornerRadius(3f, 3f)
    )
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(AccentCyan, ArTeal)),
        topLeft = Offset(tagLeft + 1f, tagTop + 6f),
        size = Size(3f, tagHeight - 12f),
        cornerRadius = CornerRadius(2f, 2f)
    )

    drawText(
        textLayoutResult = titleResult,
        topLeft = Offset(innerLeft + padding, tagTop + padding)
    )

    if (descResult != null && separatorH > 0f) {
        val lineY = tagTop + padding + titleResult.size.height + separatorH / 2
        drawLine(
            color = AccentCyan.copy(alpha = 0.25f),
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

/**
 * 7-레이어 페이크 글래스 배경.
 * Compose 가 backdrop blur 를 못 읽는 SurfaceView/GLSurfaceView 위에서도
 * specular highlight + depth shadow 만으로 유리 같은 입체감을 만든다.
 *
 * 레이어:
 *   1. drop shadow (떠 있는 느낌, 2-ring halo)
 *   2. 메인 fill (translucent navy 22%)
 *   3. 상단 ½ specular gradient (위에서 빛 받음)
 *   4. 하단 ½ inner shadow gradient (글래스 두께감)
 *   5. 상단 edge highlight 라인 (specular 모서리)
 *   6. 하단 edge inner 라인 (depth)
 *   7. 전체 border
 *   + 6초 주기 대각선 specular sweep
 */
private fun DrawScope.drawGlassBackground(
    left: Float, top: Float, width: Float, height: Float,
    cornerRadius: Float,
    specularSweep: Float
) {
    val cr = CornerRadius(cornerRadius, cornerRadius)

    // 1. drop shadow (2-ring halo — 떠 있는 느낌)
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.10f),
        topLeft = Offset(left - 4f, top + 1f),
        size = Size(width + 8f, height + 8f),
        cornerRadius = CornerRadius(cornerRadius + 4f, cornerRadius + 4f)
    )
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.18f),
        topLeft = Offset(left - 2f, top + 3f),
        size = Size(width + 4f, height + 4f),
        cornerRadius = CornerRadius(cornerRadius + 2f, cornerRadius + 2f)
    )

    // 2. 메인 fill (22% navy — 카메라 영상이 비침)
    drawRoundRect(
        color = Color(0x38101520),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = cr
    )

    // 3. 상단 ½ specular (위에서 빛 받음)
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(AccentCyan.copy(alpha = 0.25f), Color.Transparent),
            startY = top,
            endY = top + height * 0.5f
        ),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = cr
    )

    // 4. 하단 ½ inner shadow (글래스 두께감)
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f)),
            startY = top + height * 0.5f,
            endY = top + height
        ),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = cr
    )

    // 5. 상단 edge highlight (specular 모서리)
    drawLine(
        color = AccentCyan.copy(alpha = 0.55f),
        start = Offset(left + cornerRadius * 0.6f, top + 0.75f),
        end = Offset(left + width - cornerRadius * 0.6f, top + 0.75f),
        strokeWidth = 1.5f
    )

    // 6. 하단 edge inner line (depth)
    drawLine(
        color = Color.Black.copy(alpha = 0.30f),
        start = Offset(left + cornerRadius * 0.6f, top + height - 0.25f),
        end = Offset(left + width - cornerRadius * 0.6f, top + height - 0.25f),
        strokeWidth = 0.5f
    )

    // 7. 전체 border
    drawRoundRect(
        color = AccentCyan.copy(alpha = 0.32f),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = cr,
        style = Stroke(width = 1f)
    )

    // 대각선 specular sweep — sweep 위치를 대각선 방향에 매핑해 좁은 highlight 밴드만 보이게.
    // Brush.linearGradient 는 start→end 축 바깥 영역에 첫/끝 색을 clamp 하므로
    // 양끝 Transparent 사이에 중앙 cyan 을 두면 [start, end] 구간만 빛난다.
    val diagonal = width + height
    val sweepCenter = diagonal * specularSweep
    val bandHalf = diagonal * 0.12f
    val unitX = width / diagonal
    val unitY = height / diagonal
    val sx = (sweepCenter - bandHalf) * unitX
    val sy = (sweepCenter - bandHalf) * unitY
    val ex = (sweepCenter + bandHalf) * unitX
    val ey = (sweepCenter + bandHalf) * unitY
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                AccentCyan.copy(alpha = 0.18f),
                Color.Transparent
            ),
            start = Offset(left + sx, top + sy),
            end = Offset(left + ex, top + ey)
        ),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = cr
    )
}

private fun DrawScope.drawLoadingBar(
    cx: Float, cy: Float,
    shimmerProgress: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    dotCycle: Float,
    specularSweep: Float
) {
    val barWidth = 220f
    val barHeight = 5f

    val textShadow = Shadow(
        color = Color.Black.copy(alpha = 0.65f),
        offset = Offset(0f, 1.5f),
        blurRadius = 3f
    )

    val labelResult = textMeasurer.measure(
        text = "분석 중",
        style = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = AccentCyan,
            shadow = textShadow
        )
    )

    // 0→1→2 점이 순차적으로 밝아지도록 alpha 계산
    val dotResults = (0..2).map { i ->
        val phase = (dotCycle - i * 0.25f).mod(1f)
        val alpha = if (phase < 0.4f) 0.3f + (phase / 0.4f) * 0.7f else 1f - ((phase - 0.4f) / 0.6f) * 0.7f
        textMeasurer.measure(
            text = ".",
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = AccentCyan.copy(alpha = alpha.coerceIn(0.2f, 1f)),
                shadow = textShadow
            )
        )
    }
    val dotsWidth = dotResults.sumOf { it.size.width }

    val totalTextW = labelResult.size.width + dotsWidth + 2f

    val pillPadH = 28f
    val pillPadV = 14f
    val innerGap = 10f
    val pillContentW = maxOf(barWidth, totalTextW.toFloat())
    val pillW = pillContentW + pillPadH * 2
    val pillH = labelResult.size.height + innerGap + barHeight + pillPadV * 2
    val pillRadius = pillH / 2

    val pillLeft = cx - pillW / 2
    val pillTop = cy - pillH / 2

    drawGlassBackground(pillLeft, pillTop, pillW, pillH, pillRadius, specularSweep)

    val textStartX = pillLeft + (pillW - totalTextW) / 2
    val textY = pillTop + pillPadV
    drawText(textLayoutResult = labelResult, topLeft = Offset(textStartX, textY))

    var dotX = textStartX + labelResult.size.width + 2f
    for (dotResult in dotResults) {
        drawText(textLayoutResult = dotResult, topLeft = Offset(dotX, textY))
        dotX += dotResult.size.width
    }

    val barLeft = pillLeft + (pillW - barWidth) / 2
    val barY = textY + labelResult.size.height + innerGap

    drawRoundRect(
        color = AccentCyan.copy(alpha = 0.12f),
        topLeft = Offset(barLeft, barY),
        size = Size(barWidth, barHeight),
        cornerRadius = CornerRadius(barHeight / 2, barHeight / 2)
    )

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

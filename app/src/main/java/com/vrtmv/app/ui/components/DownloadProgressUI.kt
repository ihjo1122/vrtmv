package com.vrtmv.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.vrtmv.app.data.download.DownloadProgress
import com.vrtmv.app.ui.theme.ArCyan
import com.vrtmv.app.ui.theme.ArTeal
import com.vrtmv.app.ui.theme.SurfaceOverlay
import com.vrtmv.app.ui.theme.TextPrimary
import com.vrtmv.app.ui.theme.TextSecondary

@Composable
fun DownloadProgressUI(
    progress: DownloadProgress?,
    modelName: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "모델 다운로드 중...",
            color = TextPrimary,
            style = MaterialTheme.typography.titleSmall
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = modelName,
            color = ArCyan,
            style = MaterialTheme.typography.labelMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        val progressValue = progress?.progress ?: 0f
        val hasProgress = progress != null && progressValue > 0f

        GradientProgressBar(
            progress = if (hasProgress) progressValue else null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        if (progress != null && progressValue > 0f) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${(progressValue * 100).toInt()}%  (${progress.downloadedMB}MB / ${progress.totalMB}MB)",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun GradientProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier
) {
    val trackColor = SurfaceOverlay
    val gradientBrush = Brush.horizontalGradient(listOf(ArTeal, ArCyan))

    Canvas(
        modifier = modifier
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
    ) {
        // Track
        drawRoundRect(
            color = trackColor,
            cornerRadius = CornerRadius(3.dp.toPx())
        )

        if (progress != null && progress > 0f) {
            // Gradient fill
            val fillWidth = size.width * progress.coerceIn(0f, 1f)
            drawRoundRect(
                brush = gradientBrush,
                size = Size(fillWidth, size.height),
                cornerRadius = CornerRadius(3.dp.toPx())
            )
            // Glow at leading edge
            if (fillWidth > 4.dp.toPx()) {
                drawCircle(
                    color = ArCyan.copy(alpha = 0.6f),
                    radius = 4.dp.toPx(),
                    center = Offset(fillWidth, size.height / 2)
                )
            }
        } else {
            // Indeterminate shimmer placeholder
            val shimmerWidth = size.width * 0.3f
            drawRoundRect(
                brush = gradientBrush,
                topLeft = Offset(0f, 0f),
                size = Size(shimmerWidth, size.height),
                cornerRadius = CornerRadius(3.dp.toPx())
            )
        }
    }
}

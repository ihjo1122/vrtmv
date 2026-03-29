package com.vrtmv.app.ui.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vrtmv.app.domain.model.ModelInfo
import com.vrtmv.app.ui.components.AppHeader
import com.vrtmv.app.ui.components.DownloadProgressUI
import com.vrtmv.app.ui.intro.HudBackground
import com.vrtmv.app.ui.theme.ArCyan
import com.vrtmv.app.ui.theme.StatusError
import com.vrtmv.app.ui.theme.SurfaceDark
import com.vrtmv.app.ui.theme.SurfaceElevated
import com.vrtmv.app.ui.theme.SurfaceOverlay
import com.vrtmv.app.ui.theme.TextPrimary
import com.vrtmv.app.ui.theme.TextSecondary

@Composable
fun MainScreen(
    onNavigateToCamera: (modelId: String) -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val downloadState by viewModel.downloadState.collectAsState()
    val models = viewModel.getModels()

    LaunchedEffect(downloadState) {
        if (downloadState is MainDownloadState.Ready) {
            val modelId = (downloadState as MainDownloadState.Ready).modelId
            viewModel.resetState()
            onNavigateToCamera(modelId)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
    ) {
        HudBackground()

        // App branding (top)
        AppHeader(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 100.dp)
        )

        // Model cards (center)
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            models.forEach { modelInfo ->
                ModelCard(
                    modelInfo = modelInfo,
                    onClick = { viewModel.onModelSelected(modelInfo) },
                    enabled = downloadState is MainDownloadState.Idle
                )
            }
        }

        // Bottom instruction
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.TouchApp,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "터치로 객체를 선택하고 AI가 설명합니다",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    // Download dialog
    when (val state = downloadState) {
        is MainDownloadState.Downloading -> {
            AlertDialog(
                onDismissRequest = { },
                containerColor = SurfaceOverlay,
                title = null,
                text = {
                    DownloadProgressUI(
                        progress = state.progress,
                        modelName = state.modelInfo.displayName
                    )
                },
                confirmButton = { }
            )
        }
        is MainDownloadState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetState() },
                containerColor = SurfaceOverlay,
                title = {
                    Text("오류", color = StatusError)
                },
                text = {
                    Text(state.message, color = TextPrimary.copy(alpha = 0.8f))
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.resetState() }) {
                        Text("확인", color = ArCyan)
                    }
                }
            )
        }
        else -> { }
    }
}

@Composable
private fun ModelCard(
    modelInfo: ModelInfo,
    onClick: () -> Unit,
    enabled: Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, label = "cardScale")

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .scale(scale)
            .border(
                width = 1.dp,
                color = ArCyan.copy(alpha = if (enabled) 0.3f else 0.1f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(80.dp)
        ) {
            // Left accent bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(80.dp)
                    .background(ArCyan.copy(alpha = if (enabled) 0.8f else 0.3f))
            )

            // Model info
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = modelInfo.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Quantization badge
                    Text(
                        text = modelInfo.quantization.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = ArCyan,
                        modifier = Modifier
                            .border(
                                width = 1.dp,
                                color = ArCyan.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    // Size
                    Text(
                        text = "%.1f GB".format(modelInfo.expectedSizeMB / 1000f),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }
            }

            // Camera icon
            Box(
                modifier = Modifier
                    .padding(end = 16.dp)
                    .size(40.dp)
                    .background(
                        ArCyan.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = null,
                    tint = ArCyan,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

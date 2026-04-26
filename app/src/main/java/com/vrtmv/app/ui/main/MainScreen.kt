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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import com.vrtmv.app.domain.model.DetectorKind
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
    onNavigateToCamera: (modelId: String, detectorId: String, useArCore: Boolean, fullFrameVlm: Boolean) -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val downloadState by viewModel.downloadState.collectAsState()
    val useArCore by viewModel.useArCore.collectAsState()
    val fullFrameVlm by viewModel.fullFrameVlm.collectAsState()
    val modelInfo = remember { viewModel.getDefaultModel() }

    LaunchedEffect(downloadState) {
        if (downloadState is MainDownloadState.Ready) {
            val ready = downloadState as MainDownloadState.Ready
            viewModel.resetState()
            onNavigateToCamera(ready.modelId, ready.detectorKind.id, ready.useArCore, ready.fullFrameVlm)
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

        // 검출기 선택 버튼 (중앙)
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 상단: 사용 모델 정보 카드
            ModelInfoBanner(modelInfo = modelInfo)

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "객체 검출기 선택",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium
            )

            DetectorCard(
                kind = DetectorKind.MEDIAPIPE,
                subtitle = "COCO 80 · 안정 · 모바일 최적",
                icon = Icons.Filled.Hub,
                onClick = { viewModel.onDetectorSelected(DetectorKind.MEDIAPIPE) },
                enabled = downloadState is MainDownloadState.Idle
            )

            DetectorCard(
                kind = DetectorKind.YOLO,
                subtitle = "COCO 80 · 고정밀 · 작은 객체 강점",
                icon = Icons.Filled.Bolt,
                onClick = { viewModel.onDetectorSelected(DetectorKind.YOLO) },
                enabled = downloadState is MainDownloadState.Idle
            )

            Spacer(modifier = Modifier.height(8.dp))

            ArCoreToggleRow(
                checked = useArCore,
                onToggle = { viewModel.toggleArCore() },
                enabled = downloadState is MainDownloadState.Idle
            )

            FullFrameVlmToggleRow(
                checked = fullFrameVlm,
                onToggle = { viewModel.toggleFullFrameVlm() },
                enabled = downloadState is MainDownloadState.Idle
            )
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
private fun FullFrameVlmToggleRow(
    checked: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .background(SurfaceElevated.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .border(
                1.dp,
                ArCyan.copy(alpha = if (checked) 0.45f else 0.15f),
                RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Image,
            contentDescription = null,
            tint = if (checked) ArCyan else TextSecondary,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "VLM 전체 이미지 분석",
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary
            )
            Text(
                text = if (checked) "ON — 상황·맥락 포함 (권장)"
                       else "OFF — 선택 객체만 크롭하여 분석",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = ArCyan.copy(alpha = 0.6f),
                checkedThumbColor = ArCyan,
                uncheckedTrackColor = SurfaceElevated,
                uncheckedThumbColor = TextSecondary
            )
        )
    }
}

@Composable
private fun ArCoreToggleRow(
    checked: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .background(SurfaceElevated.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .border(
                1.dp,
                ArCyan.copy(alpha = if (checked) 0.45f else 0.15f),
                RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.ViewInAr,
            contentDescription = null,
            tint = if (checked) ArCyan else TextSecondary,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "ARCore 오버레이 사용",
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary
            )
            Text(
                text = if (checked) "월드 앵커 기반 — 카메라 이동에도 태그 고정"
                       else "OFF — CameraX 화면 좌표 기반 (안정)",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = ArCyan.copy(alpha = 0.6f),
                checkedThumbColor = ArCyan,
                uncheckedTrackColor = SurfaceElevated,
                uncheckedThumbColor = TextSecondary
            )
        )
    }
}

@Composable
private fun ModelInfoBanner(modelInfo: ModelInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .background(SurfaceElevated.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .border(1.dp, ArCyan.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.CameraAlt,
            contentDescription = null,
            tint = ArCyan,
            modifier = Modifier.size(18.dp)
        )
        Column {
            Text(
                text = "VLM: ${modelInfo.displayName}",
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary
            )
            Text(
                text = "${modelInfo.quantization.uppercase()} · %.1f GB".format(modelInfo.expectedSizeMB / 1000f),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun DetectorCard(
    kind: DetectorKind,
    subtitle: String,
    icon: ImageVector,
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
            modifier = Modifier.height(84.dp)
        ) {
            // Left accent bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(84.dp)
                    .background(ArCyan.copy(alpha = if (enabled) 0.8f else 0.3f))
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = kind.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Box(
                modifier = Modifier
                    .padding(end = 16.dp)
                    .size(44.dp)
                    .background(ArCyan.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = ArCyan,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

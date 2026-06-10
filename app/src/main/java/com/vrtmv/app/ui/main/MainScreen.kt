package com.vrtmv.app.ui.main

import android.content.Intent
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
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vrtmv.app.data.recording.CaptureMode
import com.vrtmv.app.domain.model.ModelInfo
import com.vrtmv.app.ui.components.AppHeader
import com.vrtmv.app.ui.components.DownloadProgressUI
import com.vrtmv.app.ui.intro.HudBackground
import com.vrtmv.app.ui.records.RecordListActivity
import com.vrtmv.app.ui.theme.ArCyan
import com.vrtmv.app.ui.theme.StatusError
import com.vrtmv.app.ui.theme.SurfaceDark
import com.vrtmv.app.ui.theme.SurfaceElevated
import com.vrtmv.app.ui.theme.SurfaceOverlay
import com.vrtmv.app.ui.theme.TextPrimary
import com.vrtmv.app.ui.theme.TextSecondary

@Composable
fun MainScreen(
    onNavigateToCamera: (modelId: String, captureMode: CaptureMode) -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val downloadState by viewModel.downloadState.collectAsState()
    val modelInfo = remember { viewModel.getDefaultModel() }
    val context = LocalContext.current

    LaunchedEffect(downloadState) {
        if (downloadState is MainDownloadState.Ready) {
            val ready = downloadState as MainDownloadState.Ready
            viewModel.resetState()
            onNavigateToCamera(ready.modelId, ready.captureMode)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
    ) {
        HudBackground()

        AppHeader(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 100.dp)
        )

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ModelInfoBanner(modelInfo = modelInfo)

            Spacer(modifier = Modifier.height(8.dp))

            ModeCard(
                title = "객체 검출 모드 시작",
                subtitle = "탭한 객체를 25% 패딩으로 크롭 → VLM",
                icon = Icons.Filled.CenterFocusStrong,
                onClick = { viewModel.onModeSelected(CaptureMode.OBJECT_DETECTION) },
                enabled = downloadState is MainDownloadState.Idle
            )

            ModeCard(
                title = "객체 검출 (패딩 없음) 시작",
                subtitle = "탭한 객체를 패딩 없이 크롭 → VLM",
                icon = Icons.Filled.CropFree,
                onClick = { viewModel.onModeSelected(CaptureMode.OBJECT_DETECTION_NO_PADDING) },
                enabled = downloadState is MainDownloadState.Idle
            )

            ModeCard(
                title = "전체 이미지 모드 시작",
                subtitle = "하단 시작 버튼으로 전체 프레임 → VLM",
                icon = Icons.Filled.Image,
                onClick = { viewModel.onModeSelected(CaptureMode.FULL_FRAME) },
                enabled = downloadState is MainDownloadState.Idle
            )

            ModeCard(
                title = "실험 기록 보기",
                subtitle = "저장된 메트릭 PNG 리스트/상세",
                icon = Icons.Filled.PhotoLibrary,
                onClick = {
                    context.startActivity(Intent(context, RecordListActivity::class.java))
                },
                enabled = true
            )
        }

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
                text = "터치로 객체를 선택하면 자동으로 기록이 저장됩니다",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

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
                title = { Text("오류", color = StatusError) },
                text = { Text(state.message, color = TextPrimary.copy(alpha = 0.8f)) },
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
private fun ModeCard(
    title: String,
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
                    text = title,
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

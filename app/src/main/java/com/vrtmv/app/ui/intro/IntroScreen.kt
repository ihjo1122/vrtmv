package com.vrtmv.app.ui.intro

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrtmv.app.ui.components.AppHeader
import com.vrtmv.app.ui.components.GradientProgressBar
import com.vrtmv.app.ui.theme.ArCyan
import com.vrtmv.app.ui.theme.StatusError
import com.vrtmv.app.ui.theme.SurfaceDark
import com.vrtmv.app.ui.theme.TextPrimary
import com.vrtmv.app.ui.theme.TextSecondary
import com.vrtmv.app.ui.theme.TextTertiary

@Composable
fun IntroScreen(
    onNavigateToMain: () -> Unit,
    viewModel: IntroViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var storageGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = Environment.isExternalStorageManager()
        if (granted && !storageGranted) {
            storageGranted = true
            viewModel.retry()
        }
        storageGranted = granted
    }

    if (!storageGranted) {
        StoragePermissionScreen(
            onRequestPermission = {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        )
        return
    }

    LaunchedEffect(uiState) {
        if (uiState is IntroUiState.Ready) {
            onNavigateToMain()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
    ) {
        // HUD Background
        HudBackground()

        // App branding (center) with entrance animation
        val headerVisible = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = headerVisible,
            enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { -40 },
            modifier = Modifier.align(Alignment.Center)
        ) {
            AppHeader(titleSize = 48.sp)
        }

        // Bottom status area
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (val state = uiState) {
                is IntroUiState.Checking -> {
                    Text(
                        text = "패치 사항 확인중...",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    GradientProgressBar(
                        progress = null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                is IntroUiState.ModelReady -> {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Text(
                            text = "최신 상태입니다",
                            style = MaterialTheme.typography.bodySmall,
                            color = ArCyan
                        )
                    }
                    GradientProgressBar(
                        progress = 1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                is IntroUiState.Downloading -> {
                    val percent = (state.progress * 100).toInt()
                    Text(
                        text = "모델 다운로드 중... $percent%",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    GradientProgressBar(
                        progress = state.progress,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.totalMB > 0) {
                        Text(
                            text = "${state.downloadedMB}MB / ${state.totalMB}MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                is IntroUiState.DownloadError -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = StatusError,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusError,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { viewModel.retry() },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = ArCyan
                        ),
                        border = ButtonDefaults.outlinedButtonBorder(true).copy(
                            brush = Brush.linearGradient(
                                listOf(ArCyan.copy(alpha = 0.5f), ArCyan.copy(alpha = 0.2f))
                            )
                        )
                    ) {
                        Text("다시 시도")
                    }
                    TextButton(
                        onClick = { viewModel.skip() }
                    ) {
                        Text(
                            text = "건너뛰기",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                is IntroUiState.Ready -> { }
            }
        }
    }
}

@Composable
private fun StoragePermissionScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
    ) {
        HudBackground()

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppHeader(titleSize = 48.sp)

            Spacer(modifier = Modifier.height(48.dp))

            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = ArCyan.copy(alpha = 0.6f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "모델 파일 접근을 위해\n파일 관리 권한이 필요합니다",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedButton(
                onClick = onRequestPermission,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = ArCyan
                ),
                border = ButtonDefaults.outlinedButtonBorder(true).copy(
                    brush = Brush.linearGradient(
                        listOf(ArCyan.copy(alpha = 0.5f), ArCyan.copy(alpha = 0.2f))
                    )
                )
            ) {
                Text("권한 설정")
            }
        }
    }
}

/**
 * HUD-style background: radial glow + grid pattern.
 * Shared between IntroScreen and MainScreen.
 */
@Composable
fun HudBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        // Radial glow at upper center
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    ArCyan.copy(alpha = 0.06f),
                    ArCyan.copy(alpha = 0.02f),
                    Color.Transparent
                ),
                center = Offset(size.width / 2, size.height * 0.35f),
                radius = size.minDimension * 0.8f
            ),
            radius = size.minDimension * 0.8f,
            center = Offset(size.width / 2, size.height * 0.35f)
        )

        // Grid lines
        val gridSpacing = 80.dp.toPx()
        val gridColor = TextTertiary.copy(alpha = 0.04f)
        val strokeWidth = 0.5.dp.toPx()

        // Vertical lines
        var x = gridSpacing
        while (x < size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth)
            x += gridSpacing
        }
        // Horizontal lines
        var y = gridSpacing
        while (y < size.height) {
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth)
            y += gridSpacing
        }
    }
}

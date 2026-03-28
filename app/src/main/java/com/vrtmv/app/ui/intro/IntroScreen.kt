package com.vrtmv.app.ui.intro

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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

private val CyanAccent = Color(0xFF00BCD4)
private val DarkBackground = Color(0xFF0A0A0A)
private val SubtleGray = Color(0xFF888888)

@Composable
fun IntroScreen(
    onNavigateToMain: () -> Unit,
    viewModel: IntroViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var storageGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }

    // 설정 화면에서 돌아왔을 때 권한 재확인
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = Environment.isExternalStorageManager()
        if (granted && !storageGranted) {
            storageGranted = true
            viewModel.retry()
        }
        storageGranted = granted
    }

    // 권한이 없으면 요청 UI 표시
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
            .background(DarkBackground)
    ) {
        // 앱 이름 (중앙)
        AppHeader(
            titleSize = 48.sp,
            modifier = Modifier.align(Alignment.Center)
        )

        // 하단 상태 영역
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
                        fontSize = 14.sp,
                        color = SubtleGray
                    )
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = CyanAccent,
                        trackColor = Color(0xFF1A1A1A)
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
                            fontSize = 14.sp,
                            color = CyanAccent
                        )
                    }
                    LinearProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.fillMaxWidth(),
                        color = CyanAccent,
                        trackColor = Color(0xFF1A1A1A)
                    )
                }

                is IntroUiState.Downloading -> {
                    val percent = (state.progress * 100).toInt()
                    Text(
                        text = "모델 다운로드 중... $percent%",
                        fontSize = 14.sp,
                        color = Color.White
                    )
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = CyanAccent,
                        trackColor = Color(0xFF1A1A1A)
                    )
                    if (state.totalMB > 0) {
                        Text(
                            text = "${state.downloadedMB}MB / ${state.totalMB}MB",
                            fontSize = 12.sp,
                            color = SubtleGray
                        )
                    }
                }

                is IntroUiState.DownloadError -> {
                    Text(
                        text = state.message,
                        fontSize = 14.sp,
                        color = Color(0xFFFF5252),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { viewModel.retry() },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = CyanAccent
                        )
                    ) {
                        Text("다시 시도")
                    }
                    TextButton(
                        onClick = { viewModel.skip() }
                    ) {
                        Text(
                            text = "건너뛰기",
                            color = SubtleGray,
                            fontSize = 12.sp
                        )
                    }
                }

                is IntroUiState.Ready -> {
                    // 네비게이션 처리 중 — LaunchedEffect에서 처리
                }
            }
        }
    }
}

@Composable
private fun StoragePermissionScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        AppHeader(
            titleSize = 48.sp,
            modifier = Modifier.align(Alignment.Center)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "모델 파일 접근을 위해\n파일 관리 권한이 필요합니다",
                fontSize = 14.sp,
                color = SubtleGray,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = onRequestPermission,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = CyanAccent
                )
            ) {
                Text("권한 설정")
            }
        }
    }
}

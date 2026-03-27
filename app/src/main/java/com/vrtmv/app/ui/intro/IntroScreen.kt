package com.vrtmv.app.ui.intro

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val CyanAccent = Color(0xFF00BCD4)
private val DarkBackground = Color(0xFF0A0A0A)
private val SubtleGray = Color(0xFF888888)

@Composable
fun IntroScreen(
    onNavigateToMain: () -> Unit,
    viewModel: IntroViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "VRTMV",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = CyanAccent,
                letterSpacing = 8.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Visual Real-Time Mobile Vision",
                fontSize = 12.sp,
                color = SubtleGray,
                letterSpacing = 2.sp
            )
        }

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

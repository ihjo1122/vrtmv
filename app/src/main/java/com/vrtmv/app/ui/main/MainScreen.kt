package com.vrtmv.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vrtmv.app.domain.model.ModelInfo
import com.vrtmv.app.ui.components.AppHeader
import com.vrtmv.app.ui.components.DownloadProgressUI

private val CyanAccent = Color(0xFF00BCD4)
private val DarkBackground = Color(0xFF0A0A0A)
private val SubtleGray = Color(0xFF888888)

@Composable
fun MainScreen(
    onNavigateToCamera: (modelId: String) -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val downloadState by viewModel.downloadState.collectAsState()
    val models = viewModel.getModels()

    // Ready 상태 → CameraScreen으로 이동
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
            .background(DarkBackground)
    ) {
        // 앱 브랜딩 (상단)
        AppHeader(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 120.dp)
        )

        // 모델별 AR Camera 버튼 (중앙)
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            models.forEach { modelInfo ->
                ModelButton(
                    modelInfo = modelInfo,
                    onClick = { viewModel.onModelSelected(modelInfo) },
                    enabled = downloadState is MainDownloadState.Idle
                )
            }
        }

        // 하단 안내
        Text(
            text = "터치로 객체를 선택하고 AI가 설명합니다",
            color = SubtleGray,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        )
    }

    // 다운로드 다이얼로그
    when (val state = downloadState) {
        is MainDownloadState.Downloading -> {
            AlertDialog(
                onDismissRequest = { },
                containerColor = Color(0xFF1A1A1A),
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
                containerColor = Color(0xFF1A1A1A),
                title = {
                    Text("오류", color = Color.White)
                },
                text = {
                    Text(state.message, color = Color.White.copy(alpha = 0.8f))
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.resetState() }) {
                        Text("확인", color = CyanAccent)
                    }
                }
            )
        }
        else -> { }
    }
}

@Composable
private fun ModelButton(
    modelInfo: ModelInfo,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CyanAccent,
            contentColor = Color.Black
        ),
        modifier = Modifier
            .fillMaxWidth(0.75f)
            .height(64.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.size(12.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "AR Camera",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = modelInfo.displayName,
                fontSize = 11.sp,
                color = Color.Black.copy(alpha = 0.6f)
            )
        }
    }
}

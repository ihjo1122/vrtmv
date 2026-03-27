package com.vrtmv.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrtmv.app.data.download.DownloadProgress

/**
 * 다운로드 진행률 공통 UI 컴포넌트.
 * IntroScreen과 MainScreen 다이얼로그에서 재사용.
 */
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
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = modelName,
            color = Color(0xFF00BCD4),
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (progress != null && progress.progress > 0f) {
            LinearProgressIndicator(
                progress = { progress.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .padding(horizontal = 16.dp),
                color = Color(0xFF00BCD4),
                trackColor = Color.White.copy(alpha = 0.2f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "${(progress.progress * 100).toInt()}%  (${progress.downloadedMB}MB / ${progress.totalMB}MB)",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .padding(horizontal = 16.dp),
                color = Color(0xFF00BCD4),
                trackColor = Color.White.copy(alpha = 0.2f)
            )
        }
    }
}

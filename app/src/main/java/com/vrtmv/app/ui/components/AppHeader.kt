package com.vrtmv.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CyanAccent = Color(0xFF00BCD4)
private val SubtleGray = Color(0xFF888888)

/**
 * 앱 브랜딩 헤더 (VRTMV + 부제).
 * IntroScreen과 MainScreen에서 공통 사용.
 */
@Composable
fun AppHeader(
    titleSize: TextUnit = 36.sp,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "VRTMV",
            fontSize = titleSize,
            fontWeight = FontWeight.Bold,
            color = CyanAccent,
            letterSpacing = (titleSize.value / 6).sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Visual Real-Time Mobile Vision",
            fontSize = if (titleSize.value >= 48) 12.sp else 11.sp,
            color = SubtleGray,
            letterSpacing = 2.sp
        )
    }
}

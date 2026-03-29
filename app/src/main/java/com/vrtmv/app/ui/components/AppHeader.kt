package com.vrtmv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrtmv.app.ui.theme.ScanLineBrush
import com.vrtmv.app.ui.theme.TextSecondary
import com.vrtmv.app.ui.theme.TitleGradient

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
            style = TextStyle(
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                letterSpacing = (titleSize.value / 6).sp,
                brush = TitleGradient
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "VISUAL REAL-TIME MOBILE VISION",
            fontSize = if (titleSize.value >= 48) 12.sp else 11.sp,
            fontFamily = FontFamily.Monospace,
            color = TextSecondary,
            letterSpacing = 3.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(0.5.dp)
                .background(ScanLineBrush)
        )
    }
}

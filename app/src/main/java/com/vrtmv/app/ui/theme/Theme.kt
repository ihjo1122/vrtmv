package com.vrtmv.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** 기본 다크 테마 색상 */
private val DarkColorScheme = darkColorScheme()

/** 기본 라이트 테마 색상 */
private val LightColorScheme = lightColorScheme()

/**
 * 앱 전역 Material3 테마.
 * Android 12+ 에서는 시스템 다이나믹 컬러를 사용하고,
 * 이하 버전에서는 기본 색상 스킴을 적용한다.
 */
@Composable
fun VrtmvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Android 12(S) 이상: 시스템 배경화면 기반 다이나믹 컬러
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

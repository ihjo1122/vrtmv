package com.vrtmv.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val VrtmvColorScheme = darkColorScheme(
    primary = ArCyan,
    secondary = ArTeal,
    tertiary = ArDeepBlue,
    background = SurfaceDark,
    surface = SurfaceElevated,
    surfaceVariant = SurfaceOverlay,
    onPrimary = SurfaceDark,
    onSecondary = SurfaceDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = StatusError,
    onError = TextPrimary
)

@Composable
fun VrtmvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VrtmvColorScheme,
        typography = VrtmvTypography,
        content = content
    )
}

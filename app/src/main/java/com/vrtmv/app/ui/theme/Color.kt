package com.vrtmv.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ── Primary Accent ──────────────────────────────────────────
val ArCyan = Color(0xFF00E5FF)
val ArTeal = Color(0xFF00BFA5)
val ArDeepBlue = Color(0xFF0D47A1)

// ── Surfaces ────────────────────────────────────────────────
val SurfaceDark = Color(0xFF0A0A0A)
val SurfaceElevated = Color(0xFF111318)
val SurfaceOverlay = Color(0xFF1A1F2E)

// ── Text ────────────────────────────────────────────────────
val TextPrimary = Color(0xFFE8EAED)
val TextSecondary = Color(0xFF9AA0A6)
val TextTertiary = Color(0xFF5F6368)

// ── Status ──────────────────────────────────────────────────
val StatusError = Color(0xFFEF5350)
val StatusSuccess = Color(0xFF66BB6A)
val StatusWarning = Color(0xFFFFCA28)

// ── Overlay (Canvas drawing) ────────────────────────────────
val OverlayCyanBright = Color(0xFF00E5FF)
val OverlayCyanDim = Color(0x6600E5FF)       // 40% alpha
val OverlayCyanFill = Color(0x1400E5FF)      // 8% alpha
val OverlayTagBg = Color(0xCC000000)         // 80% black
val OverlayTagBgSelected = Color(0xE6001529) // dark blue-black
val OverlayUnselected = Color(0x80FFFFFF)    // 50% white

// ── Crosshair ───────────────────────────────────────────────
val CrosshairGold = Color(0xFFFFAB00)

// ── Gradient Brushes ────────────────────────────────────────
val TitleGradient = Brush.linearGradient(listOf(ArTeal, ArCyan))

val ScanLineBrush = Brush.horizontalGradient(
    listOf(Color.Transparent, ArCyan.copy(alpha = 0.6f), Color.Transparent)
)

val CardBorderBrush = Brush.linearGradient(
    listOf(ArCyan.copy(alpha = 0.5f), ArTeal.copy(alpha = 0.2f))
)

val ProgressGradient = Brush.horizontalGradient(listOf(ArTeal, ArCyan))

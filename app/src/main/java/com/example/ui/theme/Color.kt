package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object RenderSAColors {
    val BackgroundBase = Color(0xFF121212)
    val BackgroundTopGradient = Color(0xFF282828)

    val SurfaceCard = Color(0x12FFFFFF)
    val SurfaceCardElevated = Color(0x1AFFFFFF)
    val SurfaceInteractive = Color(0x24FFFFFF)
    val SurfaceModal = Color(0xFF1E1E1E)
    val ScrimOverlay = Color(0xAD000000)

    val BorderSubtle = Color(0x1AFFFFFF)
    val BorderInteractive = Color(0x29FFFFFF)
    val DividerSubtle = Color(0x14FFFFFF)

    val AccentPrimary = Color(0xFF0A78D2)
    val AccentBorder = Color(0xFF3A96E4)
    val AccentSubtleBg = Color(0x240A78D2)
    val RadialGlowAccent = Color(0x1A0A78D2)
    val RadialGlowNeutral = Color(0x14FFFFFF)

    val TextPrimary = Color(0xFFF2F2F2)
    val TextSecondary = Color(0xFF9A9A9A)
    val TextMuted = Color(0xFF6E6E6E)

    val StatusReadyDot = Color(0xFF4CAF50)
    val StatusWarningDot = Color(0xFFB0B0B0)

    val ScreenBackgroundBrush = Brush.verticalGradient(
        colors = listOf(
            BackgroundTopGradient,
            BackgroundBase,
            BackgroundBase
        )
    )
}

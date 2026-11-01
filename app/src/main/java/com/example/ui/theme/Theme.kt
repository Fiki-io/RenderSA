package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RenderSADarkColorScheme = darkColorScheme(
    primary = RenderSAColors.AccentPrimary,
    onPrimary = RenderSAColors.TextPrimary,
    secondary = RenderSAColors.AccentPrimary,
    onSecondary = RenderSAColors.TextPrimary,
    background = RenderSAColors.BackgroundBase,
    onBackground = RenderSAColors.TextPrimary,
    surface = RenderSAColors.SurfaceModal,
    onSurface = RenderSAColors.TextPrimary,
    surfaceVariant = RenderSAColors.SurfaceCard,
    onSurfaceVariant = RenderSAColors.TextSecondary,
    outline = RenderSAColors.BorderSubtle
)

@Composable
fun RenderSATheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = RenderSADarkColorScheme,
        typography = RenderSATypography,
        shapes = RenderSAShapes.MaterialShapes,
        content = content
    )
}

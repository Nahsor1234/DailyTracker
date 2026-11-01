package com.nahsor1234.dailytracker

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DailyTrackerColors = darkColorScheme(
    primary = Color(0xFFB8D9A9),
    onPrimary = Color(0xFF172218),
    primaryContainer = Color(0xFF354A38),
    onPrimaryContainer = Color(0xFFE0F0D9),
    secondary = Color(0xFFC4D0BE),
    onSecondary = Color(0xFF253126),
    background = Color(0xFF101510),
    onBackground = Color(0xFFF0F2EC),
    surface = Color(0xFF1A241D),
    onSurface = Color(0xFFF0F2EC),
    surfaceVariant = Color(0xFF26352A),
    onSurfaceVariant = Color(0xFFA5B0A3),
    outline = Color(0xFF354438),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
internal fun DailyTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DailyTrackerColors, typography = Typography(), content = content)
}

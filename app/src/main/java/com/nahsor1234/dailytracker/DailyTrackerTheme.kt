package com.nahsor1234.dailytracker

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DailyTrackerColors = darkColorScheme(
    primary = Color(0xFFB5D3A9),
    onPrimary = Color(0xFF20301D),
    primaryContainer = Color(0xFF354A31),
    onPrimaryContainer = Color(0xFFD8EAD0),
    secondary = Color(0xFFC2CDB8),
    onSecondary = Color(0xFF293326),
    background = Color(0xFF111510),
    onBackground = Color(0xFFE7EBE3),
    surface = Color(0xFF171D18),
    onSurface = Color(0xFFE7EBE3),
    surfaceVariant = Color(0xFF252D26),
    onSurfaceVariant = Color(0xFFB5BDB2),
    outline = Color(0xFF465044),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
internal fun DailyTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DailyTrackerColors,
        typography = Typography(),
        content = content
    )
}

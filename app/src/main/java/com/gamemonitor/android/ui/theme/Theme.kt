package com.gamemonitor.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7BD88F),
    onPrimary = Color(0xFF07210F),
    secondary = Color(0xFF8FB4FF),
    background = Color(0xFF101418),
    surface = Color(0xFF161B21),
    surfaceVariant = Color(0xFF232A33),
    error = Color(0xFFFF8B8B),
)

@Composable
fun GameMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}

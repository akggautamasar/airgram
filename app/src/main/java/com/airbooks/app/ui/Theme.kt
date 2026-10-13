package com.airbooks.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun AirTheme(content: @Composable () -> Unit) {
    val light = lightColorScheme(
        primary = Color(0xFF3978E8),
        onPrimary = Color.White,
        secondary = Color(0xFF65558F),
        background = Color(0xFFF1F0F5),
        surface = Color(0xFFFCFBFF),
        surfaceVariant = Color(0xFFE3E2EC),
        onSurfaceVariant = Color(0xFF484957),
        primaryContainer = Color(0xFFDDE3FF),
        onPrimaryContainer = Color(0xFF26365F),
    )
    MaterialTheme(colorScheme = light, content = content)
}

package com.airbooks.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun AirTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF8FA8FF),
            onPrimary = Color(0xFF0B1030),
            secondary = Color(0xFF9AD0C2),
            background = Color(0xFF0E0F13),
            surface = Color(0xFF16181F),
            surfaceVariant = Color(0xFF22252E),
            onSurfaceVariant = Color(0xFFA9AEBB),
            tertiaryContainer = Color(0xFF4A3A12),
            onTertiaryContainer = Color(0xFFFFE2A0),
        ),
        content = content,
    )
}

package com.airbooks.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AirGramColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    secondary = Color(0xFF0891B2),
    background = Color(0xFFFFFDFB),
    surface = Color(0xFFFFFFFF)
)

@Composable
fun AirGramTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AirGramColors, content = content)
}

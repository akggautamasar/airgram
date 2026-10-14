package com.airbooks.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.airbooks.app.Prefs

@Composable
fun AirTheme(content: @Composable () -> Unit) {
    val dark = when (Prefs.themeMode) {
        "Light" -> false
        "Dark" -> true
        else -> isSystemInDarkTheme()
    }
    // Material You: wallpaper-derived palette on Android 12+, curated fallback elsewhere.
    val ctx = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (dark) darkColorScheme(
        primary = Color(0xFF9AB9FF),
        onPrimary = Color(0xFF102D60),
        secondary = Color(0xFFD0BCFF),
        background = Color(0xFF111318),
        surface = Color(0xFF191B20),
        surfaceVariant = Color(0xFF41434B),
        onSurfaceVariant = Color(0xFFC4C6D0),
        primaryContainer = Color(0xFF26365F),
        onPrimaryContainer = Color(0xFFDDE3FF),
    ) else lightColorScheme(
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
    MaterialTheme(colorScheme = scheme, content = content)
}

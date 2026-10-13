package com.airbooks.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen() {
    var streamingBuffer by remember { mutableFloatStateOf(16f) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Streaming buffer: ${streamingBuffer.toInt()} MB")
        Slider(value = streamingBuffer, onValueChange = { streamingBuffer = it }, valueRange = 4f..48f, steps = 10)
    }
}

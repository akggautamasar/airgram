package com.airbooks.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FilesScreen(files: List<String> = emptyList(), onFileClick: (String) -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Files", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        if (files.isEmpty()) Text("No files to show")
        LazyColumn { items(files) { file -> Text(file, Modifier.fillMaxWidth().clickable { onFileClick(file) }.padding(12.dp)) } }
    }
}

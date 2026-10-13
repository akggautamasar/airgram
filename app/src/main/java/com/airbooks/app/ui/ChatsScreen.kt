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
fun ChatsScreen(chats: List<String> = emptyList(), onChatClick: (String) -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Chats", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        if (chats.isEmpty()) Text("Your chats will appear here")
        LazyColumn { items(chats) { chat -> Text(chat, Modifier.fillMaxWidth().clickable { onChatClick(chat) }.padding(12.dp)) } }
    }
}

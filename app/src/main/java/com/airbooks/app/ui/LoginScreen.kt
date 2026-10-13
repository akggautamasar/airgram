package com.airbooks.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(onLogin: (String) -> Unit = {}) {
    var phone by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("AirGram", style = MaterialTheme.typography.headlineLarge)
        Text("Sign in with Telegram")
        OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone number") }, singleLine = true)
        Button(onClick = { onLogin(phone) }, enabled = phone.isNotBlank()) { Text("Continue") }
    }
}

package com.airbooks.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.airbooks.app.tg.Tg
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

@Composable
fun LoginScreen(state: TdApi.AuthorizationState) {
    val scope = rememberCoroutineScope()
    var input by remember(state.javaClass) { mutableStateOf("") }
    var err by remember(state.javaClass) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            err = null
            try {
                block()
            } catch (e: Exception) {
                err = e.message ?: "Something went wrong"
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("AirBooks", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Sign in with your Telegram account. Your session stays on this phone.",
            Modifier.padding(top = 4.dp, bottom = 24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (state) {
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    label = { Text("Phone number") }, placeholder = { Text("+91 98765 43210") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { run { Tg.submitPhone(input.trim()) } },
                    enabled = !busy && input.length >= 6,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Send code") }
            }
            is TdApi.AuthorizationStateWaitCode -> {
                Text(
                    "Enter the code Telegram sent to ${state.codeInfo.phoneNumber} " +
                        "(check your Telegram app).",
                    Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = input, onValueChange = { input = it.filter(Char::isDigit) },
                    label = { Text("Login code") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { run { Tg.submitCode(input.trim()) } },
                    enabled = !busy && input.length >= 4,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Verify") }
                TextButton(onClick = { Tg.startOver() }) { Text("Use a different number") }
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                Text("Two-step verification is on for this account.", Modifier.padding(bottom = 12.dp))
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    label = { Text("Password") },
                    supportingText = { if (state.passwordHint.isNotBlank()) Text("Hint: ${state.passwordHint}") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { run { Tg.submitPassword(input) } },
                    enabled = !busy && input.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continue") }
            }
            else -> {
                Text(
                    "This sign-in step isn't supported here (for example creating a new account). " +
                        "Sign up in the official Telegram app first, then come back.",
                )
                TextButton(onClick = { Tg.startOver() }) { Text("Start over") }
            }
        }

        if (busy) CircularProgressIndicator(Modifier.padding(top = 16.dp))
        err?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
    }
}

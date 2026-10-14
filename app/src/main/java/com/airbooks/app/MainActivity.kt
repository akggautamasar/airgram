package com.airbooks.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airbooks.app.download.DownloadManager
import com.airbooks.app.tg.Kind
import com.airbooks.app.tg.Media
import com.airbooks.app.tg.Tg
import com.airbooks.app.ui.AirTheme
import com.airbooks.app.ui.ChatsScreen
import com.airbooks.app.ui.CrashReportDialog
import com.airbooks.app.ui.DownloadsScreen
import com.airbooks.app.ui.FilesScreen
import com.airbooks.app.ui.ImageScreen
import com.airbooks.app.ui.LoginScreen
import com.airbooks.app.ui.OtherScreen
import com.airbooks.app.ui.PdfScreen
import com.airbooks.app.ui.PlayerScreen
import com.airbooks.app.ui.SettingsScreen
import org.drinkless.tdlib.TdApi

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AirTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root() }
            }
        }
    }
}

sealed interface Screen {
    data object Chats : Screen
    data object Settings : Screen
    data object Downloads : Screen
    data class Files(val chatId: Long, val title: String) : Screen
    data class Play(val item: Media, val queue: List<Media>, val chatTitle: String) : Screen
    data class Pdf(val item: Media) : Screen
    data class Other(val item: Media) : Screen
    data class Image(val items: List<Media>, val start: Media) : Screen
}

@Composable
fun Root() {
    CrashReportDialog()
    if (BuildConfig.TG_API_ID == 0 || BuildConfig.TG_API_HASH.isBlank()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Missing Telegram API keys", style = MaterialTheme.typography.titleLarge)
            Text(
                "Build the app with TG_API_ID and TG_API_HASH (from my.telegram.org) set as " +
                    "GitHub secrets or in local.properties.",
                Modifier.padding(top = 8.dp)
            )
        }
        return
    }
    val auth by Tg.authState.collectAsState()
    when (val a = auth) {
        is TdApi.AuthorizationStateReady -> MainNav()
        null,
        is TdApi.AuthorizationStateWaitTdlibParameters,
        is TdApi.AuthorizationStateLoggingOut,
        is TdApi.AuthorizationStateClosing,
        is TdApi.AuthorizationStateClosed ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> LoginScreen(a)
    }
}

@Composable
fun MainNav() {
    val stack = remember { mutableStateListOf<Screen>(Screen.Chats) }
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { pop() }
    fun openMedia(m: Media, all: List<Media>) {
        when (m.kind) {
            Kind.VIDEO, Kind.AUDIO -> push(Screen.Play(m, all, ""))
            Kind.PDF -> push(Screen.Pdf(m))
            Kind.IMAGE -> push(Screen.Image(all.filter { it.kind == Kind.IMAGE }, m))
            Kind.OTHER -> push(Screen.Other(m))
        }
    }

    when (val s = stack.last()) {
        Screen.Chats -> ChatsScreen(
            onOpen = { push(Screen.Files(it.id, it.title)) },
            onSettings = { push(Screen.Settings) },
            onDownloads = { push(Screen.Downloads) },
            onOpenMedia = { m -> openMedia(m, listOf(m)) },
        )
        Screen.Settings -> SettingsScreen(onBack = { pop() })
        Screen.Downloads -> DownloadsScreen(onBack = { pop() })
        is Screen.Files -> FilesScreen(
            chatId = s.chatId,
            title = s.title,
            onBack = { pop() },
            onDownload = { m -> DownloadManager.enqueue(m) },
            onOpen = { m, all -> openMedia(m, all) },
        )
        is Screen.Play -> PlayerScreen(s.item, s.queue, s.chatTitle, onBack = { pop() })
        is Screen.Pdf -> PdfScreen(s.item, onBack = { pop() })
        is Screen.Other -> OtherScreen(s.item, onBack = { pop() })
        is Screen.Image -> ImageScreen(s.items, s.start, onBack = { pop() })
    }
}

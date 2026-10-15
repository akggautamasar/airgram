package com.airbooks.app

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.airbooks.app.download.DownloadManager
import com.airbooks.app.tg.ChatRow
import com.airbooks.app.tg.Kind
import com.airbooks.app.tg.Media
import com.airbooks.app.tg.Tg
import com.airbooks.app.ui.AirTheme
import com.airbooks.app.ui.ChatsScreen
import com.airbooks.app.ui.ConversationScreen
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

/**
 * Navigation model — the original AirGram dashboard architecture, restored:
 *
 * Home is the dashboard ([ChatsScreen]: your Telegram chats with Discover/Search tabs, folders,
 * filters and the Continue rail, plus Downloads and Settings entries). Opening a chat pushes its
 * real conversation ([ConversationScreen]); the conversation's top bar and long-press menu open
 * the chat's own media browser ([FilesScreen]), whose sections (Videos / Audio / Docs / Photos /
 * Links / Voice) query **that chat id only**. Viewers and the player are pushed on top.
 *
 * There are no global cross-chat media destinations: media browsing always belongs to a chat.
 * The pushed stack survives rotation and process death through [NavCodec].
 */
sealed interface Screen {
    data object Settings : Screen
    data object Downloads : Screen
    data class Conversation(val chatId: Long, val title: String) : Screen
    data class Files(val chatId: Long, val title: String) : Screen
    data class Play(val item: Media, val queue: List<Media>, val chatTitle: String) : Screen
    data class Pdf(val item: Media) : Screen
    data class Other(val item: Media) : Screen
    data class Image(val items: List<Media>, val start: Media) : Screen
}

/** Pushed screens; an empty stack means the dashboard. Saved as one string via [NavCodec]. */
internal data class NavState(val dest: Int, val stack: List<Screen>)

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
                Modifier.padding(top = 8.dp),
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
    // Saved, so rotation/process death returns to the same open conversation or viewer.
    // `dest` is a NavCodec artefact kept for format compatibility; the dashboard is always 0.
    val saver = remember {
        Saver<NavState, String>(
            save = { NavCodec.encode(0, it.stack) },
            restore = { encoded ->
                val (_, stack) = NavCodec.decode(encoded)
                NavState(0, stack)
            },
        )
    }
    var nav by rememberSaveable(stateSaver = saver) { mutableStateOf(NavState(0, emptyList())) }
    val ctx = LocalContext.current

    fun push(s: Screen) {
        nav = NavState(0, nav.stack + s)
    }

    fun pop() {
        if (nav.stack.isNotEmpty()) nav = NavState(0, nav.stack.dropLast(1))
    }

    fun openMedia(m: Media, all: List<Media>) {
        when (m.kind) {
            Kind.VIDEO, Kind.AUDIO -> push(Screen.Play(m, all, ""))
            Kind.PDF -> push(Screen.Pdf(m))
            Kind.IMAGE -> push(Screen.Image(all.filter { it.kind == Kind.IMAGE }, m))
            // Links open in the browser — never in a viewer, never downloaded.
            Kind.LINK -> {
                val url = m.fileName.ifBlank { m.name }
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
            Kind.OTHER -> push(Screen.Other(m))
        }
    }

    BackHandler(enabled = nav.stack.isNotEmpty()) { pop() }

    val current = nav.stack.lastOrNull()
    if (current != null) {
        Detail(current, Tg.chatsByList.collectAsState().value, ::pop, ::push, ::openMedia)
        return
    }

    // ── The dashboard (original layout): chats with Discover/Search tabs, folders, filters,
    //    the Continue rail, and Downloads/Settings entries in its own bottom bar. ──
    ChatsScreen(
        onOpen = { push(Screen.Conversation(it.id, it.title)) },
        onOpenFiles = { push(Screen.Files(it.id, it.title)) },
        onOpenMedia = { m -> openMedia(m, listOf(m)) },
        onDownloads = { push(Screen.Downloads) },
        onSettings = { push(Screen.Settings) },
    )
}

/** A pushed screen: conversation, chat media browser, utility screen, or one of the viewers. */
@Composable
private fun Detail(
    s: Screen,
    lists: Map<String, List<ChatRow>>,
    pop: () -> Unit,
    push: (Screen) -> Unit,
    openMedia: (Media, List<Media>) -> Unit,
) {
    when (s) {
        is Screen.Conversation -> {
            // Re-resolved from the live list so title/avatar/permissions are always current.
            val row = remember(lists, s.chatId, s.title) {
                lists.values.asSequence().flatten().firstOrNull { it.id == s.chatId }
                    ?: ChatRow(s.chatId, s.title, "", 0, 0L)
            }
            ConversationScreen(
                chat = row,
                onBack = pop,
                onOpenFiles = { push(Screen.Files(s.chatId, s.title)) },
                onOpenMedia = openMedia,
                onDownload = { DownloadManager.enqueue(it) },
            )
        }
        // Per-chat media browser: every section queries this chatId only (Tg.searchMedia).
        is Screen.Files -> FilesScreen(
            chatId = s.chatId,
            title = s.title,
            onBack = pop,
            onDownload = { DownloadManager.enqueue(it) },
            onOpen = { m, all -> openMedia(m, all) },
        )
        Screen.Downloads -> DownloadsScreen(onBack = pop)
        Screen.Settings -> SettingsScreen(onBack = pop)
        is Screen.Play -> PlayerScreen(s.item, s.queue, s.chatTitle, onBack = pop)
        is Screen.Pdf -> PdfScreen(s.item, onBack = pop)
        is Screen.Other -> OtherScreen(s.item, onBack = pop)
        is Screen.Image -> ImageScreen(s.items, s.start, onBack = pop)
    }
}

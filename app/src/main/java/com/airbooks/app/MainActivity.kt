package com.airbooks.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
import com.airbooks.app.ui.MediaSectionScreen
import com.airbooks.app.ui.OtherScreen
import com.airbooks.app.ui.PdfScreen
import com.airbooks.app.ui.PlayerScreen
import com.airbooks.app.ui.SearchScreen
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
 * Navigation model: five top-level destinations plus a stack of pushed detail screens.
 * Destinations keep the app's identity (Chats / media library / search / downloads / settings);
 * the stack carries conversations, chat file browsers and viewers, exactly as before.
 */
sealed interface Screen {
    data object Chats : Screen
    data object Media : Screen
    data object Search : Screen
    data object Downloads : Screen
    data object Settings : Screen
    data class Conversation(val chatId: Long, val title: String) : Screen
    data class Files(val chatId: Long, val title: String) : Screen
    data class Play(val item: Media, val queue: List<Media>, val chatTitle: String) : Screen
    data class Pdf(val item: Media) : Screen
    data class Other(val item: Media) : Screen
    data class Image(val items: List<Media>, val start: Media) : Screen
}

/** Current destination index + pushed screens; value type so it can be saved as one string. */
internal data class NavState(val dest: Int, val stack: List<Screen>)

private data class Dest(val label: String, val icon: ImageVector)

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
    val saver = remember {
        Saver<NavState, String>(
            save = { NavCodec.encode(it.dest, it.stack) },
            restore = { encoded ->
                val (dest, stack) = NavCodec.decode(encoded)
                NavState(dest, stack)
            },
        )
    }
    // Saved, so rotation/process death returns to the same tab and the same open conversation.
    var nav by rememberSaveable(stateSaver = saver) { mutableStateOf(NavState(0, emptyList())) }

    val lists by Tg.chatsByList.collectAsState()

    fun push(s: Screen) {
        nav = NavState(nav.dest, nav.stack + s)
    }

    fun pop() {
        if (nav.stack.isNotEmpty()) nav = NavState(nav.dest, nav.stack.dropLast(1))
    }

    fun gotoDest(i: Int) {
        if (nav.dest != i || nav.stack.isNotEmpty()) nav = NavState(i, emptyList())
    }

    fun openMedia(m: Media, all: List<Media>) {
        when (m.kind) {
            Kind.VIDEO, Kind.AUDIO -> push(Screen.Play(m, all, ""))
            Kind.PDF -> push(Screen.Pdf(m))
            Kind.IMAGE -> push(Screen.Image(all.filter { it.kind == Kind.IMAGE }, m))
            Kind.OTHER -> push(Screen.Other(m))
        }
    }

    BackHandler(enabled = nav.stack.isNotEmpty() || nav.dest != 0) {
        if (nav.stack.isNotEmpty()) pop() else gotoDest(0)
    }

    val current = nav.stack.lastOrNull()
    if (current != null) {
        Detail(current, lists, ::pop, ::push, ::openMedia)
        return
    }

    val dests = listOf(
        Dest("Chats", Icons.Filled.Chat),
        Dest("Media", Icons.Filled.VideoLibrary),
        Dest("Search", Icons.Filled.Search),
        Dest("Downloads", Icons.Filled.Download),
        Dest("Settings", Icons.Filled.Settings),
    )
    // Compact phones get a bottom bar; wider screens (tablets, foldables, desktop) get a rail.
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.fillMaxHeight()) {
                dests.forEachIndexed { i, d ->
                    NavigationRailItem(
                        selected = nav.dest == i,
                        onClick = { gotoDest(i) },
                        icon = { Icon(d.icon, d.label) },
                        label = { Text(d.label) },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                DestContent(nav.dest, ::push, ::gotoDest, ::openMedia)
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxSize()) {
                DestContent(nav.dest, ::push, ::gotoDest, ::openMedia)
            }
            NavigationBar {
                dests.forEachIndexed { i, d ->
                    NavigationBarItem(
                        selected = nav.dest == i,
                        onClick = { gotoDest(i) },
                        icon = { Icon(d.icon, d.label) },
                        label = { Text(d.label) },
                    )
                }
            }
        }
    }
}

/** Body of the five destinations. */
@Composable
private fun DestContent(
    dest: Int,
    push: (Screen) -> Unit,
    gotoDest: (Int) -> Unit,
    openMedia: (Media, List<Media>) -> Unit,
) {
    when (dest) {
        0 -> ChatsScreen(
            onOpen = { push(Screen.Conversation(it.id, it.title)) },
            onOpenFiles = { push(Screen.Files(it.id, it.title)) },
            onOpenMedia = { m -> openMedia(m, listOf(m)) },
            onAccount = { gotoDest(4) },
        )
        1 -> MediaSectionScreen(
            onOpen = { m, all -> openMedia(m, all) },
            onDownload = { DownloadManager.enqueue(it) },
        )
        2 -> SearchScreen(
            onOpenChat = { push(Screen.Conversation(it.id, it.title)) },
            onOpenMedia = { m, all -> openMedia(m, all) },
        )
        3 -> DownloadsScreen()
        else -> SettingsScreen()
    }
}

/** A pushed screen: conversation, chat file browser, or one of the viewers. */
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
        is Screen.Files -> FilesScreen(
            chatId = s.chatId,
            title = s.title,
            onBack = pop,
            onDownload = { DownloadManager.enqueue(it) },
            onOpen = { m, all -> openMedia(m, all) },
        )
        is Screen.Play -> PlayerScreen(s.item, s.queue, s.chatTitle, onBack = pop)
        is Screen.Pdf -> PdfScreen(s.item, onBack = pop)
        is Screen.Other -> OtherScreen(s.item, onBack = pop)
        is Screen.Image -> ImageScreen(s.items, s.start, onBack = pop)
        else -> {}
    }
}

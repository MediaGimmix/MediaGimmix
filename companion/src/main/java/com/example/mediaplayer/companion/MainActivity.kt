package com.example.mediaplayer.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

/**
 * The app's sections, as icon tabs. Each icon is assets/icons/tab_<name>.png when present (the user's own),
 * else a Material icon (Apache 2.0).
 */
private enum class Section(val label: String, val file: String, val icon: ImageVector) {
    PLAYER("Player", "tab_player", Icons.Filled.PlayArrow),
    QUEUE("Queue", "tab_queue", Icons.AutoMirrored.Filled.List),
    PLAYLISTS("Playlists", "tab_playlists", Icons.Filled.Menu),
    SEARCH("Search", "tab_search", Icons.Filled.Search),
    SETTINGS("Settings", "tab_settings", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Android 13+: the media notification needs permission (asked once).
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            CompanionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RemoteApp()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RemoteApp(vm: RemoteViewModel = viewModel()) {
    var tab by rememberSaveable {
        mutableIntStateOf(if (vm.connection.isSet) Section.PLAYER.ordinal else Section.SETTINGS.ordinal)
    }

    // Poll the player's status while the app is on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refresh()
                delay(POLL_MS)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        TabRow(selectedTabIndex = tab) {
            Section.entries.forEach { s ->
                val selected = tab == s.ordinal
                Tab(selected = selected, onClick = { tab = s.ordinal }, modifier = Modifier.height(52.dp)) {
                    TransportIcon(
                        s.icon, s.file, contentDescription = s.label, size = 26.dp,
                        tint = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        imageAlpha = if (selected) 1f else 0.5f,
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (Section.entries[tab]) {
                Section.PLAYER -> PlayerScreen(vm)
                Section.QUEUE -> QueueScreen(vm)
                Section.PLAYLISTS -> PlaylistsScreen(vm)
                Section.SEARCH -> SearchScreen(vm)
                Section.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}

private const val POLL_MS = 2_000L

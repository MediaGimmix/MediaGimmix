package com.example.mediaplayer.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
    PLAYLISTS("Playlists", "tab_playlists", Icons.Filled.Menu),
    QUEUE("Queue", "tab_queue", Icons.AutoMirrored.Filled.List),
    SEARCH("Search", "tab_search", Icons.Filled.Search),
    SETTINGS("Settings", "tab_settings", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    /** Same instance the screens get from viewModel(). */
    private val vm: RemoteViewModel by viewModels()

    /**
     * The phone's volume keys change the player's volume while this app is open and connected (not while
     * listening on this phone: then they're the phone's own volume as usual).
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val up = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
        val down = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        if ((up || down) && !LocalPlayer.active && vm.status != null && vm.error == null) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) vm.stepVolume(up)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

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
            // One request at a time; slower while the player can't be reached (e.g. out of Bluetooth range).
            while (true) {
                val ok = vm.refreshNow()
                delay(if (ok) POLL_MS else RETRY_MS)
            }
        }
    }

    // The sun toggle: no screen timeout on this phone while the app is open (the power button still works).
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    LaunchedEffect(vm.keepScreenOn) {
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (vm.keepScreenOn) activity?.window?.addFlags(flag) else activity?.window?.clearFlags(flag)
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
      androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        TabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f)) {
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
        KeepAwakeToggle(on = vm.keepScreenOn, onChange = { vm.changeKeepScreenOn(it) })
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
private const val RETRY_MS = 5_000L

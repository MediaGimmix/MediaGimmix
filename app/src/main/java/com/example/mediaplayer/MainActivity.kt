package com.example.mediaplayer

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    /** Bumped when "Open on wake" brings the app back, to play the splash again. */
    private var splashReplays by mutableIntStateOf(0)

    /** Same instance LibraryScreen gets from viewModel(). */
    private val vm: LibraryViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        StayOnTop.onShown()
    }

    override fun onStop() {
        super.onStop()
        StayOnTop.onHidden(this) // comes back right away when "Stay on top" is on
    }

    /** Remapped buttons while the app is on screen (volume keys only reach us here). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        vm.playback.handleKey(event) || super.dispatchKeyEvent(event)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(WakeLaunch.EXTRA_SHOW_SPLASH, false)) splashReplays++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the system bars; status bar icons follow light/dark mode.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        StayOnTop.init(this)
        WakeLaunch.startIfEnabled(this) // in case Android stopped it
        // Android 13+: the media notification needs permission (asked once; "Don't allow" is respected).
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            // Stay on top + "Keep the screen on": no screen timeout while the player is in front (power button still works).
            val keepOn = StayOnTop.on && StayOnTop.keepScreenOn
            LaunchedEffect(keepOn) {
                if (keepOn) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            MediaPlayerTheme {
                // Saved, so rotating the screen doesn't show the splash again.
                var splashDone by rememberSaveable { mutableStateOf(!SplashConfig.isEnabled(this@MainActivity)) }
                LaunchedEffect(splashReplays) {
                    if (splashReplays > 0 && SplashConfig.isEnabled(this@MainActivity)) {
                        splashDone = false
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        LibraryScreen()
                    }
                    // key: a wake while the splash is still fading starts it over.
                    if (!splashDone) key(splashReplays) { StartupSplash(onDone = { splashDone = true }) }
                }
            }
        }
    }
}

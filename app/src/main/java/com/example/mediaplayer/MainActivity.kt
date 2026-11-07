package com.example.mediaplayer

import android.content.Intent
import android.os.Bundle
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(WakeLaunch.EXTRA_SHOW_SPLASH, false)) splashReplays++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the system bars; status bar icons follow light/dark mode.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WakeLaunch.startIfEnabled(this) // in case Android stopped it
        setContent {
            MediaPlayerTheme {
                // Saved, so rotating the screen doesn't show the splash again.
                var splashDone by rememberSaveable { mutableStateOf(SplashConfig.FADE_IN_MS + SplashConfig.HOLD_MS + SplashConfig.FADE_OUT_MS == 0) }
                LaunchedEffect(splashReplays) {
                    if (splashReplays > 0 && SplashConfig.FADE_IN_MS + SplashConfig.HOLD_MS + SplashConfig.FADE_OUT_MS > 0) {
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

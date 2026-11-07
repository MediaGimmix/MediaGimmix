package com.example.mediaplayer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Startup splash drawn over the library: the logo fades in quickly, holds,
 * then the whole splash fades out slowly (times in [SplashConfig]).
 * It never takes touches, so the library is usable while it fades.
 */
@Composable
fun StartupSplash(onDone: () -> Unit) {
    val logo = remember { Animatable(0f) }
    val overlay = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        logo.animateTo(1f, tween(SplashConfig.FADE_IN_MS, easing = LinearOutSlowInEasing))
        delay(SplashConfig.HOLD_MS.toLong())
        overlay.animateTo(0f, tween(SplashConfig.FADE_OUT_MS, easing = FastOutSlowInEasing))
        onDone()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlay.value }
            .background(SPLASH_BACKGROUND),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer {
                alpha = logo.value
                val scale = 0.9f + 0.1f * logo.value
                scaleX = scale
                scaleY = scale
            },
        ) {
            // Your own image: assets/icons/splash.png. Without it the splash shows just the name.
            val context = LocalContext.current.applicationContext
            val image = remember { AppIcons.load(context, "splash") }
            if (image != null) {
                Image(bitmap = image, contentDescription = null, modifier = Modifier.size(128.dp))
                Spacer(modifier = Modifier.height(16.dp))
            }
            Text("Welcome back!", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        }
    }
}

/** Also the system launch screen's colour (res/values/themes.xml), so the two blend. */
val SPLASH_BACKGROUND = Color(0xFF000000)

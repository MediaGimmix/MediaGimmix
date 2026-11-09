package com.example.mediaplayer.companion

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The sun: keeps this phone's screen on while the app is open. Drawn from assets/icons/screen_on.png (a white
 * sun, recoloured white when on and black when off, so any one-colour image works); a ☀ sign without it.
 */
@Composable
fun KeepAwakeToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val image = remember { AssetIcons.load(context.applicationContext, "screen_on") }
    val color = if (on) Color.White else Color.Black
    IconButton(onClick = { onChange(!on) }, modifier = Modifier.size(44.dp)) {
        val label = if (on) "Keep screen on: on" else "Keep screen on: off"
        if (image != null) {
            Image(image, contentDescription = label, colorFilter = ColorFilter.tint(color), modifier = Modifier.size(26.dp))
        } else {
            Text("☀", color = color)
        }
    }
}

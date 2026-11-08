package com.example.mediaplayer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The Stay on top pin (assets/icons/nav_stay_on_top.png, a push-pin): drawn white when on, black when off
 * (the image is recoloured, so any single-colour pin works). Shown on every screen except Settings, unless
 * hidden in Settings → System → Stay on top.
 */
@Composable
fun StayOnTopToggle(modifier: Modifier = Modifier) {
    if (!StayOnTop.showSwitch) return
    val context = LocalContext.current
    val on = StayOnTop.on
    val color = if (on) Color.White else Color.Black
    val image = remember { AppIcons.load(context.applicationContext, "nav_stay_on_top") }
    IconButton(onClick = { StayOnTop.setOn(context, !on) }, modifier = modifier.size(44.dp)) {
        val label = if (on) "Stay on top: on" else "Stay on top: off"
        if (image != null) {
            Image(bitmap = image, contentDescription = label, colorFilter = ColorFilter.tint(color), modifier = Modifier.size(26.dp))
        } else {
            Icon(Icons.Filled.Lock, contentDescription = label, tint = color, modifier = Modifier.size(26.dp))
        }
    }
}

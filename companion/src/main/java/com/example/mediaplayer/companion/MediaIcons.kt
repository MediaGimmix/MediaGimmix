package com.example.mediaplayer.companion

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Transport icons (drawn through [TransportIcon], which prefers the user's PNGs). The small built-in icon set has no pause/skip icons and the
 * extended set is a large dependency, so these few paths (from Material
 * Icons, Apache 2.0) are defined here.
 */
object MediaIcons {
    val Play: ImageVector = icon("Play", "M8,5v14l11,-7z")
    val Pause: ImageVector = icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    val SkipNext: ImageVector = icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    val SkipPrevious: ImageVector = icon("SkipPrevious", "M6,6h2v12H6zM9.5,12l8.5,6V6z")
    val Stop: ImageVector = icon("Stop", "M6,6h12v12H6z")
    val Shuffle: ImageVector = icon(
        "Shuffle",
        "M10.59,9.17L5.41,4L4,5.41l5.17,5.17l1.42,-1.41zM14.5,4l2.04,2.04L4,18.59L5.41,20L17.96,7.46L20,9.5V4h-5.5z" +
            "M14.83,13.41l-1.41,1.41l3.13,3.13L14.5,20H20v-5.5l-2.04,2.04l-3.13,-3.13z",
    )

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()
}

/**
 * A transport button icon: the user's own assets/icons/<[file]>.png if there is
 * one (shown in its own colours), else the built-in Material icon, tinted.
 * [imageAlpha] dims the user's image (e.g. shuffle off).
 */
@Composable
fun TransportIcon(
    vector: ImageVector,
    file: String,
    contentDescription: String?,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    imageAlpha: Float = 1f,
) {
    val context = LocalContext.current.applicationContext
    val image = remember(file) { AssetIcons.load(context, file) }
    if (image != null) {
        Image(bitmap = image, contentDescription = contentDescription, alpha = imageAlpha, modifier = Modifier.size(size))
    } else {
        Icon(vector, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size))
    }
}

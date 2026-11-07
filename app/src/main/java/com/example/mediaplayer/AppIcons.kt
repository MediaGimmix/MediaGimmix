package com.example.mediaplayer

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Menu icons, loaded from PNG files in app/src/main/assets/icons/.
 * Replace a PNG with your own (same file name, square works best) and rebuild.
 * A missing file just leaves an empty space, so the text stays aligned.
 */
object AppIcons {

    private val cache = HashMap<String, ImageBitmap?>()

    /** Icons for the library menu: menu_artists.png, menu_albums.png, ... */
    fun forSection(section: LibrarySection): String = "menu_" + section.name.lowercase()

    /** Icons for dropdown menu items, matched by the item's label. */
    fun forAction(label: String): String? = when {
        label == "Play next" -> "action_play_next"
        label == "Play last" -> "action_play_last"
        label == "Move up" -> "action_move_up"
        label == "Move down" -> "action_move_down"
        label == "Remove" -> "action_remove"
        label == "Play" -> "action_play"
        label.startsWith("Shuffle") -> "action_shuffle"
        label.startsWith("Set custom image") -> "action_set_image"
        label.startsWith("Use ") -> "action_reset_image"
        label == "Go to album" -> "action_go_album"
        label == "Go to artist" -> "action_go_artist"
        label == "Show queue" -> "action_show_queue"
        label == "Song info" -> "action_song_info"
        else -> null
    }

    fun load(context: Context, name: String): ImageBitmap? = synchronized(cache) {
        cache.getOrPut(name) {
            runCatching {
                context.assets.open("icons/$name.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
    }
}

/** An icon from assets/icons/[name].png, or an empty space of the same size if there is none. */
@Composable
fun AssetIcon(name: String?, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val image = remember(name) { name?.let { AppIcons.load(context, it) } }
    if (image != null) {
        Image(bitmap = image, contentDescription = null, modifier = modifier.size(size))
    } else {
        Spacer(modifier = modifier.size(size))
    }
}

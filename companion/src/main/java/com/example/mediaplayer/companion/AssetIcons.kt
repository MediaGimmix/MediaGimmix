package com.example.mediaplayer.companion

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** The user's optional icons in assets/icons/ (listed in its README.txt). */
object AssetIcons {
    private val cache = HashMap<String, ImageBitmap?>()

    fun load(context: Context, name: String): ImageBitmap? = synchronized(cache) {
        cache.getOrPut(name) {
            runCatching {
                context.assets.open("icons/$name.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
    }
}

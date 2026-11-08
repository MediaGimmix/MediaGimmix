package com.example.mediaplayer.companion

import java.io.File
import java.io.IOException

/**
 * Free-space check for "Listen on this phone" downloads: a song is only downloaded if, afterwards, at
 * least [RESERVE_BYTES] would still be free (Android starts clearing app data and misbehaving when storage
 * is nearly full).
 */
object Storage {
    const val RESERVE_BYTES = 300L * 1024 * 1024

    class NotEnoughSpace(needed: Long, free: Long) : IOException(
        "Not enough space on this phone: the song needs ${mb(needed)}, only ${mb((free - RESERVE_BYTES).coerceAtLeast(0))} " +
            "can be used (${mb(RESERVE_BYTES)} is kept free).",
    )

    fun fits(dir: File, bytes: Long): Boolean = dir.usableSpace - bytes >= RESERVE_BYTES

    /** Throws [NotEnoughSpace] when [bytes] more wouldn't fit. */
    fun requireRoom(dir: File, bytes: Long) {
        val free = dir.usableSpace
        if (free - bytes < RESERVE_BYTES) throw NotEnoughSpace(bytes, free)
    }

    private fun mb(b: Long) = "%.0f MB".format(b / 1024.0 / 1024.0)
}

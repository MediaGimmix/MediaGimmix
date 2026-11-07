package com.example.mediaplayer

import android.net.Uri

/** One audio file in the library. Tag fields are null/0 when the file has no such tag. */
data class Song(
    val uri: Uri,
    val fileName: String,
    /** Folder path relative to the media folder ([rootUri]); empty for files directly inside it. */
    val folderPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** The media folder (tree URI) this song was found in. */
    val rootUri: String = "",
    val lastModified: Long = 0L,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val durationMs: Long = 0L,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val year: Int = 0,
    /** When a scan first saw this song (epoch ms); 0 until it has been saved once. */
    val dateAdded: Long = 0L,
) {
    val displayTitle: String
        get() = title ?: fileName

    /** "Artist · Album" when tagged, otherwise the folder path. */
    val displaySubtitle: String
        get() {
            val parts = listOfNotNull(artist, album)
            return if (parts.isEmpty()) folderPath.ifEmpty { "/" } else parts.joinToString(" · ")
        }

    /** Path relative to the media folder, e.g. "Artist/Album/01 Song.flac". */
    val relativePath: String
        get() = if (folderPath.isEmpty()) fileName else "$folderPath/$fileName"
}

/** A playlist file, decoded. [missing] counts entries that matched no song in the library. */
data class Playlist(
    val uri: Uri,
    val rootUri: String,
    val name: String,
    val folderPath: String,
    val songs: List<Song>,
    val missing: Int,
)

/** How often and how recently a song was played. */
data class PlayStat(val plays: Int, val lastPlayed: Long)

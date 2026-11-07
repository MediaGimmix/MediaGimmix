package com.example.mediaplayer

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Low-level scanning: walks one media folder (a Storage Access Framework tree
 * URI) and reads tags from single files. [LibraryScanner] puts it together.
 *
 * Listing queries DocumentsContract directly instead of using DocumentFile,
 * which is much faster on big libraries.
 */
object MediaScanner {

    val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "wv", "ape",
        "aif", "aiff", "dsf", "dff", "wma", "mka", "alac",
    )
    val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8", "pls")

    /** A file found while listing. */
    class Found(
        val uri: Uri,
        val docId: String,
        val rootUri: String,
        val name: String,
        val folderPath: String,
        val mimeType: String,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    class Listing(val audio: List<Found>, val playlists: List<Found>, val subdirs: Int)

    /** Lists every audio and playlist file under [treeUri], including subfolders. */
    suspend fun list(
        resolver: ContentResolver,
        treeUri: Uri,
        onProgress: (Int) -> Unit = {},
    ): Listing {
        val root = treeUri.toString()
        val audio = mutableListOf<Found>()
        val playlists = mutableListOf<Found>()
        var subdirs = 0

        // Folders still to visit: (document id, path relative to the media folder)
        val pending = ArrayDeque<Pair<String, String>>()
        pending.add(DocumentsContract.getTreeDocumentId(treeUri) to "")
        var isRoot = true

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (dirId, dirPath) = pending.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)

            val cursor = resolver.query(childrenUri, projection, null, null, null)
            if (cursor == null) {
                if (isRoot) error("Can't read this folder. Remove it and add it again.")
                continue
            }
            isRoot = false

            cursor.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val modified = if (c.isNull(4)) 0L else c.getLong(4)

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        subdirs++
                        pending.add(docId to (if (dirPath.isEmpty()) name else "$dirPath/$name"))
                        continue
                    }
                    if (name.startsWith("._")) continue // macOS resource-fork junk

                    val ext = name.substringAfterLast('.', "").lowercase()
                    // Playlists first: their mime types (audio/x-mpegurl, audio/x-scpls) start with "audio/".
                    val target = when {
                        ext in PLAYLIST_EXTENSIONS -> playlists
                        mime.startsWith("audio/") -> audio
                        // Some providers report audio as application/octet-stream.
                        ext in AUDIO_EXTENSIONS -> audio
                        else -> null
                    } ?: continue

                    target.add(
                        Found(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                            docId = docId,
                            rootUri = root,
                            name = name,
                            folderPath = dirPath,
                            mimeType = mime,
                            sizeBytes = size,
                            lastModified = modified,
                        )
                    )
                    if (target === audio && audio.size % 50 == 0) onProgress(audio.size)
                }
            }
        }

        onProgress(audio.size)
        return Listing(audio, playlists, subdirs)
    }

    /** Reads the tags of one file. Files whose tags can't be read keep their file info only. */
    fun readSong(context: Context, f: Found): Song {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var durationMs = 0L
        var track = 0
        var disc = 0
        var year = 0

        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, f.uri)
            fun tag(key: Int): String? = r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            albumArtist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            track = leadingInt(tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)) // "3/12" -> 3
            disc = leadingInt(tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))
            year = leadingInt(tag(MediaMetadataRetriever.METADATA_KEY_YEAR)).takeIf { it > 0 }
                ?: leadingInt(tag(MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4))
        } catch (e: Exception) {
            // Unreadable or unsupported file: keep what we know from the listing.
        } finally {
            runCatching { r.release() }
        }

        return Song(
            uri = f.uri,
            fileName = f.name,
            folderPath = f.folderPath,
            mimeType = f.mimeType,
            sizeBytes = f.sizeBytes,
            rootUri = f.rootUri,
            lastModified = f.lastModified,
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            durationMs = durationMs,
            trackNumber = track,
            discNumber = disc,
            year = year,
        )
    }

    private fun leadingInt(s: String?): Int =
        s?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
}

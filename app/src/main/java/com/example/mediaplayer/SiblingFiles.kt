package com.example.mediaplayer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache

/** A file in the same folder as a song. */
data class SiblingFile(val name: String, val uri: Uri, val mimeType: String)

/**
 * Lists the files next to a song (for folder cover images and .lrc/.txt lyrics).
 *
 * The parent folder is worked out from the song's document id, which for
 * local storage and SD cards looks like "primary:Music/Artist/Album/01.flac".
 * Other storage providers simply return nothing. Listings are cached.
 */
object SiblingFiles {

    private val cache = LruCache<String, List<SiblingFile>>(64)

    fun list(context: Context, song: Song): List<SiblingFile> {
        if (song.rootUri.isEmpty()) return emptyList()
        val tree = Uri.parse(song.rootUri)
        val docId = runCatching { DocumentsContract.getDocumentId(song.uri) }.getOrNull() ?: return emptyList()
        val parentId = when {
            docId.contains('/') -> docId.substringBeforeLast('/')
            docId.contains(':') -> docId.substringBefore(':') + ":"
            else -> return emptyList()
        }

        val key = song.rootUri + "|" + parentId
        cache.get(key)?.let { return it }

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val files = runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val out = ArrayList<SiblingFile>()
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    out.add(SiblingFile(name, DocumentsContract.buildDocumentUriUsingTree(tree, id), c.getString(2) ?: ""))
                }
                out
            }
        }.getOrNull() ?: emptyList()

        cache.put(key, files)
        return files
    }

    fun clear() = cache.evictAll()
}

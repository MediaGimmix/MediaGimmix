package com.example.mediaplayer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.nio.charset.Charset

/**
 * Changes playlist files on disk: adding a song to an .m3u / .m3u8, and
 * creating a new .m3u8. Needs write access to the media folder.
 */
object PlaylistWriter {

    /** Appends [song] to the playlist file. Throws on failure (SecurityException without write access). */
    fun append(context: Context, playlist: Playlist, song: Song) {
        val fileName = fileNameOf(playlist.uri)
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext == "pls") error("Adding to .pls playlists isn't supported yet")

        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(playlist.uri)?.use { it.readBytes() } ?: ByteArray(0)
        val existing = decodeTextFile(bytes)
        // Keep the file's own encoding and line endings.
        val charset = if (ext == "m3u8" || isUtf8(bytes)) Charsets.UTF_8 else Charset.forName("windows-1252")
        val newline = if (existing.contains("\r\n")) "\r\n" else "\n"
        val prefix = if (existing.isEmpty() || existing.endsWith("\n")) "" else newline
        val addition = (prefix + entryFor(playlist, song) + newline).toByteArray(charset)

        try {
            resolver.openOutputStream(playlist.uri, "wa")!!.use { it.write(addition) }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            // Some storage providers don't support append mode: rewrite the whole file.
            resolver.openOutputStream(playlist.uri, "wt")!!.use {
                it.write(bytes)
                it.write(addition)
            }
        }
    }

    /** Creates "<name>.m3u8" in the top of the media folder [rootUri], containing [first]. */
    fun create(context: Context, rootUri: String, name: String, first: Song): Playlist {
        val tree = Uri.parse(rootUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        // octet-stream so the storage provider keeps the .m3u8 name as given.
        val uri = DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", "$name.m3u8")
            ?: error("Couldn't create the playlist file")
        val playlist = Playlist(uri, rootUri, name, "", emptyList(), 0)
        context.contentResolver.openOutputStream(uri, "wt")!!.use {
            it.write(("#EXTM3U\n" + entryFor(playlist, first) + "\n").toByteArray(Charsets.UTF_8))
        }
        return playlist.copy(songs = listOf(first))
    }

    /**
     * How [song] is written in [playlist]: a path relative to the playlist when
     * both are in the same media folder, otherwise the song's full device path.
     */
    fun entryFor(playlist: Playlist, song: Song): String {
        if (song.rootUri == playlist.rootUri) {
            return relativePath(playlist.folderPath, song.relativePath)
        }
        val docId = runCatching { DocumentsContract.getDocumentId(song.uri) }.getOrNull()
        if (docId != null && docId.contains(':')) {
            val volume = docId.substringBefore(':')
            val path = docId.substringAfter(':')
            val base = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
            return "$base/$path"
        }
        return song.relativePath
    }

    /** Path from folder [from] to file [to], both relative to the same media folder. */
    fun relativePath(from: String, to: String): String {
        val fromParts = from.split('/').filter { it.isNotEmpty() }
        val toParts = to.split('/').filter { it.isNotEmpty() }
        var common = 0
        while (common < fromParts.size && common < toParts.size - 1 && fromParts[common] == toParts[common]) common++
        return "../".repeat(fromParts.size - common) + toParts.drop(common).joinToString("/")
    }

    private fun fileNameOf(uri: Uri): String {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return ""
        return docId.substringAfterLast('/').substringAfterLast(':')
    }
}

package com.example.mediaplayer

import android.content.ContentResolver
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Lookup tables for matching playlist entries to library songs. */
class SongIndex(songs: List<Song>) {
    private val byPath = HashMap<String, Song>()
    private val byName = HashMap<String, MutableList<Song>>()

    init {
        for (s in songs) {
            byPath.putIfAbsent(key(s.rootUri, s.relativePath), s)
            byName.getOrPut(s.fileName.lowercase()) { mutableListOf() }.add(s)
        }
    }

    fun byRelativePath(root: String, path: String): Song? = byPath[key(root, path)]

    fun byFileName(name: String): List<Song> = byName[name.lowercase()].orEmpty()

    private fun key(root: String, path: String) = root + "\u0000" + path.lowercase()
}

/**
 * Decodes .m3u / .m3u8 / .pls playlists and matches their entries to songs.
 *
 * Entries can be:
 *  - relative to the playlist ("Album/01 Song.flac", "../Other/x.mp3")
 *  - absolute paths from this device ("/storage/emulated/0/Music/...")
 *  - absolute paths from a PC ("C:\Users\me\Music\...") or file:// URLs
 * Relative entries are looked up exactly (ignoring case). Everything else is
 * matched by file name, preferring the song whose folders match the most
 * trailing folders of the entry. Stream URLs (http://...) are skipped.
 */
object PlaylistParser {

    private const val MAX_BYTES = 4L * 1024 * 1024
    private val PLS_FILE = Regex("""File\d+\s*=(.*)""", RegexOption.IGNORE_CASE)
    private val URL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val DRIVE_LETTER = Regex("^[A-Za-z]:/")

    fun load(resolver: ContentResolver, file: MediaScanner.Found, index: SongIndex): Playlist {
        val entries = runCatching { readEntries(resolver, file) }.getOrDefault(emptyList())
        val songs = ArrayList<Song>(entries.size)
        var missing = 0
        for (entry in entries) {
            val song = resolve(entry, file.folderPath, file.rootUri, index)
            if (song != null) songs.add(song) else missing++
        }
        return Playlist(
            uri = file.uri,
            rootUri = file.rootUri,
            name = file.name.substringBeforeLast('.'),
            folderPath = file.folderPath,
            songs = songs,
            missing = missing,
        )
    }

    private fun readEntries(resolver: ContentResolver, file: MediaScanner.Found): List<String> {
        if (file.sizeBytes > MAX_BYTES) return emptyList()
        val bytes = resolver.openInputStream(file.uri)?.use { it.readBytes() } ?: return emptyList()
        val lines = decode(bytes).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }

        val ext = file.name.substringAfterLast('.', "").lowercase()
        return if (ext == "pls") {
            lines.mapNotNull { PLS_FILE.matchEntire(it)?.groupValues?.get(1)?.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        } else {
            lines.filterNot { it.startsWith("#") }.toList() // #EXTM3U, #EXTINF, comments
        }
    }

    private fun resolve(rawEntry: String, playlistFolder: String, root: String, index: SongIndex): Song? {
        var e = rawEntry.trim().removeSurrounding("\"")
        if (e.startsWith("file:", ignoreCase = true)) {
            e = "/" + Uri.decode(e.substring(5)).trimStart('/')
        } else if (URL_SCHEME.containsMatchIn(e)) {
            return null // a stream, not a file
        }
        e = e.replace('\\', '/')

        val absolute = e.startsWith("/") || DRIVE_LETTER.containsMatchIn(e)
        if (!absolute) {
            val joined = normalize(if (playlistFolder.isEmpty()) e else "$playlistFolder/$e")
            if (joined != null) index.byRelativePath(root, joined)?.let { return it }
        }

        val parts = e.split('/').filter { it.isNotEmpty() && it != "." }
        val name = parts.lastOrNull() ?: return null
        val candidates = index.byFileName(name)
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]

        val lowerParts = parts.map { it.lowercase() }
        return candidates.maxWith(
            compareBy<Song>({ trailingMatch(it, lowerParts) }, { if (it.rootUri == root) 1 else 0 })
        )
    }

    /** How many trailing path parts (file name included) of [song] match [entryParts]. */
    private fun trailingMatch(song: Song, entryParts: List<String>): Int {
        val songParts = song.relativePath.lowercase().split('/')
        var n = 0
        while (n < songParts.size && n < entryParts.size &&
            songParts[songParts.size - 1 - n] == entryParts[entryParts.size - 1 - n]
        ) n++
        return n
    }

    /** Resolves "." and ".."; null if the path climbs above the media folder. */
    private fun normalize(path: String): String? {
        val out = ArrayList<String>()
        for (p in path.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (out.isEmpty()) return null else out.removeAt(out.lastIndex)
                else -> out.add(p)
            }
        }
        return out.joinToString("/")
    }

    /** UTF-8 (with or without BOM) or UTF-16 with BOM; anything else is read as Windows-1252. */
    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) 3 else 0
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, bytes.size - start))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, start, bytes.size - start, Charset.forName("windows-1252"))
        }
    }
}

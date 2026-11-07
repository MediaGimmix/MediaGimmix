package com.example.mediaplayer

import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LyricLine(val timeMs: Long, val text: String)

/** Either synced lines (non-empty [synced]) or plain text. [source] says where they came from. */
data class Lyrics(val synced: List<LyricLine>, val plain: String?, val source: String) {
    val isSynced: Boolean get() = synced.isNotEmpty()
}

/**
 * Finds lyrics for a song, in this order:
 *  1. a sidecar file next to the song with the same name: song.lrc, then song.txt
 *  2. lyrics embedded in the file's tags (ID3 USLT, Vorbis LYRICS / UNSYNCEDLYRICS, MP4 lyrics)
 * Text with LRC timestamps ([mm:ss.xx]) is treated as synced, anything else as plain.
 */
object LyricsLoader {

    private val cache = LruCache<String, Result>(40)

    /** Wrapper so "no lyrics" can be cached too. */
    private class Result(val lyrics: Lyrics?)

    suspend fun load(context: Context, song: Song): Lyrics? {
        val key = song.uri.toString()
        cache.get(key)?.let { return it.lyrics }
        val lyrics = withContext(Dispatchers.IO) {
            runCatching {
                if (Jellyfin.isJellyfin(song)) Jellyfin.lyrics(song) else sidecar(context, song) ?: embedded(context, song)
            }.getOrNull()
        }
        cache.put(key, Result(lyrics))
        return lyrics
    }

    fun clear() = cache.evictAll()

    private fun sidecar(context: Context, song: Song): Lyrics? {
        val base = song.fileName.substringBeforeLast('.')
        val files = SiblingFiles.list(context, song)
        for (ext in listOf("lrc", "txt")) {
            val file = files.firstOrNull { it.name.equals("$base.$ext", ignoreCase = true) } ?: continue
            val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() } ?: continue
            parse(decodeTextFile(bytes), file.name)?.let { return it }
        }
        return null
    }

    private fun embedded(context: Context, song: Song): Lyrics? =
        EmbeddedLyrics.read(context, song.uri)?.let { parse(it, "embedded tag") }

    fun parse(text: String, source: String): Lyrics? {
        val synced = LrcParser.parse(text)
        if (synced.isNotEmpty()) return Lyrics(synced, null, source)
        val plain = text.trim()
        return if (plain.isEmpty()) null else Lyrics(emptyList(), plain, source)
    }
}

/** Parses LRC: "[mm:ss.xx]text", several timestamps per line, [offset:ms], and strips <mm:ss.xx> word timings. */
object LrcParser {

    private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TIME = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<LyricLine> {
        // A positive offset makes lyrics appear sooner.
        val offset = OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = ArrayList<LyricLine>()
        for (raw in text.lineSequence()) {
            var rest = raw.trim()
            val times = ArrayList<Long>()
            while (true) {
                val m = TIME.find(rest) ?: break
                if (m.range.first != 0) break
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fraction = m.groupValues[3]
                val ms = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.take(3).toLong()
                }
                times.add(((min * 60 + sec) * 1000 + ms - offset).coerceAtLeast(0L))
                rest = rest.substring(m.range.last + 1).trimStart()
            }
            if (times.isEmpty()) continue
            val lineText = WORD_TIME.replace(rest, "").trim()
            for (t in times) out.add(LyricLine(t, lineText))
        }
        return out.sortedBy { it.timeMs }
    }
}

/** Reads lyrics stored in tags, using Media3's metadata extraction. */
@OptIn(UnstableApi::class)
object EmbeddedLyrics {

    private val LYRIC_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "LYRIC")

    fun read(context: Context, uri: Uri): String? {
        val groups = try {
            MetadataRetriever.retrieveMetadata(context, MediaItem.fromUri(uri)).get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return null
        }
        for (g in 0 until groups.length) {
            val group = groups.get(g)
            for (t in 0 until group.length) {
                val metadata = group.getFormat(t).metadata ?: continue
                for (i in 0 until metadata.length()) {
                    textFrom(metadata.get(i))?.let { return it }
                }
            }
        }
        return null
    }

    private fun textFrom(e: Metadata.Entry): String? = when (e) {
        // MP4 lyrics arrive as a text frame with id USLT; TXXX:LYRICS is a common ID3 alternative.
        is TextInformationFrame ->
            if (e.id == "USLT" || (e.id == "TXXX" && (e.description?.uppercase() ?: "") in LYRIC_KEYS)) {
                e.values.firstOrNull()?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        // ID3 USLT is not decoded by Media3, so it arrives raw.
        is BinaryFrame -> if (e.id == "USLT") parseUslt(e.data) else null
        else -> vorbisComment(e)
    }

    /** FLAC / Ogg Vorbis comments, read by field name so it works across Media3 versions. */
    private fun vorbisComment(e: Metadata.Entry): String? {
        if (e.javaClass.simpleName != "VorbisComment") return null
        return runCatching {
            val key = e.javaClass.getField("key").get(e) as String
            val value = e.javaClass.getField("value").get(e) as String
            if (key.uppercase() in LYRIC_KEYS && value.isNotBlank()) value else null
        }.getOrNull()
    }

    /** USLT: encoding byte, 3-byte language, null-terminated description, then the text. */
    private fun parseUslt(d: ByteArray): String? {
        if (d.size < 5) return null
        val encoding = d[0].toInt()
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val wide = encoding == 1 || encoding == 2
        var i = 4
        if (wide) {
            while (i + 1 < d.size && !(d[i] == 0.toByte() && d[i + 1] == 0.toByte())) i += 2
            i += 2
        } else {
            while (i < d.size && d[i] != 0.toByte()) i++
            i += 1
        }
        if (i >= d.size) return null
        return String(d, i, d.size - i, charset).trimEnd('\u0000').trim().takeIf { it.isNotEmpty() }
    }
}

/** UTF-8 (with or without BOM) or UTF-16 with BOM; anything else is read as Windows-1252. */
fun decodeTextFile(bytes: ByteArray): String {
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

/** True when [bytes] are valid UTF-8 (or empty). */
fun isUtf8(bytes: ByteArray): Boolean = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
    true
} catch (e: CharacterCodingException) {
    false
}

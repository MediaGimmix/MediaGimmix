package com.example.mediaplayer

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.json.JSONObject

/**
 * Settings → Library → Track info: suggestions for a track's tags (from its filename, and from MusicBrainz),
 * and writing the chosen ones into the file.
 */
object TrackInfo {

    /** Tags to write; null fields are left as they are in the file. */
    data class Option(
        val title: String,
        val artist: String?,
        val album: String? = null,
        val albumArtist: String? = null,
        val year: Int? = null,
        val track: Int? = null,
        /** Where it came from, shown under the option ("From the filename", "MusicBrainz"). */
        val source: String,
    )

    /** Formats jaudiotagger can write. (.webm/.opus downloads from before MP3 conversion can't be edited.) */
    private val WRITABLE = setOf("mp3", "m4a", "mp4", "m4b", "flac", "ogg", "oga", "wav", "aif", "aiff", "wma", "dsf")

    fun canWrite(song: Song): Boolean = song.fileName.substringAfterLast('.', "").lowercase() in WRITABLE

    /** True when the tags look missing: no artist, or the title is just the filename. */
    fun looksUntagged(song: Song): Boolean =
        song.artist.isNullOrBlank() || song.title.isNullOrBlank() ||
            cleanName(song.fileName).equals(song.title?.trim(), ignoreCase = true)

    // ---- from the filename ------------------------------------------------------------------

    /** "Artist - Title [abc123].mp3" -> "Artist - Title" (yt-dlp id, extension, video noise and underscores removed). */
    fun cleanName(fileName: String): String {
        var n = fileName.substringBeforeLast('.')
        n = n.replace(Regex("""\s*\[[A-Za-z0-9_-]{6,}]$"""), "") // yt-dlp's [video id]
        n = n.replace('_', ' ')
        n = n.replace(
            Regex("""\s*[(\[](official|lyrics?|audio|video|music video|visuali[sz]er|hd|hq|4k|explicit)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
            "",
        )
        return n.replace(Regex("""\s+"""), " ").trim()
    }

    /** The ways the filename can be read: "Artist - Title", the other way round, or just a title. */
    fun fromFilename(song: Song): List<Option> {
        val name = cleanName(song.fileName)
        val parts = name.split(" - ", " – ", " — ", limit = 2).map { it.trim() }.filter { it.isNotEmpty() }
        val from = "From the filename"
        return if (parts.size == 2) {
            listOf(
                Option(title = parts[1], artist = parts[0], source = from),
                Option(title = parts[0], artist = parts[1], source = "$from (swapped)"),
                Option(title = name, artist = null, source = "$from (title only)"),
            )
        } else {
            listOf(Option(title = name, artist = null, source = "$from (title only)"))
        }
    }

    // ---- MusicBrainz ----------------------------------------------------------------------------

    private const val USER_AGENT = "MediaGimmix/0.1 ( https://github.com/MediaGimmix/MediaGimmix )"

    /**
     * Recordings matching [title] (and [artist] if known) on MusicBrainz (free, no account). Blocking; obeys the
     * internet switch. MusicBrainz asks for at most one request a second, which a tap at a time stays under.
     */
    fun searchMusicBrainz(title: String, artist: String?): List<Option> {
        if (!BuildConfig.FEATURE_MUSICBRAINZ) return emptyList()
        fun q(s: String) = "\"" + s.replace("\"", "") + "\""
        val query = "recording:${q(title)}" + (artist?.let { " AND artist:${q(it)}" } ?: "")
        val url = "https://musicbrainz.org/ws/2/recording?fmt=json&limit=10&query=" + URLEncoder.encode(query, "UTF-8")
        NetPolicy.check(url)
        val conn = URL(url).openConnection() as HttpURLConnection
        val body = try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) throw IOException("MusicBrainz: HTTP ${conn.responseCode}")
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
        val recordings = JSONObject(body).optJSONArray("recordings") ?: return emptyList()
        val out = LinkedHashMap<String, Option>()
        for (i in 0 until recordings.length()) {
            val r = recordings.getJSONObject(i)
            val recArtist = credit(r)
            val releases = r.optJSONArray("releases")
            val release = releases?.optJSONObject(0)
            val album = release?.optString("title")?.ifEmpty { null }
            val albumArtist = release?.let { credit(it) } ?: recArtist
            val year = release?.optString("date")?.take(4)?.toIntOrNull()
            val track = release?.optJSONArray("media")?.optJSONObject(0)?.optJSONArray("track")
                ?.optJSONObject(0)?.optString("number")?.toIntOrNull()
            val o = Option(
                title = r.optString("title"), artist = recArtist, album = album, albumArtist = albumArtist,
                year = year, track = track, source = "MusicBrainz",
            )
            out.putIfAbsent("${o.title}|${o.artist}|${o.album}".lowercase(), o)
        }
        return out.values.take(8)
    }

    /** "Artist feat. Other" from an artist-credit array. */
    private fun credit(o: JSONObject): String? {
        val a = o.optJSONArray("artist-credit") ?: return null
        return buildString {
            for (i in 0 until a.length()) {
                val c = a.getJSONObject(i)
                append(c.optString("name").ifEmpty { c.optJSONObject("artist")?.optString("name").orEmpty() })
                append(c.optString("joinphrase"))
            }
        }.trim().ifEmpty { null }
    }

    // ---- writing ------------------------------------------------------------------------------

    /**
     * Writes [option] into [song]'s file: a temporary copy is edited (jaudiotagger needs a real file) and copied
     * back over the original through the Storage Access Framework. Blocking. Needs write access to its folder.
     */
    fun write(context: Context, song: Song, option: Option) {
        if (!canWrite(song)) throw IOException("Can't edit tags in .${song.fileName.substringAfterLast('.')} files")
        TagOptionSingleton.getInstance().isAndroid = true
        val dir = File(context.cacheDir, "tagedit").apply { mkdirs() }
        val tmp = File(dir, "edit." + song.fileName.substringAfterLast('.').lowercase())
        try {
            val resolver = context.contentResolver
            resolver.openInputStream(song.uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: throw IOException("Couldn't read ${song.fileName}")
            val file = AudioFileIO.read(tmp)
            val tag = file.tagOrCreateAndSetDefault
            tag.setField(FieldKey.TITLE, option.title)
            option.artist?.let { tag.setField(FieldKey.ARTIST, it) }
            option.album?.let { tag.setField(FieldKey.ALBUM, it) }
            option.albumArtist?.let { tag.setField(FieldKey.ALBUM_ARTIST, it) }
            option.year?.let { tag.setField(FieldKey.YEAR, it.toString()) }
            option.track?.takeIf { it > 0 }?.let { tag.setField(FieldKey.TRACK, it.toString()) }
            file.commit()
            val out = try {
                resolver.openOutputStream(song.uri, "wt")
            } catch (e: SecurityException) {
                null
            } ?: throw IOException("No permission to change files there: Settings → Library → allow changes to that folder")
            out.use { o -> tmp.inputStream().use { it.copyTo(o) } }
        } finally {
            tmp.delete()
        }
    }
}

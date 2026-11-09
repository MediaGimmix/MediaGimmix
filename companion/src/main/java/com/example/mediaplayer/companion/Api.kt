package com.example.mediaplayer.companion

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * How to reach the player's API: over Wi-Fi (Settings on the player: Network → Web control) or over
 * Bluetooth to a paired player (Network → Bluetooth control). The access key is the same for both.
 */
data class Connection(
    val host: String,
    val port: Int,
    val key: String,
    val bluetooth: Boolean = false,
    val btAddress: String = "",
    val btName: String = "",
) {
    val isSet get() = if (bluetooth) btAddress.isNotBlank() else host.isNotBlank()
    /** Identifies the player (cache keys). */
    val base get() = if (bluetooth) "bt://$btAddress/api" else "http://${host.trim()}:$port/api"
    val label get() = if (bluetooth) "${btName.ifEmpty { btAddress }} (Bluetooth)" else "${host.trim()}:$port"

    companion object {
        private const val PREFS = "connection"

        fun load(context: Context): Connection {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Connection(
                p.getString("host", "") ?: "", p.getInt("port", 8080), p.getString("key", "") ?: "",
                p.getBoolean("bluetooth", false), p.getString("bt_address", "") ?: "", p.getString("bt_name", "") ?: "",
            )
        }

        fun save(context: Context, c: Connection) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("host", c.host.trim()).putInt("port", c.port).putString("key", c.key.trim())
                .putBoolean("bluetooth", c.bluetooth).putString("bt_address", c.btAddress).putString("bt_name", c.btName)
                .apply()
        }
    }
}

/** A reply from the player, whichever way it came. Header names are lower-case. */
class RawResponse(val code: Int, val headers: Map<String, String>, val body: ByteArray) {
    val text get() = body.toString(Charsets.UTF_8)
}

data class SongInfo(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumId: String?,
    val durationMs: Long,
    /** File size on the player; 0 = unknown. */
    val sizeBytes: Long = 0L,
) {
    val subtitle get() = listOfNotNull(artist, album).joinToString(" · ")

    companion object {
        fun from(o: JSONObject) = SongInfo(
            id = o.getString("id"),
            title = o.optString("title"),
            artist = o.optStringOrNull("artist"),
            album = o.optStringOrNull("album"),
            albumId = o.optStringOrNull("albumId"),
            durationMs = o.optLong("durationMs"),
            sizeBytes = o.optLong("sizeBytes"),
        )
    }
}

data class Status(
    val nowPlaying: SongInfo?,
    val isPlaying: Boolean,
    val shuffle: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** The player's music volume and its maximum; -1 when the player doesn't report it (older version). */
    val volume: Int = -1,
    val volumeMax: Int = -1,
) {
    companion object {
        fun from(o: JSONObject) = Status(
            nowPlaying = o.optJSONObject("nowPlaying")?.let { SongInfo.from(it) },
            isPlaying = o.optBoolean("isPlaying"),
            shuffle = o.optBoolean("shuffle"),
            positionMs = o.optLong("positionMs"),
            durationMs = o.optLong("durationMs"),
            volume = o.optInt("volume", -1),
            volumeMax = o.optInt("volumeMax", -1),
        )
    }
}

data class PlaylistInfo(val id: String, val name: String, val songCount: Int, val missing: Int) {
    companion object {
        fun from(o: JSONObject) = PlaylistInfo(o.getString("id"), o.optString("name"), o.optInt("songCount"), o.optInt("missing"))
    }
}

data class AlbumInfo(val id: String, val title: String, val artist: String, val year: Int, val songCount: Int) {
    companion object {
        fun from(o: JSONObject) =
            AlbumInfo(o.getString("id"), o.optString("title"), o.optString("artist"), o.optInt("year"), o.optInt("songCount"))
    }
}

data class ArtistInfo(val id: String, val name: String, val songCount: Int, val albumCount: Int) {
    companion object {
        fun from(o: JSONObject) = ArtistInfo(o.getString("id"), o.optString("name"), o.optInt("songCount"), o.optInt("albumCount"))
    }
}

class SearchResults(val artists: List<ArtistInfo>, val albums: List<AlbumInfo>, val songs: List<SongInfo>) {
    val isEmpty get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}

/**
 * The player's queue window: the last played songs ([history], oldest first), the current song, and what's
 * coming up: the play next queue first, then the general queue (at most 99 between them). *Total = full sizes.
 */
class QueueInfo(
    val history: List<SongInfo>,
    val nowPlaying: SongInfo?,
    val playNext: List<SongInfo>,
    val general: List<SongInfo>,
    val historyTotal: Int,
    val playNextTotal: Int,
    val generalTotal: Int,
    /** Position of [general]'s first song in the player's whole general queue. */
    val generalStart: Int = 0,
)

/** One page of a list, plus the full size the player reported (X-Total-Count). */
class Page<T>(val items: List<T>, val total: Int)

class ApiError(message: String) : IOException(message)

/**
 * Blocking calls to the player's API (run on Dispatchers.IO). Never asks for a
 * whole library list: only search, playlists and single albums/artists/playlists.
 */
class Api(private val c: Connection) {

    fun status(): Status = Status.from(JSONObject(get("status")))

    /** POST /player/<command>: play, pause, toggle, next, prev, stop, shuffle. Returns the new status. */
    fun command(command: String): Status = Status.from(JSONObject(post("player/$command")))

    fun songArt(songId: String, sizePx: Int): ImageBitmap? = image("songs/$songId/art?size=$sizePx")


    fun queue(): QueueInfo {
        val o = JSONObject(get("queue"))
        fun list(name: String): List<SongInfo> {
            val a = o.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).map { SongInfo.from(a.getJSONObject(it)) }
        }
        return QueueInfo(
            history = list("history"),
            nowPlaying = o.optJSONObject("nowPlaying")?.let { SongInfo.from(it) },
            playNext = list("playNext"),
            general = list("general"),
            historyTotal = o.optInt("historyTotal"),
            playNextTotal = o.optInt("playNextTotal"),
            generalTotal = o.optInt("generalTotal"),
            generalStart = o.optInt("generalStart"),
        )
    }

    /** A song's cover as a plain Bitmap (notifications). */
    fun songArtBitmap(songId: String, sizePx: Int): android.graphics.Bitmap? {
        val r = raw("GET", "songs/$songId/art?size=$sizePx")
        return if (r.code == 200) BitmapFactory.decodeByteArray(r.body, 0, r.body.size) else null
    }

    /**
     * Downloads a song's file into [dest] (the player sends the original file). [progress]: bytes so far,
     * total (-1 if unknown). Blocking; throws on failure (dest may be partly written).
     */
    fun download(songId: String, dest: java.io.File, progress: (Long, Long) -> Unit) {
        val path = "songs/$songId/download"
        // Once the size is known (first chunk), make sure it fits; a 0-byte file is checked up front by the caller.
        var checkedSpace = false
        val guarded: (Long, Long) -> Unit = { done, total ->
            if (!checkedSpace && total > 0) {
                checkedSpace = true
                Storage.requireRoom(dest.parentFile ?: dest, total - done)
            }
            progress(done, total)
        }
        dest.outputStream().buffered().use { out ->
            if (c.bluetooth) {
                val r = BluetoothLink.exchange(c.btAddress, "GET", "/api/$path", c.key.trim(), out, guarded)
                checked(r)
            } else {
                val conn = URL("${c.base}/$path").openConnection() as HttpURLConnection
                conn.connectTimeout = 4_000
                conn.readTimeout = 20_000
                if (c.key.isNotBlank()) conn.setRequestProperty("X-Api-Key", c.key.trim())
                try {
                    if (conn.responseCode !in 200..299) {
                        val body = conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
                        checked(RawResponse(conn.responseCode, emptyMap(), body))
                    }
                    val total = conn.contentLengthLong
                    if (total > 0) {
                        checkedSpace = true
                        Storage.requireRoom(dest.parentFile ?: dest, total)
                    }
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            guarded(done, total)
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

    /** GET an image ("songs/<id>/art?size=..."), null when there is none. */
    fun image(path: String): ImageBitmap? = runCatching { fetchImage(path) }.getOrNull()

    /** The player's playlists (at most [limit]; the player caps it too). */
    fun playlists(jellyfin: Boolean, limit: Int): Page<PlaylistInfo> {
        val (body, total) = getWithTotal("playlists?limit=$limit" + src(jellyfin))
        val a = JSONArray(body)
        return Page((0 until a.length()).map { PlaylistInfo.from(a.getJSONObject(it)) }, total)
    }

    /** A page of songs inside one playlist/album/artist: [path] is e.g. "playlists/<id>/songs". */
    fun songPage(path: String, jellyfin: Boolean, offset: Int, limit: Int): Page<SongInfo> {
        val (body, total) = getWithTotal("$path?offset=$offset&limit=$limit" + src(jellyfin))
        return Page(songs(body), total)
    }

    /** Search everything, at most [limit] of each kind. */
    fun search(query: String, jellyfin: Boolean, limit: Int): SearchResults {
        val o = JSONObject(get("search?q=${q(query)}&limit=$limit" + src(jellyfin)))
        fun arr(name: String) = o.optJSONArray(name) ?: JSONArray()
        val ar = arr("artists"); val al = arr("albums"); val so = arr("songs")
        return SearchResults(
            (0 until ar.length()).map { ArtistInfo.from(ar.getJSONObject(it)) },
            (0 until al.length()).map { AlbumInfo.from(al.getJSONObject(it)) },
            (0 until so.length()).map { SongInfo.from(so.getJSONObject(it)) },
        )
    }

    /** POST something that starts playback ("songs/<id>/play?playlist=...", "albums/<id>/play?shuffle=true"). */
    fun play(path: String, jellyfin: Boolean): Status =
        Status.from(JSONObject(post(path + (if ('?' in path) "&" else "?") + "source=" + if (jellyfin) "jellyfin" else "local")))

    private fun src(jellyfin: Boolean) = if (jellyfin) "&source=jellyfin" else ""

    private fun getWithTotal(path: String): Pair<String, Int> {
        val r = checked(raw("GET", path))
        return r.text to (r.headers["x-total-count"]?.toIntOrNull() ?: -1)
    }

    // ---- transport --------------------------------------------------------------------

    internal fun get(path: String): String = checked(raw("GET", path)).text
    internal fun post(path: String): String = checked(raw("POST", path)).text

    private fun checked(r: RawResponse): RawResponse {
        if (r.code !in 200..299) {
            val msg = runCatching { JSONObject(r.text).optString("error") }.getOrNull()?.ifEmpty { null }
            throw ApiError(msg ?: "HTTP ${r.code}")
        }
        return r
    }

    /** One request, over Bluetooth ([BluetoothLink]) or Wi-Fi (plain HTTP). [path] is after "/api/". */
    private fun raw(method: String, path: String): RawResponse =
        if (c.bluetooth) {
            BluetoothLink.exchange(c.btAddress, method, "/api/$path", c.key.trim())
        } else {
            http(method, path)
        }

    private fun http(method: String, path: String): RawResponse {
        val conn = URL("${c.base}/$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 4_000
        conn.readTimeout = 10_000
        if (c.key.isNotBlank()) conn.setRequestProperty("X-Api-Key", c.key.trim())
        try {
            if (method == "POST") {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(0)
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            val headers = conn.headerFields.entries
                .filter { it.key != null }
                .associate { it.key.lowercase() to it.value.joinToString(",") }
            return RawResponse(code, headers, body)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchImage(path: String): ImageBitmap? {
        val r = raw("GET", path)
        if (r.code != 200) return null
        return BitmapFactory.decodeByteArray(r.body, 0, r.body.size)?.asImageBitmap()
    }

    companion object {
        fun q(s: String): String = URLEncoder.encode(s, "UTF-8")

        fun songs(json: String): List<SongInfo> {
            val a = JSONArray(json)
            return (0 until a.length()).map { SongInfo.from(a.getJSONObject(it)) }
        }
    }
}

internal fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).ifEmpty { null }

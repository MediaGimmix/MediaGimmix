package com.example.mediaplayer

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Web control: an HTTP server on the local network with a JSON API under
 * /api/ and a control panel (assets/web/index.html, or a copy in the app's
 * external files folder: Android/data/com.example.mediaplayer/files/web/,
 * which wins so it can be edited without rebuilding).
 *
 * Never on at startup: [running] always starts false and isn't saved; the
 * port and access key are. API calls need the key (X-Api-Key header or
 * ?key=) unless "require key" is switched off. The panel itself loads
 * without a key and asks for it.
 *
 * Runs only while the app is alive (the player lives in the app, too).
 */
class WebControl(private val context: Context, private val vm: LibraryViewModel) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var running by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var port by mutableStateOf(prefs.getInt(KEY_PORT, DEFAULT_PORT))
        private set
    var accessKey by mutableStateOf(prefs.getString(KEY_KEY, null) ?: newKey().also { prefs.edit().putString(KEY_KEY, it).apply() })
        private set
    var requireKey by mutableStateOf(prefs.getBoolean(KEY_REQUIRE, true))
        private set

    private var server: WebServer? = null

    // ---- settings -------------------------------------------------------------------

    fun setEnabled(on: Boolean) {
        if (on) start() else stop()
    }

    fun changePort(newPort: Int) {
        if (newPort !in 1024..65535) {
            error = "Port must be between 1024 and 65535"
            return
        }
        port = newPort
        prefs.edit().putInt(KEY_PORT, newPort).apply()
        if (running) {
            stop()
            start()
        }
    }

    fun regenerateKey() {
        accessKey = newKey()
        prefs.edit().putString(KEY_KEY, accessKey).apply()
    }

    fun changeRequireKey(on: Boolean) {
        requireKey = on
        prefs.edit().putBoolean(KEY_REQUIRE, on).apply()
    }

    /** http://<ip>:<port>/ for each local network address. */
    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { "http://${it.hostAddress}:$port/" }
    }.getOrDefault(emptyList())

    private fun start() {
        if (running) return
        val s = WebServer(port, ::handle)
        try {
            s.start()
        } catch (e: Exception) {
            error = "Couldn't start on port $port: ${e.message}"
            return
        }
        server = s
        error = null
        running = true
    }

    fun stop() {
        server?.stop()
        server = null
        running = false
    }

    // ---- requests ---------------------------------------------------------------------

    private fun handle(req: WebServer.Request): WebServer.Response {
        if (req.method == "OPTIONS") return cors(WebServer.Response(204, "text/plain"))
        if (req.segments.firstOrNull() != "api") return staticFile(req.segments)
        val key = req.headers["x-api-key"] ?: req.query["key"]
        if (requireKey && key != accessKey) return cors(error(401, "Missing or wrong access key"))
        return cors(api(req))
    }

    private fun cors(r: WebServer.Response) = WebServer.Response(
        r.status, r.contentType, r.body, r.stream, r.length,
        r.headers + mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Headers" to "X-Api-Key, Content-Type",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
        ),
    )

    private fun api(req: WebServer.Request): WebServer.Response {
        val s = req.segments.drop(1) // after "api"
        val post = req.method == "POST"
        val jelly = req.query["source"] == "jellyfin"
        return when {
            // ---- library
            s == listOf("artists") -> ok(onMain { JSONArray(lib(jelly).artists.map { artistJson(it) }) })
            s == listOf("albums") -> ok(onMain { JSONArray(lib(jelly).albums.map { albumJson(it) }) })
            s == listOf("songs") -> ok(onMain { JSONArray(lib(jelly).songs.map { songJson(it) }) })
            s.size == 3 && s[0] == "artists" && s[2] == "albums" -> onMain {
                val l = lib(jelly)
                val artist = l.artists.firstOrNull { id("artist:" + it.key) == s[1] } ?: return@onMain null
                val uris = artist.songs.mapTo(HashSet()) { it.uri }
                JSONArray(l.albums.filter { a -> a.songs.any { it.uri in uris } }.map { albumJson(it) })
            }?.let { ok(it) } ?: error(404, "No such artist")
            s.size == 3 && s[0] == "artists" && s[2] == "songs" -> onMain {
                lib(jelly).artists.firstOrNull { id("artist:" + it.key) == s[1] }?.let { a -> JSONArray(a.songs.map { songJson(it) }) }
            }?.let { ok(it) } ?: error(404, "No such artist")
            s.size == 3 && s[0] == "albums" && s[2] == "songs" -> onMain {
                lib(jelly).albums.firstOrNull { id("album:" + it.key) == s[1] }?.let { a -> JSONArray(a.songs.map { songJson(it) }) }
            }?.let { ok(it) } ?: error(404, "No such album")

            // ---- art and downloads
            s.size == 3 && s[0] == "albums" && s[2] == "art" -> {
                val source = onMain { lib(jelly).albums.firstOrNull { id("album:" + it.key) == s[1] }?.let { vm.albumArt(it) } }
                art(source, req)
            }
            s.size == 3 && s[0] == "songs" && s[2] == "art" -> art(onMain { findSong(s[1])?.let { vm.songArt(it) } }, req)
            s.size == 3 && s[0] == "songs" && s[2] == "download" -> download(onMain { findSong(s[1]) })

            // ---- playback control
            s.size == 3 && s[0] == "songs" && s[2] == "play" -> {
                if (!post) return error(405, "Use POST")
                val played = onMain {
                    val song = findSong(s[1]) ?: return@onMain false
                    val l = lib(Jellyfin.isJellyfin(song))
                    // Same as tapping the song in its album (or in Songs, if it's on no album).
                    val context = l.albums.firstOrNull { a -> a.songs.any { it.uri == song.uri } }?.songs ?: l.songs
                    vm.playback.playFromLibrary(song, context)
                    true
                }
                if (played) ok(onMain { statusJson() }) else error(404, "No such song")
            }
            s.size == 2 && s[0] == "player" -> {
                if (!post) return error(405, "Use POST")
                val p = vm.playback
                val known = onMain {
                    when (s[1]) {
                        "play" -> p.resume()
                        "pause" -> p.pause()
                        "toggle" -> p.togglePause()
                        "next" -> p.skipNext()
                        "prev", "previous" -> p.skipPrevious()
                        "stop" -> p.stop()
                        "shuffle" -> p.changeShuffle(req.query["on"]?.toBooleanStrictOrNull() ?: !p.shuffle)
                        else -> return@onMain false
                    }
                    true
                }
                if (known) ok(onMain { statusJson() }) else error(404, "Unknown player command")
            }
            s == listOf("status") -> ok(onMain { statusJson() })
            s == listOf("queue") -> ok(onMain { queueJson() })
            else -> error(404, "Unknown endpoint")
        }
    }

    // ---- data -------------------------------------------------------------------------

    private class Lib(val songs: List<Song>, val albums: List<AlbumEntry>, val artists: List<ArtistEntry>)

    private fun lib(jellyfin: Boolean) =
        if (jellyfin) Lib(vm.jellyfinSongs, vm.jellyfinAlbums, vm.jellyfinArtists) else Lib(vm.songs, vm.albums, vm.artists)

    private fun findSong(songId: String): Song? =
        vm.songs.firstOrNull { id(it.uri.toString()) == songId } ?: vm.jellyfinSongs.firstOrNull { id(it.uri.toString()) == songId }

    private fun songJson(s: Song) = JSONObject()
        .put("id", id(s.uri.toString()))
        .put("title", s.displayTitle)
        .put("artist", s.artist ?: JSONObject.NULL)
        .put("album", s.album ?: JSONObject.NULL)
        .put("albumArtist", s.albumArtist ?: JSONObject.NULL)
        .put("track", s.trackNumber)
        .put("disc", s.discNumber)
        .put("year", s.year)
        .put("durationMs", s.durationMs)
        .put("source", if (Jellyfin.isJellyfin(s)) "jellyfin" else "local")
        .put("albumId", LibraryGrouping.albumKey(s)?.let { id("album:$it") } ?: JSONObject.NULL)

    private fun albumJson(a: AlbumEntry) = JSONObject()
        .put("id", id("album:" + a.key))
        .put("title", a.title)
        .put("artist", a.artist)
        .put("year", a.year)
        .put("songCount", a.songs.size)

    private fun artistJson(a: ArtistEntry) = JSONObject()
        .put("id", id("artist:" + a.key))
        .put("name", a.name)
        .put("songCount", a.songs.size)
        .put("albumCount", a.albumCount)

    private fun statusJson(): JSONObject {
        val p = vm.playback
        return JSONObject()
            .put("nowPlaying", p.nowPlaying?.let { songJson(it) } ?: JSONObject.NULL)
            .put("isPlaying", p.isPlaying)
            .put("shuffle", p.shuffle)
            .put("positionMs", p.positionMs())
            .put("durationMs", p.durationMs())
    }

    private fun queueJson(): JSONObject {
        val q = vm.playback.queue
        return statusJson()
            .put("playNext", JSONArray(q.next.map { songJson(it) }))
            .put("general", JSONArray(q.general.map { songJson(it) }))
            // Index in "general" of the song playback returns to (-1 = before the start).
            .put("position", q.returnIndex)
    }

    private fun art(source: ArtSource?, req: WebServer.Request): WebServer.Response {
        source ?: return error(404, "Not found")
        val size = req.query["size"]?.toIntOrNull()?.coerceIn(64, 1500) ?: 600
        val image = runBlocking(Dispatchers.IO) { Artwork.load(context, source, size) } ?: return error(404, "No cover")
        val out = ByteArrayOutputStream()
        image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, 90, out)
        return WebServer.Response(200, "image/jpeg", out.toByteArray())
    }

    private fun download(song: Song?): WebServer.Response {
        song ?: return error(404, "No such song")
        val (stream, length) = if (Jellyfin.isJellyfin(song)) {
            Jellyfin.openStream(song) ?: return error(502, "Jellyfin server didn't send the file")
        } else {
            val s = runCatching { context.contentResolver.openInputStream(song.uri) }.getOrNull()
                ?: return error(404, "File not readable")
            s to (song.sizeBytes.takeIf { it > 0 } ?: -1L)
        }
        val name = URLEncoder.encode(song.fileName, "UTF-8").replace("+", "%20")
        return WebServer.Response(
            200, song.mimeType.ifEmpty { "application/octet-stream" },
            stream = stream, length = length,
            headers = mapOf("Content-Disposition" to "attachment; filename*=UTF-8''$name"),
        )
    }

    /** The control panel: files from files/web/ (user copy) or assets/web/. */
    private fun staticFile(segments: List<String>): WebServer.Response {
        if (segments.any { it == ".." || it.contains('\\') }) return error(400, "Bad path")
        val path = segments.joinToString("/").ifEmpty { "index.html" }
        val override = context.getExternalFilesDir("web")?.let { File(it, path) }
        val bytes = if (override != null && override.isFile) {
            override.readBytes()
        } else {
            runCatching { context.assets.open("web/$path").use { it.readBytes() } }.getOrNull()
                ?: return error(404, "Not found")
        }
        val type = when (path.substringAfterLast('.').lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            else -> "application/octet-stream"
        }
        return WebServer.Response(200, type, bytes)
    }

    private fun ok(json: Any) = WebServer.Response.json(json.toString())

    private fun error(status: Int, message: String) =
        WebServer.Response.json(JSONObject().put("error", message).toString(), status)

    /** Library and player state belong to the main thread. */
    private fun <T> onMain(block: () -> T): T = runBlocking(Dispatchers.Main) { block() }

    companion object {
        private const val KEY_PORT = "web_port"
        private const val KEY_KEY = "web_key"
        private const val KEY_REQUIRE = "web_require_key"
        const val DEFAULT_PORT = 8080

        /** Stable short id: the first 16 hex digits of SHA-1. */
        fun id(s: String): String {
            val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            return d.take(8).joinToString("") { "%02x".format(it) }
        }

        private fun newKey(): String {
            val chars = "abcdefghijkmnpqrstuvwxyz23456789"
            val r = SecureRandom()
            return (1..20).map { chars[r.nextInt(chars.length)] }.joinToString("")
        }
    }
}

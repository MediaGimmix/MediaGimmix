package com.example.mediaplayer

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream
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
 * /api/ (no web page; anything outside /api/ is a 404).
 *
 * Never on at startup: [running] always starts false and isn't saved; the
 * port and access key are. API calls need the key (X-Api-Key header or
 * ?key=) unless "require key" is switched off.
 *
 * Runs only while the app is alive (the player lives in the app, too).
 *
 * API (JSON; add ?source=jellyfin to library calls for the Jellyfin library,
 * default is songs on the phone; ids = first 16 hex of SHA-1 of the song uri,
 * "album:<key>" or "artist:<key>"):
 *
 *   Lists are capped at the "Most items per reply" setting (main app, Settings → Network → Web control);
 *   ?limit= can only lower it, X-Total-Count has the full size. Inside one album/artist/playlist, ?offset= pages.
 *
 *   GET  /api/artists                       list artists
 *   GET  /api/albums                        list albums
 *   GET  /api/artists/{id}/albums           an artist's albums
 *   GET  /api/artists/{id}/songs            an artist's songs
 *   GET  /api/songs                         every song (the song heap)
 *   GET  /api/albums/{id}/songs             an album's songs
 *   GET  /api/playlists                     list playlists {id, name, songCount, missing, source}
 *   GET  /api/playlists/{id}/songs          a playlist's songs (in order)
 *   GET  /api/playlists/{id}/art?size=600   playlist cover (JPEG)
 *   POST /api/albums|artists|playlists/{id}/play[?shuffle=true]   play all of it (replaces the queues)
 *   GET  /api/albums/{id}/art?size=600      album cover (JPEG)
 *   GET  /api/songs/{id}/art?size=600       song cover (JPEG)
 *   GET  /api/artists/{id}/art?size=600     artist picture (custom, downloaded, else newest album's cover)
 *   GET  /api/songs/{id}/download           the song's file
 *   GET  /api/search?q=...&limit=50         search everything: {"query", "artists", "albums", "songs"}
 *   GET  /api/search/songs?q=...            search songs (title, artist, album, album artist, file name)
 *   GET  /api/search/albums?q=...           search albums (title, album artist)
 *   GET  /api/search/artists?q=...          search artists (name)
 *                                           (search: case/accent-insensitive, every word must match, best
 *                                            matches first; limit = max per kind, default 50)
 *   POST /api/songs/{id}/play[?playlist=|artist=|album={id}]   play a song like tapping it in that list
 *                                           (default: its album)
 *   POST /api/player/play | pause | toggle | next | prev | stop
 *   POST /api/player/shuffle[?on=true|false]   toggle (or set) shuffle
 *   GET  /api/status                        now playing, playing/paused, shuffle, position
 *   GET  /api/queue                         status + last 48 played ("history", oldest first) + up to 99 coming
 *                                           up: "playNext" then "general"; historyTotal/playNextTotal/generalTotal
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

    /** Most items any API reply lists (songs, albums, artists, playlists, search results). Set in the main app only. */
    var listLimit by mutableStateOf(prefs.getInt(KEY_LIST_LIMIT, DEFAULT_LIST_LIMIT))
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

    fun changeListLimit(n: Int) {
        listLimit = n.coerceIn(1, MAX_LIST_LIMIT)
        prefs.edit().putInt(KEY_LIST_LIMIT, listLimit).apply()
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
            .map { "http://${it.hostAddress}:$port/api/" }
    }.getOrDefault(emptyList())

    private fun start() {
        if (running) return
        if (!NetPolicy.localApi) {
            error = "Local network requests are off (Settings → Network)."
            return
        }
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

    private fun handle(req: WebServer.Request): WebServer.Response = handleApi(req, viaBluetooth = false)

    /**
     * One API request, from the network server or [BluetoothControl]. Each way in has its own switch in
     * Settings → Network → Network switches; the key, limits and endpoints are shared.
     */
    fun handleApi(req: WebServer.Request, viaBluetooth: Boolean): WebServer.Response {
        if (viaBluetooth && !NetPolicy.bluetoothApi) return error(403, "Bluetooth requests are off")
        if (!viaBluetooth && !NetPolicy.localApi) return error(403, "Local network requests are off")
        if (req.method == "OPTIONS") return cors(WebServer.Response(204, "text/plain"))
        if (req.segments.firstOrNull() != "api") return cors(error(404, "Not found (the API is under /api/)"))
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
            // ---- library (every list is capped at the main app's limit; X-Total-Count = full size)
            s == listOf("artists") -> list(req, onMain { lib(jelly).artists }) { artistJson(it) }
            s == listOf("albums") -> list(req, onMain { lib(jelly).albums }) { albumJson(it) }
            s == listOf("songs") -> list(req, onMain { lib(jelly).songs }) { songJson(it) }
            s == listOf("playlists") -> list(req, onMain { lib(jelly).playlists }) { playlistJson(it) }
            s.size == 3 && s[0] == "artists" && s[2] == "albums" -> {
                val albums = onMain {
                    val l = lib(jelly)
                    val artist = findArtist(l, s[1]) ?: return@onMain null
                    val uris = artist.songs.mapTo(HashSet()) { it.uri }
                    l.albums.filter { a -> a.songs.any { it.uri in uris } }
                } ?: return error(404, "No such artist")
                list(req, albums) { albumJson(it) }
            }
            s.size == 3 && s[0] == "artists" && s[2] == "songs" ->
                onMain { findArtist(lib(jelly), s[1])?.songs }?.let { songs -> list(req, songs) { songJson(it) } }
                    ?: error(404, "No such artist")
            s.size == 3 && s[0] == "albums" && s[2] == "songs" ->
                onMain { findAlbum(lib(jelly), s[1])?.songs }?.let { songs -> list(req, songs) { songJson(it) } }
                    ?: error(404, "No such album")
            s.size == 3 && s[0] == "playlists" && s[2] == "songs" ->
                onMain { findPlaylist(lib(jelly), s[1])?.songs }?.let { songs -> list(req, songs) { songJson(it) } }
                    ?: error(404, "No such playlist")

            // ---- play a whole album / artist / playlist (POST, ?shuffle=true)
            s.size == 3 && s[0] in setOf("albums", "artists", "playlists") && s[2] == "play" -> {
                if (!post) return error(405, "Use POST")
                val shuffled = req.query["shuffle"]?.toBooleanStrictOrNull() ?: false
                val played = onMain {
                    val l = lib(jelly)
                    val songs = when (s[0]) {
                        "albums" -> findAlbum(l, s[1])?.songs
                        "artists" -> findArtist(l, s[1])?.songs
                        else -> findPlaylist(l, s[1])?.songs
                    }
                    if (songs.isNullOrEmpty()) return@onMain false
                    vm.playback.playAll(songs, shuffled)
                    true
                }
                if (played) ok(onMain { statusJson() }) else error(404, "Not found or empty")
            }

            // ---- search
            s.firstOrNull() == "search" && s.size <= 2 -> {
                val q = req.query["q"]?.trim().orEmpty()
                if (q.isEmpty()) return error(400, "Missing ?q=")
                val limit = (req.query["limit"]?.toIntOrNull() ?: DEFAULT_SEARCH_LIMIT).coerceIn(1, listLimit)
                val l = onMain { lib(jelly) }
                when (s.getOrNull(1)) {
                    null -> {
                        val r = LibrarySearch.all(q, l.songs, l.albums, l.artists, limit)
                        ok(
                            JSONObject()
                                .put("query", q)
                                .put("artists", JSONArray(r.artists.map { artistJson(it) }))
                                .put("albums", JSONArray(r.albums.map { albumJson(it) }))
                                .put("songs", JSONArray(r.songs.map { songJson(it) })),
                        )
                    }
                    "songs" -> ok(JSONArray(LibrarySearch.songs(q, l.songs, limit).map { songJson(it) }))
                    "albums" -> ok(JSONArray(LibrarySearch.albums(q, l.albums, limit).map { albumJson(it) }))
                    "artists" -> ok(JSONArray(LibrarySearch.artists(q, l.artists, limit).map { artistJson(it) }))
                    else -> error(404, "Search songs, albums or artists")
                }
            }

            // ---- art and downloads
            s.size == 3 && s[0] == "albums" && s[2] == "art" -> {
                val source = onMain { lib(jelly).albums.firstOrNull { id("album:" + it.key) == s[1] }?.let { vm.albumArt(it) } }
                art(source, req)
            }
            s.size == 3 && s[0] == "artists" && s[2] == "art" -> {
                val source = onMain { lib(jelly).artists.firstOrNull { id("artist:" + it.key) == s[1] }?.let { vm.artistArt(it) } }
                art(source, req)
            }
            s.size == 3 && s[0] == "playlists" && s[2] == "art" ->
                art(onMain { findPlaylist(lib(jelly), s[1])?.let { vm.playlistArt(it) } }, req)
            s.size == 3 && s[0] == "songs" && s[2] == "art" -> art(onMain { findSong(s[1])?.let { vm.songArt(it) } }, req)
            s.size == 3 && s[0] == "songs" && s[2] == "download" -> download(onMain { findSong(s[1]) })

            // ---- playback control
            s.size == 3 && s[0] == "songs" && s[2] == "play" -> {
                if (!post) return error(405, "Use POST")
                val played = onMain {
                    val song = findSong(s[1]) ?: return@onMain false
                    val l = lib(Jellyfin.isJellyfin(song))
                    // Same as tapping the song in that list: ?playlist=, ?artist= or ?album= (default: its own
                    // album, or Songs if it's on no album).
                    val context = req.query["playlist"]?.let { findPlaylist(l, it)?.songs }
                        ?: req.query["artist"]?.let { findArtist(l, it)?.songs }
                        ?: req.query["album"]?.let { findAlbum(l, it)?.songs }
                        ?: l.albums.firstOrNull { a -> a.songs.any { it.uri == song.uri } }?.songs
                        ?: l.songs
                    vm.playback.playFromLibrary(song, if (context.any { it.uri == song.uri }) context else l.songs)
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

    private class Lib(
        val songs: List<Song>,
        val albums: List<AlbumEntry>,
        val artists: List<ArtistEntry>,
        val playlists: List<Playlist>,
    )

    private fun lib(jellyfin: Boolean) =
        if (jellyfin) {
            Lib(vm.jellyfinSongs, vm.jellyfinAlbums, vm.jellyfinArtists, vm.jellyfinPlaylists)
        } else {
            Lib(vm.songs, vm.albums, vm.artists, vm.playlists)
        }

    private fun findAlbum(l: Lib, albumId: String) = l.albums.firstOrNull { id("album:" + it.key) == albumId }
    private fun findArtist(l: Lib, artistId: String) = l.artists.firstOrNull { id("artist:" + it.key) == artistId }
    private fun findPlaylist(l: Lib, playlistId: String) = l.playlists.firstOrNull { id("playlist:" + it.uri) == playlistId }

    /**
     * A JSON array of at most [listLimit] items (less with ?limit=), starting at ?offset= (default 0), with the
     * full size in X-Total-Count. Paging is only for lists inside one album/artist/playlist; the top-level
     * lists ignore ?offset= so the whole library can't be pulled out page by page.
     */
    private fun <T> list(req: WebServer.Request, items: List<T>, json: (T) -> JSONObject): WebServer.Response {
        val limit = (req.query["limit"]?.toIntOrNull() ?: listLimit).coerceIn(1, listLimit)
        val topLevel = req.segments.size == 2 // /api/<kind>
        val offset = if (topLevel) 0 else (req.query["offset"]?.toIntOrNull() ?: 0).coerceIn(0, items.size)
        val page = items.subList(offset, minOf(items.size, offset + limit))
        return WebServer.Response(
            200, "application/json; charset=utf-8", JSONArray(page.map(json)).toString().toByteArray(),
            headers = mapOf("X-Total-Count" to items.size.toString(), "Access-Control-Expose-Headers" to "X-Total-Count"),
        )
    }

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

    private fun playlistJson(p: Playlist) = JSONObject()
        .put("id", id("playlist:" + p.uri))
        .put("name", p.name)
        .put("songCount", p.songs.size)
        .put("missing", p.missing)
        .put("source", if (p.rootUri == Jellyfin.ROOT) "jellyfin" else "local")

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

    /**
     * A window around the current song: the last [QUEUE_BACK] played ("history", oldest first) and up to
     * [QUEUE_AHEAD] coming up, play next queue first ("playNext"), then the rest of the general queue
     * ("general"). The *Total fields give the full sizes.
     */
    private fun queueJson(): JSONObject {
        val q = vm.playback.queue
        val history = q.recentHistory(QUEUE_BACK)
        val next = q.next.take(QUEUE_AHEAD)
        val upcoming = q.general.drop(q.returnIndex + 1)
        val general = upcoming.take(QUEUE_AHEAD - next.size)
        return statusJson()
            .put("history", JSONArray(history.map { songJson(it) }))
            .put("playNext", JSONArray(next.map { songJson(it) }))
            .put("general", JSONArray(general.map { songJson(it) }))
            .put("historyTotal", q.historySize)
            .put("playNextTotal", q.next.size)
            .put("generalTotal", upcoming.size)
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

    private fun ok(json: Any) = WebServer.Response.json(json.toString())

    private fun error(status: Int, message: String) =
        WebServer.Response.json(JSONObject().put("error", message).toString(), status)

    /** Library and player state belong to the main thread. */
    private fun <T> onMain(block: () -> T): T = runBlocking(Dispatchers.Main) { block() }

    companion object {
        private const val KEY_PORT = "web_port"
        private const val DEFAULT_SEARCH_LIMIT = 50
        private const val KEY_KEY = "web_key"
        private const val KEY_REQUIRE = "web_require_key"
        private const val KEY_LIST_LIMIT = "web_list_limit"
        const val DEFAULT_LIST_LIMIT = 250
        const val MAX_LIST_LIMIT = 5000
        /** Queue window: songs already played, and songs coming up (play next first, then the general queue). */
        const val QUEUE_BACK = 48
        const val QUEUE_AHEAD = 99
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

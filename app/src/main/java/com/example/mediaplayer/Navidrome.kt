package com.example.mediaplayer

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Navidrome streaming: sign-in, the music library, stream/cover/lyrics URLs.
 *
 * Talks the Subsonic API at /rest, authenticated with the stable per-user token
 * and salt (the u/t/s query params) returned by the native /auth/login — never
 * the short-lived JWT, which expires after two days. Songs are streamed as the
 * original files (/rest/stream?format=raw). Subsonic only authenticates in the
 * query string, so the token is added to stream URLs in [authorize] rather than
 * stored in the library. Nothing is reported back to the server: play counts
 * stay on the phone.
 *
 * Sync pages through search3 with an empty query (Navidrome returns every song),
 * then reads the playlists. Navidrome songs are ordinary [Song]s with [ROOT] as
 * their rootUri; the library is cached in filesDir/[CACHE_FILE] between syncs.
 */
object Navidrome : NetshareBackend {
    const val ROOT = "navidrome"
    override val id = ROOT
    override val label = "Navidrome"
    override val builtIn get() = BuildConfig.FEATURE_NAVIDROME
    override val fields = listOf(
        SignInField("server", "Server address", "192.168.1.10:4533"),
        SignInField("user", "User name"),
        SignInField("password", "Password", secret = true),
    )
    override val keeps = "the server's token and salt"

    private const val PREFS = "navidrome"
    private const val CACHE_FILE = "navidrome_library.json"
    private const val SUBSONIC_VERSION = "1.16.1"
    private const val CLIENT = "mediagimmix"
    private const val PAGE = 500

    data class Account(
        val server: String,
        val serverName: String,
        val userName: String,
        val t: String, // subsonicToken
        val s: String, // subsonicSalt
    )

    /** The secret the vault keeps for this backend: the subsonic token and salt. */
    private data class Credential(val t: String, val s: String) {
        fun json() = JSONObject().put("t", t).put("s", s).toString()

        companion object {
            fun parse(text: String?): Credential? = text?.let {
                runCatching {
                    val o = JSONObject(it)
                    Credential(o.getString("t"), o.getString("s"))
                }.getOrNull()
            }
        }
    }

    /** The signed-in account, read by the stream, cover and lyrics code on any thread. Null while locked. */
    @Volatile var account: Account? = null
        private set

    /** The signed-in account even while its token is locked by YubiKey (t and s empty then). For display. */
    @Volatile var signedIn: Account? = null
        private set

    override val shown: ServerAccount? get() = signedIn?.let { ServerAccount(it.server, it.serverName, it.userName) }
    override val ready: Boolean get() = account != null

    // ---- account ------------------------------------------------------------

    override fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        TokenVault.init(context)
        val known = p.getString("user_name", null)?.let {
            Account(p.getString("server", "").orEmpty(), p.getString("server_name", "").orEmpty(), it, "", "")
        }
        signedIn = known
        // A locked vault leaves the token null: Navidrome waits for a trusted YubiKey.
        account = known?.let { k -> Credential.parse(TokenVault.readToken(ROOT))?.let { k.copy(t = it.t, s = it.s) } }
    }

    override fun isLocked(): Boolean = signedIn != null && account == null

    override fun unlock(): Boolean {
        val known = signedIn ?: return false
        val cred = Credential.parse(TokenVault.readToken(ROOT)) ?: return false
        account = known.copy(t = cred.t, s = cred.s)
        return true
    }

    override fun lock() {
        TokenVault.lock()
        if (TokenVault.isLocked()) account = null
    }

    override fun signIn(context: Context, values: Map<String, String>) {
        val server = Netshare.normalizeServer(values["server"].orEmpty())
        val userName = values["user"].orEmpty()
        val login = try {
            JSONObject(jsonPost("$server/auth/login", mapOf("username" to userName, "password" to values["password"].orEmpty())))
        } catch (e: HttpError) {
            throw IOException(
                when (e.code) {
                    401 -> "Wrong user name or password"
                    404, 405 -> "$server doesn't look like a Navidrome server"
                    else -> "Couldn't sign in to $server (HTTP ${e.code})"
                },
            )
        } catch (e: IOException) {
            throw IOException("Couldn't reach a Navidrome server at $server (${e.message})")
        } catch (e: Exception) {
            throw IOException("$server doesn't look like a Navidrome server")
        }
        val t = login.optString("subsonicToken")
        val s = login.optString("subsonicSalt")
        if (t.isEmpty() || s.isEmpty()) throw IOException("$server doesn't look like a Navidrome server")
        val acc = Account(
            server = server,
            serverName = runCatching { URL(server).host }.getOrNull()?.ifEmpty { null } ?: server,
            userName = login.optString("username", userName),
            t = t,
            s = s,
        )
        // The token must actually work over /rest before we keep it.
        if (!ping(acc)) throw IOException("$server doesn't look like a Navidrome server")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("server", acc.server)
            .putString("server_name", acc.serverName)
            .putString("user_name", acc.userName)
            .apply()
        // Plain, or encrypted when YubiKeys protect it (held in memory until one unlocks, if locked).
        TokenVault.writeToken(Credential(acc.t, acc.s).json(), ROOT)
        signedIn = acc.copy(t = "", s = "")
        account = acc
    }

    /** Signs out: forgets the token and the cached library (the token stays valid on the server). */
    override fun signOut(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("server_name").remove("user_name")
            .apply() // the server address stays, to make signing in again easier
        TokenVault.deleteToken(ROOT)
        File(context.filesDir, CACHE_FILE).delete()
        account = null
        signedIn = null
    }

    override fun lastServer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("server", "").orEmpty()

    private fun ping(acc: Account): Boolean = runCatching { subsonic(acc, "ping", emptyMap()); true }.getOrDefault(false)

    // ---- library ------------------------------------------------------------

    override fun sync(context: Context, onProgress: (String) -> Unit): ServerLibrary {
        val acc = account ?: throw IOException("Not signed in")
        val songs = JSONArray()
        var offset = 0
        while (true) {
            val page = subsonic(
                acc, "search3",
                mapOf(
                    "query" to "", "artistCount" to "0", "albumCount" to "0",
                    "songCount" to PAGE.toString(), "songOffset" to offset.toString(),
                ),
            ).optJSONObject("searchResult3")?.optJSONArray("song") ?: JSONArray()
            for (i in 0 until page.length()) songs.put(page.getJSONObject(i))
            offset += page.length()
            onProgress("Reading songs: ${"%,d".format(songs.length())}")
            if (page.length() < PAGE) break
        }

        onProgress("Reading playlists")
        val playlists = JSONArray()
        val pls = subsonic(acc, "getPlaylists", emptyMap()).optJSONObject("playlists")?.optJSONArray("playlist") ?: JSONArray()
        for (i in 0 until pls.length()) {
            val pl = pls.getJSONObject(i)
            val entries = subsonic(acc, "getPlaylist", mapOf("id" to pl.getString("id")))
                .optJSONObject("playlist")?.optJSONArray("entry") ?: JSONArray()
            val ids = JSONArray()
            for (j in 0 until entries.length()) ids.put(entries.getJSONObject(j).optString("id"))
            playlists.put(JSONObject().put("id", pl.getString("id")).put("name", pl.optString("name")).put("ids", ids))
        }

        val cache = JSONObject()
            .put("server", acc.server)
            .put("syncedAt", System.currentTimeMillis())
            .put("songs", songs)
            .put("playlists", playlists)
        File(context.filesDir, CACHE_FILE).writeText(cache.toString())
        return parse(cache)
    }

    override fun loadCached(context: Context): ServerLibrary? {
        val acc = account ?: return null
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            if (json.optString("server") != acc.server) null else parse(json)
        }.getOrNull()
    }

    private fun parse(json: JSONObject): ServerLibrary {
        val server = json.getString("server")
        val arr = json.getJSONArray("songs")
        val songs = ArrayList<Song>(arr.length())
        val byId = HashMap<String, Song>()
        val artIds = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val song = toSong(server, o) ?: continue
            songs += song
            byId[o.getString("id")] = song
            o.optString("coverArt").takeIf { it.isNotEmpty() }?.let { artIds[song.uri.toString()] = it }
        }
        val pls = json.optJSONArray("playlists") ?: JSONArray()
        val playlists = (0 until pls.length()).map { i ->
            val pl = pls.getJSONObject(i)
            val ids = pl.getJSONArray("ids")
            val list = (0 until ids.length()).mapNotNull { byId[ids.getString(it)] }
            Playlist(
                uri = Uri.parse("navidrome:playlist/" + pl.getString("id")),
                rootUri = ROOT,
                name = pl.optString("name").ifEmpty { "Playlist" },
                folderPath = "",
                songs = list,
                missing = ids.length() - list.size,
            )
        }.sortedBy { it.name.lowercase() }
        return ServerLibrary(songs, playlists, artIds, json.optLong("syncedAt"))
    }

    private fun toSong(server: String, o: JSONObject): Song? {
        val id = o.optString("id").ifEmpty { return null }
        val title = o.optString("title").ifEmpty { null }
        val artist = o.optString("artist").ifEmpty { null }
        val album = o.optString("album").ifEmpty { null }
        // OpenSubsonic: albumArtists (array), else displayAlbumArtist.
        val albumArtist = o.optJSONArray("albumArtists")
            ?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") }.filter { it.isNotEmpty() }.joinToString("; ") }
            ?.ifEmpty { null }
            ?: o.optString("displayAlbumArtist").ifEmpty { null }
        val suffix = o.optString("suffix").lowercase().ifEmpty { "mp3" }
        val path = o.optString("path")
        val fileName = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { "${title ?: id}.$suffix" }
        val owner = albumArtist ?: artist ?: "Unknown artist"
        return Song(
            uri = Uri.parse("$server/rest/stream?id=${Uri.encode(id)}&format=raw"),
            fileName = fileName,
            folderPath = listOfNotNull(owner, album).joinToString("/"),
            mimeType = o.optString("contentType").ifEmpty { "audio/$suffix" },
            sizeBytes = o.optLong("size"),
            rootUri = ROOT,
            title = title,
            // ";" is one of the default delimiters, so the app's own splitting (and whitelist) still applies.
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            durationMs = o.optLong("duration") * 1000L, // Subsonic counts whole seconds
            trackNumber = o.optInt("track"),
            discNumber = o.optInt("discNumber"),
            year = o.optInt("year"),
            dateAdded = runCatching { Instant.parse(o.optString("created")).toEpochMilli() }.getOrDefault(0L),
        )
    }

    // ---- covers, lyrics, streams ------------------------------------------------

    override fun cover(artId: String, sizePx: Int): ByteArray? {
        val acc = account ?: return null
        return runCatching {
            requestBytes("${acc.server}/rest/getCoverArt?id=${Uri.encode(artId)}&size=$sizePx&" + authQuery(acc))
        }.getOrNull()
    }

    override fun openStream(song: Song): Pair<java.io.InputStream, Long>? {
        val acc = account ?: return null
        if (!song.uri.toString().startsWith("${acc.server}/rest/")) return null
        return runCatching {
            val c = open("GET", song.uri.toString() + "&" + authQuery(acc))
            if (c.responseCode !in 200..299) {
                c.disconnect()
                return null
            }
            c.inputStream to c.contentLengthLong
        }.getOrNull()
    }

    /**
     * Lyrics: OpenSubsonic getLyricsBySongId (synced when the server has timestamps),
     * else the classic getLyrics by artist and title (plain text). Blocking.
     */
    override fun lyrics(song: Song): Lyrics? {
        val acc = account ?: return null
        val id = song.uri.getQueryParameter("id")
        if (id != null) {
            runCatching {
                val list = subsonic(acc, "getLyricsBySongId", mapOf("id" to id))
                    .optJSONObject("lyricsList")?.optJSONArray("structuredLyrics")
                structured(list)
            }.getOrNull()?.let { return it }
        }
        val title = song.title ?: return null
        val artist = song.artist ?: return null
        val value = runCatching {
            subsonic(acc, "getLyrics", mapOf("artist" to artist, "title" to title)).optJSONObject("lyrics")?.optString("value")
        }.getOrNull()
        return value?.trim()?.ifEmpty { null }?.let { LyricsLoader.parse(it, "Navidrome") }
    }

    /** Picks synced lyrics over plain ones from an OpenSubsonic structuredLyrics array. */
    private fun structured(list: JSONArray?): Lyrics? {
        if (list == null || list.length() == 0) return null
        val all = (0 until list.length()).map { list.getJSONObject(it) }
        val pick = all.firstOrNull { it.optBoolean("synced") } ?: all.first()
        val lines = pick.optJSONArray("line") ?: return null
        if (lines.length() == 0) return null
        val offset = pick.optLong("offset")
        return if (pick.optBoolean("synced")) {
            val synced = (0 until lines.length()).map { i ->
                val l = lines.getJSONObject(i)
                LyricLine((l.optLong("start") + offset).coerceAtLeast(0), l.optString("value"))
            }
            Lyrics(synced.sortedBy { it.timeMs }, null, "Navidrome")
        } else {
            Lyrics(emptyList(), (0 until lines.length()).joinToString("\n") { lines.getJSONObject(it).optString("value") }, "Navidrome")
        }
    }

    /** Adds the Subsonic token to stream requests for the signed-in server (used by ExoPlayer). */
    @OptIn(UnstableApi::class)
    override fun authorize(spec: DataSpec): DataSpec {
        val acc = account ?: return spec
        val s = spec.uri.toString()
        if (!s.startsWith("${acc.server}/rest/")) return spec
        return spec.withUri(Uri.parse(s + "&" + authQuery(acc)))
    }

    // ---- HTTP -------------------------------------------------------------------

    private class HttpError(val code: Int) : IOException("HTTP $code")

    private fun authQuery(acc: Account): String =
        "u=${Uri.encode(acc.userName)}&t=${acc.t}&s=${acc.s}&v=$SUBSONIC_VERSION&c=$CLIENT"

    /** GETs /rest/[action] with u/t/s auth and returns the checked envelope (payloads sit at its top level). */
    private fun subsonic(acc: Account, action: String, extra: Map<String, String>): JSONObject {
        val query = extra.entries.joinToString("") { (k, v) -> "$k=${Uri.encode(v)}&" }
        val json = JSONObject(String(requestBytes("${acc.server}/rest/$action?$query" + authQuery(acc) + "&f=json"), Charsets.UTF_8))
        val env = json.optJSONObject("subsonic-response") ?: throw IOException("Unexpected reply from ${acc.server}")
        env.optJSONObject("error")?.let {
            throw IOException("Navidrome error ${it.optInt("code")}: ${it.optString("message")}")
        }
        return env
    }

    private fun open(method: String, url: String): HttpURLConnection {
        NetPolicy.check(url) // Settings → Network: internet access off blocks public servers
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 60_000
        return c
    }

    private fun requestBytes(url: String): ByteArray {
        val c = open("GET", url)
        try {
            if (c.responseCode !in 200..299) throw HttpError(c.responseCode)
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun jsonPost(url: String, body: Map<String, String>): String {
        val c = open("POST", url)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject(body).toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code)
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }
}

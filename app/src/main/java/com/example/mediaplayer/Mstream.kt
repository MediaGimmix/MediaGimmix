package com.example.mediaplayer

import android.content.Context
import android.net.Uri
import android.util.Base64
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
 * mStream streaming (https://mstream.io, API v1), optionally behind an Authentik proxy.
 *
 * Two independent layers of credentials, both sent as headers on every request (never in URLs):
 *  - Authentik (or any proxy that takes HTTP Basic auth): `Authorization: Basic user:app password`.
 *    Authentik's proxy provider accepts this when "Intercept header auth" is on (its default).
 *  - mStream's own login: POST /api/v1/auth/login gives a token, sent as `x-access-token`.
 *    A server without users needs none.
 * Both are kept in the TokenVault (scope [ROOT]) as one JSON secret; passwords typed for mStream
 * are used once and never saved, the Authentik app password is kept (it's what each request needs).
 *
 * Sync pages through POST /api/v1/sync/manifest, then reads the playlists. Songs stream as the
 * original files from /media/<vpath>/<path> (express.static: Range works). Covers:
 * /album-art/<file>?compress=zs|zl (92 / 256 px thumbnails). Lyrics: /api/v1/lyrics?path=.
 * Nothing is reported back to the server.
 */
object Mstream : NetshareBackend {
    const val ROOT = "mstream"
    override val id = ROOT
    override val label = "mStream"
    override val builtIn get() = BuildConfig.FEATURE_MSTREAM
    override val fields = listOf(
        SignInField("server", "Server address", "https://music.example.org"),
        SignInField("proxyUser", "Authentik user name", optional = true),
        SignInField("proxyPass", "Authentik app password", secret = true, optional = true),
        SignInField("user", "mStream user name", optional = true),
        SignInField("password", "mStream password", secret = true, optional = true),
    )
    override val hint = "Behind Authentik: make an app password in your user settings → Tokens and App passwords; " +
        "the proxy provider needs \"Intercept header auth\" on (the default). Leave the mStream fields empty if " +
        "mStream itself has no users. A server on the internet also needs Settings → Network → Internet access on."
    override val keeps = "the Authentik app password and mStream's access token (the mStream password isn't kept)"

    private const val PREFS = "mstream"
    private const val CACHE_FILE = "mstream_library.json"
    private const val PAGE = 2000

    data class Account(
        val server: String,
        val serverName: String,
        val userName: String,
        val proxyUser: String,
        val proxyPass: String,
        val token: String,
    )

    @Volatile var account: Account? = null
        private set
    @Volatile var signedIn: Account? = null
        private set

    override val shown: ServerAccount? get() = signedIn?.let { ServerAccount(it.server, it.serverName, it.userName) }
    override val ready: Boolean get() = account != null

    private fun secretJson(a: Account) =
        JSONObject().put("pu", a.proxyUser).put("pp", a.proxyPass).put("tk", a.token).toString()

    private fun withSecret(a: Account, text: String?): Account? = text?.let {
        runCatching {
            val o = JSONObject(it)
            a.copy(proxyUser = o.optString("pu"), proxyPass = o.optString("pp"), token = o.optString("tk"))
        }.getOrNull()
    }

    // ---- account ------------------------------------------------------------

    override fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        TokenVault.init(context)
        val known = p.getString("user_name", null)?.let {
            Account(p.getString("server", "").orEmpty(), p.getString("server_name", "").orEmpty(), it, "", "", "")
        }
        signedIn = known
        account = known?.let { withSecret(it, TokenVault.readToken(ROOT)) }
    }

    override fun isLocked(): Boolean = signedIn != null && account == null

    override fun unlock(): Boolean {
        val known = signedIn ?: return false
        account = withSecret(known, TokenVault.readToken(ROOT)) ?: return false
        return true
    }

    override fun lock() {
        TokenVault.lock()
        if (TokenVault.isLocked()) account = null
    }

    override fun signIn(context: Context, values: Map<String, String>) {
        val server = Netshare.normalizeServer(values["server"].orEmpty())
        val proxyUser = values["proxyUser"].orEmpty().trim()
        val proxyPass = values["proxyPass"].orEmpty()
        val user = values["user"].orEmpty().trim()
        val password = values["password"].orEmpty()
        if (proxyUser.isEmpty() != proxyPass.isEmpty()) throw IOException("Enter both the Authentik user name and app password, or neither")
        var acc = Account(
            server = server,
            serverName = runCatching { URL(server).host }.getOrNull()?.ifEmpty { null } ?: server,
            userName = user.ifEmpty { proxyUser }.ifEmpty { "public" },
            proxyUser = proxyUser,
            proxyPass = proxyPass,
            token = "",
        )

        if (user.isNotEmpty()) {
            val login = try {
                JSONObject(request(acc, "POST", "/api/v1/auth/login", JSONObject().put("username", user).put("password", password)))
            } catch (e: HttpError) {
                throw IOException(if (e.code == 401) "Wrong user name or password (mStream or Authentik)" else "Sign-in failed (HTTP ${e.code})")
            }
            acc = acc.copy(token = login.optString("token").ifEmpty { throw IOException("$server didn't send an mStream token") })
        }
        // Proves both layers: Authentik lets us through and mStream accepts us (or has no users).
        try {
            request(acc, "GET", "/api")
        } catch (e: HttpError) {
            throw IOException(
                if (e.code == 401 && user.isEmpty()) "Not allowed in: check the Authentik app password, or enter the mStream user name and password"
                else "$server refused the sign-in (HTTP ${e.code})",
            )
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("server", acc.server)
            .putString("server_name", acc.serverName)
            .putString("user_name", acc.userName)
            .apply()
        TokenVault.writeToken(secretJson(acc), ROOT)
        signedIn = acc.copy(proxyPass = "", token = "")
        account = acc
    }

    override fun signOut(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("server_name").remove("user_name")
            .apply() // the server address stays
        TokenVault.deleteToken(ROOT)
        File(context.filesDir, CACHE_FILE).delete()
        account = null
        signedIn = null
    }

    override fun lastServer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("server", "").orEmpty()

    // ---- library ------------------------------------------------------------

    override fun sync(context: Context, onProgress: (String) -> Unit): ServerLibrary {
        val acc = account ?: throw IOException("Not signed in")
        val entries = JSONArray()
        var cursor: Long? = null
        while (true) {
            val body = JSONObject().put("limit", PAGE)
            cursor?.let { body.put("cursor", it) }
            val page = JSONObject(request(acc, "POST", "/api/v1/sync/manifest", body))
            val got = page.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until got.length()) entries.put(got.getJSONObject(i))
            onProgress("Reading songs: ${"%,d".format(entries.length())}")
            if (page.isNull("next") || got.length() == 0) break
            cursor = page.getLong("next")
        }

        onProgress("Reading playlists")
        val playlists = JSONArray()
        val names = runCatching { JSONArray(request(acc, "GET", "/api/v1/playlist/getall")) }.getOrDefault(JSONArray())
        for (i in 0 until names.length()) {
            val name = names.optJSONObject(i)?.optString("name").orEmpty()
            if (name.isEmpty()) continue
            val items = runCatching {
                JSONArray(request(acc, "POST", "/api/v1/playlist/load", JSONObject().put("playlistname", name)))
            }.getOrDefault(JSONArray())
            val paths = JSONArray()
            for (j in 0 until items.length()) items.optJSONObject(j)?.optString("filepath")?.ifEmpty { null }?.let { paths.put(it) }
            playlists.put(JSONObject().put("name", name).put("paths", paths))
        }

        val cache = JSONObject()
            .put("server", acc.server)
            .put("syncedAt", System.currentTimeMillis())
            .put("entries", entries)
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
        val arr = json.getJSONArray("entries")
        val songs = ArrayList<Song>(arr.length())
        val byPath = HashMap<String, Song>()
        val artIds = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val song = toSong(server, o) ?: continue
            songs += song
            byPath[o.getString("filepath")] = song
            o.optJSONObject("metadata")?.optString("album-art")?.ifEmpty { null }?.let { artIds[song.uri.toString()] = it }
        }
        val pls = json.optJSONArray("playlists") ?: JSONArray()
        val playlists = (0 until pls.length()).map { i ->
            val pl = pls.getJSONObject(i)
            val paths = pl.getJSONArray("paths")
            val list = (0 until paths.length()).mapNotNull { byPath[paths.getString(it)] }
            Playlist(
                uri = Uri.parse("mstream:playlist/" + Uri.encode(pl.getString("name"))),
                rootUri = ROOT,
                name = pl.getString("name"),
                folderPath = "",
                songs = list,
                missing = paths.length() - list.size,
            )
        }.sortedBy { it.name.lowercase() }
        return ServerLibrary(songs, playlists, artIds, json.optLong("syncedAt"))
    }

    private fun toSong(server: String, o: JSONObject): Song? {
        val path = o.optString("filepath").ifEmpty { return null }
        val m = o.optJSONObject("metadata") ?: JSONObject()
        val fileName = path.substringAfterLast('/')
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val format = o.optString("format").lowercase().ifEmpty { ext }.ifEmpty { "audio" }
        val artists = m.optJSONArray("artists")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
        return Song(
            uri = Uri.parse("$server/media/" + path.split('/').joinToString("/") { Uri.encode(it) }),
            fileName = fileName,
            folderPath = path.substringBeforeLast('/', ""),
            mimeType = mimeFor(format),
            sizeBytes = o.optLong("file-size"),
            rootUri = ROOT,
            title = m.optString("title").ifEmpty { null },
            // ";" is one of the default delimiters, so the app's own splitting (and whitelist) still applies.
            artist = artists.joinToString("; ").ifEmpty { null } ?: m.optString("artist").ifEmpty { null },
            album = m.optString("album").ifEmpty { null },
            albumArtist = m.optString("album-artist").ifEmpty { null },
            durationMs = (m.optDouble("duration", 0.0) * 1000).toLong(),
            trackNumber = m.optInt("track"),
            discNumber = m.optInt("disk"),
            year = m.optInt("year"),
            dateAdded = epochMs(o.opt("created-at")) ?: epochMs(o.opt("modified")) ?: 0L,
        )
    }

    private fun mimeFor(format: String) = when (format) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac", "alac", "mp4" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "audio/$format"
    }

    /** A timestamp as epoch ms: seconds or ms numbers, or ISO-8601 text. */
    private fun epochMs(v: Any?): Long? = when (v) {
        is Number -> v.toLong().takeIf { it > 0 }?.let { if (it < 100_000_000_000L) it * 1000 else it }
        is String -> v.toLongOrNull()?.let { epochMs(it) } ?: runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
        else -> null
    }

    /** The library path ("<vpath>/<rest>") of a song, from its stream URI. */
    private fun filePath(acc: Account, song: Song): String? {
        val prefix = "${acc.server}/media/"
        val s = song.uri.toString()
        if (!s.startsWith(prefix)) return null
        return s.removePrefix(prefix).split('/').joinToString("/") { Uri.decode(it) }
    }

    // ---- covers, lyrics, streams ------------------------------------------------

    override fun cover(artId: String, sizePx: Int): ByteArray? {
        val acc = account ?: return null
        // The server keeps 92 px (zs) and 256 px (zl) thumbnails; bigger asks get the original.
        val compress = when {
            sizePx <= 92 -> "?compress=zs"
            sizePx <= 256 -> "?compress=zl"
            else -> ""
        }
        return runCatching { requestBytes(acc, "/album-art/" + Uri.encode(artId) + compress) }.getOrNull()
    }

    override fun lyrics(song: Song): Lyrics? {
        val acc = account ?: return null
        val path = filePath(acc, song) ?: return null
        val json = runCatching { JSONObject(request(acc, "GET", "/api/v1/lyrics?path=" + Uri.encode(path))) }.getOrNull() ?: return null
        fun first(key: String) = json.optJSONObject(key)?.optJSONArray("lyrics")?.optJSONObject(0)?.optString("data")?.trim()?.ifEmpty { null }
        first("syncedLyrics")?.let { lrc -> LyricsLoader.parse(lrc, "mStream")?.let { return it } }
        return first("lyrics")?.let { Lyrics(emptyList(), it, "mStream") }
    }

    @OptIn(UnstableApi::class)
    override fun authorize(spec: DataSpec): DataSpec {
        val acc = account ?: return spec
        if (!spec.uri.toString().startsWith("${acc.server}/")) return spec
        return spec.withAdditionalHeaders(headers(acc))
    }

    override fun openStream(song: Song): Pair<java.io.InputStream, Long>? {
        val acc = account ?: return null
        if (filePath(acc, song) == null) return null
        return runCatching {
            val c = open(acc, "GET", song.uri.toString())
            if (c.responseCode !in 200..299) {
                c.disconnect()
                return null
            }
            c.inputStream to c.contentLengthLong
        }.getOrNull()
    }

    // ---- HTTP -------------------------------------------------------------------

    private class HttpError(val code: Int) : IOException("HTTP $code")

    private fun headers(acc: Account): Map<String, String> = buildMap {
        if (acc.proxyUser.isNotEmpty()) {
            val basic = Base64.encodeToString("${acc.proxyUser}:${acc.proxyPass}".toByteArray(), Base64.NO_WRAP)
            put("Authorization", "Basic $basic")
        }
        if (acc.token.isNotEmpty()) put("x-access-token", acc.token)
    }

    private fun open(acc: Account, method: String, url: String): HttpURLConnection {
        NetPolicy.check(url) // Settings → Network: internet access off blocks public servers
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 60_000
        // A redirect means the proxy wants a browser sign-in: report it instead of following it.
        c.instanceFollowRedirects = false
        headers(acc).forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }

    /** Throws on anything but a direct 2xx reply from mStream itself. */
    private fun check(c: HttpURLConnection) {
        val code = c.responseCode
        if (code in 300..399) {
            throw IOException("The proxy asked for a browser sign-in: check the Authentik user name and app password, and that \"Intercept header auth\" is on")
        }
        if (code !in 200..299) throw HttpError(code)
        if (c.contentType.orEmpty().startsWith("text/html")) {
            throw IOException("Got a web page instead of mStream's API: check the server address and the Authentik settings")
        }
    }

    private fun request(acc: Account, method: String, path: String, body: JSONObject? = null): String {
        val c = open(acc, method, acc.server + path)
        try {
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            check(c)
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private fun requestBytes(acc: Account, path: String): ByteArray {
        val c = open(acc, "GET", acc.server + path)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code)
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}

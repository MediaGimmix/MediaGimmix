package com.example.mediaplayer

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jellyfin streaming: sign-in, the music library, stream/cover/lyrics URLs.
 *
 * Only ever talks to the server the user signed in to. Songs are streamed as
 * the original files (`/Audio/{id}/stream?static=true`), with the access token
 * sent in the Authorization header (never in the URL). Nothing is reported back
 * to the server: play counts stay on the phone.
 *
 * Jellyfin songs are ordinary [Song]s with [ROOT] as their rootUri, so the
 * queue, play counts, artist splitting and the visualizer treat them like local
 * files. The library is cached in filesDir/[CACHE_FILE] between syncs.
 */
object Jellyfin {
    const val ROOT = "jellyfin"
    private const val PREFS = "jellyfin"
    private const val CACHE_FILE = "jellyfin_library.json"
    private const val PAGE = 1000

    data class Account(
        val server: String,
        val serverName: String,
        val userId: String,
        val userName: String,
        val token: String,
    )

    /** A synced library. [artIds] maps a song URI to the item whose Primary image is its cover. */
    data class Library(
        val songs: List<Song>,
        val playlists: List<Playlist>,
        val artIds: Map<String, String>,
        val syncedAt: Long,
    )

    /** The signed-in account, read by the stream, cover and lyrics code on any thread. Null while locked. */
    @Volatile var account: Account? = null
        private set

    /** The signed-in account even while its token is locked by YubiKey (token empty then). For display. */
    @Volatile var signedIn: Account? = null
        private set

    private var deviceId: String = ""

    fun isJellyfin(song: Song): Boolean = song.rootUri == ROOT

    // ---- account ------------------------------------------------------------

    /** Loads the saved account (call once at startup). */
    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        deviceId = p.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("device_id", it).apply()
        }
        TokenVault.init(context)
        val userId = p.getString("user_id", null)
        val known = userId?.let {
            Account(
                server = p.getString("server", "").orEmpty(),
                serverName = p.getString("server_name", "").orEmpty(),
                userId = it,
                userName = p.getString("user_name", "").orEmpty(),
                token = "",
            )
        }
        signedIn = known
        // A locked vault leaves the token null: Jellyfin waits for a trusted YubiKey.
        account = TokenVault.readToken()?.let { t -> known?.copy(token = t) }
    }

    /** True when signed in but the token is locked behind a YubiKey. */
    fun isLocked(): Boolean = signedIn != null && account == null

    /** After a trusted YubiKey unlocked the vault. Returns false if there's still no token. */
    fun unlock(): Boolean {
        val token = TokenVault.readToken() ?: return false
        account = signedIn?.copy(token = token) ?: return false
        return true
    }

    /** "Lock now": forget the token in memory until a trusted key is plugged in again. */
    fun lock() {
        TokenVault.lock()
        if (TokenVault.isLocked()) account = null
    }

    /** "192.168.1.5:8096" -> "http://192.168.1.5:8096"; trailing slashes dropped. */
    fun normalizeServer(input: String): String {
        val t = input.trim().trimEnd('/')
        return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "http://$t"
    }

    /** Signs in (blocking; call off the main thread). Throws IOException with a readable message. */
    fun signIn(context: Context, serverInput: String, userName: String, password: String): Account {
        val server = normalizeServer(serverInput)
        val info = try {
            JSONObject(request("GET", "$server/System/Info/Public", token = null))
        } catch (e: IOException) {
            throw IOException("Couldn't reach a Jellyfin server at $server (${e.message})")
        } catch (e: Exception) {
            throw IOException("$server doesn't look like a Jellyfin server")
        }
        val body = JSONObject().put("Username", userName).put("Pw", password).toString()
        val auth = try {
            JSONObject(request("POST", "$server/Users/AuthenticateByName", token = null, body = body))
        } catch (e: HttpError) {
            throw IOException(if (e.code == 401) "Wrong user name or password" else "Sign-in failed (HTTP ${e.code})")
        }
        val user = auth.getJSONObject("User")
        val acc = Account(
            server = server,
            serverName = info.optString("ServerName", server),
            userId = user.getString("Id"),
            userName = user.optString("Name", userName),
            token = auth.getString("AccessToken"),
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("server", acc.server)
            .putString("server_name", acc.serverName)
            .putString("user_id", acc.userId)
            .putString("user_name", acc.userName)
            .apply()
        // Plain, or encrypted when YubiKeys protect it (held in memory until one unlocks, if locked).
        TokenVault.writeToken(acc.token)
        signedIn = acc.copy(token = "")
        account = acc
        return acc
    }

    /** Signs out: ends the session on the server (best effort) and forgets the token and cached library. */
    fun signOut(context: Context) {
        account?.let { acc -> runCatching { request("POST", "${acc.server}/Sessions/Logout", acc.token) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("server_name").remove("user_id").remove("user_name").remove("token")
            .apply() // the server address stays, to make signing in again easier
        TokenVault.deleteToken()
        File(context.filesDir, CACHE_FILE).delete()
        account = null
        signedIn = null
    }

    fun lastServer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("server", "").orEmpty()

    // ---- library ------------------------------------------------------------

    /** Fetches every song and playlist (blocking). [onProgress] gets a status line. */
    fun sync(context: Context, acc: Account, onProgress: (String) -> Unit): Library {
        val items = JSONArray()
        var start = 0
        while (true) {
            val page = JSONObject(
                request(
                    "GET",
                    "${acc.server}/Users/${acc.userId}/Items?Recursive=true&IncludeItemTypes=Audio" +
                        "&Fields=Path,DateCreated&EnableUserData=false&StartIndex=$start&Limit=$PAGE",
                    acc.token,
                ),
            )
            val got = page.getJSONArray("Items")
            for (i in 0 until got.length()) items.put(got.getJSONObject(i))
            val total = page.optInt("TotalRecordCount", items.length())
            onProgress("Reading songs: ${"%,d".format(items.length())} of ${"%,d".format(total)}")
            start += got.length()
            if (got.length() == 0 || start >= total) break
        }

        onProgress("Reading playlists")
        val playlists = JSONArray()
        val pls = JSONObject(
            request("GET", "${acc.server}/Users/${acc.userId}/Items?Recursive=true&IncludeItemTypes=Playlist", acc.token),
        ).getJSONArray("Items")
        for (i in 0 until pls.length()) {
            val pl = pls.getJSONObject(i)
            if (pl.optString("MediaType").let { it.isNotEmpty() && it != "Audio" }) continue
            val entries = JSONObject(
                request("GET", "${acc.server}/Playlists/${pl.getString("Id")}/Items?UserId=${acc.userId}", acc.token),
            ).getJSONArray("Items")
            val ids = JSONArray()
            for (j in 0 until entries.length()) ids.put(entries.getJSONObject(j).getString("Id"))
            playlists.put(JSONObject().put("Id", pl.getString("Id")).put("Name", pl.optString("Name")).put("Ids", ids))
        }

        val cache = JSONObject()
            .put("server", acc.server)
            .put("syncedAt", System.currentTimeMillis())
            .put("items", items)
            .put("playlists", playlists)
        File(context.filesDir, CACHE_FILE).writeText(cache.toString())
        return parse(cache)
    }

    /** The library from the last sync, if it belongs to the signed-in server. */
    fun loadCached(context: Context): Library? {
        val acc = account ?: return null
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            if (json.optString("server") != acc.server) null else parse(json)
        }.getOrNull()
    }

    private fun parse(json: JSONObject): Library {
        val server = json.getString("server")
        val items = json.getJSONArray("items")
        val songs = ArrayList<Song>(items.length())
        val byId = HashMap<String, Song>()
        val artIds = HashMap<String, String>()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val id = o.getString("Id")
            val song = toSong(server, o)
            songs += song
            byId[id] = song
            val art = when {
                o.has("AlbumPrimaryImageTag") && o.optString("AlbumId").isNotEmpty() -> o.getString("AlbumId")
                o.optJSONObject("ImageTags")?.has("Primary") == true -> id
                else -> null
            }
            if (art != null) artIds[song.uri.toString()] = art
        }
        val pls = json.getJSONArray("playlists")
        val playlists = (0 until pls.length()).map { i ->
            val pl = pls.getJSONObject(i)
            val ids = pl.getJSONArray("Ids")
            val list = (0 until ids.length()).mapNotNull { byId[ids.getString(it)] }
            Playlist(
                uri = Uri.parse("jellyfin:playlist/" + pl.getString("Id")),
                rootUri = ROOT,
                name = pl.optString("Name", "Playlist"),
                folderPath = "",
                songs = list,
                missing = ids.length() - list.size,
            )
        }.sortedBy { it.name.lowercase() }
        return Library(songs, playlists, artIds, json.optLong("syncedAt"))
    }

    private fun toSong(server: String, o: JSONObject): Song {
        val id = o.getString("Id")
        val title = o.optString("Name").ifEmpty { null }
        val artists = o.optJSONArray("Artists")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val albumArtist = o.optString("AlbumArtist").ifEmpty { null }
        val album = o.optString("Album").ifEmpty { null }
        val container = o.optString("Container").substringBefore(',').ifEmpty { "audio" }
        val path = o.optString("Path")
        val fileName = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { "${title ?: id}.$container" }
        val owner = albumArtist ?: artists.firstOrNull() ?: "Unknown artist"
        return Song(
            uri = Uri.parse("$server/Audio/$id/stream?static=true"),
            fileName = fileName,
            folderPath = listOfNotNull(owner, album).joinToString("/"),
            mimeType = "audio/$container",
            sizeBytes = 0L,
            rootUri = ROOT,
            title = title,
            // Jellyfin has already split the artists; ";" is one of the default delimiters, so
            // the app's own splitting (and whitelist) still applies.
            artist = artists.joinToString("; ").ifEmpty { null },
            album = album,
            albumArtist = albumArtist,
            durationMs = o.optLong("RunTimeTicks") / 10_000L,
            trackNumber = o.optInt("IndexNumber"),
            discNumber = o.optInt("ParentIndexNumber"),
            year = o.optInt("ProductionYear"),
            dateAdded = runCatching { Instant.parse(o.optString("DateCreated")).toEpochMilli() }.getOrDefault(0L),
        )
    }

    // ---- covers, lyrics, streams ------------------------------------------------

    fun imageUrl(itemId: String, sizePx: Int): String? {
        val acc = account ?: return null
        return "${acc.server}/Items/$itemId/Images/Primary?maxWidth=$sizePx&maxHeight=$sizePx&quality=90"
    }

    /** Downloads bytes from the signed-in server (covers). Blocking. */
    fun download(url: String): ByteArray? {
        val acc = account ?: return null
        if (!url.startsWith(acc.server)) return null
        return runCatching { requestBytes(url, acc.token) }.getOrNull()
    }

    /** Opens a song's original file on the server (for web control downloads). Blocking. */
    fun openStream(song: Song): Pair<java.io.InputStream, Long>? {
        val acc = account ?: return null
        if (!song.uri.toString().startsWith(acc.server)) return null
        return runCatching {
            val c = open("GET", song.uri.toString(), acc.token)
            if (c.responseCode !in 200..299) {
                c.disconnect()
                return null
            }
            c.inputStream to c.contentLengthLong
        }.getOrNull()
    }

    /** Lyrics from the server (Jellyfin 10.9+), synced when they have timestamps. Blocking. */
    fun lyrics(song: Song): Lyrics? {
        val acc = account ?: return null
        val id = itemId(song) ?: return null
        val json = try {
            JSONObject(request("GET", "${acc.server}/Audio/$id/Lyrics", acc.token))
        } catch (e: Exception) {
            return null // 404 = no lyrics; older servers lack the endpoint
        }
        val lines = json.optJSONArray("Lyrics") ?: return null
        val synced = ArrayList<LyricLine>()
        val plain = StringBuilder()
        for (i in 0 until lines.length()) {
            val l = lines.getJSONObject(i)
            val text = l.optString("Text")
            if (l.has("Start") && !l.isNull("Start")) synced += LyricLine(l.getLong("Start") / 10_000L, text)
            plain.append(text).append('\n')
        }
        if (lines.length() == 0) return null
        return if (synced.size == lines.length()) {
            Lyrics(synced.sortedBy { it.timeMs }, null, "Jellyfin")
        } else {
            Lyrics(emptyList(), plain.toString().trimEnd(), "Jellyfin")
        }
    }

    /** The Jellyfin item id in a stream URI (".../Audio/{id}/stream"). */
    fun itemId(song: Song): String? {
        val seg = song.uri.pathSegments
        val i = seg.indexOf("Audio")
        return if (i >= 0 && i + 1 < seg.size) seg[i + 1] else null
    }

    /** Adds the Authorization header to requests for the signed-in server (used by ExoPlayer). */
    @OptIn(UnstableApi::class)
    fun authorize(spec: DataSpec): DataSpec {
        val acc = account ?: return spec
        if (!spec.uri.toString().startsWith(acc.server)) return spec
        return spec.withAdditionalHeaders(mapOf("Authorization" to authHeader(acc.token)))
    }

    // ---- HTTP -------------------------------------------------------------------

    private class HttpError(val code: Int) : IOException("HTTP $code")

    private fun authHeader(token: String?): String =
        "MediaBrowser Client=\"Media Player\", Device=\"${Build.MODEL}\", DeviceId=\"$deviceId\", Version=\"0.1.0\"" +
            (token?.let { ", Token=\"$it\"" } ?: "")

    private fun open(method: String, url: String, token: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", authHeader(token))
        c.setRequestProperty("Accept", "application/json")
        return c
    }

    private fun request(method: String, url: String, token: String?, body: String? = null): String {
        val c = open(method, url, token)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code)
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private fun requestBytes(url: String, token: String): ByteArray {
        val c = open("GET", url, token)
        try {
            if (c.responseCode !in 200..299) throw HttpError(c.responseCode)
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}

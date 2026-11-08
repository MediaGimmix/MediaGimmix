package com.example.mediaplayer.companion

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Last.fm (API 2.0). The user's own API key and shared secret (made at last.fm/api/account/create) and a
 * session key from signing in once with username + password (auth.getMobileSession). The password is never
 * stored; only the session key is (prefs "lastfm"). Only the companion talks to Last.fm, never the player.
 */
object LastFm {
    private const val ENDPOINT = "https://ws.audioscrobbler.com/2.0/"
    private const val PREFS = "lastfm"

    class Account(val apiKey: String, val secret: String, val session: String, val user: String)

    class LastFmError(message: String) : IOException(message)

    fun account(context: Context): Account? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = p.getString("api_key", "").orEmpty()
        val secret = p.getString("secret", "").orEmpty()
        val session = p.getString("session", "").orEmpty()
        if (key.isEmpty() || secret.isEmpty() || session.isEmpty()) return null
        return Account(key, secret, session, p.getString("user", "").orEmpty())
    }

    fun savedApiKey(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("api_key", "").orEmpty()
    fun savedSecret(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("secret", "").orEmpty()

    /** Signs in and keeps the session key (not the password). Blocking. Returns the Last.fm user name. */
    fun signIn(context: Context, apiKey: String, secret: String, username: String, password: String): String {
        val o = call(
            secret,
            mapOf("method" to "auth.getMobileSession", "api_key" to apiKey, "username" to username, "password" to password),
        )
        val session = o.optJSONObject("session") ?: throw LastFmError("No session in Last.fm's reply")
        val name = session.optString("name").ifEmpty { username }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("api_key", apiKey).putString("secret", secret)
            .putString("session", session.getString("key")).putString("user", name)
            .apply()
        return name
    }

    fun signOut(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("session").remove("user").apply()
    }

    /** "Now playing" on the profile. Blocking. */
    fun nowPlaying(a: Account, t: Scrobble) {
        call(a.secret, base(a, "track.updateNowPlaying") + t.params(null))
    }

    /** Scrobbles up to 50 plays in one request. Blocking. */
    fun scrobble(a: Account, plays: List<Scrobble>) {
        if (plays.isEmpty()) return
        val params = base(a, "track.scrobble").toMutableMap()
        plays.take(50).forEachIndexed { i, t -> params += t.params(i) }
        call(a.secret, params)
    }

    private fun base(a: Account, method: String) = mapOf("method" to method, "api_key" to a.apiKey, "sk" to a.session)

    /** A signed POST; throws [LastFmError] with Last.fm's message on an error reply. */
    private fun call(secret: String, params: Map<String, String>): JSONObject {
        val sig = md5(params.toSortedMap().entries.joinToString("") { it.key + it.value } + secret)
        val body = (params + mapOf("api_sig" to sig, "format" to "json")).entries
            .joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", "MediaGimmixRemote/0.1")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val o = runCatching { JSONObject(text) }.getOrNull() ?: throw IOException("Last.fm: HTTP $code")
            if (o.has("error")) throw LastFmError(o.optString("message").ifEmpty { "Last.fm error ${o.opt("error")}" })
            return o
        } finally {
            conn.disconnect()
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** One play, as Last.fm wants it. [timestamp] = when it started, in seconds. */
data class Scrobble(
    val artist: String,
    val track: String,
    val album: String?,
    val durationSec: Int,
    val timestamp: Long,
) {
    /** Parameters for one track; [i] = index in a batch scrobble, null for now playing. */
    fun params(i: Int?): Map<String, String> {
        fun k(name: String) = if (i == null) name else "$name[$i]"
        val m = mutableMapOf(k("artist") to artist, k("track") to track)
        if (!album.isNullOrBlank()) m[k("album")] = album
        if (durationSec > 0) m[k("duration")] = durationSec.toString()
        if (i != null) m[k("timestamp")] = timestamp.toString()
        return m
    }

    fun toJson(): JSONObject = JSONObject().put("artist", artist).put("track", track).put("album", album ?: "")
        .put("duration", durationSec).put("timestamp", timestamp)

    companion object {
        fun fromJson(o: JSONObject) = Scrobble(
            o.getString("artist"), o.getString("track"), o.optString("album").ifEmpty { null },
            o.optInt("duration"), o.getLong("timestamp"),
        )

        fun listToJson(list: List<Scrobble>) = JSONArray(list.map { it.toJson() }).toString()
        fun listFromJson(s: String?): List<Scrobble> = runCatching {
            val a = JSONArray(s ?: "[]")
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}

package com.example.mediaplayer

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * Artist pictures from Deezer's public artist search (no account or key),
 * fetched by [LibraryViewModel] while on Wi-Fi and internet access is on.
 *
 * Only an exact name match is used (accents and case ignored), so a wrong
 * person's photo is unlikely; no match = the artist keeps the newest album's
 * cover. Pictures are kept in filesDir/artist_images/<sha1 of the name>.jpg
 * (not the cache: they survive "Clear artwork cache"). A name with no picture
 * gets a .none marker and is tried again after [RETRY_MS].
 */
object ArtistImages {

    private const val RETRY_MS = 30L * 24 * 60 * 60 * 1000
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val PAUSE_MS = 250L // Deezer allows 50 requests / 5 s; stay well under

    /** Names that are never a real artist. */
    private val SKIP = setOf("", "various artists", "various", "unknown", "unknown artist", "<unknown>", "va")

    fun id(name: String): String = sha1(LibrarySearch.normalize(name))

    fun dir(context: Context): File = File(context.filesDir, "artist_images").apply { mkdirs() }

    fun file(context: Context, name: String): File = File(dir(context), id(name) + ".jpg")

    /** Ids (see [id]) of every artist with a downloaded picture. Blocking. */
    fun downloadedIds(context: Context): Set<String> =
        dir(context).listFiles { f -> f.name.endsWith(".jpg") }?.mapTo(HashSet()) { it.name.removeSuffix(".jpg") } ?: emptySet()

    /** Names still worth looking up: no picture and not recently found missing. Blocking. */
    fun pending(context: Context, names: Collection<String>): List<String> {
        val d = dir(context)
        val now = System.currentTimeMillis()
        return names
            .filter { LibrarySearch.normalize(it) !in SKIP }
            .distinctBy { id(it) }
            .filter { n ->
                val i = id(n)
                if (File(d, "$i.jpg").exists()) return@filter false
                val none = File(d, "$i.none")
                !none.exists() || now - none.lastModified() > RETRY_MS
            }
    }

    /** Forget "no picture found", so the next run looks again. */
    fun clearMisses(context: Context) {
        dir(context).listFiles { f -> f.name.endsWith(".none") }?.forEach { it.delete() }
    }

    /** Delete every downloaded picture and miss marker. */
    fun clearAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /**
     * Looks up [names] one by one; [onFound] gets each id that now has a picture.
     * Stops when [keepGoing] turns false (Wi-Fi lost, internet switched off) or
     * the coroutine is cancelled. Blocking network calls: run on Dispatchers.IO.
     */
    suspend fun fetch(context: Context, names: List<String>, keepGoing: () -> Boolean, onFound: (String) -> Unit) {
        val d = dir(context)
        for (name in names) {
            coroutineContext.ensureActive()
            if (!keepGoing()) return
            val i = id(name)
            try {
                val bytes = lookup(name)
                if (bytes != null) {
                    val tmp = File(d, "$i.tmp")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(File(d, "$i.jpg"))
                    File(d, "$i.none").delete()
                    onFound(i)
                } else {
                    File(d, "$i.none").apply { delete(); createNewFile() }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Network trouble: don't mark as missing, try again next time.
            }
            delay(PAUSE_MS)
        }
    }

    /** The picture for an exact name match, or null when Deezer has none. Throws on network errors. */
    private fun lookup(name: String): ByteArray? {
        val url = "https://api.deezer.com/search/artist?limit=10&q=" + URLEncoder.encode(name, "UTF-8")
        val json = JSONObject(String(get(url) ?: return null, Charsets.UTF_8))
        // Rate limit or outage: not a "no picture" answer.
        if (json.has("error")) throw java.io.IOException("Deezer: " + json.opt("error"))
        val data = json.optJSONArray("data") ?: return null
        val wanted = LibrarySearch.normalize(name)
        for (k in 0 until data.length()) {
            val a = data.getJSONObject(k)
            if (LibrarySearch.normalize(a.optString("name")) != wanted) continue
            val pic = a.optString("picture_xl").ifEmpty { a.optString("picture_big") }
            // Deezer's grey placeholder has an empty image hash: ".../images/artist//..."
            if (pic.isEmpty() || "/artist//" in pic) return null
            val bytes = get(pic) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            return if (bounds.outWidth > 0) bytes else null
        }
        return null
    }

    private fun get(url: String): ByteArray? {
        NetPolicy.check(url)
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "MediaPlayer/0.1")
            if (c.responseCode == 404) return null
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > MAX_BYTES) return null
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

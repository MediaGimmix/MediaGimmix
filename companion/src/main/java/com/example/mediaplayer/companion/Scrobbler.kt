package com.example.mediaplayer.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

/**
 * Scrobbles what the player plays, from the status the companion already polls (the app while open,
 * [RemoteService] for the notification in the background). Plays the companion doesn't see aren't scrobbled.
 *
 * Last.fm's rules: songs over 30 s, scrobbled once half has played or 4 minutes, whichever is first;
 * timestamp = when the song started. A new play starts when the song changes or jumps back to the start.
 * Scrobbles that can't be sent wait in prefs "lastfm" ("pending") and go with the next one (dropped after
 * 14 days, which Last.fm refuses anyway).
 */
object Scrobbler {
    private const val PREFS = "lastfm"
    private const val KEY_ENABLED = "scrobble"
    private const val KEY_PENDING = "pending"
    private const val MIN_DURATION_MS = 30_000L
    private const val MAX_WAIT_MS = 4 * 60_000L
    private const val MAX_AGE_SEC = 14L * 24 * 60 * 60

    /** For the settings screen. */
    var lastResult by mutableStateOf("")
        private set
    var pendingCount by mutableIntStateOf(0)
        private set

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "lastfm").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    private var songId: String? = null
    private var startedSec = 0L
    private var lastPos = 0L
    private var scrobbled = false
    private var nowPlayingSent = false

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()

    fun refreshPending(context: Context) {
        pendingCount = Scrobble.listFromJson(prefs(context).getString(KEY_PENDING, null)).size
    }

    /** Feed every status poll in here. */
    @Synchronized
    fun onStatus(context: Context, s: Status) {
        val app = context.applicationContext
        val song = s.nowPlaying
        if (song == null) {
            songId = null
            return
        }
        val pos = s.positionMs
        val nowSec = System.currentTimeMillis() / 1000
        val restarted = song.id == songId && pos + 10_000 < lastPos && pos < 15_000 // repeat / previous
        if (song.id != songId || restarted) {
            songId = song.id
            startedSec = nowSec - pos / 1000
            scrobbled = false
            nowPlayingSent = false
        }
        lastPos = pos

        val account = LastFm.account(app) ?: return
        if (!isEnabled(app)) return
        val artist = song.artist ?: return // Last.fm needs an artist
        val durationMs = s.durationMs.takeIf { it > 0 } ?: song.durationMs
        val play = Scrobble(artist, song.title, song.album, (durationMs / 1000).toInt(), startedSec)

        if (s.isPlaying && !nowPlayingSent) {
            nowPlayingSent = true
            worker.execute { runCatching { LastFm.nowPlaying(account, play) } }
        }
        if (!scrobbled && durationMs > MIN_DURATION_MS && pos >= minOf(durationMs / 2, MAX_WAIT_MS)) {
            scrobbled = true
            worker.execute { send(app, account, play) }
        }
    }

    /** Sends [play] together with anything that was waiting. Runs on [worker]. */
    private fun send(context: Context, account: LastFm.Account, play: Scrobble?) {
        val p = prefs(context)
        val oldest = System.currentTimeMillis() / 1000 - MAX_AGE_SEC
        val queue = (Scrobble.listFromJson(p.getString(KEY_PENDING, null)) + listOfNotNull(play))
            .filter { it.timestamp > oldest }
        var left = queue
        val result = try {
            while (left.isNotEmpty()) {
                LastFm.scrobble(account, left.take(50))
                left = left.drop(50)
            }
            play?.let { "Scrobbled: ${it.artist} – ${it.track}" } ?: "Sent waiting scrobbles"
        } catch (e: LastFm.LastFmError) {
            left = emptyList() // Last.fm refused them (bad data): don't retry forever
            "Last.fm said: ${e.message}"
        } catch (e: Exception) {
            "Not sent yet (${e.message ?: "no connection"}); will retry"
        }
        p.edit().putString(KEY_PENDING, Scrobble.listToJson(left)).apply()
        main.post {
            lastResult = result
            pendingCount = left.size
        }
    }

    /** Try the waiting scrobbles now (Settings button). */
    fun retry(context: Context) {
        val app = context.applicationContext
        val account = LastFm.account(app) ?: return
        worker.execute { send(app, account, null) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

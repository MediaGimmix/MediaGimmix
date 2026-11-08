package com.example.mediaplayer.companion

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Listen on this phone": the song playing on the player is downloaded to a temporary file and played
 * here, from the same spot, while the player pauses. The player's queue stays in charge: Next / Previous
 * move it along with ?keepPaused=true (no sound there) and the new song is downloaded before the current
 * one stops. The next song is fetched ahead of time so the end of a song flows on. "Back to the player"
 * hands the position back and lets the player play again. Files live in filesDir/listen_here (not the
 * cache, which Android may empty mid-song) and are deleted when local playback ends or the app next starts.
 *
 * Process-wide (the notification service and the screens share it); runs on the main thread.
 */
object LocalPlayer {
    private const val RESTART_THRESHOLD_MS = 3_000L
    private const val TICK_MS = 500L

    var active by mutableStateOf(false)
        private set
    var song by mutableStateOf<SongInfo?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    /** What it's busy with ("Downloading… 40%"), null when idle. */
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: Context
    private var player: MediaPlayer? = null
    private var currentFile: File? = null
    private var prefetch: Pair<String, File>? = null
    private var prefetchJob: Job? = null
    private var ticker: Job? = null
    private var switching = false
    private var focus: AudioFocusRequest? = null

    private fun api() = Api(Connection.load(app))
    // Not cacheDir: Android empties caches by itself when storage runs low, even mid-song. Deleted by us instead.
    private fun dir() = File(app.filesDir, "listen_here").apply { mkdirs() }

    /** At app start: removes songs left behind if the app was killed while listening here. */
    fun cleanStale(context: Context) {
        if (active) return
        app = context.applicationContext
        File(app.filesDir, "listen_here").listFiles()?.forEach { it.delete() }
    }

    // ---- starting and ending -----------------------------------------------------------

    /** Takes over what the player is playing. Call from the app (starts the notification service). */
    fun start(context: Context) {
        app = context.applicationContext
        if (switching) return
        switching = true
        error = null
        scope.launch {
            try {
                busy = "Asking the player…"
                val status = call { status() }
                val now = status.nowPlaying ?: throw IllegalStateException("Nothing is playing on the player.")
                // Check the space before pausing the player, so a song that won't fit changes nothing.
                if (now.sizeBytes > 0) Storage.requireRoom(dir(), now.sizeBytes)
                val wasPlaying = status.isPlaying
                call { command("pause") }
                val file = try {
                    download(now, "Downloading")
                } catch (e: Exception) {
                    if (wasPlaying) runCatching { call { command("play") } } // didn't work out: the player carries on
                    throw e
                }
                active = true
                RemoteService.start(app)
                play(now, file, status.positionMs)
                prefetchNext()
            } catch (e: Exception) {
                error = message(e)
                if (!active) cleanUp()
            } finally {
                busy = null
                switching = false
            }
        }
    }

    /** Ends local playback and lets the player carry on from here. */
    fun handBack() {
        if (!active) return
        val pos = currentPosition()
        stopLocal()
        scope.launch {
            busy = "Handing back to the player…"
            runCatching {
                call { post("player/seek?ms=$pos") }
                call { command("play") }
            }.onFailure { error = "Couldn't restart the player: ${message(it)}" }
            busy = null
        }
    }

    /** Ends local playback; the player stays paused. */
    fun stop() {
        if (active) stopLocal()
    }

    /** Stops a background download of the next song (and frees the Bluetooth link for what's wanted now). */
    private fun cancelPrefetch() {
        val job = prefetchJob ?: return
        if (!job.isActive) return
        job.cancel()
        if (Connection.load(app).bluetooth) BluetoothLink.abort()
    }

    private fun stopLocal() {
        cancelPrefetch()
        ticker?.cancel()
        player?.runCatching { stop(); release() }
        player = null
        abandonFocus()
        active = false
        playing = false
        song = null
        positionMs = 0
        durationMs = 0
        cleanUp()
    }

    private fun cleanUp() {
        currentFile = null
        prefetch = null
        dir().listFiles()?.forEach { it.delete() }
    }

    // ---- controls ----------------------------------------------------------------------------

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) pause() else resume()
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        playing = false
    }

    fun resume() {
        val p = player ?: return
        if (!requestFocus()) return
        p.start()
        playing = true
    }

    fun seek(ms: Long) {
        val p = player ?: return
        p.seekTo(ms.coerceIn(0L, durationMs).toInt())
        positionMs = ms.coerceIn(0L, durationMs)
    }

    /** Downloads the player's next song, then switches to it (the player's queue moves along, paused). */
    fun next() {
        if (!active || switching) return
        switching = true
        error = null
        scope.launch {
            try {
                busy = "Next song…"
                // Won't fit? Say so before the player's queue moves (unless it's already downloaded).
                val q = call { queue() }
                val upcoming = q.playNext.firstOrNull() ?: q.general.firstOrNull()
                if (upcoming != null && upcoming.sizeBytes > 0 && prefetch?.first != upcoming.id) {
                    Storage.requireRoom(dir(), upcoming.sizeBytes)
                }
                val status = call { post("player/next?keepPaused=true") }.let { Status.from(org.json.JSONObject(it)) }
                val target = status.nowPlaying ?: run {
                    error = "That was the last song in the player's queue."
                    pause()
                    return@launch
                }
                val ready = prefetch?.takeIf { it.first == target.id && it.second.exists() }?.second
                val file = ready ?: download(target, "Downloading next")
                if (ready != null) prefetch = null
                play(target, file, 0L)
                prefetchNext()
            } catch (e: Exception) {
                error = message(e)
            } finally {
                busy = null
                switching = false
            }
        }
    }

    /**
     * A song tapped in the player's queue while listening here: the player jumps to it (staying paused),
     * then it's downloaded and replaces the current song. [path] = "queue/general|next/{index}/play?id=...".
     */
    fun playQueued(path: String, target: SongInfo) {
        if (!active || switching) return
        switching = true
        error = null
        scope.launch {
            try {
                busy = "Queue…"
                cancelPrefetch()
                if (target.sizeBytes > 0) Storage.requireRoom(dir(), target.sizeBytes)
                val status = call { post("$path&keepPaused=true") }.let { Status.from(org.json.JSONObject(it)) }
                val now = status.nowPlaying ?: return@launch
                val file = download(now, "Downloading")
                play(now, file, 0L)
                prefetchNext()
            } catch (e: Exception) {
                error = message(e)
            } finally {
                busy = null
                switching = false
            }
        }
    }

    /** More than 3 s in: back to the start. Otherwise downloads the previous song, then switches to it. */
    fun previous() {
        if (!active || switching) return
        if (currentPosition() > RESTART_THRESHOLD_MS) {
            seek(0)
            return
        }
        switching = true
        error = null
        scope.launch {
            try {
                cancelPrefetch() // it was fetching the song after this one, not before
                busy = "Previous song…"
                val before = call { queue() }.history.lastOrNull()
                if (before != null && before.sizeBytes > 0) Storage.requireRoom(dir(), before.sizeBytes)
                val status = call { post("player/prev?force=true&keepPaused=true") }.let { Status.from(org.json.JSONObject(it)) }
                val target = status.nowPlaying ?: return@launch
                if (target.id == song?.id) {
                    seek(0) // nothing before it
                    return@launch
                }
                val file = download(target, "Downloading previous")
                play(target, file, 0L)
                prefetchNext()
            } catch (e: Exception) {
                error = message(e)
            } finally {
                busy = null
                switching = false
            }
        }
    }

    // ---- internals --------------------------------------------------------------------------

    /** Starts [file] (the current song stops only now, after the new one is downloaded). */
    private fun play(s: SongInfo, file: File, startMs: Long) {
        player?.runCatching { stop(); release() }
        val old = currentFile
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
        )
        mp.setDataSource(file.path)
        mp.prepare()
        mp.setOnCompletionListener { next() }
        if (startMs > 0) mp.seekTo(startMs.toInt())
        player = mp
        currentFile = file
        if (old != null && old != file) old.delete()
        song = s
        durationMs = mp.duration.toLong().coerceAtLeast(0L)
        positionMs = startMs
        if (requestFocus()) {
            mp.start()
            playing = true
        } else {
            playing = false
        }
        startTicker()
    }

    /** Fetches the song after this one ahead of time (from the player's queue window). */
    private fun prefetchNext() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            val q = runCatching { call { queue() } }.getOrNull() ?: return@launch
            val upcoming = q.playNext.firstOrNull() ?: q.general.firstOrNull() ?: return@launch
            if (prefetch?.first == upcoming.id) return@launch
            prefetch?.second?.delete()
            prefetch = null
            if (upcoming.sizeBytes > 0 && !Storage.fits(dir(), upcoming.sizeBytes)) return@launch // fetched on demand instead
            val file = File(dir(), "${upcoming.id}.next")
            val ok = withContext(Dispatchers.IO) { runCatching { api().download(upcoming.id, file) { _, _ -> } }.isSuccess }
            if (ok && isActive) prefetch = upcoming.id to file else file.delete()
        }
    }

    private suspend fun download(s: SongInfo, label: String): File {
        // Known size (songs on the player): refuse before downloading anything. Unknown (Jellyfin): checked
        // as soon as the download reports its size.
        if (s.sizeBytes > 0) Storage.requireRoom(dir(), s.sizeBytes)
        val file = File(dir(), "${s.id}.${System.nanoTime()}")
        busy = "$label: ${s.title}…"
        try {
            withContext(Dispatchers.IO) {
                api().download(s.id, file) { done, total ->
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        scope.launch { busy = "$label: ${s.title} $pct%" }
                    }
                }
            }
        } catch (e: Exception) {
            file.delete() // no half songs left behind
            throw e
        }
        return file
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var n = 0
            while (isActive) {
                positionMs = currentPosition()
                playing = player?.isPlaying == true
                // Scrobbling works the same as when the player plays (about every 2 s).
                if (n++ % 4 == 0) song?.let { Scrobbler.onStatus(app, Status(it, playing, false, positionMs, durationMs)) }
                delay(TICK_MS)
            }
        }
    }

    private fun currentPosition(): Long = player?.runCatching { currentPosition.toLong() }?.getOrNull() ?: positionMs

    private suspend fun <T> call(block: Api.() -> T): T {
        val api = api()
        return withContext(Dispatchers.IO) { api.block() }
    }

    private fun message(e: Throwable): String = when (e) {
        is java.net.ConnectException, is java.net.SocketTimeoutException -> "Can't reach the player."
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---- audio focus: pause when another app starts playing ----------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
    }

    private fun requestFocus(): Boolean {
        val am = app.getSystemService(AudioManager::class.java)
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(focusListener)
            .build().also { focus = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focus?.let { app.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
    }
}

package com.example.mediaplayer

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.session.MediaSession

/**
 * Owns the ExoPlayer, the [PlayQueue] and a MediaSession.
 *
 * ExoPlayer only ever holds the current song; when it ends, the next one is
 * taken from the queues. The MediaSession is what receives hardware media
 * buttons (play/pause, next, previous) and lock-screen / Bluetooth controls.
 * It wraps the player in a ForwardingPlayer whose next/previous/play go
 * through the queue logic here instead of ExoPlayer's own playlist.
 *
 * Must be created and used on the main thread.
 */
@OptIn(UnstableApi::class)
class Playback(
    private val context: Context,
    initialMode: OutputMode,
    initialDeviceKey: String?,
    initialShuffle: Boolean,
) {

    /** Spectrum and beats of what's playing, for the visualizer. Survives player rebuilds. */
    val analyzer = AudioAnalyzer()

    /** The audio output path; see [changeOutputMode]. */
    var outputMode by mutableStateOf(initialMode)
        private set

    /** Connected outputs that can be picked (kept up to date while the app runs). */
    var outputDevices by mutableStateOf<List<OutputDevice>>(emptyList())
        private set

    /** The output the user picked ([OutputDevice.key]), null = Android decides. Kept while it is unplugged. */
    var preferredDeviceKey by mutableStateOf(initialDeviceKey)
        private set

    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** Formats of the current song and of the opened AudioTrack (for Settings). */
    var outputInfo by mutableStateOf(OutputInfo())
        private set

    private var player: ExoPlayer = buildPlayer(initialMode)

    val queue = PlayQueue()

    /** Remapped hardware buttons (Settings → Buttons); see [handleKey]. */
    val buttons = ButtonMap(context)

    /** True after Stop until playback starts again. */
    var stopped by mutableStateOf(false)
        private set

    /** Called when the shuffle setting changes, so it can be saved. */
    var onShuffleChanged: ((Boolean) -> Unit)? = null

    /** Called once per song when it counts as played (see [checkPlayCount]). */
    var onPlayCounted: ((Song) -> Unit)? = null
    private var counted = false
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            checkPlayCount()
            handler.postDelayed(this, PLAY_CHECK_INTERVAL_MS)
        }
    }

    var nowPlaying by mutableStateOf<Song?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var playWhenReady by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** True when nothing is playing and nothing is left in either queue. */
    var idle by mutableStateOf(true)
        private set
    var shuffle by mutableStateOf(initialShuffle)
        private set

    /** What the MediaSession (and so the media buttons) talks to. */
    private fun sessionPlayerFor(player: ExoPlayer) = object : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                )
                .build()

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

        override fun hasNextMediaItem(): Boolean = queue.hasNext
        override fun hasPreviousMediaItem(): Boolean = nowPlaying != null

        override fun seekToNext() = skipNext()
        override fun seekToNextMediaItem() = skipNext()
        override fun seekToPrevious() = skipPrevious()
        override fun seekToPreviousMediaItem() = skipPrevious()

        override fun play() = resume()
        // A hardware or Bluetooth stop key: same as the Stop button (clears the queues, keeps the song loaded).
        override fun stop() = this@Playback.stop()
    }

    private val session: MediaSession = MediaSession.Builder(context, sessionPlayerFor(player))
        // A unique id avoids a crash if a new Playback is built before the old one is released.
        .setId("player-${System.nanoTime()}")
        // Remapped media keys while the app is in the background or the screen is off.
        .setCallback(object : MediaSession.Callback {
            override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                intent: Intent,
            ): Boolean {
                @Suppress("DEPRECATION")
                val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                return handleKey(event)
            }
        })
        .build()

    /** Media notification / system media player (seek bar comes from the session). */
    private val notification = PlayerNotification(context, session)
    private val scope = MainScope()
    /** The current song's cover for the notification and the session, once loaded. */
    private var notificationArt: Bitmap? = null

    /** The cover to use for a song (the view model's choice, honouring custom album art). */
    var artFor: ((Song) -> ArtSource?)? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@Playback.isPlaying = isPlaying
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            this@Playback.playWhenReady = playWhenReady
            refreshNotification()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                countPlay() // a song played to the end always counts
                advance()
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            error = "${e.errorCodeName}: ${e.message}"
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            outputInfo = outputInfo.copy(input = OutputFormatting.input(format))
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            outputInfo = outputInfo.copy(decoder = decoderName)
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            outputInfo = outputInfo.copy(
                output = OutputFormatting.output(audioTrackConfig),
                direct = OutputFormatting.direct(audioTrackConfig),
            )
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshDevices()
    }

    init {
        active = this
        attach(player)
        handler.post(ticker)
        refreshDevices()
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
    }

    /** Plays on [key] (an [OutputDevice.key]) when it is connected; null lets Android decide. */
    fun changeOutputDevice(key: String?) {
        preferredDeviceKey = key
        applyPreferredDevice()
    }

    private fun refreshDevices() {
        outputDevices = OutputDevices.list(audioManager)
        applyPreferredDevice()
    }

    /** Re-run when devices come and go, so a picked device is used again once it reconnects. */
    private fun applyPreferredDevice() {
        val device = outputDevices.firstOrNull { it.key == preferredDeviceKey }
        player.setPreferredAudioDevice(device?.info)
    }

    private fun buildPlayer(mode: OutputMode): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? {
                val sink = when (mode) {
                    OutputMode.OPENSL -> NativeAudioSink(NativeAudio.API_OPENSL, ::onNativeInfo)
                    OutputMode.AAUDIO -> NativeAudioSink(NativeAudio.API_AAUDIO, ::onNativeInfo)
                    else -> super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                }
                return sink?.let { AnalyzingAudioSink(it, analyzer) }
            }
        }.setEnableAudioFloatOutput(mode == OutputMode.HI_RES)
        // Streams from the Jellyfin server get its Authorization header; local content:// URIs pass straight through.
        val dataSources = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(
                context,
                DefaultHttpDataSource.Factory()
                    .setUserAgent("MediaGimmix/0.1")
                    .setConnectTimeoutMs(10_000)
                    .setReadTimeoutMs(30_000)
                    .setAllowCrossProtocolRedirects(true),
            ),
        ) { spec ->
            NetPolicy.check(spec.uri) // Settings → Network (runs on the loading thread)
            Jellyfin.authorize(spec)
        }
        val p = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .build()
        if (mode == OutputMode.OFFLOAD) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setAudioOffloadPreferences(
                    TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                        .build(),
                )
                .build()
        }
        return p
    }

    /** A native sink opened its stream (posted to the main thread). */
    private fun onNativeInfo(info: String) {
        if (!outputMode.isNative) return
        outputInfo = outputInfo.copy(output = info, direct = OutputFormatting.nativeDirect(context, info))
    }

    private fun attach(p: ExoPlayer) {
        p.addListener(listener)
        p.addAnalyticsListener(analytics)
    }

    /**
     * Switches the audio output path. The renderers are fixed when an ExoPlayer
     * is built, so this builds a new one and carries over the current song,
     * position and play/pause state. The queues are untouched.
     */
    fun changeOutputMode(mode: OutputMode) {
        if (mode == outputMode) return
        val song = nowPlaying
        val position = player.currentPosition
        val wasPlaying = player.playWhenReady
        val loaded = song != null && !idle

        player.removeListener(listener)
        player.removeAnalyticsListener(analytics)
        player.release()

        outputMode = mode
        outputInfo = OutputInfo()
        player = buildPlayer(mode)
        attach(player)
        applyPreferredDevice()
        session.player = sessionPlayerFor(player)
        isPlaying = false
        playWhenReady = false

        if (loaded && song != null) {
            player.setMediaItem(mediaItemFor(song))
            player.prepare()
            player.seekTo(position)
            player.playWhenReady = wasPlaying
        }
    }

    // ---- taps and queue actions ---------------------------------------------

    /** A song was tapped in a library list; [library] is that list as displayed. */
    fun playFromLibrary(song: Song, library: List<Song>) {
        val active = queue.general.isNotEmpty() && nowPlaying != null && !idle
        if (active) {
            // Keep the general queue and its spot; the tapped song plays first.
            queue.clearNext()
            nowPlaying?.let { queue.pushHistory(it) }
            queue.markCurrentFromOutside()
        } else {
            queue.fill(library, song, shuffle)
            queue.clearHistory()
        }
        start(song)
    }

    /**
     * "Play all" / "Shuffle" on a list: clears both queues and the history,
     * fills the general queue with [songs] (shuffled if [shuffled]) and starts
     * the first song. The shuffle switch is set to match.
     */
    fun playAll(songs: List<Song>, shuffled: Boolean) {
        if (songs.isEmpty()) return
        shuffle = shuffled
        onShuffleChanged?.invoke(shuffled)
        queue.replaceAll(if (shuffled) songs.shuffled() else songs)
        val first = queue.takeNext() ?: return
        start(first)
    }

    /** The song's file is gone: drop it from the queues; if it is playing, move on to the next song. */
    fun forget(song: Song) {
        queue.removeSong(song.uri)
        if (nowPlaying?.uri != song.uri) return
        nowPlaying = null
        val next = queue.takeNext()
        if (next != null) {
            start(next)
        } else {
            idle = true
            player.stop()
            player.clearMediaItems()
            refreshNotification()
        }
    }

    fun playNext(song: Song) = queue.addNextFirst(song)

    fun playLast(song: Song) = queue.addNextLast(song)

    /** Plays general queue row [index] now; the spot moves there (see [PlayQueue.jumpGeneral]). */
    fun playFromGeneral(index: Int) {
        val current = nowPlaying
        val song = queue.jumpGeneral(index) ?: return
        if (current != null) queue.pushHistory(current)
        start(song)
    }

    /** Plays play next row [index] now, skipping the ones above it (see [PlayQueue.jumpNext]). */
    fun playFromNext(index: Int) {
        val current = nowPlaying
        val song = queue.jumpNext(index) ?: return
        if (current != null) queue.pushHistory(current)
        start(song)
    }

    fun playNextFromGeneral(index: Int) {
        val song = queue.general.getOrNull(index) ?: return
        queue.addNextFirst(song)
    }

    // Not named setShuffle: that clashes with the setter Kotlin generates for `shuffle`.
    fun changeShuffle(on: Boolean) {
        shuffle = on
        onShuffleChanged?.invoke(on)
        if (on) queue.shuffleGeneral()
    }

    // ---- transport ----------------------------------------------------------

    /**
     * Stops playback and clears every queue (general, play next and history)
     * and the current song, so the bottom bar goes away and the now playing
     * screen closes. A tap in the library starts fresh.
     */
    fun stop() {
        queue.clearAll()
        player.stop()
        player.clearMediaItems()
        nowPlaying = null
        idle = true
        counted = false
        stopped = true
        outputInfo = outputInfo.copy(input = "", decoder = "")
        refreshNotification()
    }

    fun skipNext() = advance()

    /**
     * More than 3 seconds into a song: restart it. Otherwise go to the
     * previous song (see [PlayQueue.stepBack]); with nothing before, restart.
     */
    fun skipPrevious() {
        val current = nowPlaying ?: return
        if (idle) {
            start(current)
            return
        }
        if (player.currentPosition > RESTART_THRESHOLD_MS) {
            player.seekTo(0)
            return
        }
        val prev = queue.stepBack(current)
        if (prev != null) start(prev) else player.seekTo(0)
    }

    /** Always the song before (no restart rule), for the companion's own playback. */
    fun previousTrack() {
        val current = nowPlaying ?: return
        if (idle) {
            start(current)
            return
        }
        val prev = queue.stepBack(current)
        if (prev != null) start(prev) else player.seekTo(0)
    }

    /** Play, starting the queue if nothing is loaded. */
    fun resume() {
        stopped = false
        val song = nowPlaying
        if (song == null || idle) {
            if (queue.hasNext) advance() else if (song != null) start(song)
            return
        }
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun togglePause() {
        if (player.playWhenReady && !idle) player.pause() else resume()
    }

    // ---- remapped buttons ---------------------------------------------------

    /**
     * A hardware key (from the activity while it's on screen, or the media
     * session otherwise). Returns true when the key is remapped and was used;
     * false lets it do its usual job.
     */
    fun handleKey(event: KeyEvent): Boolean {
        val action = buttons.actionFor(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || action.repeats)) perform(action)
        return true // swallow the key up (and ignored repeats) too
    }

    fun perform(action: ButtonAction) {
        when (action) {
            ButtonAction.NORMAL, ButtonAction.NOTHING -> Unit
            ButtonAction.PLAY_PAUSE -> togglePause()
            ButtonAction.PLAY -> resume()
            ButtonAction.PAUSE -> pause()
            ButtonAction.NEXT -> skipNext()
            ButtonAction.PREVIOUS -> skipPrevious()
            ButtonAction.STOP -> stop()
            ButtonAction.SHUFFLE -> changeShuffle(!shuffle)
            ButtonAction.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            ButtonAction.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            ButtonAction.SEEK_FORWARD -> seekBy(SEEK_STEP_MS)
            ButtonAction.SEEK_BACK -> seekBy(-SEEK_STEP_MS)
        }
    }

    private fun adjustVolume(direction: Int) =
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)

    private fun seekBy(deltaMs: Long) {
        if (nowPlaying == null || idle) return
        val d = player.duration
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        player.seekTo(if (d > 0) target.coerceAtMost(d - 500) else target)
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    fun positionMs(): Long = player.currentPosition.coerceAtLeast(0L)

    fun durationMs(): Long {
        val d = player.duration
        return if (d == C.TIME_UNSET || d < 0L) 0L else d
    }

    fun release() {
        if (active === this) active = null
        scope.cancel()
        notification.cancel()
        handler.removeCallbacks(ticker)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        session.release()
        player.release()
    }

    // ---- internals ----------------------------------------------------------

    private fun advance() {
        val current = nowPlaying
        val song = queue.takeNext()
        if (song != null) {
            if (current != null) queue.pushHistory(current)
            start(song)
        } else {
            // The queue ran out: unload the last song so the bottom bar and the now playing screen go away
            // (like Stop, but the queues and history stay as they are).
            if (current != null) queue.pushHistory(current)
            idle = true
            player.stop()
            player.clearMediaItems()
            nowPlaying = null
            counted = false
            outputInfo = outputInfo.copy(input = "", decoder = "")
            refreshNotification()
        }
    }

    private fun start(song: Song) {
        stopped = false
        counted = false
        error = null
        nowPlaying = song
        idle = false
        notificationArt = null
        player.setMediaItem(mediaItemFor(song))
        player.prepare()
        player.play()
        refreshNotification()
        loadNotificationArt(song)
    }

    /** Posts or removes the media notification for the current state. */
    private fun refreshNotification() {
        val song = nowPlaying
        notification.update(if (stopped) null else song, player.playWhenReady && !idle, notificationArt)
    }

    /**
     * Loads [song]'s cover, then shows it in the notification and puts it in the session's metadata
     * (the system media player's background). Replacing the item only changes metadata: no restart.
     */
    private fun loadNotificationArt(song: Song) {
        scope.launch {
            val source = artFor?.invoke(song) ?: ArtSource.FromSong(song)
            val image = Artwork.load(context, source, NOTIFICATION_ART_PX) ?: return@launch
            if (nowPlaying?.uri != song.uri) return@launch
            val bitmap = image.asAndroidBitmap()
            notificationArt = bitmap
            refreshNotification()
            val bytes = withContext(Dispatchers.Default) {
                java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            }
            if (nowPlaying?.uri != song.uri || player.mediaItemCount == 0) return@launch
            val current = player.currentMediaItem ?: return@launch
            if (current.mediaId != song.uri.toString()) return@launch
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                current.buildUpon().setMediaMetadata(
                    current.mediaMetadata.buildUpon().setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build(),
                ).build(),
            )
        }
    }

    private fun mediaItemFor(song: Song): MediaItem = MediaItem.Builder()
        .setUri(song.uri)
        .setMediaId(song.uri.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(song.displayTitle).setArtist(song.artist).setAlbumTitle(song.album).build())
        .build()

    /** A song counts as played once half of it (at most 4 minutes) has played. */
    private fun checkPlayCount() {
        if (counted || idle || !player.isPlaying) return
        val d = durationMs()
        val threshold = if (d > 0) minOf(d / 2, PLAY_COUNT_MAX_MS) else PLAY_COUNT_UNKNOWN_MS
        if (player.currentPosition >= threshold) countPlay()
    }

    private fun countPlay() {
        val song = nowPlaying ?: return
        if (counted) return
        counted = true
        onPlayCounted?.invoke(song)
    }

    companion object {
        const val RESTART_THRESHOLD_MS = 3_000L
        const val NOTIFICATION_ART_PX = 512

        /** The live instance, for the notification buttons ([NotificationActionReceiver]). */
        @Volatile
        var active: Playback? = null
            private set
        const val SEEK_STEP_MS = 10_000L
        const val PLAY_CHECK_INTERVAL_MS = 2_000L
        const val PLAY_COUNT_MAX_MS = 4 * 60_000L
        const val PLAY_COUNT_UNKNOWN_MS = 30_000L
    }
}

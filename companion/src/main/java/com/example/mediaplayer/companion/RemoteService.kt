package com.example.mediaplayer.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The media notification for what the player is playing, WITHOUT a seek bar: the session never
 * reports a duration or offers seeking, so Android draws no bar. Buttons send commands to the player.
 *
 * Runs as a foreground service while the player has a song loaded, so the notification keeps up while
 * Player Remote is in the background (polls the player every [POLL_MS]). Stops itself when the player
 * stops, after a minute without reaching it, or with the notification's ✕ button.
 */
class RemoteService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSession
    private var poller: Job? = null
    private var shownSongId: String? = null
    private var art: Bitmap? = null
    private var last: Status? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Now playing on the player", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        session = MediaSession(this, "PlayerRemote").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = send("play")
                override fun onPause() = send("pause")
                override fun onSkipToNext() = send("next")
                override fun onSkipToPrevious() = send("prev")
                override fun onStop() = send("stop")
                // ✕ on Android 13's media card (it shows session actions, not the notification's buttons).
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    if (action == ACTION_CLOSE) stopSelf()
                }
            })
            isActive = true
        }
        // Must be in the foreground within seconds of starting.
        startForegroundCompat(build(null))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> send("toggle")
            ACTION_NEXT -> send("next")
            ACTION_PREVIOUS -> send("prev")
            ACTION_CLOSE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (poller?.isActive != true) poller = scope.launch { poll() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    private suspend fun poll() {
        var failingSince = 0L
        while (scope.isActive) {
            val api = Api(Connection.load(this))
            val result = withContext(Dispatchers.IO) { runCatching { api.status() } }
            val s = result.getOrNull()
            if (s != null) {
                failingSince = 0L
                if (s.nowPlaying == null) {
                    stopSelf() // the player stopped
                    return
                }
                show(s)
            } else {
                if (failingSince == 0L) failingSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - failingSince > GIVE_UP_MS) {
                    stopSelf()
                    return
                }
            }
            delay(POLL_MS)
        }
    }

    private fun send(command: String) {
        scope.launch {
            val api = Api(Connection.load(this@RemoteService))
            withContext(Dispatchers.IO) { runCatching { api.command(command) } }.getOrNull()?.let { s ->
                if (s.nowPlaying == null) stopSelf() else show(s)
            }
        }
    }

    private fun show(s: Status) {
        val song = s.nowPlaying ?: return
        last = s
        Scrobbler.onStatus(this, s)
        if (song.id != shownSongId) {
            shownSongId = song.id
            art = null
            scope.launch {
                val api = Api(Connection.load(this@RemoteService))
                val image = withContext(Dispatchers.IO) { runCatching { api.songArtBitmap(song.id, 512) }.getOrNull() }
                if (shownSongId == song.id && image != null) {
                    art = image
                    last?.let { show(it) }
                }
            }
        }
        // No METADATA_KEY_DURATION and no ACTION_SEEK_TO: no seek bar.
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, song.album.orEmpty())
                .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
                )
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(ACTION_CLOSE, "Close", android.R.drawable.ic_menu_close_clear_cancel).build(),
                )
                .setState(
                    if (s.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (s.isPlaying) 1f else 0f,
                )
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(ID, build(s))
    }

    private fun build(s: Status?): Notification {
        val song = s?.nowPlaying
        val playing = s?.isPlaying == true
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(icon("notification", android.R.drawable.ic_media_play))
            .setContentTitle(song?.title ?: "Player Remote")
            .setContentText(song?.subtitle ?: "Connecting to the player…")
            .setLargeIcon(art)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action("button_previous", android.R.drawable.ic_media_previous, "Previous", ACTION_PREVIOUS))
            .addAction(
                if (playing) action("button_pause", android.R.drawable.ic_media_pause, "Pause", ACTION_TOGGLE)
                else action("button_play", android.R.drawable.ic_media_play, "Play", ACTION_TOGGLE),
            )
            .addAction(action("button_next", android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
            .addAction(action("button_close", android.R.drawable.ic_menu_close_clear_cancel, "Close", ACTION_CLOSE))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun action(file: String, fallback: Int, title: String, name: String): Notification.Action {
        val intent = PendingIntent.getService(
            this, name.hashCode(), Intent(this, RemoteService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(icon(file, fallback), title, intent).build()
    }

    /** assets/icons/<file>.png when present (the user's own), else an Android icon. */
    private fun icon(file: String, fallback: Int): Icon =
        runCatching { assets.open("icons/$file.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
            ?.let { Icon.createWithBitmap(it) } ?: Icon.createWithResource(this, fallback)

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "remote_playback"
        private const val ID = 2001
        private const val POLL_MS = 3_000L
        private const val GIVE_UP_MS = 60_000L
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_NEXT = "next"
        private const val ACTION_PREVIOUS = "prev"
        private const val ACTION_CLOSE = "close"

        @Volatile
        var running = false
            private set

        /** Starts the notification (call while the app is on screen: Android limits starts from the background). */
        fun start(context: Context) {
            if (running) return
            runCatching { context.startForegroundService(Intent(context, RemoteService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteService::class.java))
        }
    }
}

package com.example.mediaplayer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper

/**
 * The media notification (and, on Android 13, the system media player in the shade and on the lock
 * screen), tied to [Playback]'s MediaSession. Android draws the seek bar from the session: the song's
 * duration plus seeking, which ExoPlayer supports. Posted while a song is loaded, removed on Stop.
 * Ongoing (can't be swiped away) only while playing. No service, on purpose (see CLAUDE.md).
 *
 * Icons: assets/icons/notification.png (small icon) and button_*.png (actions) when present, else
 * Android's built-in media icons.
 */
@OptIn(UnstableApi::class)
class PlayerNotification(private val context: Context, private val session: MediaSession) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Now playing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "What's playing, with playback controls"
                setShowBadge(false)
            },
        )
    }

    fun update(song: Song?, playing: Boolean, art: Bitmap?) {
        if (song == null) {
            manager.cancel(ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(smallIcon())
            .setContentTitle(song.displayTitle)
            .setContentText(listOfNotNull(song.artist, song.album).joinToString(" · "))
            .setLargeIcon(art)
            .setContentIntent(open)
            .setDeleteIntent(action(ACTION_STOP))
            .setOngoing(playing)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(act(icon("button_previous", android.R.drawable.ic_media_previous), "Previous", action(ACTION_PREVIOUS)))
            .addAction(act(
                if (playing) icon("button_pause", android.R.drawable.ic_media_pause) else icon("button_play", android.R.drawable.ic_media_play),
                if (playing) "Pause" else "Play",
                action(ACTION_TOGGLE),
            ))
            .addAction(act(icon("button_next", android.R.drawable.ic_media_next), "Next", action(ACTION_NEXT)))
            .setStyle(MediaStyleNotificationHelper.MediaStyle(session).setShowActionsInCompactView(0, 1, 2))
        runCatching { manager.notify(ID, builder.build()) }
    }

    fun cancel() = manager.cancel(ID)

    private fun act(icon: IconCompat, title: String, intent: PendingIntent) =
        NotificationCompat.Action.Builder(icon, title, intent).build()

    private fun action(name: String): PendingIntent = PendingIntent.getBroadcast(
        context, name.hashCode(),
        Intent(context, NotificationActionReceiver::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun smallIcon(): IconCompat =
        assetBitmap("notification")?.let { IconCompat.createWithBitmap(it) }
            ?: IconCompat.createWithResource(context, android.R.drawable.ic_media_play)

    private fun icon(file: String, fallback: Int): IconCompat =
        assetBitmap(file)?.let { IconCompat.createWithBitmap(it) } ?: IconCompat.createWithResource(context, fallback)

    private fun assetBitmap(name: String): Bitmap? =
        runCatching { context.assets.open("icons/$name.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()

    companion object {
        private const val CHANNEL = "playback"
        private const val ID = 1001
        const val ACTION_PREVIOUS = "com.example.mediaplayer.PREVIOUS"
        const val ACTION_TOGGLE = "com.example.mediaplayer.TOGGLE"
        const val ACTION_NEXT = "com.example.mediaplayer.NEXT"
        const val ACTION_STOP = "com.example.mediaplayer.STOP"
    }
}

/** Notification buttons (older Android; Android 13 drives the session directly). */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = Playback.active ?: return
        when (intent.action) {
            PlayerNotification.ACTION_PREVIOUS -> p.skipPrevious()
            PlayerNotification.ACTION_TOGGLE -> p.togglePause()
            PlayerNotification.ACTION_NEXT -> p.skipNext()
            // Swiped away (only possible while paused): just forget the notification, keep the queue.
            PlayerNotification.ACTION_STOP -> Unit
        }
    }
}

package com.example.mediaplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings

/**
 * "Open on wake": brings the app to the front whenever the phone is unlocked
 * (with no screen lock, that is as soon as the screen turns on).
 *
 * Android only tells running apps about unlocks, so this needs a small
 * foreground service ([WakeLaunchService]) with a notification, and it may only
 * open itself from the background with the "Display over other apps"
 * permission. Off by default; switched in Settings. Restarted at boot by
 * [WakeLaunchBootReceiver] and whenever the app opens.
 */
object WakeLaunch {
    const val KEY = "wake_launch"

    /** Set on the unlock launch so MainActivity plays the splash again. */
    const val EXTRA_SHOW_SPLASH = "show_splash"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        syncService(context)
    }

    /** The service runs while Open on wake or [StayOnTop] is on. */
    fun syncService(context: Context) {
        if (isEnabled(context) || StayOnTop.isOn(context)) {
            start(context)
        } else {
            context.stopService(Intent(context, WakeLaunchService::class.java))
        }
    }

    /** Starts the service if a setting needs it. Must be called while the app is in front, or at boot. */
    fun startIfEnabled(context: Context) {
        if (isEnabled(context) || StayOnTop.isOn(context)) start(context)
    }

    /** "Display over other apps": without it Android blocks the app from opening itself. */
    fun canOpenFromBackground(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    private fun start(context: Context) {
        runCatching { context.startForegroundService(Intent(context, WakeLaunchService::class.java)) }
    }
}

class WakeLaunchService : Service() {

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!WakeLaunch.canOpenFromBackground(context)) return
            if (StayOnTop.isOn(context)) {
                StayOnTop.bringBack(context, showSplash = WakeLaunch.isEnabled(context))
                return
            }
            if (!WakeLaunch.isEnabled(context)) return
            val launch = (packageManager.getLaunchIntentForPackage(packageName) ?: Intent(context, MainActivity::class.java))
                // SINGLE_TOP: an open player gets onNewIntent (and replays the splash) instead of a second copy.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(WakeLaunch.EXTRA_SHOW_SPLASH, true)
            runCatching { startActivity(launch) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlockReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Open on wake", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while the app waits to open when the phone is unlocked, or stays on top"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Your own icon: assets/icons/notification.png (white on transparent). Else Android's built-in play icon.
        val custom = runCatching { assets.open("icons/notification.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
        val icon = custom?.let { Icon.createWithBitmap(it) } ?: Icon.createWithResource(this, android.R.drawable.ic_media_play)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(if (StayOnTop.isOn(this)) "Stay on top is on" else "Open on wake is on")
            .setContentText(
                if (StayOnTop.isOn(this)) "Keeps the player in front while unlocked. Tap the pin to turn off."
                else "Opens the player when the phone is unlocked. Turn off in Settings.",
            )
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "wake_launch"
        const val NOTIFICATION_ID = 7
    }
}

/** Restarts [WakeLaunchService] after a reboot (if the setting is on). */
class WakeLaunchBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            WakeLaunch.startIfEnabled(context)
        }
    }
}

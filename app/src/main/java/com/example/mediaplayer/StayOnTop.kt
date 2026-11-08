package com.example.mediaplayer

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * "Stay on top" (like FiiO's Pure Music mode): while on, the player is always the screen in front whenever the
 * device is awake and unlocked. If the app leaves the screen (Home, Recents, another app opening), it brings
 * itself back right away; unlocking brings it back too ([WakeLaunchService], which keeps running while this
 * is on). Other apps' background services aren't touched.
 *
 * Needs "Display over other apps" (Android blocks background starts without it), shared with Open on wake.
 * Pickers the app opens run inside its task and stay on top when it's brought back, so they can't be used
 * to get out; Android screens it opens (permission settings) call [allowLeaving] for at most 3 minutes.
 *
 * Saved in "settings": "stay_on_top" (the mode) and "stay_on_top_switch" (show the pin toggle on screens;
 * Settings → System → Stay on top can hide it).
 */
object StayOnTop {
    private const val KEY_ON = "stay_on_top"
    private const val KEY_SWITCH = "stay_on_top_switch"
    private const val RETURN_DELAY_MS = 400L
    /** While hidden, how often it checks it's still in front (catches escapes from a picker). */
    private const val WATCH_MS = 1_500L
    /** Leaving for a separate screen the app opened (Android settings): at most this long. */
    private const val LEAVE_WINDOW_MS = 3 * 60_000L

    var on by mutableStateOf(false)
        private set
    var showSwitch by mutableStateOf(true)
        private set

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var allowLeaveUntil = 0L
    @Volatile private var visible = false
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        val p = prefs(context)
        on = p.getBoolean(KEY_ON, false)
        showSwitch = p.getBoolean(KEY_SWITCH, true)
        loaded = true
    }

    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    fun setOn(context: Context, value: Boolean) {
        on = value
        prefs(context).edit().putBoolean(KEY_ON, value).apply()
        WakeLaunch.syncService(context)
    }

    fun setShowSwitch(context: Context, value: Boolean) {
        showSwitch = value
        prefs(context).edit().putBoolean(KEY_SWITCH, value).apply()
    }

    /**
     * Call before opening a picker or Android settings screen. Pickers inside the app's task are kept on top
     * anyway; a screen in another task may stay in front for up to [LEAVE_WINDOW_MS] (or until the app returns).
     */
    fun allowLeaving() {
        allowLeaveUntil = System.currentTimeMillis() + LEAVE_WINDOW_MS
    }

    private var watcher: Runnable? = null

    /** MainActivity.onStart. */
    fun onShown() {
        visible = true
        allowLeaveUntil = 0L
        watcher?.let { main.removeCallbacks(it) }
        watcher = null
    }

    /**
     * MainActivity.onStop: if staying on top, come back unless the screen is off or locked, then keep checking
     * every [WATCH_MS] while hidden (a picker can be left for another app without the player noticing).
     */
    fun onHidden(context: Context) {
        visible = false
        val app = context.applicationContext
        if (!isOn(app)) return
        watcher?.let { main.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                if (visible || !isOn(app)) return
                bringBack(app)
                main.postDelayed(this, WATCH_MS)
            }
        }
        watcher = tick
        main.postDelayed(tick, RETURN_DELAY_MS)
    }

    /**
     * Puts the player's task in front if it should be there now. A picker the app opened runs inside that
     * task, so it comes back with the picker still on top (it's never covered); anything else the user
     * switched to gets replaced.
     */
    fun bringBack(context: Context, showSplash: Boolean = false) {
        if (visible || !isOn(context)) return
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (!power.isInteractive || keyguard.isKeyguardLocked) return // asleep or locked: wait for the unlock
        if (!WakeLaunch.canOpenFromBackground(context)) return
        val task = context.getSystemService(ActivityManager::class.java).appTasks.firstOrNull()
        val top = runCatching { task?.taskInfo?.topActivity }.getOrNull()
        val childOnTop = top != null && top.packageName != context.packageName // e.g. the folder picker
        if (!childOnTop && System.currentTimeMillis() < allowLeaveUntil) return // a separate screen we opened
        if (task != null && (childOnTop || !showSplash)) {
            if (runCatching { task.moveToFront() }.isSuccess) return
        }
        val launch = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(context, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(WakeLaunch.EXTRA_SHOW_SPLASH, showSplash)
        runCatching { context.startActivity(launch) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
}

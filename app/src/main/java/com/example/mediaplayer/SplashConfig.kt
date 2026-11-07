package com.example.mediaplayer

/**
 * Startup splash timing, in milliseconds. Change these and rebuild.
 *
 *   FADE_IN_MS   how long the logo takes to fade in
 *   HOLD_MS      how long it stays fully visible
 *   FADE_OUT_MS  how long the splash takes to fade away, revealing the library
 *
 * Set all three to 0 to skip the splash.
 */
object SplashConfig {
    /** The Settings → Startup switch (on unless turned off). */
    const val KEY_ENABLED = "splash_enabled"

    fun isEnabled(context: android.content.Context): Boolean =
        FADE_IN_MS + HOLD_MS + FADE_OUT_MS > 0 &&
            context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: android.content.Context, on: Boolean) {
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    const val FADE_IN_MS = 350
    const val HOLD_MS = 500
    const val FADE_OUT_MS = 1600
}

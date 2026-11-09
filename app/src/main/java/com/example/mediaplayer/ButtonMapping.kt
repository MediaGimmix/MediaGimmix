package com.example.mediaplayer

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateMapOf

/**
 * Remappable hardware buttons. To add a button, add an entry here: it shows up
 * in Settings → Buttons in the given [column] (0 = left, 1 = right, in order),
 * and its choice is saved as "button_<name>" in "settings".
 */
enum class HwButton(val label: String, val column: Int, val keyCodes: Set<Int>) {
    VOLUME_UP("Vol up", 0, setOf(KeyEvent.KEYCODE_VOLUME_UP)),
    VOLUME_DOWN("Vol down", 0, setOf(KeyEvent.KEYCODE_VOLUME_DOWN)),
    PREVIOUS("Prev", 1, setOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS)),
    PLAY_PAUSE(
        "Play/pause", 1,
        setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
        ),
    ),
    NEXT("Next", 1, setOf(KeyEvent.KEYCODE_MEDIA_NEXT)),
    ;

    companion object {
        fun forKey(keyCode: Int): HwButton? = entries.firstOrNull { keyCode in it.keyCodes }
    }
}

/**
 * What a button can do. [NORMAL] leaves the key alone (the button's usual job),
 * which is every button's default. To add an action, add an entry here and a
 * branch in [Playback.perform].
 */
enum class ButtonAction(val label: String, val repeats: Boolean = false) {
    NORMAL("Normal"),
    PLAY_PAUSE("Play/pause"),
    PLAY("Play"),
    PAUSE("Pause"),
    NEXT("Next"),
    PREVIOUS("Previous"),
    STOP("Stop"),
    SHUFFLE("Shuffle toggle"),
    VOLUME_UP("Volume up", repeats = true),
    VOLUME_DOWN("Volume down", repeats = true),
    SEEK_FORWARD("Forward 10 s", repeats = true),
    SEEK_BACK("Back 10 s", repeats = true),
    EQ_NEXT("Next EQ profile"),
    NOTHING("Do nothing"),
}

/** The saved button → action choices (Compose state, so Settings updates live). */
class ButtonMap(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val map = mutableStateMapOf<HwButton, ButtonAction>().apply {
        HwButton.entries.forEach { b ->
            put(b, prefs.getString(key(b), null)
                ?.let { runCatching { ButtonAction.valueOf(it) }.getOrNull() } ?: ButtonAction.NORMAL)
        }
    }

    operator fun get(button: HwButton): ButtonAction = map[button] ?: ButtonAction.NORMAL

    fun set(button: HwButton, action: ButtonAction) {
        map[button] = action
        prefs.edit().putString(key(button), action.name).apply()
    }

    fun resetAll() = HwButton.entries.forEach { set(it, ButtonAction.NORMAL) }

    /** The remapped action for a key, or null when the key keeps its usual job. */
    fun actionFor(keyCode: Int): ButtonAction? =
        HwButton.forKey(keyCode)?.let { this[it] }?.takeIf { it != ButtonAction.NORMAL }

    private fun key(b: HwButton) = "button_${b.name.lowercase()}"
}

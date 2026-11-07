package com.example.mediaplayer.companion

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Connection, the player's status (polled while the app is on screen) and commands. */
class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    var connection by mutableStateOf(Connection.load(app))
        private set
    var status by mutableStateOf<Status?>(null)
        private set
    /** Last connection problem, null while things work. */
    var error by mutableStateOf<String?>(null)
        private set
    var cover by mutableStateOf<ImageBitmap?>(null)
        private set
    private var coverFor: String? = null

    private fun api() = Api(connection)

    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    /** Most search results (of each kind) and playlists to ask for. The player has its own, higher cap. */
    var searchLimit by mutableStateOf(prefs.getInt(KEY_SEARCH_LIMIT, DEFAULT_SEARCH_LIMIT))
        private set

    fun changeSearchLimit(n: Int) {
        searchLimit = n.coerceIn(1, MAX_SEARCH_LIMIT)
        prefs.edit().putInt(KEY_SEARCH_LIMIT, searchLimit).apply()
    }

    /** Show what the player is playing as a media notification (no seek bar), see [RemoteService]. */
    var notificationOn by mutableStateOf(prefs.getBoolean(KEY_NOTIFICATION, true))
        private set

    fun changeNotification(on: Boolean) {
        notificationOn = on
        prefs.edit().putBoolean(KEY_NOTIFICATION, on).apply()
        if (on) {
            if (status?.nowPlaying != null) RemoteService.start(getApplication())
        } else {
            RemoteService.stop(getApplication())
        }
    }

    /** Browse the player's Jellyfin library instead of the songs on it. */
    var jellyfin by mutableStateOf(false)

    /** Runs an API call off the main thread. */
    suspend fun <T> call(block: Api.() -> T): Result<T> {
        val api = api()
        return withContext(Dispatchers.IO) { runCatching { api.block() } }
    }

    /** Starts playback on the player (see [Api.play]) and shows the new status. */
    fun play(path: String) {
        val source = jellyfin
        viewModelScope.launch {
            call { play(path, source) }.onSuccess { apply(it) }.onFailure { error = message(it) }
        }
    }

    fun saveConnection(c: Connection) {
        viewModelScope.launch(Dispatchers.IO) { BluetoothLink.close() } // reopened on the next request if needed
        Connection.save(getApplication(), c)
        connection = c
        status = null
        cover = null
        coverFor = null
        refresh()
    }

    /** Fetch the status once (the activity calls this every couple of seconds while resumed). */
    fun refresh() {
        if (!connection.isSet) {
            error = "Set the player's address in Settings."
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api().status() } }
            result.onSuccess { apply(it) }.onFailure { error = message(it) }
        }
    }

    /** play, pause, toggle, next, prev, stop, shuffle */
    fun command(name: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api().command(name) } }
            result.onSuccess { apply(it) }.onFailure { error = message(it) }
        }
    }

    private fun apply(s: Status) {
        status = s
        error = null
        Scrobbler.onStatus(getApplication(), s)
        // Only ever started from here, i.e. while the app is in use (Android limits background starts).
        if (notificationOn && s.nowPlaying != null) RemoteService.start(getApplication())
        val id = s.nowPlaying?.id
        if (id != coverFor) {
            coverFor = id
            cover = null
            if (id != null) {
                viewModelScope.launch {
                    val art = withContext(Dispatchers.IO) { runCatching { api().songArt(id, 800) }.getOrNull() }
                    if (coverFor == id) cover = art
                }
            }
        }
    }

    fun message(e: Throwable): String = when (e) {
        is ApiError -> e.message ?: "Error"
        is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException ->
            "Can't reach the player. Is Web control switched on?"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        private const val KEY_SEARCH_LIMIT = "search_limit"
        private const val KEY_NOTIFICATION = "notification"
        const val DEFAULT_SEARCH_LIMIT = 100
        const val MAX_SEARCH_LIMIT = 1000
    }
}

package com.example.mediaplayer

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import java.io.InputStream
import kotlinx.coroutines.Job

/**
 * Netshare: music servers shown as their own library section (Jellyfin, Navidrome, mStream).
 *
 * Each server is a [NetshareBackend] object; [Netshare.all] lists them. Its [NetshareBackend.id] is
 * used everywhere: the rootUri of its songs, its TokenVault scope, its SharedPreferences name and
 * the web API's ?source= value. The ViewModel keeps one [ServerState] per backend built into the app.
 *
 * Adding a server = a new backend object, an entry in [Netshare.all], its scope in TokenVault's
 * list, and a feature flag in app/build.gradle.kts.
 */
interface NetshareBackend {
    /** "jellyfin", "navidrome", "mstream". */
    val id: String
    val label: String
    /** In this build (feature flags). */
    val builtIn: Boolean
    /** The sign-in form in Settings → Netshare; the first field is always the server address ("server"). */
    val fields: List<SignInField>
    /** What the app keeps after signing in (shown under the form). */
    val keeps: String
    /** Extra help under the sign-in form. */
    val hint: String? get() = null

    /** Loads the saved account (call once at startup, off the main thread). */
    fun init(context: Context)
    /** The signed-in account, also while locked behind a YubiKey. For display. */
    val shown: ServerAccount?
    /** Signed in and unlocked: requests can be made. */
    val ready: Boolean
    fun isLocked(): Boolean
    /** After a trusted YubiKey unlocked the vault. Returns false if there's still no secret. */
    fun unlock(): Boolean
    fun lock()
    fun lastServer(context: Context): String

    /** Signs in with the form's values (blocking). Throws IOException with a readable message. */
    fun signIn(context: Context, values: Map<String, String>)
    fun signOut(context: Context)
    /** Fetches the whole library (blocking). [onProgress] gets a status line. */
    fun sync(context: Context, onProgress: (String) -> Unit): ServerLibrary
    /** The library from the last sync, if it belongs to the signed-in server. */
    fun loadCached(context: Context): ServerLibrary?

    /** Cover bytes for an art id from [ServerLibrary.artIds]. Blocking. */
    fun cover(artId: String, sizePx: Int): ByteArray?
    fun lyrics(song: Song): Lyrics?
    /** Adds credentials to ExoPlayer requests for this server's streams; other URIs pass unchanged. */
    @OptIn(UnstableApi::class)
    fun authorize(spec: DataSpec): DataSpec
    /** A song's original file (web control downloads). Blocking. */
    fun openStream(song: Song): Pair<InputStream, Long>?
}

/** One field of a sign-in form. [secret] fields are never saved as typed. */
data class SignInField(
    val key: String,
    val label: String,
    val placeholder: String = "",
    val secret: Boolean = false,
    val optional: Boolean = false,
)

data class ServerAccount(val server: String, val serverName: String, val userName: String)

/** A synced library. [artIds] maps a song URI to its cover's id on the server. */
data class ServerLibrary(
    val songs: List<Song>,
    val playlists: List<Playlist>,
    val artIds: Map<String, String>,
    val syncedAt: Long,
) {
    companion object {
        val EMPTY = ServerLibrary(emptyList(), emptyList(), emptyMap(), 0L)
    }
}

/** The ViewModel's Compose state for one backend. */
class ServerState(val backend: NetshareBackend) {
    val id: String get() = backend.id
    val label: String get() = backend.label

    var account by mutableStateOf<ServerAccount?>(null)
    var locked by mutableStateOf(false)
    var songs by mutableStateOf<List<Song>>(emptyList())
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
    var albums by mutableStateOf<List<AlbumEntry>>(emptyList())
    var artists by mutableStateOf<List<ArtistEntry>>(emptyList())
    var syncedAt by mutableStateOf(0L)
    var busy by mutableStateOf(false)
    /** Progress or the last problem, shown in Settings and the Netshare section. */
    var status by mutableStateOf("")
    var artIds: Map<String, String> = emptyMap()
    var job: Job? = null

    /** Signed in and unlocked. */
    val ready: Boolean get() = account != null && !locked
}

object Netshare {
    val all: List<NetshareBackend> = listOf(Jellyfin, Navidrome, Mstream)
    val builtIn: List<NetshareBackend> get() = all.filter { it.builtIn }
    val anyBuiltIn: Boolean get() = all.any { it.builtIn }

    fun byId(id: String?): NetshareBackend? = all.firstOrNull { it.id == id }
    /** The server a song streams from, or null for files on the phone. */
    fun of(song: Song): NetshareBackend? = byId(song.rootUri)
    fun isServerSong(song: Song): Boolean = of(song) != null

    @OptIn(UnstableApi::class)
    fun authorize(spec: DataSpec): DataSpec = all.fold(spec) { s, b -> b.authorize(s) }

    /** "192.168.1.5:8096" -> "http://192.168.1.5:8096"; trailing slashes dropped. */
    fun normalizeServer(input: String): String {
        val t = input.trim().trimEnd('/')
        return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "http://$t"
    }

    /** Shown below a sign-in made while the YubiKey vault is locked. */
    const val SIGNED_IN_FOR_NOW = "Signed in for now. Plug in a trusted YubiKey to keep this sign-in after the app closes."
}

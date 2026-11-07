package com.example.mediaplayer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Counts shown in Settings. */
data class LibraryStats(
    val songs: Int = 0,
    val albums: Int = 0,
    val artists: Int = 0,
    val playlists: Int = 0,
    val subdirs: Int = 0,
    val totalBytes: Long = 0L,
    val totalDurationMs: Long = 0L,
    val lastScan: Long = 0L,
    val lastScanFull: Boolean = false,
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val db = LibraryDb(app)
    private val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val playback = Playback(
        app,
        OutputMode.fromKey(settings.getString(OutputMode.KEY, null)),
        settings.getString(OutputDevices.KEY, null),
        settings.getBoolean(KEY_SHUFFLE, false),
    )

    /** Picks the output device (an [OutputDevice.key], null = automatic) and remembers it. */
    fun setOutputDevice(key: String?) {
        settings.edit().apply { if (key == null) remove(OutputDevices.KEY) else putString(OutputDevices.KEY, key) }.apply()
        playback.changeOutputDevice(key)
    }

    /** Switches the audio output path and remembers it. */
    fun setOutputMode(mode: OutputMode) {
        settings.edit().putString(OutputMode.KEY, mode.key).apply()
        playback.changeOutputMode(mode)
    }

    var folders by mutableStateOf<List<String>>(emptyList())
        private set
    var songs by mutableStateOf<List<Song>>(emptyList())
        private set
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set
    var albums by mutableStateOf<List<AlbumEntry>>(emptyList())
        private set
    var artists by mutableStateOf<List<ArtistEntry>>(emptyList())
        private set
    var stats by mutableStateOf(LibraryStats())
        private set

    /** Play counts keyed by song URI (only songs played at least once). */
    var playStats by mutableStateOf<Map<String, PlayStat>>(emptyMap())
        private set
    /** Most played first, at most [MOST_PLAYED_LIMIT]. */
    var mostPlayed by mutableStateOf<List<Song>>(emptyList())
        private set
    /** Newest first, at most [RECENTLY_ADDED_LIMIT]. */
    var recentlyAdded by mutableStateOf<List<Song>>(emptyList())
        private set
    /** Songs over [LONG_SONG_MS], longest first. */
    var longSongs by mutableStateOf<List<Song>>(emptyList())
        private set

    /** Custom cover images, keyed "album:<album key>" or "playlist:<playlist uri>". */
    var customArt by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    /** Bumped when folder permissions may have changed, so Settings checks them again. */
    var permissionVersion by mutableIntStateOf(0)
        private set

    /** Strings that split an artist tag into separate artists. */
    var delimiters by mutableStateOf(loadList(KEY_DELIMITERS) ?: ArtistSplitter.DEFAULT_DELIMITERS)
        private set
    /** Artist names that are never split. */
    var whitelist by mutableStateOf(loadList(KEY_WHITELIST) ?: emptyList())
        private set

    /** True until the stored library has been loaded. */
    var loading by mutableStateOf(true)
        private set
    var scanning by mutableStateOf(false)
        private set
    var scanStatus by mutableStateOf("")
        private set
    /** 0..1 while reading tags, null while the amount of work is unknown. */
    var scanProgress by mutableStateOf<Float?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The web control server (off at every start; see WebControl.kt). */
    val web = WebControl(app, this)
    /** The API over Bluetooth (own switch, off at every start like [web]). */
    val bluetooth = BluetoothControl(app, web)

    // ---- Jellyfin (a separate library; see Jellyfin.kt)
    /** Signed-in account (also while locked; its token is never shown). */
    var jellyfinAccount by mutableStateOf<Jellyfin.Account?>(null)
        private set
    /** Signed in, but the token is locked behind a YubiKey (see SecurityKeys.kt). */
    var jellyfinLocked by mutableStateOf(false)
        private set

    /** USB YubiKeys: registering trusted keys and unlocking the Jellyfin token. */
    val yubikey = YubiKeyUsb(app) { onJellyfinUnlocked() }
    var jellyfinSongs by mutableStateOf<List<Song>>(emptyList())
        private set
    var jellyfinPlaylists by mutableStateOf<List<Playlist>>(emptyList())
        private set
    var jellyfinAlbums by mutableStateOf<List<AlbumEntry>>(emptyList())
        private set
    var jellyfinArtists by mutableStateOf<List<ArtistEntry>>(emptyList())
        private set
    var jellyfinSyncedAt by mutableStateOf(0L)
        private set
    var jellyfinBusy by mutableStateOf(false)
        private set
    /** Progress or the last problem, shown in Settings and the Jellyfin section. */
    var jellyfinStatus by mutableStateOf("")
        private set
    private var jellyfinArtIds: Map<String, String> = emptyMap()
    private var jellyfinJob: Job? = null

    private var scanJob: Job? = null
    private var rebuildJob: Job? = null
    private var scanMeta = ScanMeta(0, 0L, false)

    init {
        NetPolicy.init(app)
        bluetooth.startIfWanted() // "Start with the app" (Settings → Network → Bluetooth control)
        viewModelScope.launch {
            artistPhotoIds = withContext(Dispatchers.IO) { ArtistImages.downloadedIds(app) }
        }
        playback.onPlayCounted = { song -> recordPlay(song) }
        playback.artFor = { song -> songArt(song) }
        playback.onShuffleChanged = { on -> settings.edit().putBoolean(KEY_SHUFFLE, on).apply() }
        viewModelScope.launch {
            val neverScanned = withContext(Dispatchers.IO) {
                migrateOldFolderSetting()
                db.folders().isNotEmpty() && db.meta(LibraryDb.KEY_LAST_SCAN) == null
            }
            reload()
            loading = false
            loadJellyfin()
            // Only scan on startup if folders exist but nothing has ever been stored.
            if (neverScanned) rescan(full = false)
        }
    }

    // ---- folders --------------------------------------------------------------

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                db.addFolder(uri.toString())
                db.folders()
            }
            folders = list
            permissionVersion++
            rescan(full = false)
        }
    }

    fun removeFolder(uri: String) {
        cancelScan()
        runCatching {
            getApplication<Application>().contentResolver
                .releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.removeFolder(uri) }
            reload()
            scanStatus = "Removed ${folderLabel(uri)}"
        }
    }

    // ---- play counts ------------------------------------------------------------

    fun playCountOf(song: Song): Int = playStats[song.uri.toString()]?.plays ?: 0

    private fun recordPlay(song: Song) {
        val key = song.uri.toString()
        val now = System.currentTimeMillis()
        val old = playStats[key]
        playStats = playStats + (key to PlayStat((old?.plays ?: 0) + 1, now))
        mostPlayed = computeMostPlayed(songs, playStats)
        viewModelScope.launch(Dispatchers.IO) { db.recordPlay(key, now) }
    }

    // ---- covers -----------------------------------------------------------------

    /** Sets ([imageUri] not null) or clears the custom cover for [key]. */
    fun setCustomArt(key: String, imageUri: String?) {
        customArt = if (imageUri == null) customArt - key else customArt + (key to imageUri)
        viewModelScope.launch(Dispatchers.IO) { db.setCustomArt(key, imageUri) }
    }

    /** The cover to show for an album: its custom image, else its first song's cover. */
    fun albumArt(album: AlbumEntry): ArtSource? =
        customArt["album:" + album.key]?.let { ArtSource.FromImage(it) }
            ?: album.songs.firstOrNull()?.let { ownArt(it) }

    /** The cover to show for a playlist: its custom image, else its first song's cover. */
    fun playlistArt(playlist: Playlist): ArtSource? =
        customArt["playlist:" + playlist.uri]?.let { ArtSource.FromImage(it) }
            ?: playlist.songs.firstOrNull()?.let { ownArt(it) }

    /** An artist's picture: custom image, else the downloaded photo, else the cover of their newest album (year tag). */
    fun artistArt(artist: ArtistEntry): ArtSource? {
        customArt["artist:" + artist.key]?.let { return ArtSource.FromImage(it) }
        if (ArtistImages.id(artist.name) in artistPhotoIds) {
            return ArtSource.FromFile(ArtistImages.file(getApplication(), artist.name).path)
        }
        val newest = artist.songs.filter { LibraryGrouping.albumKey(it) != null }.maxByOrNull { it.year }
            ?: artist.songs.firstOrNull()
        return newest?.let { songArt(it) }
    }

    // ---- artist pictures --------------------------------------------------------------

    /** Ids ([ArtistImages.id]) of artists with a downloaded picture. */
    var artistPhotoIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var artistFetchStatus by mutableStateOf("")
        private set
    @Volatile private var onWifi = false
    private var artistFetch: Job? = null

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        private val up = HashSet<Network>()
        override fun onAvailable(network: Network) {
            synchronized(up) { up.add(network) }
            onWifi = true
            viewModelScope.launch { fetchArtistPictures() }
        }
        override fun onLost(network: Network) {
            onWifi = synchronized(up) { up.remove(network); up.isNotEmpty() }
        }
    }

    // After the properties above exist (init blocks run in order).
    init {
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                wifiCallback,
            )
        }
    }

    /** Looks up pictures for artists that have none, while on Wi-Fi with internet access on. */
    fun fetchArtistPictures() {
        if (artistFetch?.isActive == true) return
        if (!onWifi) {
            artistFetchStatus = "Waiting for Wi-Fi"
            return
        }
        if (!NetPolicy.internet) {
            artistFetchStatus = "Internet access is off"
            return
        }
        val names = (artists + jellyfinArtists).map { it.name }
        if (names.isEmpty()) return
        val app = getApplication<Application>()
        artistFetch = viewModelScope.launch {
            val todo = withContext(Dispatchers.IO) { ArtistImages.pending(app, names) }
            if (todo.isEmpty()) {
                artistFetchStatus = "Up to date"
                return@launch
            }
            var done = 0
            artistFetchStatus = "Looking up ${todo.size} artists…"
            withContext(Dispatchers.IO) {
                ArtistImages.fetch(app, todo, keepGoing = { onWifi && NetPolicy.internet }) { id ->
                    done++
                    viewModelScope.launch {
                        artistPhotoIds = artistPhotoIds + id
                        artistFetchStatus = "Looking up ${todo.size} artists… $done found"
                    }
                }
            }
            artistFetchStatus = when {
                !onWifi -> "Stopped: Wi-Fi lost"
                !NetPolicy.internet -> "Stopped: internet access is off"
                else -> "Done: $done new pictures"
            }
        }
    }

    fun retryArtistPictures() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ArtistImages.clearMisses(app) }
            fetchArtistPictures()
        }
    }

    fun deleteArtistPictures() {
        artistFetch?.cancel()
        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ArtistImages.clearAll(app) }
            artistPhotoIds = emptySet()
            artistFetchStatus = "Deleted"
        }
    }

    fun setInternetAccess(on: Boolean) {
        NetPolicy.setInternet(getApplication(), on)
        if (on) {
            fetchArtistPictures()
        } else if (artistFetch?.isActive == true) {
            artistFetch?.cancel()
            artistFetchStatus = "Stopped: internet access is off"
        }
    }

    fun setBluetoothApiAccess(on: Boolean) {
        NetPolicy.setBluetoothApi(getApplication(), on)
        if (!on) bluetooth.stop()
    }

    fun setLocalApiAccess(on: Boolean) {
        NetPolicy.setLocalApi(getApplication(), on)
        if (!on) web.stop()
    }

    /** The cover for a playing song: its album's custom image if there is one, else the song's own. */
    fun songArt(song: Song): ArtSource =
        LibraryGrouping.albumKey(song)?.let { customArt["album:$it"] }?.let { ArtSource.FromImage(it) }
            ?: ownArt(song)

    /** A song's own cover: from the file, or for Jellyfin songs the server's album image. */
    private fun ownArt(song: Song): ArtSource =
        if (Jellyfin.isJellyfin(song)) {
            jellyfinArtIds[song.uri.toString()]?.let { ArtSource.FromJellyfin(it) } ?: ArtSource.FromSong(song)
        } else {
            ArtSource.FromSong(song)
        }

    // ---- Jellyfin -----------------------------------------------------------------

    private fun loadJellyfin() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val lib = withContext(Dispatchers.IO) {
                Jellyfin.init(app)
                Jellyfin.loadCached(app)
            }
            jellyfinAccount = Jellyfin.signedIn
            jellyfinLocked = Jellyfin.isLocked()
            yubikey.refresh() // watches USB for a trusted key while locked
            if (lib != null) applyJellyfin(lib)
            // Refresh in the background when signed in and the copy is missing or over 12 hours old.
            if (Jellyfin.account != null && (lib == null || System.currentTimeMillis() - lib.syncedAt > JELLYFIN_STALE_MS)) {
                syncJellyfin()
            }
        }
    }

    /** A trusted YubiKey unlocked the vault: start Jellyfin. */
    private fun onJellyfinUnlocked() {
        if (!Jellyfin.unlock()) return
        jellyfinLocked = false
        if (jellyfinStatus.startsWith("Signed in for now")) jellyfinStatus = ""
        viewModelScope.launch {
            val lib = withContext(Dispatchers.IO) { Jellyfin.loadCached(getApplication()) }
            if (lib != null) applyJellyfin(lib)
            if (lib == null || System.currentTimeMillis() - lib.syncedAt > JELLYFIN_STALE_MS) syncJellyfin()
        }
    }

    /** De-trusts a YubiKey; shows why if it can't (last key while locked). */
    fun detrustKey(id: String) {
        val error = TokenVault.removeKey(id)
        if (error != null) toast(error)
        // No keys left: the token is plain again; make sure Jellyfin runs.
        if (!TokenVault.isProtected && jellyfinLocked) onJellyfinUnlocked()
        yubikey.refresh()
    }

    /** "Lock now": forget the Jellyfin token until a trusted YubiKey is plugged in again. */
    fun lockJellyfin() {
        jellyfinJob?.cancel()
        jellyfinBusy = false
        Jellyfin.lock()
        jellyfinLocked = Jellyfin.isLocked()
        if (jellyfinLocked) applyJellyfin(Jellyfin.Library(emptyList(), emptyList(), emptyMap(), 0L))
        yubikey.refresh()
    }

    fun jellyfinLastServer(): String = Jellyfin.lastServer(getApplication())

    fun signInJellyfin(server: String, user: String, password: String) {
        if (jellyfinBusy) return
        jellyfinBusy = true
        jellyfinStatus = "Signing in..."
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Jellyfin.signIn(getApplication(), server, user, password) }
            }
            jellyfinBusy = false
            result.onSuccess {
                jellyfinAccount = Jellyfin.signedIn
                jellyfinLocked = false
                jellyfinStatus = if (TokenVault.isLocked()) {
                    "Signed in for now. Plug in a trusted YubiKey to keep this sign-in after the app closes."
                } else {
                    ""
                }
                yubikey.refresh()
                syncJellyfin()
            }.onFailure { jellyfinStatus = it.message ?: "Sign-in failed" }
        }
    }

    fun signOutJellyfin() {
        jellyfinJob?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Jellyfin.signOut(getApplication()) }
            jellyfinAccount = null
            jellyfinLocked = false
            yubikey.refresh()
            jellyfinBusy = false
            jellyfinStatus = ""
            applyJellyfin(Jellyfin.Library(emptyList(), emptyList(), emptyMap(), 0L))
        }
    }

    fun syncJellyfin() {
        val acc = Jellyfin.account ?: return
        if (jellyfinJob?.isActive == true) return
        jellyfinBusy = true
        jellyfinStatus = "Connecting to ${acc.serverName}..."
        jellyfinJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Jellyfin.sync(getApplication(), acc) { msg -> viewModelScope.launch { jellyfinStatus = msg } }
                }
            }
            jellyfinBusy = false
            result.onSuccess {
                applyJellyfin(it)
                jellyfinStatus = ""
            }.onFailure { jellyfinStatus = "Sync failed: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    private fun applyJellyfin(lib: Jellyfin.Library) {
        jellyfinArtIds = lib.artIds
        jellyfinSongs = lib.songs
        jellyfinPlaylists = lib.playlists
        jellyfinSyncedAt = lib.syncedAt
        rebuildJellyfinIndex()
    }

    /** Albums and artists for the Jellyfin section, with the same artist splitting as local songs. */
    private fun rebuildJellyfinIndex() {
        val s = jellyfinSongs
        val splitter = ArtistSplitter(delimiters, whitelist)
        viewModelScope.launch {
            val (al, ar) = withContext(Dispatchers.Default) {
                LibraryGrouping.albums(s) to LibraryGrouping.artists(s, splitter)
            }
            if (s === jellyfinSongs) {
                jellyfinAlbums = al
                jellyfinArtists = ar
                fetchArtistPictures() // new names to look up (does nothing off Wi-Fi)
            }
        }
    }

    fun clearArtworkCache() {
        viewModelScope.launch(Dispatchers.IO) {
            Artwork.clearCache(getApplication<Application>())
            LyricsLoader.clear()
        }
        toast("Artwork cache cleared")
    }

    // ---- write access, playlists and deleting -------------------------------------

    /** True when the app may change files in this media folder. */
    fun canWrite(folderUri: String): Boolean {
        if (permissionVersion < 0) return false // reading it makes callers recompose when it changes
        return getApplication<Application>().contentResolver.persistedUriPermissions
            .any { it.uri.toString() == folderUri && it.isWritePermission }
    }

    /** Playlists that songs can be added to (.pls files can't be yet). */
    val editablePlaylists: List<Playlist>
        get() = playlists.filterNot { it.uri.toString().endsWith(".pls", ignoreCase = true) }

    fun addToPlaylist(song: Song, playlist: Playlist) {
        if (Jellyfin.isJellyfin(song)) {
            toast("Not available for Jellyfin songs")
            return
        }
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { PlaylistWriter.append(getApplication<Application>(), playlist, song) }.exceptionOrNull()
            }
            if (failure != null) {
                toast(writeError(failure))
                return@launch
            }
            val updated = playlist.copy(songs = playlist.songs + song)
            playlists = playlists.map { if (it.uri == playlist.uri) updated else it }
            withContext(Dispatchers.IO) {
                db.appendPlaylistEntry(playlist.uri.toString(), song.uri.toString(), updated.songs.size - 1)
            }
            toast("Added to ${playlist.name}")
        }
    }

    /** Creates a new .m3u8 playlist in the song's media folder, starting with [first]. */
    fun createPlaylist(name: String, first: Song) {
        if (Jellyfin.isJellyfin(first)) {
            toast("Not available for Jellyfin songs")
            return
        }
        val clean = name.replace(Regex("""[\\/:*?"<>|]"""), "").trim()
        if (clean.isEmpty()) {
            toast("Enter a playlist name")
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { PlaylistWriter.create(getApplication<Application>(), first.rootUri, clean, first) }
            }
            val created = result.getOrElse {
                toast(writeError(it))
                return@launch
            }
            playlists = (playlists + created).sortedBy { it.name.lowercase() }
            withContext(Dispatchers.IO) { db.insertPlaylist(created) }
            rebuildDerived()
            toast("Created $clean")
        }
    }

    /** Deletes the song's file from the device and removes it everywhere in the app. */
    fun deleteSong(song: Song) {
        if (Jellyfin.isJellyfin(song)) {
            toast("Not available for Jellyfin songs")
            return
        }
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    val deleted = DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, song.uri)
                    if (!deleted) throw IllegalStateException("The file could not be deleted")
                }.exceptionOrNull()
            }
            if (failure != null) {
                toast(writeError(failure))
                return@launch
            }
            playback.forget(song)
            songs = songs.filter { it.uri != song.uri }
            playlists = playlists.map { p ->
                if (p.songs.any { it.uri == song.uri }) p.copy(songs = p.songs.filter { it.uri != song.uri }) else p
            }
            SiblingFiles.clear()
            withContext(Dispatchers.IO) { db.deleteSong(song.uri.toString()) }
            rebuildDerived()
            toast("Deleted ${song.fileName}")
        }
    }

    private fun writeError(e: Throwable): String = when (e) {
        is SecurityException -> "No permission to change files here. In Settings, tap \"Allow changes\" for this folder."
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun toast(text: String) {
        Toast.makeText(getApplication<Application>(), text, Toast.LENGTH_SHORT).show()
    }

    // ---- artist splitting -------------------------------------------------------

    fun addDelimiter(d: String) {
        if (d.isEmpty() || delimiters.any { it.equals(d, ignoreCase = true) }) return
        delimiters = delimiters + d
        saveSplitSettings()
    }

    fun removeDelimiter(d: String) {
        delimiters = delimiters - d
        saveSplitSettings()
    }

    fun resetDelimiters() {
        delimiters = ArtistSplitter.DEFAULT_DELIMITERS
        saveSplitSettings()
    }

    fun addWhitelisted(name: String) {
        val n = name.trim()
        if (n.isEmpty() || whitelist.any { it.equals(n, ignoreCase = true) }) return
        whitelist = whitelist + n
        saveSplitSettings()
    }

    fun removeWhitelisted(name: String) {
        whitelist = whitelist - name
        saveSplitSettings()
    }

    /** How [tag] would be split with the current settings. */
    fun previewSplit(tag: String): List<String> = ArtistSplitter(delimiters, whitelist).split(tag)

    private fun saveSplitSettings() {
        settings.edit()
            .putString(KEY_DELIMITERS, JSONArray(delimiters).toString())
            .putString(KEY_WHITELIST, JSONArray(whitelist).toString())
            .apply()
        rebuildDerived()
    }

    private fun loadList(key: String): List<String>? {
        val raw = settings.getString(key, null) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        }.getOrNull()
    }

    // ---- scanning ---------------------------------------------------------------

    /** Quick rescan when [full] is false (new/changed files only), otherwise re-read everything. */
    fun rescan(full: Boolean) {
        cancelScan()
        if (folders.isEmpty()) return
        val previousSongs = songs
        val previousPlaylists = playlists
        val job = viewModelScope.launch {
            scanning = true
            error = null
            scanStatus = if (full) "Starting full rescan..." else "Starting quick rescan..."
            scanProgress = null
            try {
                val app = getApplication<Application>()
                val result = withContext(Dispatchers.IO) {
                    val r = LibraryScanner.scan(app, db.folders(), previousSongs, previousPlaylists, full) { status, progress ->
                        scanStatus = status
                        scanProgress = progress
                    }
                    scanStatus = "Saving..."
                    db.saveLibrary(r.songs, r.playlists, r.subdirs, full)
                    r
                }
                reload()
                error = result.errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
                scanStatus = "Done: ${songs.size} songs, ${playlists.size} playlists"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                scanStatus = "Scan failed"
            } finally {
                // Only the scan that is still current may clear the flags.
                if (scanJob === coroutineContext[Job]) {
                    scanning = false
                    scanProgress = null
                    scanJob = null
                }
            }
        }
        scanJob = job
    }

    fun stopScan() {
        if (!scanning) return
        cancelScan()
        scanStatus = "Scan stopped; nothing was changed"
    }

    private fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        scanning = false
        scanProgress = null
    }

    // ---- internals ----------------------------------------------------------------

    private class ScanMeta(val subdirs: Int, val lastScan: Long, val lastScanFull: Boolean)

    private class Loaded(
        val folders: List<String>,
        val songs: List<Song>,
        val playlists: List<Playlist>,
        val playStats: Map<String, PlayStat>,
        val meta: ScanMeta,
    )

    private class Derived(
        val albums: List<AlbumEntry>,
        val artists: List<ArtistEntry>,
        val recentlyAdded: List<Song>,
        val longSongs: List<Song>,
        val mostPlayed: List<Song>,
        val stats: LibraryStats,
    )

    /** Loads everything from the database, then rebuilds the derived lists and stats. */
    private suspend fun reload() {
        val data = withContext(Dispatchers.IO) {
            val s = db.loadSongs()
            Loaded(
                folders = db.folders(),
                songs = s,
                playlists = db.loadPlaylists(s.associateBy { it.uri.toString() }),
                playStats = db.loadPlayStats(),
                meta = ScanMeta(
                    subdirs = db.meta(LibraryDb.KEY_SUBDIRS)?.toIntOrNull() ?: 0,
                    lastScan = db.meta(LibraryDb.KEY_LAST_SCAN)?.toLongOrNull() ?: 0L,
                    lastScanFull = db.meta(LibraryDb.KEY_LAST_SCAN_FULL) == "1",
                ),
            )
        }
        folders = data.folders
        songs = data.songs
        playlists = data.playlists
        playStats = data.playStats
        scanMeta = data.meta
        customArt = withContext(Dispatchers.IO) { db.loadCustomArt() }
        rebuildDerived()
    }

    /** Recomputes albums, artists, smart lists and stats in the background; a newer request cancels an older one. */
    private fun rebuildDerived() {
        rebuildJellyfinIndex() // artist splitting applies to Jellyfin too
        val s = songs
        val p = playlists
        val counts = playStats
        val meta = scanMeta
        val splitter = ArtistSplitter(delimiters, whitelist)
        rebuildJob?.cancel()
        rebuildJob = viewModelScope.launch {
            val d = withContext(Dispatchers.Default) {
                val albumList = LibraryGrouping.albums(s)
                val artistList = LibraryGrouping.artists(s, splitter)
                Derived(
                    albums = albumList,
                    artists = artistList,
                    recentlyAdded = s.sortedByDescending { it.dateAdded }.take(RECENTLY_ADDED_LIMIT),
                    longSongs = s.filter { it.durationMs > LONG_SONG_MS }.sortedByDescending { it.durationMs },
                    mostPlayed = computeMostPlayed(s, counts),
                    stats = LibraryStats(
                        songs = s.size,
                        albums = albumList.size,
                        artists = artistList.size,
                        playlists = p.size,
                        subdirs = meta.subdirs,
                        totalBytes = s.sumOf { it.sizeBytes },
                        totalDurationMs = s.sumOf { it.durationMs },
                        lastScan = meta.lastScan,
                        lastScanFull = meta.lastScanFull,
                    ),
                )
            }
            albums = d.albums
            artists = d.artists
            fetchArtistPictures()
            recentlyAdded = d.recentlyAdded
            longSongs = d.longSongs
            mostPlayed = d.mostPlayed
            stats = d.stats
        }
    }

    private fun computeMostPlayed(s: List<Song>, counts: Map<String, PlayStat>): List<Song> =
        s.mapNotNull { song -> counts[song.uri.toString()]?.let { song to it } }
            .sortedWith(compareByDescending<Pair<Song, PlayStat>> { it.second.plays }.thenByDescending { it.second.lastPlayed })
            .take(MOST_PLAYED_LIMIT)
            .map { it.first }

    /** Earlier versions kept a single folder in SharedPreferences; move it into the database. */
    private fun migrateOldFolderSetting() {
        val prefs = getApplication<Application>().getSharedPreferences("library", Context.MODE_PRIVATE)
        val old = prefs.getString("folder_uri", null) ?: return
        db.addFolder(old)
        prefs.edit().remove("folder_uri").apply()
    }

    override fun onCleared() {
        bluetooth.stop()
        runCatching { connectivity.unregisterNetworkCallback(wifiCallback) }
        web.stop()
        yubikey.stop()
        playback.release()
        db.close()
    }

    companion object {
        private const val JELLYFIN_STALE_MS = 12 * 60 * 60 * 1000L
        const val MOST_PLAYED_LIMIT = 100
        const val RECENTLY_ADDED_LIMIT = 250
        const val LONG_SONG_MS = 10 * 60_000L
        private const val KEY_DELIMITERS = "artist_delimiters"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_WHITELIST = "artist_whitelist"
    }
}

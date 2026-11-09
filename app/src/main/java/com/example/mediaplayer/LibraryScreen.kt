package com.example.mediaplayer

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Surface
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.lifecycle.viewmodel.compose.viewModel

/** Entries of the library menu. Only the implemented ones can be opened. */
enum class LibrarySection(val label: String, val implemented: Boolean) {
    ARTISTS("Artists", true),
    ALBUMS("Albums", true),
    SONGS("Songs", true),
    PLAYLISTS("Playlists", true),
    MOST_PLAYED("Most played", true),
    RECENTLY_ADDED("Recently added", true),
    LONG("Long (over 10 minutes)", true),
    JELLYFIN("Jellyfin", true),
}

/** Back from [openKey]: Jellyfin keys are paths ("albums/<key>" -> "albums" -> menu); others go to the section. */
private fun parentKey(section: LibrarySection?, openKey: String): String? =
    if (section == LibrarySection.JELLYFIN && '/' in openKey) openKey.substringBefore('/') else null

@Composable
fun LibraryScreen(vm: LibraryViewModel = viewModel()) {
    val context = LocalContext.current
    val playback = vm.playback
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var section by rememberSaveable { mutableStateOf<LibrarySection?>(null) }
    // The open item inside a section (playlist URI, album key or artist key).
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    // Which album/playlist a custom cover is being picked for ("album:<key>" / "playlist:<uri>").
    var pendingArtKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showEq by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchJellyfin by rememberSaveable { mutableStateOf(false) }
    // An artist/album opened from search: going back returns to the results.
    var fromSearch by rememberSaveable { mutableStateOf(false) }
    val backToSearch = {
        fromSearch = false
        section = null
        openKey = null
        showSearch = true
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Keep access across app restarts. Ask for write access too (adding to
            // playlists, deleting files); fall back to read-only if it isn't granted.
            val resolver = context.contentResolver
            val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { resolver.takePersistableUriPermission(uri, readWrite) }
                .onFailure { runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            vm.addFolder(uri)
        }
    }
    val addFolder: () -> Unit = {
        StayOnTop.allowLeaving() // the folder picker is another app
        folderPicker.launch(null)
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val key = pendingArtKey
        pendingArtKey = null
        if (uri != null && key != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.setCustomArt(key, uri.toString())
        }
    }
    val pickImage: (String) -> Unit = { key ->
        pendingArtKey = key
        StayOnTop.allowLeaving()
        imagePicker.launch(arrayOf("image/*"))
    }

    val openSection: (LibrarySection?) -> Unit = {
        if (fromSearch) backToSearch() else {
            section = it
            openKey = null
        }
    }

    // Stay on top: Back on the main screen does nothing (closing would stop the music and it would reopen anyway).
    // Registered first, so the handler below wins whenever there is somewhere to go back to.
    BackHandler(enabled = StayOnTop.on) {}

    BackHandler(enabled = showNowPlaying || showSettings || showSearch || showEq || tab == 1 || section != null) {
        when {
            showNowPlaying -> showNowPlaying = false
            showSettings -> showSettings = false
            showEq -> showEq = false
            showSearch -> showSearch = false
            tab == 1 -> tab = 0
            fromSearch -> backToSearch()
            openKey != null -> openKey = parentKey(section, openKey!!)
            else -> section = null
        }
    }

    // Stop clears the current song: leave the now playing screen.
    LaunchedEffect(playback.nowPlaying) {
        if (playback.nowPlaying == null) showNowPlaying = false
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        when {
            showNowPlaying -> NowPlayingScreen(
                vm = vm,
                onClose = { showNowPlaying = false },
                onGoToAlbum = { key ->
                    showNowPlaying = false
                    showSearch = false
                    fromSearch = false
                    tab = 0
                    section = LibrarySection.ALBUMS
                    openKey = key
                },
                onGoToArtist = { key ->
                    showNowPlaying = false
                    showSearch = false
                    fromSearch = false
                    tab = 0
                    section = LibrarySection.ARTISTS
                    openKey = key
                },
                onOpenQueue = {
                    showNowPlaying = false
                    tab = 1
                },
            )

            showSettings -> Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SettingsScreen(
                    vm = vm,
                    onAddFolder = addFolder,
                    // Picking the same folder again grants write access; the folder list doesn't change.
                    onRegrantFolder = { uri ->
                        StayOnTop.allowLeaving()
                        folderPicker.launch(Uri.parse(uri))
                    },
                    onBack = { showSettings = false },
                )
            }

            showEq -> Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { showEq = false }) { Text("← Back") }
                    Text("Equalizer", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    StayOnTopToggle()
                }
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    EqualizerSettings(playback.outputMode)
                }
            }

            showSearch -> Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SearchScreen(
                    vm = vm,
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    jellyfin = searchJellyfin,
                    onJellyfinChange = { searchJellyfin = it },
                    onBack = { showSearch = false },
                    onOpenArtist = { key, jelly ->
                        showSearch = false
                        fromSearch = true
                        tab = 0
                        section = if (jelly) LibrarySection.JELLYFIN else LibrarySection.ARTISTS
                        openKey = if (jelly) "artists/$key" else key
                    },
                    onOpenAlbum = { key, jelly ->
                        showSearch = false
                        fromSearch = true
                        tab = 0
                        section = if (jelly) LibrarySection.JELLYFIN else LibrarySection.ALBUMS
                        openKey = if (jelly) "albums/$key" else key
                    },
                )
            }

            else -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (tab == 1) {
                        QueueTab(playback, art = { vm.songArt(it) })
                    } else {
                        LibraryContent(
                            vm = vm,
                            section = section,
                            openKey = openKey,
                            onOpenSection = openSection,
                            onOpenKey = { if (fromSearch) backToSearch() else openKey = it },
                            onAddFolder = addFolder,
                            onPickImage = pickImage,
                        )
                    }
                }
            }
        }

        if (!showNowPlaying) {
            NowPlayingBar(
                playback = playback,
                artFor = { vm.songArt(it) },
                onOpen = { showNowPlaying = true },
            )
        }
        // Library / Queue view: the navigation bar at the very bottom.
        if (!showNowPlaying && !showSettings && !showSearch && !showEq) {
            NavBar(
                tab = tab,
                onLibrary = { tab = 0 },
                onQueue = { tab = 1 },
                onEq = { showEq = true },
                onSearch = { showSearch = true },
                onSettings = { showSettings = true },
            )
        }
    }
}

/**
 * Bottom navigation: Library, Queue, Search, Settings spaced evenly (icons from assets/icons/nav_*.png,
 * else Material icons), with the Stay on top pin at the end.
 */
@Composable
private fun NavBar(
    tab: Int,
    onLibrary: () -> Unit,
    onQueue: () -> Unit,
    onEq: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                NavIcon(Icons.Filled.Home, "nav_library", "Library", selected = tab == 0, onClick = onLibrary)
                NavIcon(Icons.AutoMirrored.Filled.List, "nav_queue", "Queue", selected = tab == 1, onClick = onQueue)
                NavIcon(Icons.Filled.Star, "nav_eq", "Equalizer", selected = false, onClick = onEq)
                NavIcon(Icons.Filled.Search, "nav_search", "Search", selected = false, onClick = onSearch)
                NavIcon(Icons.Filled.Settings, "nav_settings", "Settings", selected = false, onClick = onSettings)
            }
            StayOnTopToggle()
        }
    }
}

@Composable
private fun NavIcon(vector: androidx.compose.ui.graphics.vector.ImageVector, file: String, label: String, selected: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        TransportIcon(
            vector, file, contentDescription = label, size = 38.dp,
            imageAlpha = if (selected) 1f else 0.5f,
            tint = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibraryContent(
    vm: LibraryViewModel,
    section: LibrarySection?,
    openKey: String?,
    onOpenSection: (LibrarySection?) -> Unit,
    onOpenKey: (String?) -> Unit,
    onAddFolder: () -> Unit,
    onPickImage: (String) -> Unit,
) {
    val playback = vm.playback
    val backToMenu = { onOpenSection(null) }
    val backToSection = { onOpenKey(null) }
    val rowArt: (Song) -> ArtSource? = { vm.songArt(it) }

    when (section) {
        null -> LibraryHome(vm, onAddFolder = onAddFolder, onOpen = onOpenSection)

        LibrarySection.SONGS -> SongList(
            title = "Songs",
            status = when {
                vm.scanning -> vm.scanStatus
                vm.folders.isEmpty() -> "No music folders yet. Add one in Settings."
                else -> "${"%,d".format(vm.songs.size)} songs"
            },
            songs = vm.songs,
            playback = playback,
            onBack = backToMenu,
            rowArt = rowArt,
        )

        LibrarySection.PLAYLISTS -> {
            val open = openKey?.let { k -> vm.playlists.firstOrNull { it.uri.toString() == k } }
            if (open != null) {
                SongList(
                    title = open.name,
                    status = playlistSummary(open),
                    songs = open.songs,
                    playback = playback,
                    onBack = backToSection,
                    art = vm.playlistArt(open),
                    rowArt = rowArt,
                )
            } else {
                CoverGrid(
                    title = "Playlists",
                    emptyText = "No playlists found. .m3u, .m3u8 and .pls files inside your music folders are picked up when the library is scanned.",
                    entries = vm.playlists,
                    key = { it.uri.toString() },
                    label = { it.name },
                    subtitle = { playlistSummary(it) },
                    art = { vm.playlistArt(it) },
                    actions = { p ->
                        val artKey = "playlist:" + p.uri
                        buildList {
                            add(MenuAction("Play") { playback.playAll(p.songs, shuffled = false) })
                            add(MenuAction("Shuffle") { playback.playAll(p.songs, shuffled = true) })
                            add(MenuAction("Set custom image...") { onPickImage(artKey) })
                            if (artKey in vm.customArt) add(MenuAction("Use first song's cover") { vm.setCustomArt(artKey, null) })
                        }
                    },
                    onOpen = { onOpenKey(it.uri.toString()) },
                    onBack = backToMenu,
                )
            }
        }

        LibrarySection.ALBUMS -> {
            val open = openKey?.let { k -> vm.albums.firstOrNull { it.key == k } }
            if (open != null) {
                SongList(
                    title = open.title,
                    status = albumSummary(open),
                    songs = open.songs,
                    playback = playback,
                    onBack = backToSection,
                    art = vm.albumArt(open),
                    subtitle = { s ->
                        listOfNotNull(
                            if (s.trackNumber > 0) "Track ${s.trackNumber}" else null,
                            s.artist,
                        ).joinToString(" · ").ifEmpty { s.fileName }
                    },
                )
            } else {
                CoverGrid(
                    title = "Albums",
                    emptyText = "No albums yet. Albums come from the album tags read during a scan.",
                    entries = vm.albums,
                    key = { it.key },
                    label = { it.title },
                    subtitle = { a -> listOfNotNull(a.artist, if (a.year > 0) a.year.toString() else null).joinToString(" · ") },
                    art = { vm.albumArt(it) },
                    actions = { a ->
                        val artKey = "album:" + a.key
                        buildList {
                            add(MenuAction("Play") { playback.playAll(a.songs, shuffled = false) })
                            add(MenuAction("Shuffle") { playback.playAll(a.songs, shuffled = true) })
                            add(MenuAction("Set custom image...") { onPickImage(artKey) })
                            if (artKey in vm.customArt) add(MenuAction("Use song cover") { vm.setCustomArt(artKey, null) })
                        }
                    },
                    onOpen = { onOpenKey(it.key) },
                    onBack = backToMenu,
                )
            }
        }

        LibrarySection.ARTISTS -> {
            val open = openKey?.let { k -> vm.artists.firstOrNull { it.key == k } }
            if (open != null) {
                SongList(
                    open.name, artistSummary(open), open.songs, playback,
                    onBack = backToSection, art = vm.artistArt(open), rowArt = rowArt,
                )
            } else {
                GroupList(
                    title = "Artists",
                    emptyText = "No artists yet. Artists come from the artist tags read during a scan.",
                    entries = vm.artists,
                    key = { it.key },
                    label = { it.name },
                    subtitle = { artistSummary(it) },
                    onOpen = { onOpenKey(it.key) },
                    onBack = backToMenu,
                    art = { vm.artistArt(it) },
                    actions = { artistActions(vm, it, onPickImage) },
                )
            }
        }

        LibrarySection.MOST_PLAYED -> SongList(
            title = "Most played",
            status = if (vm.mostPlayed.isEmpty()) {
                "Nothing counted yet. A song counts as played once half of it (at most 4 minutes) has played."
            } else {
                "Top ${vm.mostPlayed.size} by play count"
            },
            songs = vm.mostPlayed,
            playback = playback,
            onBack = backToMenu,
            rowArt = rowArt,
            subtitle = { s ->
                val n = vm.playCountOf(s)
                "$n play${if (n == 1) "" else "s"} · ${s.displaySubtitle}"
            },
        )

        LibrarySection.RECENTLY_ADDED -> SongList(
            title = "Recently added",
            status = "Newest ${vm.recentlyAdded.size} songs",
            songs = vm.recentlyAdded,
            playback = playback,
            onBack = backToMenu,
            rowArt = rowArt,
            subtitle = { s -> "${shortDateText(s.dateAdded)} · ${s.displaySubtitle}" },
        )

        LibrarySection.JELLYFIN -> JellyfinContent(vm, openKey, onOpenKey, onBack = backToMenu, rowArt = rowArt)

        LibrarySection.LONG -> SongList(
            title = "Long",
            status = "${"%,d".format(vm.longSongs.size)} songs over 10 minutes, longest first",
            songs = vm.longSongs,
            playback = playback,
            onBack = backToMenu,
            rowArt = rowArt,
            subtitle = { s -> "${songLengthText(s.durationMs)} · ${s.displaySubtitle}" },
        )
    }
}

/**
 * The Jellyfin section: its own Albums / Artists / Songs / Playlists from the server.
 * [openKey] is a path: null (this menu), "albums", "albums/<album key>", "artists/<key>", ...
 */
@Composable
private fun JellyfinContent(
    vm: LibraryViewModel,
    openKey: String?,
    onOpenKey: (String?) -> Unit,
    onBack: () -> Unit,
    rowArt: (Song) -> ArtSource?,
) {
    val playback = vm.playback
    val area = openKey?.substringBefore('/')
    val item = openKey?.substringAfter('/', "")?.ifEmpty { null }
    val up = { onOpenKey(area) }
    val toMenu = { onOpenKey(null) }

    when (area) {
        null -> Column(modifier = Modifier.fillMaxSize()) {
            ListHeader(title = vm.jellyfinAccount?.serverName ?: "Jellyfin", onBack = onBack)
            val acc = vm.jellyfinAccount
            when {
                acc == null -> StatusText("Not signed in. Sign in to your server in Settings → Jellyfin.")
                vm.jellyfinLocked -> StatusText(vm.yubikey.prompt ?: "Locked: plug in a trusted YubiKey.")
                vm.jellyfinStatus.isNotEmpty() -> StatusText(vm.jellyfinStatus)
                vm.jellyfinSongs.isEmpty() -> StatusText("No music found on the server yet. Sync in Settings → Jellyfin.")
            }
            if (acc != null && !vm.jellyfinLocked) {
                MenuRow("Albums", "${"%,d".format(vm.jellyfinAlbums.size)} albums", true, { onOpenKey("albums") }, icon = "menu_albums")
                MenuRow("Artists", "${"%,d".format(vm.jellyfinArtists.size)} artists", true, { onOpenKey("artists") }, icon = "menu_artists")
                MenuRow("Songs", "${"%,d".format(vm.jellyfinSongs.size)} songs", true, { onOpenKey("songs") }, icon = "menu_songs")
                MenuRow("Playlists", "${vm.jellyfinPlaylists.size} playlists", true, { onOpenKey("playlists") }, icon = "menu_playlists")
            }
        }

        "songs" -> SongList(
            title = "Songs",
            status = "${"%,d".format(vm.jellyfinSongs.size)} songs",
            songs = vm.jellyfinSongs.sortedBy { it.displayTitle.lowercase() },
            playback = playback,
            onBack = toMenu,
            rowArt = rowArt,
        )

        "albums" -> {
            val open = item?.let { k -> vm.jellyfinAlbums.firstOrNull { it.key == k } }
            if (open != null) {
                SongList(
                    title = open.title,
                    status = albumSummary(open),
                    songs = open.songs,
                    playback = playback,
                    onBack = up,
                    art = vm.albumArt(open),
                    subtitle = { s ->
                        listOfNotNull(if (s.trackNumber > 0) "Track ${s.trackNumber}" else null, s.artist)
                            .joinToString(" · ").ifEmpty { s.fileName }
                    },
                )
            } else {
                CoverGrid(
                    title = "Albums",
                    emptyText = "No albums on the server.",
                    entries = vm.jellyfinAlbums,
                    key = { it.key },
                    label = { it.title },
                    subtitle = { a -> listOfNotNull(a.artist, if (a.year > 0) a.year.toString() else null).joinToString(" · ") },
                    art = { vm.albumArt(it) },
                    actions = { a ->
                        listOf(
                            MenuAction("Play") { playback.playAll(a.songs, shuffled = false) },
                            MenuAction("Shuffle") { playback.playAll(a.songs, shuffled = true) },
                        )
                    },
                    onOpen = { onOpenKey("albums/" + it.key) },
                    onBack = toMenu,
                )
            }
        }

        "artists" -> {
            val open = item?.let { k -> vm.jellyfinArtists.firstOrNull { it.key == k } }
            if (open != null) {
                SongList(
                    open.name, artistSummary(open), open.songs, playback,
                    onBack = up, art = vm.artistArt(open), rowArt = rowArt,
                )
            } else {
                GroupList(
                    title = "Artists",
                    emptyText = "No artists on the server.",
                    entries = vm.jellyfinArtists,
                    key = { it.key },
                    label = { it.name },
                    subtitle = { artistSummary(it) },
                    onOpen = { onOpenKey("artists/" + it.key) },
                    onBack = toMenu,
                    art = { vm.artistArt(it) },
                    actions = { artistActions(vm, it, onPickImage = null) },
                )
            }
        }

        else -> { // "playlists"
            val open = item?.let { k -> vm.jellyfinPlaylists.firstOrNull { it.uri.toString() == k } }
            if (open != null) {
                SongList(
                    title = open.name,
                    status = playlistSummary(open),
                    songs = open.songs,
                    playback = playback,
                    onBack = up,
                    art = vm.playlistArt(open),
                    rowArt = rowArt,
                )
            } else {
                CoverGrid(
                    title = "Playlists",
                    emptyText = "No playlists on the server.",
                    entries = vm.jellyfinPlaylists,
                    key = { it.uri.toString() },
                    label = { it.name },
                    subtitle = { playlistSummary(it) },
                    art = { vm.playlistArt(it) },
                    actions = { p ->
                        listOf(
                            MenuAction("Play") { playback.playAll(p.songs, shuffled = false) },
                            MenuAction("Shuffle") { playback.playAll(p.songs, shuffled = true) },
                        )
                    },
                    onOpen = { onOpenKey("playlists/" + it.uri) },
                    onBack = toMenu,
                )
            }
        }
    }
}

private fun playlistSummary(pl: Playlist): String =
    "${pl.songs.size} songs" + if (pl.missing > 0) " · ${pl.missing} not found" else ""

internal fun albumSummary(a: AlbumEntry): String =
    listOfNotNull(a.artist, if (a.year > 0) a.year.toString() else null, "${a.songs.size} songs")
        .joinToString(" · ")

internal fun artistSummary(a: ArtistEntry): String =
    "${a.songs.size} songs" + if (a.albumCount > 0) " · ${a.albumCount} albums" else ""

// ---- menu ------------------------------------------------------------------

@Composable
private fun LibraryHome(vm: LibraryViewModel, onAddFolder: () -> Unit, onOpen: (LibrarySection) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        when {
            vm.loading -> item { StatusText("Loading library...") }
            vm.folders.isEmpty() -> item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("No music folders yet.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onAddFolder, modifier = Modifier.padding(top = 8.dp)) { Text("Add folder") }
                }
            }
            vm.scanning -> item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(vm.scanStatus, style = MaterialTheme.typography.bodyMedium)
                    val p = vm.scanProgress
                    if (p != null) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
            vm.error != null -> item { StatusText("The last scan had problems. See Settings.") }
        }
        item { HorizontalDivider() }
        items(LibrarySection.entries) { s ->
            // A feature left out of this build stays listed, greyed out, with how to get it.
            val builtIn = s != LibrarySection.JELLYFIN || BuildConfig.FEATURE_JELLYFIN
            val subtitle = when (s) {
                LibrarySection.SONGS -> "${"%,d".format(vm.songs.size)} songs"
                LibrarySection.PLAYLISTS -> "${vm.playlists.size} playlists"
                LibrarySection.ALBUMS -> "${"%,d".format(vm.albums.size)} albums"
                LibrarySection.ARTISTS -> "${"%,d".format(vm.artists.size)} artists"
                LibrarySection.MOST_PLAYED -> if (vm.mostPlayed.isEmpty()) "Nothing played yet" else "Top ${vm.mostPlayed.size}"
                LibrarySection.RECENTLY_ADDED -> "Newest ${vm.recentlyAdded.size}"
                LibrarySection.LONG -> "${"%,d".format(vm.longSongs.size)} songs"
                LibrarySection.JELLYFIN -> when {
                    !builtIn -> NOT_BUILT_IN
                    vm.jellyfinAccount == null -> "Not signed in: Settings → Jellyfin"
                    vm.jellyfinLocked -> "Locked: plug in a trusted YubiKey"
                    vm.jellyfinBusy -> "Syncing..."
                    else -> "${"%,d".format(vm.jellyfinSongs.size)} songs on ${vm.jellyfinAccount?.serverName}"
                }
            }
            MenuRow(
                label = s.label,
                subtitle = subtitle,
                enabled = s.implemented && builtIn,
                onClick = { onOpen(s) },
                icon = AppIcons.forSection(s),
            )
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(16.dp),
    )
}

/** A menu entry. With [icon], the icon from assets/icons/<icon>.png sits on the left. */
@Composable
internal fun MenuRow(label: String, subtitle: String, enabled: Boolean, onClick: () -> Unit, icon: String? = null) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AssetIcon(icon, size = 30.dp)
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---- grids of covers (albums, playlists) --------------------------------------------

@Composable
private fun <T> CoverGrid(
    title: String,
    emptyText: String,
    entries: List<T>,
    key: (T) -> String,
    label: (T) -> String,
    subtitle: (T) -> String,
    art: (T) -> ArtSource?,
    actions: (T) -> List<MenuAction>,
    onOpen: (T) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ListHeader(title = "$title (${"%,d".format(entries.size)})", onBack = onBack)
        if (entries.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            gridItems(entries, key = key) { e ->
                CoverTile(
                    label = label(e),
                    subtitle = subtitle(e),
                    art = art(e),
                    actions = { actions(e) },
                    onClick = { onOpen(e) },
                )
            }
        }
    }
}

/** A square cover with title and subtitle. Tap opens; long-press shows a menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverTile(
    label: String,
    subtitle: String,
    art: ArtSource?,
    actions: () -> List<MenuAction>,
    onClick: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
        ) {
            CoverImage(
                source = art,
                title = label,
                sizePx = 400,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            MenuItems(actions(), onDismiss = { menuOpen = false })
        }
    }
}

// ---- plain lists (artists) ------------------------------------------------------------

@Composable
private fun <T> GroupList(
    title: String,
    emptyText: String,
    entries: List<T>,
    key: (T) -> String,
    label: (T) -> String,
    subtitle: (T) -> String,
    onOpen: (T) -> Unit,
    onBack: () -> Unit,
    art: ((T) -> ArtSource?)? = null,
    actions: (T) -> List<MenuAction> = { emptyList() },
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ListHeader(title = "$title (${"%,d".format(entries.size)})", onBack = onBack)
        if (entries.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(entries, key = key) { e ->
                if (art != null) {
                    PictureRow(label(e), subtitle(e), art(e), actions = { actions(e) }, onClick = { onOpen(e) })
                } else {
                    MenuRow(label = label(e), subtitle = subtitle(e), enabled = true, onClick = { onOpen(e) })
                }
            }
        }
    }
}

/** A row with a small picture (artists). Tap opens; long-press shows [actions]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PictureRow(
    label: String,
    subtitle: String,
    art: ArtSource?,
    actions: () -> List<MenuAction>,
    onClick: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowCover(art, label)
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            MenuItems(actions(), onDismiss = { menuOpen = false })
        }
    }
}

/** Long-press actions for an artist. */
internal fun artistActions(vm: LibraryViewModel, a: ArtistEntry, onPickImage: ((String) -> Unit)?): List<MenuAction> {
    val playback = vm.playback
    val artKey = "artist:" + a.key
    return buildList {
        add(MenuAction("Play") { playback.playAll(a.songs, shuffled = false) })
        add(MenuAction("Shuffle") { playback.playAll(a.songs, shuffled = true) })
        if (onPickImage != null) add(MenuAction("Set custom image...") { onPickImage(artKey) })
        if (artKey in vm.customArt) add(MenuAction("Use downloaded or album picture") { vm.setCustomArt(artKey, null) })
    }
}

// ---- song lists -----------------------------------------------------------------

/**
 * A list of songs with Play all / Shuffle (and a cover when [art] is given).
 * Tapping a song plays it with this list as the source for the general queue.
 */
@Composable
private fun SongList(
    title: String,
    status: String,
    songs: List<Song>,
    playback: Playback,
    onBack: () -> Unit,
    art: ArtSource? = null,
    subtitle: (Song) -> String = { it.displaySubtitle },
    /** Small cover left of each song; null for lists where it would repeat (an album). */
    rowArt: ((Song) -> ArtSource?)? = null,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ListHeader(title = title, onBack = onBack)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (art != null) {
                CoverImage(source = art, title = title, sizePx = 400, modifier = Modifier.size(96.dp))
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { playback.playAll(songs, shuffled = false) }, enabled = songs.isNotEmpty()) {
                        Text("Play all")
                    }
                    OutlinedButton(onClick = { playback.playAll(songs, shuffled = true) }, enabled = songs.isNotEmpty()) {
                        Text("Shuffle")
                    }
                }
            }
        }
        // No item keys: a playlist may contain the same song twice.
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(songs) { _, song ->
                SongRow(
                    song = song,
                    subtitle = subtitle(song),
                    art = rowArt?.invoke(song),
                    showArt = rowArt != null,
                    isCurrent = song.uri == playback.nowPlaying?.uri,
                    onClick = { playback.playFromLibrary(song, songs) },
                    actions = listOf(
                        MenuAction("Play next") { playback.playNext(song) },
                        MenuAction("Play last") { playback.playLast(song) },
                    ),
                )
            }
        }
    }
}

@Composable
private fun ListHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SongRow(
    song: Song,
    subtitle: String,
    art: ArtSource?,
    showArt: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    actions: List<MenuAction>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = if (showArt) 12.dp else 16.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArt) RowCover(art, song.displayTitle)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RowMenu(actions)
    }
}

/** Shown on entries and settings for features left out with the -Pmg.no* build flags (see the README). */
internal const val NOT_BUILT_IN = "Not in this build: rebuild with network features"

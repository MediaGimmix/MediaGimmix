package com.example.mediaplayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Full-screen now playing view:
 *   [v]  track name  [...]
 *   cover (or lyrics)
 *   artist - album
 *   seek bar
 *   VIS  |<  play/pause  >|  LRC
 *   +                       trash
 */
@Composable
fun NowPlayingScreen(
    vm: LibraryViewModel,
    onClose: () -> Unit,
    onGoToAlbum: (String) -> Unit,
    onGoToArtist: (String) -> Unit,
    onOpenQueue: () -> Unit,
) {
    val playback = vm.playback
    val song = playback.nowPlaying
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showVis by rememberSaveable { mutableStateOf(false) }
    var visStyle by rememberSaveable { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            positionMs = playback.positionMs()
            durationMs = playback.durationMs()
            delay(200)
        }
    }

    // Remembered: the screen recomposes several times a second for the seek bar.
    val albumKey = remember(song?.uri, vm.albums) {
        song?.let { LibraryGrouping.albumKey(it) }?.takeIf { k -> vm.albums.any { it.key == k } }
    }
    val artistKey = remember(song?.uri, vm.artists) {
        song?.let { s ->
            vm.previewSplit(s.artist ?: s.albumArtist ?: "").firstOrNull()?.lowercase()
        }?.takeIf { k -> vm.artists.any { it.key == k } }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- top: back, title, menu
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Back to library")
            }
            Text(
                text = song?.displayTitle ?: "Nothing playing",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StayOnTopToggle()
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Text("⋯", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Go to album") },
                        leadingIcon = { AssetIcon(AppIcons.forAction("Go to album"), size = 22.dp) },
                        enabled = albumKey != null,
                        onClick = {
                            menuOpen = false
                            albumKey?.let(onGoToAlbum)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Go to artist") },
                        leadingIcon = { AssetIcon(AppIcons.forAction("Go to artist"), size = 22.dp) },
                        enabled = artistKey != null,
                        onClick = {
                            menuOpen = false
                            artistKey?.let(onGoToArtist)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Show queue") },
                        leadingIcon = { AssetIcon(AppIcons.forAction("Show queue"), size = 22.dp) },
                        onClick = {
                            menuOpen = false
                            onOpenQueue()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (playback.shuffle) "Shuffle: on" else "Shuffle: off") },
                        leadingIcon = { AssetIcon(AppIcons.forAction("Shuffle"), size = 22.dp) },
                        onClick = {
                            menuOpen = false
                            playback.changeShuffle(!playback.shuffle)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Song info") },
                        leadingIcon = { AssetIcon(AppIcons.forAction("Song info"), size = 22.dp) },
                        enabled = song != null,
                        onClick = {
                            menuOpen = false
                            showInfo = true
                        },
                    )
                }
            }
        }

        // ---- cover or lyrics
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (showVis) {
                VisualizerView(
                    analyzer = playback.analyzer,
                    playing = playback.isPlaying,
                    unavailable = if (playback.outputMode == OutputMode.OFFLOAD) {
                        "No visualizer with AudioTrack offload: the audio chip decodes, so the app never sees the sound"
                    } else {
                        null
                    },
                    style = VisStyle.entries[visStyle % VisStyle.entries.size],
                    onNextStyle = { visStyle = (visStyle + 1) % VisStyle.entries.size },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (showLyrics) {
                LyricsView(
                    song = song,
                    positionMs = positionMs,
                    onSeek = { playback.seekTo(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val side = minOf(maxWidth, maxHeight)
                // Swipe the cover for the next/previous song (the buttons below stay).
                TrackSwiper(
                    current = song,
                    previous = playback.queue.peekPrevious(),
                    next = playback.queue.peekNext(),
                    onPrevious = { playback.previousTrack() },
                    onNext = { playback.skipNext() },
                    modifier = Modifier.fillMaxWidth(),
                ) { shown, label ->
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CoverImage(
                            source = shown?.let { vm.songArt(it) },
                            title = shown?.displayTitle ?: "",
                            sizePx = 1000,
                            modifier = Modifier.size(side),
                            corner = 12.dp,
                            placeholderStyle = MaterialTheme.typography.displayLarge,
                        )
                        // The incoming song: its name (and queue change) over the bottom of its cover.
                        if (shown != null && shown.uri != song?.uri) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .width(side)
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(shown.displayTitle, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    label ?: shown.displaySubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (label != null) MaterialTheme.colorScheme.secondary else Color.White.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- artist - album
        Text(
            text = song?.let { listOfNotNull(it.artist, it.album).joinToString(" - ").ifEmpty { it.folderPath } } ?: "",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )

        // ---- seek bar
        val fraction = dragFraction
            ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val shownMs = if (dragFraction != null) (fraction * durationMs).toLong() else positionMs
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Slider(
                value = fraction,
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    val f = dragFraction
                    if (f != null && durationMs > 0) {
                        val target = (f * durationMs).toLong()
                        playback.seekTo(target)
                        positionMs = target
                    }
                    dragFraction = null
                },
                enabled = durationMs > 0,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(songLengthText(shownMs), style = MaterialTheme.typography.bodySmall)
                Text(songLengthText(durationMs), style = MaterialTheme.typography.bodySmall)
            }
        }

        // ---- VIS, transport, LRC
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = {
                showVis = !showVis
                if (showVis) showLyrics = false
            }) {
                Text(
                    text = "VIS",
                    fontWeight = if (showVis) FontWeight.Bold else FontWeight.Normal,
                    color = if (showVis) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { playback.skipPrevious() }, enabled = song != null) {
                TransportIcon(MediaIcons.SkipPrevious, "button_previous", "Previous", size = 32.dp)
            }
            FilledIconButton(
                onClick = { playback.togglePause() },
                modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = DarkCyan),
            ) {
                if (playback.isPlaying) {
                    TransportIcon(MediaIcons.Pause, "button_pause", "Pause", size = 36.dp)
                } else {
                    TransportIcon(MediaIcons.Play, "button_play", "Play", size = 36.dp)
                }
            }
            IconButton(onClick = { playback.skipNext() }, enabled = playback.queue.hasNext) {
                TransportIcon(MediaIcons.SkipNext, "button_next", "Next", size = 32.dp)
            }
            IconButton(onClick = { playback.stop() }, enabled = song != null && !playback.idle) {
                TransportIcon(MediaIcons.Stop, "button_stop", "Stop", size = 28.dp)
            }
            TextButton(onClick = {
                showLyrics = !showLyrics
                if (showLyrics) showVis = false
            }) {
                Text(
                    text = "LRC",
                    fontWeight = if (showLyrics) FontWeight.Bold else FontWeight.Normal,
                    color = if (showLyrics) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- add to playlist (left), delete file (right)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Local playlists and deleting only work on files on the phone.
            val local = song != null && !Jellyfin.isJellyfin(song)
            IconButton(onClick = { showAddToPlaylist = true }, enabled = local) {
                Icon(Icons.Filled.Add, contentDescription = "Add to playlist")
            }
            IconButton(onClick = { confirmDelete = true }, enabled = local) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete file")
            }
        }
    }

    // ---- dialogs
    if (showInfo && song != null) SongInfoDialog(song, vm.playCountOf(song), onDismiss = { showInfo = false })

    if (showAddToPlaylist && song != null) {
        AddToPlaylistDialog(
            playlists = vm.editablePlaylists,
            onPick = { p ->
                vm.addToPlaylist(song, p)
                showAddToPlaylist = false
            },
            onCreate = { name ->
                vm.createPlaylist(name, song)
                showAddToPlaylist = false
            },
            onDismiss = { showAddToPlaylist = false },
        )
    }

    if (confirmDelete && song != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this file?") },
            text = {
                Text("${song.fileName}\n${song.folderPath.ifEmpty { "/" }}\n\nThe file is deleted from the device and can't be recovered.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSong(song)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Lyrics for the playing song: synced lines follow playback (tap a line to jump there); plain text scrolls. */
@Composable
private fun LyricsView(song: Song?, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val state by produceState<LyricsState>(LyricsState.Loading, song?.uri) {
        value = LyricsState.Loading
        value = if (song == null) LyricsState.None else LyricsLoader.load(context, song)?.let { LyricsState.Found(it) } ?: LyricsState.None
    }

    when (val s = state) {
        LyricsState.Loading -> Box(modifier, contentAlignment = Alignment.Center) {
            Text("Looking for lyrics...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LyricsState.None -> Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "No lyrics found.\n\nPut a .lrc or .txt file with the same name next to the song, " +
                    "or embed lyrics in its tags.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is LyricsState.Found -> {
            val lyrics = s.lyrics
            if (lyrics.isSynced) {
                val lines = lyrics.synced
                val current = lines.indexOfLast { it.timeMs <= positionMs }
                val listState = rememberLazyListState()
                LaunchedEffect(current) {
                    if (current >= 0) listState.animateScrollToItem((current - 3).coerceAtLeast(0))
                }
                LazyColumn(
                    state = listState,
                    modifier = modifier,
                    contentPadding = PaddingValues(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    itemsIndexed(lines) { i, line ->
                        val isCurrent = i == current
                        Text(
                            text = line.text.ifEmpty { "•" },
                            style = if (isCurrent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSeek(line.timeMs) }
                                .padding(vertical = 6.dp, horizontal = 8.dp),
                        )
                    }
                    item {
                        Text(
                            text = "Lyrics: ${lyrics.source}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            } else {
                Column(
                    modifier = modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = lyrics.plain.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    Text(
                        text = "Lyrics: ${lyrics.source} (not synced)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
}

@Composable
private fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onPick: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column {
                if (playlists.isEmpty()) {
                    Text("No playlists yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(playlists, key = { it.uri.toString() }) { p ->
                            Text(
                                text = p.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(p) }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("New playlist (.m3u8 in the music folder)", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = { Text("Name") },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(newName) }, enabled = newName.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SongInfoDialog(song: Song, plays: Int, onDismiss: () -> Unit) {
    val rows = listOf(
        "Title" to song.displayTitle,
        "Artist" to (song.artist ?: "-"),
        "Album artist" to (song.albumArtist ?: "-"),
        "Album" to (song.album ?: "-"),
        "Track" to listOfNotNull(
            song.trackNumber.takeIf { it > 0 }?.toString(),
            song.discNumber.takeIf { it > 0 }?.let { "disc $it" },
        ).joinToString(", ").ifEmpty { "-" },
        "Year" to (song.year.takeIf { it > 0 }?.toString() ?: "-"),
        "Length" to songLengthText(song.durationMs),
        "Plays" to plays.toString(),
        "Added" to shortDateText(song.dateAdded),
        "File" to song.fileName,
        "Folder" to song.folderPath.ifEmpty { "/" },
        "Format" to song.mimeType.ifEmpty { "-" },
        "Size" to "%.1f MB".format(song.sizeBytes / 1e6),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Song info") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(rows) { (label, value) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.35f),
                        )
                        Text(text = value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.65f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

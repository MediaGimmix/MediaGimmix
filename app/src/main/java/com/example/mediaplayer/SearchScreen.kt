package com.example.mediaplayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How many of each kind show before "Show all". */
private const val PREVIEW_COUNT = 5

/**
 * Search the library (or the Jellyfin library) for artists, albums and songs.
 * Tapping a song plays it from its album; artists and albums open their page.
 */
@Composable
fun SearchScreen(
    vm: LibraryViewModel,
    query: String,
    onQueryChange: (String) -> Unit,
    jellyfin: Boolean,
    onJellyfinChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenArtist: (key: String, jellyfin: Boolean) -> Unit,
    onOpenAlbum: (key: String, jellyfin: Boolean) -> Unit,
) {
    val playback = vm.playback
    val jellyfinReady = vm.jellyfinAccount != null && !vm.jellyfinLocked
    val useJellyfin = jellyfin && jellyfinReady
    val songs = if (useJellyfin) vm.jellyfinSongs else vm.songs
    val albums = if (useJellyfin) vm.jellyfinAlbums else vm.albums
    val artists = if (useJellyfin) vm.jellyfinArtists else vm.artists

    var results by remember { mutableStateOf<LibrarySearch.Results?>(null) }
    LaunchedEffect(query, songs, albums, artists) {
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        delay(150) // wait for typing to pause
        results = withContext(Dispatchers.Default) { LibrarySearch.all(query, songs, albums, artists) }
    }

    // Which kinds are expanded past the preview.
    var allArtists by rememberSaveable(query) { mutableStateOf(false) }
    var allAlbums by rememberSaveable(query) { mutableStateOf(false) }
    var allSongs by rememberSaveable(query) { mutableStateOf(false) }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Songs, albums, artists") },
                singleLine = true,
                trailingIcon = if (query.isNotEmpty()) {
                    { TextButton(onClick = { onQueryChange("") }) { Text("✕") } }
                } else null,
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        if (jellyfinReady) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = !useJellyfin, onClick = { onJellyfinChange(false) }, label = { Text("On phone") })
                FilterChip(selected = useJellyfin, onClick = { onJellyfinChange(true) }, label = { Text("Jellyfin") })
            }
        }

        val r = results
        when {
            query.isBlank() -> SearchNote("Type to search titles, artists, albums and file names.")
            r == null -> SearchNote("Searching…")
            r.isEmpty -> SearchNote("Nothing found for \"${query.trim()}\".")
            else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                section("Artists", r.artists, allArtists, { allArtists = true }) { a ->
                    PictureRow(
                        label = a.name,
                        subtitle = artistSummary(a),
                        art = vm.artistArt(a),
                        actions = { artistActions(vm, a, onPickImage = null) },
                        onClick = { onOpenArtist(a.key, useJellyfin) },
                    )
                }
                section("Albums", r.albums, allAlbums, { allAlbums = true }) { a ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenAlbum(a.key, useJellyfin) }
                            .padding(start = 12.dp, end = 16.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RowCover(vm.albumArt(a), a.title)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(a.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                albumSummary(a),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                section("Songs", r.songs, allSongs, { allSongs = true }) { song ->
                    SongRow(
                        song = song,
                        subtitle = song.displaySubtitle,
                        art = vm.songArt(song),
                        showArt = true,
                        isCurrent = song.uri == playback.nowPlaying?.uri,
                        onClick = {
                            // Like tapping it in its album (or in Songs, when it's on no album).
                            val key = LibraryGrouping.albumKey(song)
                            val context = albums.firstOrNull { it.key == key }?.songs ?: songs
                            playback.playFromLibrary(song, context)
                        },
                        actions = listOf(
                            MenuAction("Play next") { playback.playNext(song) },
                            MenuAction("Play last") { playback.playLast(song) },
                        ),
                    )
                }
            }
        }
    }
}

/** A titled group of results: the first few, then a "Show all" row. */
private fun <T> LazyListScope.section(
    title: String,
    entries: List<T>,
    expanded: Boolean,
    onExpand: () -> Unit,
    row: @Composable (T) -> Unit,
) {
    if (entries.isEmpty()) return
    item(contentType = "header") {
        Text(
            "$title (${"%,d".format(entries.size)})",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        )
    }
    val shown = if (expanded) entries else entries.take(PREVIEW_COUNT)
    items(shown) { row(it) }
    if (!expanded && entries.size > PREVIEW_COUNT) {
        item(contentType = "more") {
            TextButton(onClick = onExpand, modifier = Modifier.padding(start = 8.dp)) {
                Text("Show all ${"%,d".format(entries.size)}")
            }
        }
    }
}

@Composable
private fun SearchNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

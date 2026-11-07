package com.example.mediaplayer.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Search the player's library. Only the matches come over (at most the "Most results" setting
 * of each kind); albums and artists open their songs.
 */
@Composable
fun SearchScreen(vm: RemoteViewModel) {
    var open by remember { mutableStateOf<ListRef?>(null) }
    BackHandler(enabled = open != null) { open = null }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    val source = vm.jellyfin
    val limit = vm.searchLimit

    LaunchedEffect(query, source, limit) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = null
            problem = null
            return@LaunchedEffect
        }
        delay(300) // wait for typing to pause
        searching = true
        vm.call { search(q, source, limit) }
            .onSuccess { results = it; problem = null }
            .onFailure { problem = vm.message(it) }
        searching = false
    }

    val ref = open
    if (ref != null) {
        SongListPage(vm, ref, onBack = { open = null })
        return
    }
    val src = if (source) "&source=jellyfin" else ""

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Songs, albums, artists") },
            singleLine = true,
            trailingIcon = if (query.isNotEmpty()) {
                { TextButton(onClick = { query = "" }) { Text("✕") } }
            } else null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SourceChips(vm)
        val r = results
        when {
            problem != null -> Note(problem!!, error = true)
            query.isBlank() -> Note("Type to search the player's library. Up to $limit of each kind are shown.")
            r == null -> Note("Searching…")
            r.isEmpty -> Note(if (searching) "Searching…" else "Nothing found.")
            else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                group("Artists", r.artists) { a ->
                    ItemRow(vm, "artists/${a.id}/art?size=120$src", a.name, "${a.songCount} songs · ${a.albumCount} albums") {
                        open = ListRef("artists", a.id, a.name)
                    }
                }
                group("Albums", r.albums) { a ->
                    val sub = listOfNotNull(a.artist.ifEmpty { null }, a.year.takeIf { it > 0 }?.toString()).joinToString(" · ")
                    ItemRow(vm, "albums/${a.id}/art?size=120$src", a.title, sub) {
                        open = ListRef("albums", a.id, a.title)
                    }
                }
                group("Songs", r.songs) { s ->
                    // Plays it from its own album on the player.
                    ItemRow(vm, "songs/${s.id}/art?size=120", s.title, s.subtitle) { vm.play("songs/${s.id}/play") }
                }
            }
        }
    }
}

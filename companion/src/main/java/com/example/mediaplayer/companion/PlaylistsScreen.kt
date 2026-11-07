package com.example.mediaplayer.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** The player's playlists; tap one for its songs. */
@Composable
fun PlaylistsScreen(vm: RemoteViewModel) {
    var open by remember { mutableStateOf<ListRef?>(null) }
    BackHandler(enabled = open != null) { open = null }
    val ref = open
    if (ref != null) {
        SongListPage(vm, ref, onBack = { open = null })
        return
    }

    var page by remember { mutableStateOf<Page<PlaylistInfo>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val source = vm.jellyfin
    val limit = vm.searchLimit
    LaunchedEffect(source, limit, vm.connection) {
        page = null
        vm.call { playlists(source, limit) }
            .onSuccess { page = it; problem = null }
            .onFailure { problem = vm.message(it) }
    }
    val src = if (source) "&source=jellyfin" else ""

    Column(modifier = Modifier.fillMaxSize()) {
        SourceChips(vm)
        val p = page
        when {
            problem != null -> Note(problem!!, error = true)
            p == null -> Note("Loading…")
            p.items.isEmpty() -> Note("No playlists on the player.")
            else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (p.total > p.items.size) {
                    item { Note("Showing ${p.items.size} of ${"%,d".format(p.total)} playlists (limit in Settings).") }
                }
                items(p.items, key = { it.id }) { pl ->
                    val sub = "${pl.songCount} songs" + if (pl.missing > 0) " · ${pl.missing} not found" else ""
                    ItemRow(vm, "playlists/${pl.id}/art?size=160$src", pl.name, sub) {
                        open = ListRef("playlists", pl.id, pl.name)
                    }
                }
            }
        }
    }
}

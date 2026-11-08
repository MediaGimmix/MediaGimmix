package com.example.mediaplayer.companion

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How often the queue is fetched again while this tab is open. */
private const val QUEUE_REFRESH_MS = 4_000L

/**
 * The player's queues, in two submenus: Queue (recently played, now playing, the general queue
 * coming up) and Play next. The player sends a window: the last 48 played and up to 99 coming up.
 */
@Composable
fun QueueScreen(vm: RemoteViewModel) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    var queue by remember { mutableStateOf<QueueInfo?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    var reload by remember { mutableIntStateOf(0) }
    // Tap a song: it plays now (on the player, or here while listening on this phone).
    val playQueued: (kind: String, index: Int, song: SongInfo) -> Unit = { kind, index, song ->
        val path = "queue/$kind/$index/play?id=${song.id}"
        if (LocalPlayer.active) LocalPlayer.playQueued(path, song) else vm.playQueued(path)
        reload++
    }

    LaunchedEffect(vm.connection, reload) {
        if (reload > 0) delay(800) // let the player move first
        while (true) {
            vm.call { queue() }
                .onSuccess { queue = it; problem = null }
                .onFailure { problem = vm.message(it) }
            delay(QUEUE_REFRESH_MS)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val q = queue
        TabRow(selectedTabIndex = sub) {
            Tab(selected = sub == 0, onClick = { sub = 0 }, text = {
                Text(if (q != null) "Queue (${"%,d".format(q.generalTotal)})" else "Queue")
            })
            Tab(selected = sub == 1, onClick = { sub = 1 }, text = {
                Text(if (q != null) "Play next (${"%,d".format(q.playNextTotal)})" else "Play next")
            })
        }
        when {
            problem != null && q == null -> Note(problem!!, error = true)
            q == null -> Note("Loading…")
            sub == 0 -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (q.history.isNotEmpty()) {
                    header(
                        "Played" + if (q.historyTotal > q.history.size) " (last ${q.history.size} of ${"%,d".format(q.historyTotal)})" else "",
                    )
                    songs(vm, q.history, dim = true)
                }
                header("Now playing")
                if (q.nowPlaying != null) songs(vm, listOf(q.nowPlaying), current = true) else item { Note("Nothing playing.") }
                header(
                    "Coming up" + when {
                        q.general.size < q.generalTotal -> " (next ${q.general.size} of ${"%,d".format(q.generalTotal)})"
                        else -> ""
                    },
                )
                if (q.general.isEmpty()) {
                    item {
                        Note(
                            if (q.generalTotal > 0) "The play next queue fills the window; these come after it."
                            else "The general queue is empty.",
                        )
                    }
                }
                songs(vm, q.general) { i, song -> playQueued("general", q.generalStart + i, song) }
            }
            else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (q.playNext.isEmpty()) {
                    item { Note("Nothing in Play next. Songs added with \"Play next\" or \"Play last\" on the player show here.") }
                } else {
                    if (q.playNext.size < q.playNextTotal) {
                        item { Note("Showing the first ${q.playNext.size} of ${"%,d".format(q.playNextTotal)}.") }
                    }
                    songs(vm, q.playNext) { i, song -> playQueued("next", i, song) }
                }
            }
        }
    }
}

private fun LazyListScope.header(text: String) {
    item {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        )
    }
}

/** Queue rows; [onTap] (coming up / play next) plays that song now. Played rows are read-only. */
private fun LazyListScope.songs(
    vm: RemoteViewModel,
    list: List<SongInfo>,
    dim: Boolean = false,
    current: Boolean = false,
    onTap: ((Int, SongInfo) -> Unit)? = null,
) {
    itemsIndexed(list) { i, s ->
        QueueRow(vm, s, dim, current, onTap?.let { tap -> { tap(i, s) } })
    }
}

@Composable
private fun QueueRow(vm: RemoteViewModel, s: SongInfo, dim: Boolean, current: Boolean, onClick: (() -> Unit)?) {
    val alpha = if (dim) 0.55f else 1f
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.alpha(alpha)) { RemoteCover(vm, "songs/${s.id}/art?size=120", s.title) }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                s.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                s.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

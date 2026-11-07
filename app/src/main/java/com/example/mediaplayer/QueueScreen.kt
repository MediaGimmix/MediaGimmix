package com.example.mediaplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** One entry in a row's "more" menu. Its icon is picked from the label (see [AppIcons.forAction]). */
internal class MenuAction(val label: String, val run: () -> Unit)

/** The items of a dropdown menu, each with its icon on the left. */
@Composable
internal fun MenuItems(actions: List<MenuAction>, onDismiss: () -> Unit) {
    actions.forEach { action ->
        DropdownMenuItem(
            text = { Text(action.label) },
            leadingIcon = { AssetIcon(AppIcons.forAction(action.label), size = 22.dp) },
            onClick = {
                onDismiss()
                action.run()
            },
        )
    }
}

/** A small "more" button that opens a dropdown of [actions]. */
@Composable
internal fun RowMenu(actions: List<MenuAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("⋮") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItems(actions, onDismiss = { open = false })
        }
    }
}

/** A small cover for song rows. */
@Composable
internal fun RowCover(source: ArtSource?, title: String) {
    CoverImage(
        source = source,
        title = title,
        sizePx = 160,
        modifier = Modifier.size(44.dp),
        corner = 4.dp,
        placeholderStyle = MaterialTheme.typography.titleSmall,
    )
    Spacer(modifier = Modifier.width(12.dp))
}

/**
 * Both queues in one scrolling list: "Play next" on top, then the general queue.
 * The general queue is a lazy list, so only the rows on screen are built, which
 * is what keeps a few thousand songs cheap.
 */
@Composable
fun QueueTab(playback: Playback, art: (Song) -> ArtSource? = { ArtSource.FromSong(it) }) {
    val queue = playback.queue
    val next = queue.next
    val general = queue.general
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Flattened list: [next header][next rows or a hint][general header][general rows]
    val generalHeaderIndex = 1 + maxOf(next.size, 1)

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            SectionHeader(
                title = "Play next (${next.size})",
                icon = "header_play_next",
                actionLabel = "Clear",
                actionEnabled = next.isNotEmpty(),
                onAction = { queue.clearNext() },
            )
        }
        if (next.isEmpty()) {
            item { Hint("Nothing queued. Use the menu on a song to add it.") }
        }
        itemsIndexed(next) { i, song ->
            QueueRow(
                song = song,
                art = art(song),
                subtitle = null,
                dimmed = false,
                actions = buildList {
                    if (i > 0) add(MenuAction("Move up") { queue.moveNext(i, i - 1) })
                    if (i < next.lastIndex) add(MenuAction("Move down") { queue.moveNext(i, i + 1) })
                    add(MenuAction("Remove") { queue.removeNext(i) })
                },
            )
        }

        item {
            SectionHeader(
                title = "Queue (${general.size})",
                icon = "header_queue",
                actionLabel = "Jump to spot",
                actionEnabled = general.isNotEmpty(),
                onAction = {
                    scope.launch {
                        listState.animateScrollToItem(generalHeaderIndex + 1 + maxOf(queue.returnIndex, 0))
                    }
                },
            )
        }
        if (general.isEmpty()) {
            item { Hint("Empty. Tap a song in the Library to start.") }
        }
        itemsIndexed(general) { i, song ->
            QueueRow(
                song = song,
                art = art(song),
                subtitle = if (i == queue.returnIndex) "Spot held here - the queue continues after this" else null,
                dimmed = i <= queue.returnIndex,
                actions = buildList {
                    add(MenuAction("Play next") { playback.playNextFromGeneral(i) })
                    if (i > 0) add(MenuAction("Move up") { queue.moveGeneral(i, i - 1) })
                    if (i < general.lastIndex) add(MenuAction("Move down") { queue.moveGeneral(i, i + 1) })
                    add(MenuAction("Remove") { queue.removeGeneral(i) })
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: String, actionLabel: String, actionEnabled: Boolean, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AssetIcon(icon, size = 24.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction, enabled = actionEnabled) { Text(actionLabel) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun QueueRow(song: Song, art: ArtSource?, subtitle: String?, dimmed: Boolean, actions: List<MenuAction>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowCover(art, song.displayTitle)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = if (dimmed) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle ?: song.displaySubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RowMenu(actions)
    }
}

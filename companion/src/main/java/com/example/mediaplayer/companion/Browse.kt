package com.example.mediaplayer.companion

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Songs inside one playlist/album/artist are fetched this many at a time ("Load more"). */
private const val PAGE_SIZE = 100

/** Small covers fetched from the player, kept in memory. */
object RemoteImages {
    /** Limited by bytes, not count (bitmap pixels are native memory outside the Java heap limit). */
    private val cache = object : LruCache<String, ImageBitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            (value.asAndroidBitmap().allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val missing = HashSet<String>()

    fun peek(key: String): ImageBitmap? = cache.get(key)
    fun isMissing(key: String) = synchronized(missing) { key in missing }
    fun put(key: String, image: ImageBitmap?) {
        if (image != null) cache.put(key, image) else synchronized(missing) { missing.add(key) }
    }
}

/** A cover from the player ([path] like "songs/<id>/art?size=160"); a letter while there is none. */
@Composable
fun RemoteCover(vm: RemoteViewModel, path: String, title: String, size: Dp = 48.dp) {
    val key = vm.connection.base + "/" + path
    var image by remember(key) { mutableStateOf(RemoteImages.peek(key)) }
    LaunchedEffect(key) {
        if (image == null && !RemoteImages.isMissing(key)) {
            val loaded = vm.call { image(path) }.getOrNull()
            RemoteImages.put(key, loaded)
            image = loaded
        }
    }
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(title.take(1).uppercase(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** On the player / Jellyfin switch (only matters when the player is signed in to Jellyfin). */
@Composable
fun SourceChips(vm: RemoteViewModel) {
    Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !vm.jellyfin, onClick = { vm.jellyfin = false }, label = { Text("On the player") })
        FilterChip(selected = vm.jellyfin, onClick = { vm.jellyfin = true }, label = { Text("Jellyfin") })
    }
}

@Composable
fun ItemRow(vm: RemoteViewModel, coverPath: String?, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (coverPath != null) {
            RemoteCover(vm, coverPath, title)
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
}

@Composable
fun PageHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

/**
 * One playlist, album or artist on the player. [kind] is "playlists", "albums" or "artists".
 * Songs load [PAGE_SIZE] at a time; tapping one plays it in this list's order on the player.
 */
data class ListRef(val kind: String, val id: String, val title: String)

@Composable
fun SongListPage(vm: RemoteViewModel, ref: ListRef, onBack: () -> Unit) {
    val songs = remember(ref) { mutableStateOf<List<SongInfo>>(emptyList()) }
    var total by remember(ref) { mutableIntStateOf(-1) }
    var loading by remember(ref) { mutableStateOf(false) }
    var problem by remember(ref) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val source = vm.jellyfin

    fun loadMore() {
        if (loading) return
        loading = true
        val offset = songs.value.size
        scope.launch {
            vm.call { songPage("${ref.kind}/${ref.id}/songs", source, offset, PAGE_SIZE) }
                .onSuccess { page ->
                    songs.value = songs.value + page.items
                    total = if (page.total >= 0) page.total else songs.value.size
                    problem = null
                }
                .onFailure { problem = vm.message(it) }
            loading = false
        }
    }
    LaunchedEffect(ref) { loadMore() }

    val contextParam = when (ref.kind) {
        "playlists" -> "playlist"
        "albums" -> "album"
        else -> "artist"
    } + "=" + ref.id
    val src = if (source) "&source=jellyfin" else ""

    Column(modifier = Modifier.fillMaxSize()) {
        PageHeader(ref.title, onBack)
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RemoteCover(vm, "${ref.kind}/${ref.id}/art?size=300$src", ref.title, size = 72.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (total >= 0) "${"%,d".format(total)} songs" else "Loading…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.play("${ref.kind}/${ref.id}/play") }, enabled = total > 0) { Text("Play all") }
                    OutlinedButton(onClick = { vm.play("${ref.kind}/${ref.id}/play?shuffle=true") }, enabled = total > 0) {
                        Text("Shuffle")
                    }
                }
            }
        }
        problem?.let { Note(it, error = true) }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // No keys: a playlist can hold the same song twice.
            itemsIndexed(songs.value) { _, s ->
                ItemRow(vm, "songs/${s.id}/art?size=120", s.title, s.subtitle) {
                    vm.play("songs/${s.id}/play?$contextParam")
                }
            }
            if (total > songs.value.size) {
                item {
                    TextButton(onClick = { loadMore() }, enabled = !loading, modifier = Modifier.padding(8.dp)) {
                        Text(if (loading) "Loading…" else "Load more (${"%,d".format(total - songs.value.size)} left)")
                    }
                }
            }
        }
    }
}

/** A titled group of results in a LazyColumn. */
fun <T> LazyListScope.group(title: String, entries: List<T>, row: @Composable (T) -> Unit) {
    if (entries.isEmpty()) return
    item {
        Text(
            "$title (${entries.size})",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        )
    }
    items(entries) { row(it) }
}

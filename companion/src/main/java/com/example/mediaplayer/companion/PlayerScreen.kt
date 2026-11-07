package com.example.mediaplayer.companion

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Now playing on the player, with its buttons. No seek bar, on purpose. */
@Composable
fun PlayerScreen(vm: RemoteViewModel) {
    val s = vm.status
    val song = s?.nowPlaying
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val cover = vm.cover
            if (cover != null) {
                Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    song?.title?.take(1)?.uppercase() ?: "♪",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            song?.title ?: if (s == null) "Not connected" else "Nothing playing",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            song?.subtitle.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        vm.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(24.dp))

        val enabled = s != null
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { vm.command("shuffle") }, enabled = enabled) {
                TransportIcon(
                    MediaIcons.Shuffle, "button_shuffle", "Shuffle",
                    tint = if (s?.shuffle == true) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    imageAlpha = if (s?.shuffle == true) 1f else 0.4f,
                )
            }
            IconButton(onClick = { vm.command("prev") }, enabled = enabled) {
                TransportIcon(MediaIcons.SkipPrevious, "button_previous", "Previous", size = 36.dp)
            }
            FilledIconButton(onClick = { vm.command("toggle") }, enabled = enabled, modifier = Modifier.size(72.dp)) {
                if (s?.isPlaying == true) {
                    TransportIcon(MediaIcons.Pause, "button_pause", "Pause", size = 40.dp)
                } else {
                    TransportIcon(MediaIcons.Play, "button_play", "Play", size = 40.dp)
                }
            }
            IconButton(onClick = { vm.command("next") }, enabled = enabled) {
                TransportIcon(MediaIcons.SkipNext, "button_next", "Next", size = 36.dp)
            }
            IconButton(onClick = { vm.command("stop") }, enabled = enabled) {
                TransportIcon(MediaIcons.Stop, "button_stop", "Stop")
            }
        }
    }
}

package com.example.mediaplayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The bar under the library. Tapping the cover or title opens the now playing screen. */
@Composable
fun NowPlayingBar(playback: Playback, art: ArtSource?, onOpen: () -> Unit) {
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    // Poll the player a few times a second for the seek bar.
    LaunchedEffect(Unit) {
        while (true) {
            positionMs = playback.positionMs()
            durationMs = playback.durationMs()
            delay(250)
        }
    }

    val song = playback.nowPlaying
    if (song == null && !playback.queue.hasNext) return

    val fraction = dragFraction
        ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shownMs = if (dragFraction != null) (fraction * durationMs).toLong() else positionMs

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = song != null, onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(
                    source = art,
                    title = song?.displayTitle ?: "",
                    sizePx = 160,
                    modifier = Modifier.size(44.dp),
                    corner = 6.dp,
                    placeholderStyle = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song?.displayTitle ?: "Nothing playing",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = playback.error ?: when {
                            song == null -> "Press Play to start the queue"
                            playback.idle || playback.stopped -> "Stopped"
                            !playback.isPlaying && playback.playWhenReady -> "Buffering..."
                            !playback.isPlaying -> "Paused · ${song.displaySubtitle}"
                            else -> song.displaySubtitle
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

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
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "${songLengthText(shownMs)} / ${songLengthText(durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { playback.skipPrevious() }, enabled = song != null) {
                    TransportIcon(MediaIcons.SkipPrevious, "button_previous", "Previous")
                }
                FilledIconButton(onClick = { playback.togglePause() }) {
                    if (playback.isPlaying) TransportIcon(MediaIcons.Pause, "button_pause", "Pause") else TransportIcon(MediaIcons.Play, "button_play", "Play")
                }
                IconButton(onClick = { playback.skipNext() }, enabled = playback.queue.hasNext) {
                    TransportIcon(MediaIcons.SkipNext, "button_next", "Next")
                }
                IconButton(onClick = { playback.stop() }, enabled = song != null && !playback.idle) {
                    TransportIcon(MediaIcons.Stop, "button_stop", "Stop")
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Shuffle toggle: lighter cyan when on, grey when off.
                    IconButton(onClick = { playback.changeShuffle(!playback.shuffle) }) {
                        TransportIcon(
                            MediaIcons.Shuffle,
                            "button_shuffle",
                            contentDescription = if (playback.shuffle) "Shuffle on" else "Shuffle off",
                            imageAlpha = if (playback.shuffle) 1f else 0.4f,
                            tint = if (playback.shuffle) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            },
                        )
                    }
                }
            }
        }
    }
}

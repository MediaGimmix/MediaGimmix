package com.example.mediaplayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
@OptIn(ExperimentalMaterial3Api::class) // thinner seek bar track (Slider's track slot)
@Composable
fun NowPlayingBar(playback: Playback, artFor: (Song) -> ArtSource?, onOpen: () -> Unit) {
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
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Swipe left/right for the next/previous song (no skip buttons on this bar); tap to open.
                TrackSwiper(
                    current = song,
                    previous = playback.queue.peekPrevious(),
                    next = playback.queue.peekNext(),
                    onPrevious = { playback.previousTrack() },
                    onNext = { playback.skipNext() },
                    modifier = Modifier.fillMaxWidth(),
                ) { shown, label ->
                    val isCurrent = shown?.uri == song?.uri && label == null
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = song != null, onClick = onOpen),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverImage(
                            source = shown?.let { artFor(it) },
                            title = shown?.displayTitle ?: "",
                            sizePx = 200,
                            modifier = Modifier.size(56.dp),
                            corner = 6.dp,
                            placeholderStyle = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = shown?.displayTitle ?: "Nothing playing",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = when {
                                    label != null -> label
                                    !isCurrent -> shown?.displaySubtitle.orEmpty()
                                    else -> playback.error ?: when {
                                        song == null -> "Press Play to start the queue"
                                        playback.idle || playback.stopped -> "Stopped"
                                        !playback.isPlaying && playback.playWhenReady -> "Buffering..."
                                        !playback.isPlaying -> "Paused · ${song.displaySubtitle}"
                                        else -> song.displaySubtitle
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (label != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Seek bar: a little lower and thinner than the default.
                Spacer(modifier = Modifier.height(6.dp))
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
                    track = { state -> SliderDefaults.Track(sliderState = state, modifier = Modifier.height(12.dp)) },
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                )

                // Elapsed on the left; remaining / length on the right.
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = songLengthText(shownMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${songLengthText((durationMs - shownMs).coerceAtLeast(0L))} / ${songLengthText(durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            // Pause and stop, stacked on the right.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(onClick = { playback.togglePause() }) {
                    if (playback.isPlaying) TransportIcon(MediaIcons.Pause, "button_pause", "Pause") else TransportIcon(MediaIcons.Play, "button_play", "Play")
                }
                IconButton(onClick = { playback.stop() }, enabled = song != null && !playback.idle) {
                    TransportIcon(MediaIcons.Stop, "button_stop", "Stop")
                }
            }
        }
    }
}

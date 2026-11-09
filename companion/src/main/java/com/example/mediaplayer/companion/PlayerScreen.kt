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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Now playing on the player, with its buttons. No seek bar while the player plays (on purpose); while
 * listening on this phone ([LocalPlayer]) there is one, and the buttons control the phone's playback.
 */
@Composable
fun PlayerScreen(vm: RemoteViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val local = LocalPlayer.active
    val s = vm.status
    val song = if (local) LocalPlayer.song else s?.nowPlaying
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
        if (!local) {
            vm.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
        LocalPlayer.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        LocalPlayer.busy?.let {
            Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }

        if (local) {
            SeekBar()
        } else {
            Spacer(Modifier.height(24.dp))
        }

        val enabled = local || s != null
        val playing = if (local) LocalPlayer.playing else s?.isPlaying == true
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { vm.command("shuffle") }, enabled = enabled) {
                TransportIcon(
                    MediaIcons.Shuffle, "button_shuffle", "Shuffle",
                    tint = if (s?.shuffle == true) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    imageAlpha = if (s?.shuffle == true) 1f else 0.4f,
                )
            }
            IconButton(onClick = { if (local) LocalPlayer.previous() else vm.command("prev") }, enabled = enabled) {
                TransportIcon(MediaIcons.SkipPrevious, "button_previous", "Previous", size = 36.dp)
            }
            FilledIconButton(
                onClick = { if (local) LocalPlayer.toggle() else vm.command("toggle") },
                enabled = enabled,
                modifier = Modifier.size(72.dp),
            ) {
                if (playing) {
                    TransportIcon(MediaIcons.Pause, "button_pause", "Pause", size = 40.dp)
                } else {
                    TransportIcon(MediaIcons.Play, "button_play", "Play", size = 40.dp)
                }
            }
            IconButton(onClick = { if (local) LocalPlayer.next() else vm.command("next") }, enabled = enabled) {
                TransportIcon(MediaIcons.SkipNext, "button_next", "Next", size = 36.dp)
            }
            IconButton(onClick = { if (local) LocalPlayer.stop() else vm.command("stop") }, enabled = enabled) {
                TransportIcon(MediaIcons.Stop, "button_stop", "Stop")
            }
        }

        VolumeRow(vm, local)

        Spacer(Modifier.height(16.dp))
        if (local) {
            Text("Playing on this phone · the player is paused", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            OutlinedButton(onClick = { LocalPlayer.handBack() }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Back to the player")
            }
        } else {
            OutlinedButton(
                onClick = { LocalPlayer.start(context) },
                enabled = s?.nowPlaying != null && LocalPlayer.busy == null,
            ) { Text("Listen on this phone") }
        }
    }
}

/** Position and length of the song playing on this phone, draggable. */
@Composable
private fun SeekBar() {
    val duration = LocalPlayer.durationMs.coerceAtLeast(1L)
    var dragging by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    val shown = dragging ?: (LocalPlayer.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { LocalPlayer.seek((it * duration).toLong()) }
                dragging = null
            },
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(time((shown * duration).toLong()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(time(LocalPlayer.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun time(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * Volume: the player's music volume (from its status), or this phone's while listening here.
 * The ends step one notch; the slider changes the volume live as it moves (snapping to whole steps).
 */
@Composable
private fun VolumeRow(vm: RemoteViewModel, local: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val audio = androidx.compose.runtime.remember { context.getSystemService(android.media.AudioManager::class.java) }
    val stream = android.media.AudioManager.STREAM_MUSIC
    // The phone's volume can change from its own keys: re-read it while listening here.
    var phoneVolume by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(audio.getStreamVolume(stream)) }
    androidx.compose.runtime.LaunchedEffect(local) {
        while (local) {
            phoneVolume = audio.getStreamVolume(stream)
            kotlinx.coroutines.delay(500)
        }
    }
    val max = if (local) audio.getStreamMaxVolume(stream) else vm.status?.volumeMax ?: -1
    val current = if (local) phoneVolume else vm.status?.volume ?: -1
    if (max <= 0 || current < 0) return // an older player that doesn't report its volume

    var dragging by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    val shown = dragging ?: current.toFloat()
    fun set(level: Int) {
        if (local) {
            audio.setStreamVolume(stream, level.coerceIn(0, max), 0)
            phoneVolume = audio.getStreamVolume(stream)
        } else {
            vm.setVolume(level)
        }
    }
    fun step(up: Boolean) {
        if (local) {
            audio.adjustStreamVolume(stream, if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER, 0)
            phoneVolume = audio.getStreamVolume(stream)
        } else {
            vm.stepVolume(up)
        }
    }
    Column(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { step(false) }) { VolumeIcon("volume_down", "−", "Volume down") }
            Slider(
                value = shown,
                // Live: every step reached while dragging is applied right away (no jump on release).
                onValueChange = { v ->
                    val before = (dragging ?: current.toFloat()).roundToInt()
                    dragging = v
                    if (v.roundToInt() != before) set(v.roundToInt())
                },
                onValueChangeFinished = {
                    dragging?.let { set(it.roundToInt()) }
                    dragging = null
                },
                steps = (max - 1).coerceAtLeast(0),
                valueRange = 0f..max.toFloat(),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { step(true) }) { VolumeIcon("volume_up", "+", "Volume up") }
        }
        Text(
            (if (local) "Phone volume " else "Player volume ") + "${shown.roundToInt()} / $max",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/** assets/icons/<file>.png (replaceable), else a plain sign. */
@Composable
private fun VolumeIcon(file: String, fallback: String, label: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val image = androidx.compose.runtime.remember(file) { AssetIcons.load(context.applicationContext, file) }
    if (image != null) {
        Image(image, contentDescription = label, modifier = Modifier.size(32.dp))
    } else {
        Text(fallback, style = MaterialTheme.typography.titleLarge)
    }
}

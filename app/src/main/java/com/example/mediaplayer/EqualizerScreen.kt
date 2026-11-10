package com.example.mediaplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt

/** The Equalizer tab (navbar Equalizer icon); the bottom navbar stays below it. */
@Composable
fun EqualizerSettings(outputMode: OutputMode) {
    val profile = Equalizer.profiles[Equalizer.active]
    var selected by remember { mutableIntStateOf(0) }
    val sel = selected.coerceIn(0, (profile.bands.size - 1).coerceAtLeast(0))
    val accent = MaterialTheme.colorScheme.secondary

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Equalizer", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = Equalizer.enabled, onCheckedChange = { Equalizer.turnOn(it) })
        }
        if (outputMode == OutputMode.OFFLOAD) {
            Text(
                "Not active with AudioTrack offload: the audio chip decodes, so the app never sees the sound. Pick another output mode in Settings → Audio.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Equalizer.profiles.forEachIndexed { i, p ->
                FilterChip(selected = i == Equalizer.active, onClick = { Equalizer.selectProfile(i); selected = 0 }, label = { Text(p.name) })
            }
            if (Equalizer.profiles.size < Equalizer.MAX_PROFILES) {
                TextButton(onClick = { Equalizer.addProfile(); selected = 0 }) { Text("+ Add") }
            }
        }
        // Removing needs a deliberate tap on the selected profile's own button; at least 2 always stay.
        OutlinedButton(
            onClick = { Equalizer.removeActiveProfile(); selected = 0 },
            enabled = Equalizer.profiles.size > Equalizer.MIN_PROFILES,
        ) { Text("Remove ${profile.name}") }
        ResponseGraph(profile, highlight = sel)

        Text("Preamp ${fmtDb(profile.preampDb)}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = profile.preampDb, onValueChange = { Equalizer.setPreamp((it * 2).roundToInt() / 2f) },
            valueRange = -Equalizer.MAX_GAIN..Equalizer.MAX_GAIN,
        )

        // Bands
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            profile.bands.forEachIndexed { i, b ->
                FilterChip(
                    selected = i == sel, onClick = { selected = i },
                    label = { Text("${i + 1} · ${shortType(b.type)} ${fmtHz(b.freq)}" + if (b.on) "" else " (off)") },
                )
            }
            if (profile.bands.size < Equalizer.MAX_BANDS) {
                TextButton(onClick = { val at = Equalizer.addBand(); if (at >= 0) selected = at }) { Text("+ Add") }
            }
        }

        val band = profile.bands.getOrNull(sel)
        if (band != null) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EqType.entries.forEach { t ->
                    FilterChip(selected = band.type == t, onClick = { Equalizer.updateBand(sel, band.copy(type = t)) }, label = { Text(t.label) })
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Dial(
                    label = "Frequency", text = fmtHz(band.freq), color = accent,
                    fraction = logFraction(band.freq, Equalizer.MIN_FREQ, Equalizer.MAX_FREQ),
                    onFraction = { Equalizer.updateBand(sel, band.copy(freq = fromLog(it, Equalizer.MIN_FREQ, Equalizer.MAX_FREQ))) },
                    onReset = { Equalizer.updateBand(sel, band.copy(freq = 1000f)) },
                )
                Dial(
                    label = "Q", text = "%.2f".format(band.q), color = accent,
                    fraction = logFraction(band.q, Equalizer.MIN_Q, Equalizer.MAX_Q),
                    onFraction = { Equalizer.updateBand(sel, band.copy(q = fromLog(it, Equalizer.MIN_Q, Equalizer.MAX_Q))) },
                    onReset = { Equalizer.updateBand(sel, band.copy(q = if (band.type == EqType.BELL) 1f else 0.71f)) },
                )
                Fader(
                    valueDb = band.gainDb, enabled = band.type.usesGain, color = accent,
                    onValue = { Equalizer.updateBand(sel, band.copy(gainDb = it)) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Band on", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = band.on, onCheckedChange = { Equalizer.updateBand(sel, band.copy(on = it)) })
                OutlinedButton(onClick = { Equalizer.removeBand(sel); selected = (sel - 1).coerceAtLeast(0) }) { Text("Remove band") }
            }
        }
        TextButton(onClick = { Equalizer.resetActive(); selected = 0 }) { Text("Reset this profile") }
        Text(
            "Drag a dial up or down to turn it, the fader to set the gain; double-tap either to reset. " +
                "Cut rather than boost where you can, or lower the preamp, to avoid clipping. Remap a button to " +
                "\"Next EQ profile\" (Settings → Audio → Buttons) to switch profiles without the screen.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The profile's response, 20 Hz–20 kHz (log), ±15 dB; the selected band's frequency is marked. */
@Composable
private fun ResponseGraph(profile: EqProfile, highlight: Int) {
    val line = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        val w = size.width
        val h = size.height
        fun y(db: Float) = h / 2 - db / Equalizer.MAX_GAIN * (h / 2)
        drawLine(grid, Offset(0f, h / 2), Offset(w, h / 2), 2f)
        listOf(100f, 1000f, 10000f).forEach { f ->
            val x = logFraction(f, Equalizer.MIN_FREQ, Equalizer.MAX_FREQ) * w
            drawLine(grid, Offset(x, 0f), Offset(x, h), 1f)
        }
        profile.bands.getOrNull(highlight)?.let { b ->
            val x = logFraction(b.freq, Equalizer.MIN_FREQ, Equalizer.MAX_FREQ) * w
            drawLine(line.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, h), 3f)
        }
        val path = Path()
        val steps = 160
        for (i in 0..steps) {
            val f = fromLog(i / steps.toFloat(), Equalizer.MIN_FREQ, Equalizer.MAX_FREQ)
            val py = y(Equalizer.responseDb(profile, f).coerceIn(-Equalizer.MAX_GAIN, Equalizer.MAX_GAIN))
            val px = i / steps.toFloat() * w
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, line, style = Stroke(width = 4f))
    }
}

/** A rotary knob: drag up/down to turn (0..1), double-tap to reset. */
@Composable
private fun Dial(label: String, text: String, fraction: Float, color: Color, onFraction: (Float) -> Unit, onReset: () -> Unit) {
    // The gesture handlers live across recompositions: read the latest value AND callbacks (which carry the
    // selected band), or a drag would keep editing whichever band was selected when the dial first appeared.
    val current by rememberUpdatedState(fraction)
    val change by rememberUpdatedState(onFraction)
    val reset by rememberUpdatedState(onReset)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier.size(84.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { ev, dy ->
                        ev.consume()
                        change((current - dy / 600f).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) },
        ) {
            val stroke = 10f
            val arc = Size(size.width - stroke * 2, size.height - stroke * 2)
            val topLeft = Offset(stroke, stroke)
            drawArc(track, 135f, 270f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * current, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A vertical fader for ±dB (0 in the middle): drag to set, double-tap for 0. Greyed for pass filters. */
@Composable
private fun Fader(valueDb: Float, enabled: Boolean, color: Color, onValue: (Float) -> Unit) {
    val current by rememberUpdatedState(valueDb)
    val set by rememberUpdatedState(onValue) // latest band, see Dial
    val track = MaterialTheme.colorScheme.surfaceVariant
    val alpha = if (enabled) 1f else 0.3f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.width(48.dp).height(140.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures { ev, dy ->
                        ev.consume()
                        val perPx = 2 * Equalizer.MAX_GAIN / size.height
                        set(((current - dy * perPx) * 2).roundToInt() / 2f)
                    }
                }
                .pointerInput(enabled) { if (enabled) detectTapGestures(onDoubleTap = { set(0f) }) },
        ) {
            Canvas(modifier = Modifier.width(48.dp).height(140.dp)) {
                val cx = size.width / 2
                val h = size.height
                drawLine(track.copy(alpha = alpha), Offset(cx, 0f), Offset(cx, h), 12f, StrokeCap.Round)
                drawLine(track.copy(alpha = alpha), Offset(cx - 14f, h / 2), Offset(cx + 14f, h / 2), 3f)
                val y = h / 2 - current / Equalizer.MAX_GAIN * (h / 2)
                drawLine(color.copy(alpha = alpha), Offset(cx, h / 2), Offset(cx, y), 12f, StrokeCap.Round)
                drawRoundRect(
                    color.copy(alpha = alpha), Offset(cx - 22f, y - 10f), Size(44f, 20f),
                    androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                )
            }
        }
        Text(if (enabled) fmtDb(valueDb) else "—", style = MaterialTheme.typography.bodyMedium)
        Text("Gain", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun logFraction(v: Float, min: Float, max: Float) = (ln(v / min) / ln(max / min)).coerceIn(0f, 1f)
private fun fromLog(f: Float, min: Float, max: Float) = min * exp(f * ln(max / min))
private fun fmtHz(f: Float) = if (f >= 1000) "%.1f kHz".format(f / 1000).replace(".0 ", " ") else "${f.roundToInt()} Hz"
private fun fmtDb(db: Float) = (if (db > 0) "+" else "") + "%.1f dB".format(db)
private fun shortType(t: EqType) = when (t) {
    EqType.LOW_PASS -> "LP"; EqType.LOW_SHELF -> "LS"; EqType.BELL -> "Bell"; EqType.HIGH_SHELF -> "HS"; EqType.HIGH_PASS -> "HP"
}

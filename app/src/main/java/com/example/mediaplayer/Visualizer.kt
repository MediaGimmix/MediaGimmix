package com.example.mediaplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class VisStyle(val label: String) {
    BLOOM("Bass bloom"),
    SPECTRUM("Spectrum"),
    WARP("Warp"),
    LIQUID("Liquid"),
}

/**
 * Music visualizer: reacts mostly to bass (size, speed, beat flashes), with
 * mids steering colour and motion and spectral flux adding general rhythm.
 * Tap to switch style. Turns the [AudioAnalyzer] on only while shown.
 */
@Composable
fun VisualizerView(
    analyzer: AudioAnalyzer,
    playing: Boolean,
    unavailable: String?,
    style: VisStyle,
    onNextStyle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(analyzer) {
        analyzer.enabled = true
        onDispose { analyzer.enabled = false }
    }
    val state = remember { VisState() }
    val isPlaying by rememberUpdatedState(playing)
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(analyzer) {
        var lastNs = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.1f)
                lastNs = now
                state.update(analyzer.poll(), isPlaying, dt)
                tick++
            }
        }
    }

    var showLabel by remember { mutableStateOf(true) }
    LaunchedEffect(style) {
        showLabel = true
        delay(1500)
        showLabel = false
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onNextStyle),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") tick // redraw every frame
            when (style) {
                VisStyle.BLOOM -> drawBloom(state)
                VisStyle.SPECTRUM -> drawSpectrum(state)
                VisStyle.WARP -> drawWarp(state)
                VisStyle.LIQUID -> drawLiquid(state)
            }
        }
        val label = unavailable ?: if (showLabel) style.label else null
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            )
        }
    }
}

private class Star(var x: Float, var y: Float, var z: Float)

/** Smoothed levels and animation state, updated once per screen frame. */
private class VisState {
    val n = AudioAnalyzer.BANDS
    val bands = FloatArray(n)
    val peaks = FloatArray(n)
    var bass = 0f
    var mid = 0f
    var high = 0f
    var flux = 0f
    /** 1 on a beat, decays quickly. */
    var pulse = 0f
    var hue = 170f // starts near the app's teal
    var spin = 0f
    var time = 0f
    val phases = FloatArray(3)
    /** Expanding rings from beats: [age, strength]. */
    val rings = ArrayList<FloatArray>()
    val stars = Array(220) { Star(rnd(), rnd(), Random.nextFloat()) }

    // Auto-ranging, so quiet and loud masters both move the full range.
    private val lo = floatArrayOf(1f, 1f, 1f)
    private val hi = floatArrayOf(0f, 0f, 0f)

    fun update(snap: VisSnapshot, playing: Boolean, dt: Float) {
        val f = snap.frame.takeIf { playing }
        val attack = 1f - exp(-dt * 30f)
        val release = 1f - exp(-dt * 7f)
        fun approach(cur: Float, target: Float) = cur + (target - cur) * (if (target > cur) attack else release)

        for (i in 0 until n) {
            val target = f?.bands?.get(i)?.let { ((it - 0.12f) / 0.85f).coerceIn(0f, 1f) } ?: 0f
            bands[i] = approach(bands[i], target)
            peaks[i] = max(peaks[i] - dt * 0.6f, bands[i])
        }
        bass = approach(bass, ranged(0, f?.bass, dt))
        mid = approach(mid, ranged(1, f?.mid, dt))
        high = approach(high, ranged(2, f?.high, dt))
        flux = approach(flux, if (playing) snap.flux else 0f)

        if (playing && snap.beat > 0f) {
            pulse = max(pulse, snap.beat)
            rings.add(floatArrayOf(0f, snap.beat))
            hue += 25f * snap.beat
        }
        pulse *= exp(-dt * 6f)
        rings.forEach { it[0] += dt }
        rings.removeAll { it[0] > 1.4f }

        val energy = if (playing) 1f else 0.15f
        hue = (hue + dt * (6f + 40f * mid) * energy) % 360f
        spin += dt * (0.15f + 1.2f * mid + 2f * flux) * energy
        time += dt
        for (k in 0..2) phases[k] += dt * (1f + k) * (0.6f + 3f * flux + mid) * energy

        val speed = (0.12f + 1.1f * bass + 2.5f * pulse) * energy
        for (s in stars) {
            s.z -= dt * speed
            if (s.z <= 0.03f) {
                s.x = rnd()
                s.y = rnd()
                s.z = 1f
            }
        }
    }

    private fun ranged(i: Int, value: Float?, dt: Float): Float {
        if (value == null) return 0f
        lo[i] = min(value, lo[i] + dt * 0.08f)
        hi[i] = max(value, hi[i] - dt * 0.08f)
        val range = max(hi[i] - lo[i], 0.15f)
        return ((value - lo[i]) / range).coerceIn(0f, 1f)
    }

    companion object {
        fun rnd() = Random.nextFloat() * 2f - 1f
    }
}

private fun hsv(h: Float, s: Float, v: Float, a: Float = 1f): Color =
    Color.hsv(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f), a.coerceIn(0f, 1f))

// ---- styles -----------------------------------------------------------------

/** A glowing core that swells with the bass, radial spectrum around it, rings on beats. */
private fun DrawScope.drawBloom(s: VisState) {
    val c = center
    val m = min(size.width, size.height)
    drawRect(
        Brush.radialGradient(
            listOf(hsv(s.hue, 0.85f, 0.2f + 0.35f * s.bass + 0.3f * s.pulse), Color.Black),
            center = c,
            radius = m * (0.6f + 0.2f * s.bass),
        ),
    )
    for (r in s.rings) {
        val age = r[0]
        drawCircle(
            color = hsv(s.hue + 50f, 0.7f, 1f, (1f - age / 1.4f) * r[1]),
            radius = m * (0.2f + age * 0.55f),
            center = c,
            style = Stroke(width = 10f * (1.4f - age)),
        )
    }
    val r0 = m * (0.15f + 0.09f * s.bass + 0.05f * s.pulse)
    val count = s.n * 2
    val barWidth = (2f * PI.toFloat() * r0 / count) * 0.65f
    for (i in 0 until count) {
        val band = if (i < s.n) i else count - 1 - i // mirrored halves
        val a = s.spin + i * 2f * PI.toFloat() / count
        val len = m * (0.015f + 0.3f * s.bands[band])
        val dx = cos(a)
        val dy = sin(a)
        drawLine(
            color = hsv(s.hue + band * 4f, 0.8f, 1f),
            start = Offset(c.x + dx * r0, c.y + dy * r0),
            end = Offset(c.x + dx * (r0 + len), c.y + dy * (r0 + len)),
            strokeWidth = barWidth,
            cap = StrokeCap.Round,
        )
    }
    drawCircle(
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.6f + 0.4f * s.pulse), hsv(s.hue, 0.9f, 0.9f), hsv(s.hue + 40f, 1f, 0.3f)),
            center = c,
            radius = r0 * 0.92f,
        ),
        radius = r0 * 0.92f,
        center = c,
    )
}

/** Mirrored spectrum bars with falling peak caps; the bass lights the floor and beats flash. */
private fun DrawScope.drawSpectrum(s: VisState) {
    val w = size.width
    val h = size.height
    val mid = h / 2f
    drawRect(hsv(s.hue, 0.6f, 1f, 0.22f * s.pulse))
    drawRect(
        Brush.verticalGradient(
            listOf(Color.Transparent, hsv(s.hue + 180f, 0.9f, 0.6f, 0.15f + 0.5f * s.bass)),
            startY = mid,
            endY = h,
        ),
    )
    val slot = w / s.n
    val bar = slot * 0.72f
    val maxH = h * 0.44f * (0.85f + 0.15f * s.bass)
    for (i in 0 until s.n) {
        val x = i * slot + (slot - bar) / 2f
        val hh = maxH * s.bands[i]
        val color = hsv(s.hue + i * (220f / s.n), 0.85f, 0.95f)
        drawRect(color, topLeft = Offset(x, mid - hh), size = androidx.compose.ui.geometry.Size(bar, hh))
        drawRect(color.copy(alpha = 0.35f), topLeft = Offset(x, mid), size = androidx.compose.ui.geometry.Size(bar, hh * 0.7f))
        val py = mid - maxH * s.peaks[i]
        drawRect(Color.White.copy(alpha = 0.85f), topLeft = Offset(x, py - 4f), size = androidx.compose.ui.geometry.Size(bar, 4f))
    }
}

/** A starfield rushing at you; bass is the throttle, beats are boosts, mids tint the stars. */
private fun DrawScope.drawWarp(s: VisState) {
    val c = center
    val m = max(size.width, size.height) * 0.5f
    drawRect(hsv(s.hue, 0.8f, 0.5f, 0.12f * s.pulse))
    drawCircle(
        Brush.radialGradient(
            listOf(hsv(s.hue + 30f, 0.7f, 0.9f, 0.25f + 0.5f * s.bass), Color.Transparent),
            center = c,
            radius = m * (0.15f + 0.3f * s.bass),
        ),
        radius = m * (0.15f + 0.3f * s.bass),
        center = c,
    )
    val trail = 0.04f + 0.2f * s.bass + 0.3f * s.pulse
    for (st in s.stars) {
        val z0 = st.z
        val z1 = min(1f, st.z + trail)
        val p0 = Offset(c.x + st.x / z0 * m * 0.5f, c.y + st.y / z0 * m * 0.5f)
        val p1 = Offset(c.x + st.x / z1 * m * 0.5f, c.y + st.y / z1 * m * 0.5f)
        val near = 1f - z0
        drawLine(
            color = hsv(s.hue + st.x * 70f + s.mid * 120f, 0.55f, 1f, near * 1.2f),
            start = p1,
            end = p0,
            strokeWidth = 1.5f + near * 7f,
            cap = StrokeCap.Round,
        )
    }
}

/** Three layered waves: bass (big, slow), mids, highs (small, fast), swelling on beats. */
private fun DrawScope.drawLiquid(s: VisState) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(hsv(s.hue + 200f, 0.7f, 0.12f + 0.15f * s.pulse), Color.Black)))
    val layers = listOf(
        Triple(h * (0.08f + 0.22f * s.bass + 0.08f * s.pulse), 1.3f, 0f),
        Triple(h * (0.03f + 0.13f * s.mid), 2.7f, 45f),
        Triple(h * (0.015f + 0.07f * s.high + 0.04f * s.flux), 5.5f, 95f),
    )
    for ((k, layer) in layers.withIndex()) {
        val (amp, freq, hueShift) = layer
        val baseY = h * (0.42f + k * 0.14f)
        val path = Path()
        path.moveTo(0f, h)
        var x = 0f
        val step = max(4f, w / 120f)
        while (x <= w + step) {
            val t = x / w
            val wobble = 0.75f + 0.25f * sin(s.time * 0.7f + k + t * 3f)
            val y = baseY + amp * wobble * sin(2f * PI.toFloat() * freq * t + s.phases[k]) +
                amp * 0.3f * sin(2f * PI.toFloat() * freq * 2.3f * t - s.phases[k] * 1.7f)
            path.lineTo(x, y)
            x += step
        }
        path.lineTo(w, h)
        path.close()
        drawPath(
            path,
            Brush.verticalGradient(
                listOf(hsv(s.hue + hueShift, 0.8f, 1f, 0.75f), hsv(s.hue + hueShift + 30f, 0.9f, 0.4f, 0.35f)),
                startY = baseY - amp,
                endY = h,
            ),
        )
    }
}

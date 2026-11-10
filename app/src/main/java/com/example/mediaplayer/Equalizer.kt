package com.example.mediaplayer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/** One EQ filter. Gain only matters for shelves and bells. */
data class EqBand(val type: EqType, val freq: Float, val q: Float, val gainDb: Float, val on: Boolean = true)

enum class EqType(val label: String, val usesGain: Boolean) {
    LOW_PASS("Low pass", false),
    LOW_SHELF("Low shelf", true),
    BELL("Bell", true),
    HIGH_SHELF("High shelf", true),
    HIGH_PASS("High pass", false),
}

data class EqProfile(val name: String, val preampDb: Float, val bands: List<EqBand>)

/**
 * Parametric equalizer (navbar EQ): 2–[MAX_PROFILES] profiles of up to [MAX_BANDS] biquad filters (RBJ "Audio EQ
 * Cookbook": low/high pass, low/high shelf, bell) plus a preamp. Runs in [AnalyzingAudioSink], so it works in every
 * output mode except offload (the audio chip decodes there). Saved as JSON in "settings" → "eq". Button action
 * "Next EQ profile" calls [cycle].
 */
object Equalizer {
    const val MAX_BANDS = 8
    const val MIN_PROFILES = 2
    const val MAX_PROFILES = 8
    const val MIN_FREQ = 20f
    const val MAX_FREQ = 20_000f
    const val MIN_Q = 0.1f
    const val MAX_Q = 10f
    const val MAX_GAIN = 15f
    private const val KEY = "eq"

    var enabled by mutableStateOf(false)
        private set
    var active by mutableIntStateOf(0)
        private set
    var profiles by mutableStateOf(List(3) { defaultProfile(it) })
        private set

    /** What the audio thread reads; replaced (never mutated) on every change. */
    @Volatile internal var dsp: Dsp = Dsp(false, 1f, emptyList(), 0)
        private set
    private var version = 0
    private var prefs: android.content.SharedPreferences? = null

    internal class Dsp(val on: Boolean, val preamp: Float, val bands: List<EqBand>, val version: Int)

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            val o = JSONObject(p.getString(KEY, null) ?: return@runCatching)
            enabled = o.optBoolean("enabled")
            val arr = o.getJSONArray("profiles")
            val loaded = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { profileFrom(it) } }.take(MAX_PROFILES)
            profiles = loaded + List((MIN_PROFILES - loaded.size).coerceAtLeast(0)) { defaultProfile(loaded.size + it) }
            active = o.optInt("active").coerceIn(0, profiles.size - 1)
        }
        publish(save = false)
    }

    fun turnOn(on: Boolean) { enabled = on; publish() }

    fun selectProfile(index: Int) { active = index.coerceIn(0, profiles.size - 1); publish() }

    /** Adds a flat profile (up to [MAX_PROFILES]) and selects it. */
    fun addProfile() {
        if (profiles.size >= MAX_PROFILES) return
        profiles = profiles + defaultProfile(nextNumber() - 1)
        active = profiles.size - 1
        publish()
    }

    /** Removes the selected profile, keeping at least [MIN_PROFILES]. */
    fun removeActiveProfile() {
        if (profiles.size <= MIN_PROFILES) return
        profiles = profiles.filterIndexed { i, _ -> i != active }
        active = active.coerceAtMost(profiles.size - 1)
        publish()
    }

    /** Lowest "Profile N" number not in use, so names stay unique after removals. */
    private fun nextNumber(): Int {
        val used = profiles.mapNotNull { it.name.removePrefix("Profile ").toIntOrNull() }.toSet()
        return generateSequence(1) { it + 1 }.first { it !in used }
    }

    /** The "Next EQ profile" button action: cycles through all profiles (and switches the EQ on). Returns its name. */
    fun cycle(): String {
        active = (active + 1) % profiles.size
        enabled = true
        publish()
        return profiles[active].name
    }

    fun setPreamp(db: Float) = editActive { it.copy(preampDb = db.coerceIn(-MAX_GAIN, MAX_GAIN)) }

    fun updateBand(index: Int, band: EqBand) = editActive { p ->
        p.copy(bands = p.bands.mapIndexed { i, b -> if (i == index) clamp(band) else b })
    }

    fun addBand(): Int {
        var at = -1
        editActive { p ->
            if (p.bands.size >= MAX_BANDS) p else {
                at = p.bands.size
                p.copy(bands = p.bands + EqBand(EqType.BELL, 1000f, 1f, 0f))
            }
        }
        return at
    }

    fun removeBand(index: Int) = editActive { p -> p.copy(bands = p.bands.filterIndexed { i, _ -> i != index }) }

    fun resetActive() = editActive { defaultProfile(active).copy(name = it.name) }

    private fun editActive(change: (EqProfile) -> EqProfile) {
        profiles = profiles.mapIndexed { i, p -> if (i == active) change(p) else p }
        publish()
    }

    private fun clamp(b: EqBand) = b.copy(
        freq = b.freq.coerceIn(MIN_FREQ, MAX_FREQ),
        q = b.q.coerceIn(MIN_Q, MAX_Q),
        gainDb = b.gainDb.coerceIn(-MAX_GAIN, MAX_GAIN),
    )

    private fun publish(save: Boolean = true) {
        val p = profiles[active]
        dsp = Dsp(enabled, 10f.pow(p.preampDb / 20f), p.bands.filter { it.on }, ++version)
        if (save) prefs?.edit()?.putString(KEY, toJson().toString())?.apply()
    }

    private fun defaultProfile(i: Int) = EqProfile(
        "Profile ${i + 1}", 0f,
        listOf(
            EqBand(EqType.LOW_SHELF, 100f, 0.7f, 0f),
            EqBand(EqType.BELL, 400f, 1f, 0f),
            EqBand(EqType.BELL, 1500f, 1f, 0f),
            EqBand(EqType.BELL, 4000f, 1f, 0f),
            EqBand(EqType.HIGH_SHELF, 9000f, 0.7f, 0f),
        ),
    )

    private fun toJson() = JSONObject().put("enabled", enabled).put("active", active).put(
        "profiles",
        JSONArray(profiles.map { p ->
            JSONObject().put("name", p.name).put("preamp", p.preampDb.toDouble()).put(
                "bands",
                JSONArray(p.bands.map { b ->
                    JSONObject().put("type", b.type.name).put("freq", b.freq.toDouble()).put("q", b.q.toDouble())
                        .put("gain", b.gainDb.toDouble()).put("on", b.on)
                }),
            )
        }),
    )

    private fun profileFrom(o: JSONObject): EqProfile {
        val bands = o.optJSONArray("bands") ?: JSONArray()
        return EqProfile(
            o.optString("name").ifEmpty { "Profile" }, o.optDouble("preamp", 0.0).toFloat(),
            (0 until bands.length()).mapNotNull { i ->
                val b = bands.getJSONObject(i)
                val type = runCatching { EqType.valueOf(b.getString("type")) }.getOrNull() ?: return@mapNotNull null
                clamp(EqBand(type, b.optDouble("freq").toFloat(), b.optDouble("q").toFloat(), b.optDouble("gain").toFloat(), b.optBoolean("on", true)))
            }.take(MAX_BANDS),
        )
    }

    // ---- maths ------------------------------------------------------------------------------

    /** Normalised biquad coefficients b0, b1, b2, a1, a2 (a0 = 1). */
    internal fun coefficients(b: EqBand, sampleRate: Int): DoubleArray {
        val f = b.freq.toDouble().coerceAtMost(sampleRate * 0.45)
        val w = 2 * PI * f / sampleRate
        val cw = cos(w)
        val alpha = sin(w) / (2 * b.q)
        val a = 10.0.pow(b.gainDb / 40.0)
        val sa = 2 * sqrt(a) * alpha
        val c = when (b.type) {
            EqType.BELL -> doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
            EqType.LOW_SHELF -> doubleArrayOf(
                a * ((a + 1) - (a - 1) * cw + sa), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sa),
                (a + 1) + (a - 1) * cw + sa, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sa,
            )
            EqType.HIGH_SHELF -> doubleArrayOf(
                a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa),
                (a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa,
            )
            EqType.LOW_PASS -> doubleArrayOf((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
            EqType.HIGH_PASS -> doubleArrayOf((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
        }
        val a0 = c[3]
        return doubleArrayOf(c[0] / a0, c[1] / a0, c[2] / a0, c[4] / a0, c[5] / a0)
    }

    /** The whole profile's response in dB at [freq] (for the graph), at 48 kHz. */
    fun responseDb(p: EqProfile, freq: Float): Float {
        val w = 2 * PI * freq / 48_000.0
        var db = p.preampDb.toDouble()
        for (b in p.bands) {
            if (!b.on) continue
            val k = coefficients(b, 48_000)
            // |H(e^jw)| = |b0 + b1 e^-jw + b2 e^-2jw| / |1 + a1 e^-jw + a2 e^-2jw|
            val nr = k[0] + k[1] * cos(w) + k[2] * cos(2 * w)
            val ni = -(k[1] * sin(w) + k[2] * sin(2 * w))
            val dr = 1 + k[3] * cos(w) + k[4] * cos(2 * w)
            val di = -(k[3] * sin(w) + k[4] * sin(2 * w))
            db += 20 * log10((hypot(nr, ni) / hypot(dr, di)).coerceAtLeast(1e-9))
        }
        return db.toFloat()
    }
}

/**
 * Runs [Equalizer] on PCM buffers in place (16-bit, 24-bit packed, 32-bit int, float; little-endian). One per output
 * sink; filter state per channel and band, coefficients rebuilt when settings or the sample rate change.
 */
internal class EqProcessor {
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var rate = 0
    private var builtFor = -1
    private var coeffs: Array<DoubleArray> = emptyArray()
    /** [band][channel * 2 + {0,1}] transposed direct form II state. */
    private var state: Array<DoubleArray> = emptyArray()

    fun configure(encoding: Int, channels: Int, sampleRate: Int) {
        this.encoding = encoding
        this.channels = channels
        rate = sampleRate
        builtFor = -1
    }

    fun reset() { state.forEach { it.fill(0.0) } }

    /** True when [process] would change samples (EQ on, PCM format known). */
    fun wouldProcess(): Boolean = Equalizer.dsp.on && channels > 0 && rate > 0 && encoding in
        intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)

    fun process(buffer: ByteBuffer) {
        val d = Equalizer.dsp
        if (!d.on || channels <= 0 || rate <= 0) return
        val bytes = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> return // compressed (offload) or unknown: untouched
        }
        if (builtFor != d.version) {
            coeffs = d.bands.map { Equalizer.coefficients(it, rate) }.toTypedArray()
            if (state.size != coeffs.size || state.firstOrNull()?.size != channels * 2) {
                state = Array(coeffs.size) { DoubleArray(channels * 2) }
            }
            builtFor = d.version
        }
        val buf = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var pos = buffer.position()
        val end = buffer.limit() - bytes + 1
        var ch = 0
        while (pos < end) {
            var x = when (bytes) {
                2 -> buf.getShort(pos) / 32768.0
                3 -> ((buf.get(pos).toInt() and 0xFF) or ((buf.get(pos + 1).toInt() and 0xFF) shl 8) or (buf.get(pos + 2).toInt() shl 16)) / 8388608.0
                else -> if (encoding == C.ENCODING_PCM_FLOAT) buf.getFloat(pos).toDouble() else buf.getInt(pos) / 2147483648.0
            } * d.preamp
            for (i in coeffs.indices) {
                val k = coeffs[i]
                val s = state[i]
                val y = k[0] * x + s[ch * 2]
                s[ch * 2] = k[1] * x - k[3] * y + s[ch * 2 + 1]
                s[ch * 2 + 1] = k[2] * x - k[4] * y
                x = y
            }
            x = x.coerceIn(-1.0, 1.0)
            when (bytes) {
                2 -> buf.putShort(pos, (x * 32767.0).toInt().toShort())
                3 -> {
                    val v = (x * 8388607.0).toInt()
                    buf.put(pos, v.toByte()); buf.put(pos + 1, (v shr 8).toByte()); buf.put(pos + 2, (v shr 16).toByte())
                }
                else -> if (encoding == C.ENCODING_PCM_FLOAT) buf.putFloat(pos, x.toFloat()) else buf.putInt(pos, (x * 2147483647.0).toInt())
            }
            pos += bytes
            ch = (ch + 1) % channels
        }
    }
}

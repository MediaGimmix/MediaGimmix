package com.example.mediaplayer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One analysis window. Levels are 0..1 (dB scaled); [beat] > 0 marks a bass hit. */
class VisFrame(
    val ptsUs: Long,
    val bands: FloatArray,
    val bass: Float,
    val mid: Float,
    val high: Float,
    val beat: Float,
    val flux: Float,
)

/** What the visualizer gets per screen frame: the frame playing now plus the strongest beat/flux since the last poll. */
class VisSnapshot(val frame: VisFrame?, val beat: Float, val flux: Float)

/**
 * Spectrum and beat analysis of the decoded audio, for the visualizer.
 *
 * [AnalyzingAudioSink] feeds it PCM on ExoPlayer's playback thread, ahead of
 * what is heard; frames are stamped with their media time and handed out by
 * [poll] only once the sink's play position reaches them, so the picture
 * matches the sound in every output mode. Does nothing unless [enabled].
 */
class AudioAnalyzer {
    @Volatile var enabled = false

    /** The sink's play position (renderer time, same clock as the frame stamps). */
    @Volatile var positionUs = Long.MIN_VALUE

    private val lock = Any()
    private val frames = ArrayDeque<VisFrame>()
    private var last: VisFrame? = null

    // ---- playback thread only
    private var rate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var n = 0
    private var acc = FloatArray(0)
    private var accCount = 0
    private var accStartUs = C.TIME_UNSET
    private var hann = FloatArray(0)
    private var re = FloatArray(0)
    private var im = FloatArray(0)
    private var prevBands = FloatArray(BANDS)
    private var bassAvg = 0f
    private var lastBeatUs = Long.MIN_VALUE

    fun configure(format: Format) {
        rate = format.sampleRate
        channels = format.channelCount
        encoding = if (format.sampleMimeType == MimeTypes.AUDIO_RAW) format.pcmEncoding else C.ENCODING_INVALID
        if (rate <= 0) return
        // ~20 Hz per FFT bin at any sample rate, so the bass bins stay usable.
        n = when {
            rate <= 50_000 -> 2048
            rate <= 100_000 -> 4096
            else -> 8192
        }
        acc = FloatArray(n)
        re = FloatArray(n)
        im = FloatArray(n)
        hann = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }
        reset()
    }

    fun reset() {
        accCount = 0
        accStartUs = C.TIME_UNSET
        prevBands.fill(0f)
        bassAvg = 0f
        lastBeatUs = Long.MIN_VALUE
        synchronized(lock) {
            frames.clear()
            last = null
        }
    }

    fun feed(buffer: ByteBuffer, ptsUs: Long) {
        if (!enabled || n == 0 || channels <= 0) return
        val bps = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameCount = b.remaining() / (bps * channels)
        val usPerFrame = 1_000_000.0 / rate
        if (accCount == 0 || accStartUs == C.TIME_UNSET) {
            accStartUs = ptsUs
            accCount = 0
        } else {
            val expected = accStartUs + (accCount * usPerFrame).toLong()
            if (abs(expected - ptsUs) > 100_000) { // a jump: start a fresh window
                accStartUs = ptsUs
                accCount = 0
            }
        }
        var pos = b.position()
        val hop = n / 2
        for (i in 0 until frameCount) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += when (encoding) {
                    C.ENCODING_PCM_16BIT -> b.getShort(pos) / 32768f
                    C.ENCODING_PCM_FLOAT -> b.getFloat(pos)
                    C.ENCODING_PCM_24BIT -> {
                        val v = (b.get(pos).toInt() and 0xff) or ((b.get(pos + 1).toInt() and 0xff) shl 8) or (b.get(pos + 2).toInt() shl 16)
                        v / 8388608f
                    }
                    else -> b.getInt(pos) / 2147483648f
                }
                pos += bps
            }
            acc[accCount++] = sum / channels
            if (accCount == n) {
                analyze(accStartUs + (hop * usPerFrame).toLong())
                System.arraycopy(acc, hop, acc, 0, n - hop) // 50% overlap
                accCount = n - hop
                accStartUs += (hop * usPerFrame).toLong()
            }
        }
    }

    /** Called on the UI thread every screen frame. */
    fun poll(): VisSnapshot {
        val pos = positionUs
        synchronized(lock) {
            var beat = 0f
            var flux = 0f
            while (frames.isNotEmpty() && frames.first().ptsUs <= pos) {
                val f = frames.removeFirst()
                beat = max(beat, f.beat)
                flux = max(flux, f.flux)
                last = f
            }
            return VisSnapshot(last, beat, flux)
        }
    }

    private fun analyze(centerUs: Long) {
        for (i in 0 until n) {
            re[i] = acc[i] * hann[i]
            im[i] = 0f
        }
        fft(re, im)
        val binHz = rate.toFloat() / n
        val scale = 4f / n // a full-scale sine comes out near 1.0

        fun amp(k: Int): Float = sqrt(re[k] * re[k] + im[k] * im[k]) * scale
        fun rangeEnergy(loHz: Float, hiHz: Float): Float {
            val k0 = max(1, (loHz / binHz).toInt())
            val k1 = min(n / 2 - 1, max(k0, (hiHz / binHz).toInt()))
            var e = 0f
            for (k in k0..k1) e += amp(k).let { it * it }
            return e / (k1 - k0 + 1)
        }

        val topHz = min(16_000f, rate / 2f - binHz)
        val bands = FloatArray(BANDS)
        for (bnd in 0 until BANDS) {
            val lo = MIN_HZ * (topHz / MIN_HZ).pow(bnd.toFloat() / BANDS)
            val hi = MIN_HZ * (topHz / MIN_HZ).pow((bnd + 1).toFloat() / BANDS)
            val k0 = max(1, (lo / binHz).toInt())
            val k1 = max(k0, (hi / binHz).toInt())
            var peak = 0f
            for (k in k0..min(k1, n / 2 - 1)) peak = max(peak, amp(k))
            // Tilt up the highs a little: music has far less energy there.
            bands[bnd] = dbLevel(peak * (1f + 2f * bnd / BANDS))
        }

        val bassE = rangeEnergy(20f, 150f)
        val midE = rangeEnergy(250f, 2000f)
        val highE = rangeEnergy(4000f, 12000f)

        // Beat: bass energy jumping well above its recent average.
        if (bassAvg == 0f) bassAvg = bassE
        val ratio = bassE / (bassAvg + 1e-9f)
        var beat = 0f
        if (ratio > BEAT_RATIO && bassE > 1e-6f && centerUs - lastBeatUs > MIN_BEAT_GAP_US) {
            beat = (0.4f + (ratio - BEAT_RATIO) / 1.5f).coerceAtMost(1f)
            lastBeatUs = centerUs
        }
        bassAvg = bassAvg * 0.9f + bassE * 0.1f

        // Spectral flux: how much the spectrum rose since the last window (general rhythm).
        var flux = 0f
        for (i in 0 until BANDS) flux += max(0f, bands[i] - prevBands[i])
        prevBands = bands
        flux = (flux / BANDS * 6f).coerceAtMost(1f)

        val frame = VisFrame(
            ptsUs = centerUs,
            bands = bands,
            bass = dbLevel(sqrt(bassE)),
            mid = dbLevel(sqrt(midE)),
            high = dbLevel(sqrt(highE)),
            beat = beat,
            flux = flux,
        )
        synchronized(lock) {
            frames.addLast(frame)
            while (frames.size > MAX_FRAMES) frames.removeFirst()
        }
    }

    /** Amplitude to 0..1 over a [-66 dB, -6 dB] range. */
    private fun dbLevel(amplitude: Float): Float {
        if (amplitude <= 1e-7f) return 0f
        val db = 20f * log10(amplitude)
        return ((db + 66f) / 60f).coerceIn(0f, 1f)
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val size = re.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < size) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        const val BANDS = 48
        private const val MIN_HZ = 30f
        private const val MAX_FRAMES = 400
        private const val BEAT_RATIO = 1.45f
        private const val MIN_BEAT_GAP_US = 230_000L
    }
}

/**
 * Wraps any output sink (AudioTrack or native) and shows the PCM going into it
 * to an [AudioAnalyzer]. Compressed data (offload mode) is passed on untouched.
 */
@OptIn(UnstableApi::class)
class AnalyzingAudioSink(sink: AudioSink, private val analyzer: AudioAnalyzer) : ForwardingAudioSink(sink) {
    private var lastBuffer: ByteBuffer? = null
    private var lastPts = C.TIME_UNSET

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        analyzer.configure(inputFormat)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // A buffer that wasn't fully taken comes back again; analyse it only the first time.
        if (buffer !== lastBuffer || presentationTimeUs != lastPts) {
            lastBuffer = buffer
            lastPts = presentationTimeUs
            analyzer.feed(buffer, presentationTimeUs)
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = super.getCurrentPositionUs(sourceEnded)
        if (p != AudioSink.CURRENT_POSITION_NOT_SET) analyzer.positionUs = p
        return p
    }

    override fun flush() {
        super.flush()
        lastBuffer = null
        analyzer.reset()
    }

    override fun reset() {
        super.reset()
        lastBuffer = null
        analyzer.reset()
    }
}

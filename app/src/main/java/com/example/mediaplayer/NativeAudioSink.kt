package com.example.mediaplayer

import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** JNI bindings for app/src/main/cpp/native_audio.cpp. */
object NativeAudio {
    const val API_OPENSL = 0
    const val API_AAUDIO = 1

    const val IN_I16 = 0
    const val IN_FLOAT = 1
    const val IN_I24 = 2
    const val IN_I32 = 3

    init {
        System.loadLibrary("nativeaudio")
    }

    @JvmStatic external fun nCreate(api: Int): Long
    @JvmStatic external fun nDestroy(handle: Long)
    @JvmStatic external fun nOpen(handle: Long, rate: Int, channels: Int, inEncoding: Int, ringMs: Int, deviceId: Int): String?
    @JvmStatic external fun nClose(handle: Long)
    @JvmStatic external fun nStart(handle: Long)
    @JvmStatic external fun nPause(handle: Long)
    @JvmStatic external fun nFlush(handle: Long)
    @JvmStatic external fun nWrite(handle: Long, buffer: ByteBuffer, offset: Int, length: Int): Int
    @JvmStatic external fun nFramesPlayed(handle: Long): Long
    @JvmStatic external fun nSetVolume(handle: Long, volume: Float)
    @JvmStatic external fun nIsDisconnected(handle: Long): Boolean
    @JvmStatic external fun nInfo(handle: Long): String
    @JvmStatic external fun nUnderruns(handle: Long): Int
}

/**
 * An ExoPlayer [AudioSink] that plays PCM through AAudio or OpenSL ES instead
 * of AudioTrack. Takes 16-bit, 24-bit, 32-bit and float PCM in mono or stereo;
 * the decoder is asked for float, which goes out as float at the file's rate.
 * No speed change or silence skipping.
 *
 * ExoPlayer calls all of this on its playback thread. [onInfo] gets a line
 * describing the opened stream, on the main thread.
 */
@OptIn(UnstableApi::class)
class NativeAudioSink(private val api: Int, private val onInfo: (String) -> Unit) : AudioSink {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: AudioSink.Listener? = null
    private var handle = 0L

    private var format: Format? = null
    private var inEncoding = NativeAudio.IN_FLOAT
    private var sampleRate = 0
    private var channelCount = 0
    private var inFrameBytes = 0
    private var opened = false
    private var playing = false

    /** Media time of the first frame written since the last flush. */
    private var startMediaTimeUs = C.TIME_UNSET
    private var framesWritten = 0L
    private var endOfStream = false
    /** The buffer being consumed across several [handleBuffer] calls. */
    private var currentBuffer: ByteBuffer? = null
    private var scratch: ByteBuffer? = null

    /** AudioDeviceInfo id to play on (AAudio only), 0 = Android decides. */
    private var preferredDeviceId = 0
    private var reopenPending = false

    private var volume = 1f
    private var audioAttributes = AudioAttributes.DEFAULT
    private var playbackParameters = PlaybackParameters.DEFAULT

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW) return AudioSink.SINK_FORMAT_UNSUPPORTED
        if (format.channelCount !in 1..2) return AudioSink.SINK_FORMAT_UNSUPPORTED
        return if (encodingCode(format.pcmEncoding) != null) {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!opened || startMediaTimeUs == C.TIME_UNSET) return AudioSink.CURRENT_POSITION_NOT_SET
        val played = minOf(NativeAudio.nFramesPlayed(handle), framesWritten)
        return startMediaTimeUs + framesToUs(played)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val code = encodingCode(inputFormat.pcmEncoding)
        if (inputFormat.sampleMimeType != MimeTypes.AUDIO_RAW || code == null || inputFormat.channelCount !in 1..2) {
            throw AudioSink.ConfigurationException("Unsupported format for native output: $inputFormat", inputFormat)
        }
        val changed = code != inEncoding || inputFormat.sampleRate != sampleRate || inputFormat.channelCount != channelCount
        if (opened && changed) {
            // Let what's buffered finish? Simpler and rare (format changes within one song): start over.
            closeStream()
        }
        format = inputFormat
        inEncoding = code
        sampleRate = inputFormat.sampleRate
        channelCount = inputFormat.channelCount
        inFrameBytes = channelCount * when (code) {
            NativeAudio.IN_I16 -> 2
            NativeAudio.IN_I24 -> 3
            else -> 4
        }
    }

    override fun play() {
        playing = true
        if (opened) NativeAudio.nStart(handle)
    }

    override fun handleDiscontinuity() {}

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!opened) openStream()
        if (reopenPending || NativeAudio.nIsDisconnected(handle)) {
            // A different output device was picked, or the device went away (e.g. USB DAC unplugged): reopen.
            reopenPending = false
            val positionUs = getCurrentPositionUs(false)
            closeStream()
            openStream()
            if (positionUs != AudioSink.CURRENT_POSITION_NOT_SET) startMediaTimeUs = positionUs
            listener?.onPositionDiscontinuity()
        }

        if (buffer !== currentBuffer) {
            currentBuffer = buffer
            if (startMediaTimeUs == C.TIME_UNSET) {
                startMediaTimeUs = presentationTimeUs
            } else {
                val expectedUs = startMediaTimeUs + framesToUs(framesWritten)
                if (abs(expectedUs - presentationTimeUs) > DISCONTINUITY_THRESHOLD_US) {
                    startMediaTimeUs += presentationTimeUs - expectedUs
                    listener?.onPositionDiscontinuity()
                }
            }
        }

        val remaining = buffer.remaining()
        if (remaining > 0) {
            val taken = if (buffer.isDirect) {
                NativeAudio.nWrite(handle, buffer, buffer.position(), remaining)
            } else {
                val s = scratchFor(remaining)
                s.put(buffer.duplicate())
                NativeAudio.nWrite(handle, s, 0, remaining)
            }
            buffer.position(buffer.position() + taken)
            framesWritten += taken / inFrameBytes
        }
        if (buffer.hasRemaining()) return false
        currentBuffer = null
        return true
    }

    override fun playToEndOfStream() {
        endOfStream = true
    }

    override fun isEnded(): Boolean = !opened || (endOfStream && !hasPendingData())

    override fun hasPendingData(): Boolean = opened && NativeAudio.nFramesPlayed(handle) < framesWritten

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        // Speed and pitch changes aren't supported here.
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}

    override fun getSkipSilenceEnabled(): Boolean = false

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes = audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {}

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        val id = audioDeviceInfo?.id ?: 0
        if (id == preferredDeviceId) return
        preferredDeviceId = id
        // OpenSL ES has no way to pick a device; only AAudio reopens.
        if (opened && api == NativeAudio.API_AAUDIO) reopenPending = true
    }

    override fun enableTunnelingV21() {}

    override fun disableTunneling() {}

    override fun setVolume(volume: Float) {
        this.volume = volume
        if (handle != 0L) NativeAudio.nSetVolume(handle, volume)
    }

    override fun pause() {
        playing = false
        if (opened) NativeAudio.nPause(handle)
    }

    override fun flush() {
        if (opened) {
            NativeAudio.nFlush(handle)
            if (playing) NativeAudio.nStart(handle)
        }
        startMediaTimeUs = C.TIME_UNSET
        framesWritten = 0
        endOfStream = false
        currentBuffer = null
    }

    override fun reset() {
        flush()
        closeStream()
        playing = false
    }

    override fun release() {
        reset()
        if (handle != 0L) {
            NativeAudio.nDestroy(handle)
            handle = 0L
        }
    }

    // ---- internals ----------------------------------------------------------

    private fun openStream() {
        if (handle == 0L) handle = NativeAudio.nCreate(api)
        val error = NativeAudio.nOpen(handle, sampleRate, channelCount, inEncoding, RING_MS, preferredDeviceId)
        if (error != null) {
            throw AudioSink.InitializationException(error, 0, format ?: Format.Builder().build(), false, null)
        }
        opened = true
        framesWritten = 0
        startMediaTimeUs = C.TIME_UNSET
        NativeAudio.nSetVolume(handle, volume)
        if (playing) NativeAudio.nStart(handle)
        val info = NativeAudio.nInfo(handle)
        mainHandler.post { onInfo(info) }
    }

    private fun closeStream() {
        if (handle != 0L) NativeAudio.nClose(handle)
        opened = false
        framesWritten = 0
        startMediaTimeUs = C.TIME_UNSET
        currentBuffer = null
    }

    private fun scratchFor(size: Int): ByteBuffer {
        val s = scratch?.takeIf { it.capacity() >= size }
            ?: ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()).also { scratch = it }
        s.clear()
        return s
    }

    private fun framesToUs(frames: Long): Long = if (sampleRate == 0) 0 else frames * 1_000_000L / sampleRate

    private fun encodingCode(pcmEncoding: Int): Int? = when (pcmEncoding) {
        C.ENCODING_PCM_16BIT -> NativeAudio.IN_I16
        C.ENCODING_PCM_FLOAT -> NativeAudio.IN_FLOAT
        C.ENCODING_PCM_24BIT -> NativeAudio.IN_I24
        C.ENCODING_PCM_32BIT -> NativeAudio.IN_I32
        else -> null
    }

    private companion object {
        const val RING_MS = 500
        const val DISCONTINUITY_THRESHOLD_US = 200_000L
    }
}

package com.example.mediaplayer

import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink

/**
 * How decoded audio reaches the hardware. Changing the mode rebuilds the
 * ExoPlayer (see [Playback.changeOutputMode]); the choice is stored in the
 * "settings" SharedPreferences under [KEY].
 */
enum class OutputMode(val key: String, val label: String, val description: String) {
    AUDIO_TRACK(
        "audiotrack",
        "AudioTrack (16-bit)",
        "Standard Android output. Everything is converted to 16-bit PCM. Most compatible.",
    ),
    HI_RES(
        "hires",
        "Hi-Res AudioTrack (32-bit float)",
        "Decodes to 32-bit float and opens the output at the file's own sample rate, so 24-bit and " +
            "high sample rate files are not reduced. Whether Android's mixer resamples after that depends " +
            "on the device; the info below shows what Android reports.",
    ),
    OFFLOAD(
        "offload",
        "AudioTrack offload",
        "The audio hardware decodes formats it supports (often MP3, AAC, FLAC) to save battery. " +
            "Other formats fall back to 16-bit AudioTrack. Gaps between songs and seeking can be less precise.",
    ),
    OPENSL(
        "opensl",
        "OpenSL ES",
        "Native OpenSL ES output (32-bit float at the file's sample rate). Goes through Android's mixer " +
            "like AudioTrack, but by a different route; can sound or behave differently on some devices.",
    ),
    AAUDIO(
        "aaudio",
        "AAudio",
        "Native AAudio output (32-bit float at the file's sample rate). Asks for an exclusive stream, " +
            "which can skip Android's mixer; falls back to a shared stream if the device says no. " +
            "While exclusive, other apps' sounds may not play.",
    ),
    ;

    val isNative: Boolean get() = this == OPENSL || this == AAUDIO

    companion object {
        const val KEY = "output_mode"
        fun fromKey(key: String?): OutputMode = entries.firstOrNull { it.key == key } ?: AUDIO_TRACK
    }
}

/** What is actually playing: the file's format, the decoder and the opened AudioTrack. */
data class OutputInfo(
    val input: String = "",
    val decoder: String = "",
    val output: String = "",
    val direct: String = "",
)

@OptIn(UnstableApi::class)
object OutputFormatting {

    fun input(format: Format): String {
        val parts = mutableListOf<String>()
        parts += codecName(format.sampleMimeType)
        if (format.pcmEncoding != Format.NO_VALUE) parts += encodingName(format.pcmEncoding)
        if (format.sampleRate != Format.NO_VALUE) parts += khz(format.sampleRate)
        if (format.channelCount != Format.NO_VALUE) parts += channels(format.channelCount)
        if (format.bitrate != Format.NO_VALUE) parts += "${format.bitrate / 1000} kbps"
        return parts.joinToString(" · ")
    }

    /** The "Direct" line for the native outputs, from the stream description native_audio.cpp gives. */
    fun nativeDirect(context: android.content.Context, info: String): String {
        if (info.contains("exclusive")) return "Yes: exclusive AAudio stream, skips Android's mixer"
        val am = context.getSystemService(AudioManager::class.java)
        val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        return if (rate != null) "No: Android's mixer runs at ${khz(rate)}" else "No: goes through Android's mixer"
    }

    fun output(config: AudioSink.AudioTrackConfig): String {
        val parts = mutableListOf(encodingName(config.encoding), khz(config.sampleRate))
        val ch = Integer.bitCount(config.channelConfig)
        if (ch > 0) parts += channels(ch)
        if (config.offload) parts += "offloaded"
        return parts.joinToString(" · ")
    }

    /**
     * Asks Android (13+) whether this format can skip the mixer. Only a hint:
     * the answer is about what the audio policy offers, not the track itself.
     */
    fun direct(config: AudioSink.AudioTrackConfig): String {
        if (config.offload) return "Offloaded to the audio hardware"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return ""
        return try {
            val format = AudioFormat.Builder()
                .setEncoding(config.encoding)
                .setSampleRate(config.sampleRate)
                .setChannelMask(config.channelConfig)
                .build()
            val attrs = PlatformAudioAttributes.Builder()
                .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val support = AudioManager.getDirectPlaybackSupport(format, attrs)
            if (support == AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED) {
                "No: goes through Android's mixer (may be resampled)"
            } else {
                "Yes: Android offers a direct output for this format"
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun codecName(mime: String?): String = when (mime) {
        null -> "?"
        "audio/flac" -> "FLAC"
        "audio/mpeg" -> "MP3"
        "audio/mp4a-latm" -> "AAC"
        "audio/alac" -> "ALAC"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/raw" -> "PCM"
        "audio/wav", "audio/x-wav" -> "WAV"
        else -> mime.removePrefix("audio/").uppercase()
    }

    fun encodingName(encoding: Int): String = when (encoding) {
        C.ENCODING_PCM_8BIT -> "8-bit"
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> "16-bit"
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> "24-bit"
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> "32-bit"
        C.ENCODING_PCM_FLOAT -> "32-bit float"
        C.ENCODING_MP3 -> "MP3"
        C.ENCODING_AAC_LC -> "AAC"
        C.ENCODING_OPUS -> "Opus"
        else -> "encoding $encoding"
    }

    fun khz(rate: Int): String =
        if (rate % 1000 == 0) "${rate / 1000} kHz" else "%.1f kHz".format(rate / 1000.0)

    private fun channels(n: Int): String = when (n) {
        1 -> "mono"
        2 -> "stereo"
        else -> "$n ch"
    }
}

/** A connected output the user can pick. [key] stays the same when the device reconnects. */
data class OutputDevice(val key: String, val label: String, val info: AudioDeviceInfo)

object OutputDevices {
    const val KEY = "output_device"

    /** Outputs worth offering for music (no earpiece, phone-call or virtual devices). */
    private val musicTypes = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_DOCK,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        26, // TYPE_BLE_HEADSET (API 31)
        27, // TYPE_BLE_SPEAKER (API 31)
        30, // TYPE_BLE_BROADCAST (API 33)
    )

    fun list(am: AudioManager): List<OutputDevice> =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink && it.type in musicTypes }
            .map { d ->
                val product = d.productName?.toString().orEmpty()
                val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) d.address.orEmpty() else ""
                val key = "${d.type}|$product|$address"
                OutputDevice(key, label(d.type, product), d)
            }
            .distinctBy { it.key }

    /** A readable name for a stored key, for a device that isn't connected right now. */
    fun labelFromKey(key: String): String {
        val parts = key.split('|')
        return label(parts.getOrNull(0)?.toIntOrNull() ?: 0, parts.getOrNull(1).orEmpty())
    }

    private fun label(type: Int, product: String): String {
        val kind = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Headphones (wired)"
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE -> "Line out"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital line out"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
            AudioDeviceInfo.TYPE_DOCK -> "Dock"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            26, 27, 30 -> "Bluetooth LE"
            else -> "Output $type"
        }
        // The built-in outputs report the phone's model name; that adds nothing.
        return if (product.isBlank() || product == Build.MODEL) kind else "$kind · $product"
    }
}

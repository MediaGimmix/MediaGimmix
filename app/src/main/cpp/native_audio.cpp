// Native audio output for NativeAudioSink.kt: AAudio or OpenSL ES.
//
// ExoPlayer's playback thread writes decoded PCM into a ring buffer (nWrite);
// the audio API's callback thread pulls from it (render). The ring stores the
// stream's own format: 32-bit float, or 16-bit when the input is 16-bit.
// A play position is kept as "frames handed to the audio API".

#include <jni.h>
#include <aaudio/AAudio.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "NativeAudio"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

enum Api { API_OPENSL = 0, API_AAUDIO = 1 };
enum InEnc { IN_I16 = 0, IN_FLOAT = 1, IN_I24 = 2, IN_I32 = 3 };

int inBytesPerSample(int enc) {
    switch (enc) {
        case IN_I16: return 2;
        case IN_I24: return 3;
        default: return 4;
    }
}

/** Single producer (playback thread), single consumer (audio callback). */
struct Ring {
    std::vector<uint8_t> buf;
    size_t cap = 0;
    std::atomic<uint64_t> w{0};
    std::atomic<uint64_t> r{0};

    void init(size_t bytes) {
        buf.assign(bytes, 0);
        cap = bytes;
        reset();
    }
    void reset() {
        w.store(0);
        r.store(0);
    }
    size_t readable() const { return (size_t)(w.load(std::memory_order_acquire) - r.load(std::memory_order_relaxed)); }
    size_t writable() const { return cap - (size_t)(w.load(std::memory_order_relaxed) - r.load(std::memory_order_acquire)); }

    void write(const uint8_t* src, size_t n) {
        uint64_t wp = w.load(std::memory_order_relaxed);
        size_t off = wp % cap;
        size_t first = std::min(n, cap - off);
        memcpy(&buf[off], src, first);
        if (n > first) memcpy(&buf[0], src + first, n - first);
        w.store(wp + n, std::memory_order_release);
    }
    void read(uint8_t* dst, size_t n) {
        uint64_t rp = r.load(std::memory_order_relaxed);
        size_t off = rp % cap;
        size_t first = std::min(n, cap - off);
        memcpy(dst, &buf[off], first);
        if (n > first) memcpy(dst + first, &buf[0], n - first);
        r.store(rp + n, std::memory_order_release);
    }
};

struct Player {
    int api = API_AAUDIO;
    int rate = 0;
    int channels = 0;
    int inEnc = IN_FLOAT;
    int deviceId = 0; // AAudio only; 0 = let Android choose
    bool isFloat = true;
    size_t frameBytes = 0;   // stream format
    size_t inFrameBytes = 0; // what ExoPlayer hands us

    Ring ring;
    std::mutex flushLock; // flush vs. the callback; the callback only try_locks
    std::atomic<int64_t> framesPlayed{0};
    std::atomic<float> volume{1.0f};
    std::atomic<bool> disconnected{false};
    std::atomic<int> underruns{0};
    bool started = false;
    std::string info;

    // AAudio
    AAudioStream* stream = nullptr;

    // OpenSL ES
    SLObjectItf engineObj = nullptr;
    SLEngineItf engine = nullptr;
    SLObjectItf mixObj = nullptr;
    SLObjectItf playerObj = nullptr;
    SLPlayItf play = nullptr;
    SLAndroidSimpleBufferQueueItf bq = nullptr;
    std::vector<uint8_t> slBufs;
    int slBufFrames = 0;
    int slBufCount = 4;
    int slNext = 0;
};

/** Fills [frames] frames at [dst] from the ring, silence for whatever is missing. */
void render(Player* p, uint8_t* dst, int frames) {
    size_t want = (size_t)frames * p->frameBytes;
    size_t got = 0;
    if (p->flushLock.try_lock()) {
        got = std::min(p->ring.readable(), want);
        got -= got % p->frameBytes;
        if (got > 0) p->ring.read(dst, got);
        p->framesPlayed.fetch_add((int64_t)(got / p->frameBytes), std::memory_order_release);
        p->flushLock.unlock();
    }
    if (got < want) {
        memset(dst + got, 0, want - got);
        if (got > 0) p->underruns.fetch_add(1);
    }
    float v = p->volume.load(std::memory_order_relaxed);
    if (v != 1.0f && got > 0) {
        size_t samples = got / (p->isFloat ? 4 : 2);
        if (p->isFloat) {
            auto* f = reinterpret_cast<float*>(dst);
            for (size_t i = 0; i < samples; i++) f[i] *= v;
        } else {
            auto* s = reinterpret_cast<int16_t*>(dst);
            for (size_t i = 0; i < samples; i++) s[i] = (int16_t)std::clamp((int)(s[i] * v), -32768, 32767);
        }
    }
}

/** Converts [frames] input frames to the stream format and puts them in the ring. */
void convertAndWrite(Player* p, const uint8_t* src, size_t frames) {
    if (!p->isFloat || p->inEnc == IN_FLOAT) { // same format
        p->ring.write(src, frames * p->frameBytes);
        return;
    }
    constexpr size_t CHUNK = 1024;
    float tmp[CHUNK];
    size_t samples = frames * p->channels;
    size_t inSample = inBytesPerSample(p->inEnc);
    while (samples > 0) {
        size_t n = std::min(samples, CHUNK);
        for (size_t i = 0; i < n; i++) {
            const uint8_t* s = src + i * inSample;
            switch (p->inEnc) {
                case IN_I16: {
                    int16_t v;
                    memcpy(&v, s, 2);
                    tmp[i] = v / 32768.0f;
                    break;
                }
                case IN_I24: {
                    int32_t v = (int32_t)((uint32_t)s[0] << 8 | (uint32_t)s[1] << 16 | (uint32_t)s[2] << 24) >> 8;
                    tmp[i] = v / 8388608.0f;
                    break;
                }
                default: {
                    int32_t v;
                    memcpy(&v, s, 4);
                    tmp[i] = (float)(v / 2147483648.0);
                    break;
                }
            }
        }
        p->ring.write(reinterpret_cast<uint8_t*>(tmp), n * 4);
        src += n * inSample;
        samples -= n;
    }
}

std::string khz(int rate) {
    char b[32];
    if (rate % 1000 == 0) snprintf(b, sizeof b, "%d kHz", rate / 1000);
    else snprintf(b, sizeof b, "%.1f kHz", rate / 1000.0);
    return b;
}

std::string chans(int n) { return n == 1 ? "mono" : n == 2 ? "stereo" : std::to_string(n) + " ch"; }

// ---- AAudio ------------------------------------------------------------------

aaudio_data_callback_result_t aaudioData(AAudioStream*, void* user, void* audio, int32_t frames) {
    render(static_cast<Player*>(user), static_cast<uint8_t*>(audio), frames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void aaudioError(AAudioStream*, void* user, aaudio_result_t error) {
    // The stream can't be closed from here; Kotlin sees the flag and reopens it.
    if (error == AAUDIO_ERROR_DISCONNECTED) static_cast<Player*>(user)->disconnected.store(true);
}

aaudio_result_t tryOpenAAudio(Player* p, bool exclusive) {
    AAudioStreamBuilder* b = nullptr;
    aaudio_result_t r = AAudio_createStreamBuilder(&b);
    if (r != AAUDIO_OK) return r;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    if (p->deviceId != 0) AAudioStreamBuilder_setDeviceId(b, p->deviceId);
    AAudioStreamBuilder_setSharingMode(b, exclusive ? AAUDIO_SHARING_MODE_EXCLUSIVE : AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(b, exclusive ? AAUDIO_PERFORMANCE_MODE_LOW_LATENCY : AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setSampleRate(b, p->rate);
    AAudioStreamBuilder_setChannelCount(b, p->channels);
    AAudioStreamBuilder_setFormat(b, p->isFloat ? AAUDIO_FORMAT_PCM_FLOAT : AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setDataCallback(b, aaudioData, p);
    AAudioStreamBuilder_setErrorCallback(b, aaudioError, p);
    if (__builtin_available(android 28, *)) {
        AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
        AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
    }
    AAudioStream* s = nullptr;
    r = AAudioStreamBuilder_openStream(b, &s);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) return r;

    // The stream must run at the file's rate and in our format, or the audio would play at the wrong pitch.
    if (AAudioStream_getSampleRate(s) != p->rate || AAudioStream_getChannelCount(s) != p->channels ||
        AAudioStream_getFormat(s) != (p->isFloat ? AAUDIO_FORMAT_PCM_FLOAT : AAUDIO_FORMAT_PCM_I16)) {
        AAudioStream_close(s);
        return AAUDIO_ERROR_INVALID_FORMAT;
    }
    // Largest buffer: fewer glitches with the screen off; latency doesn't matter for music.
    AAudioStream_setBufferSizeInFrames(s, AAudioStream_getBufferCapacityInFrames(s));
    p->stream = s;
    return AAUDIO_OK;
}

std::string openAAudio(Player* p) {
    aaudio_result_t r = tryOpenAAudio(p, /* exclusive = */ true);
    if (r != AAUDIO_OK) r = tryOpenAAudio(p, false);
    if (r != AAUDIO_OK) return std::string("AAudio: ") + AAudio_convertResultToText(r);

    AAudioStream* s = p->stream;
    bool excl = AAudioStream_getSharingMode(s) == AAUDIO_SHARING_MODE_EXCLUSIVE;
    aaudio_performance_mode_t perf = AAudioStream_getPerformanceMode(s);
    p->info = std::string("AAudio · ") + (excl ? "exclusive" : "shared") +
              (perf == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY ? " · low latency" : "") + " · " +
              (p->isFloat ? "32-bit float" : "16-bit") + " · " + khz(p->rate) + " · " + chans(p->channels) +
              " · buffer " + std::to_string(AAudioStream_getBufferSizeInFrames(s)) + " frames";
    return "";
}

// ---- OpenSL ES ---------------------------------------------------------------

void slCallback(SLAndroidSimpleBufferQueueItf bq, void* ctx) {
    auto* p = static_cast<Player*>(ctx);
    size_t bytes = (size_t)p->slBufFrames * p->frameBytes;
    uint8_t* b = &p->slBufs[(size_t)p->slNext * bytes];
    render(p, b, p->slBufFrames);
    (*bq)->Enqueue(bq, b, (SLuint32)bytes);
    p->slNext = (p->slNext + 1) % p->slBufCount;
}

/** Queues every buffer with silence so the callback chain keeps running. */
void slPrime(Player* p) {
    size_t bytes = (size_t)p->slBufFrames * p->frameBytes;
    std::fill(p->slBufs.begin(), p->slBufs.end(), 0);
    for (int i = 0; i < p->slBufCount; i++) (*p->bq)->Enqueue(p->bq, &p->slBufs[(size_t)i * bytes], (SLuint32)bytes);
    p->slNext = 0;
}

std::string openOpenSL(Player* p) {
    SLresult r;
#define SL_CHECK(what) \
    if (r != SL_RESULT_SUCCESS) return std::string("OpenSL ES: ") + what + " failed (" + std::to_string(r) + ")"

    r = slCreateEngine(&p->engineObj, 0, nullptr, 0, nullptr, nullptr);
    SL_CHECK("create engine");
    r = (*p->engineObj)->Realize(p->engineObj, SL_BOOLEAN_FALSE);
    SL_CHECK("realize engine");
    r = (*p->engineObj)->GetInterface(p->engineObj, SL_IID_ENGINE, &p->engine);
    SL_CHECK("engine interface");
    r = (*p->engine)->CreateOutputMix(p->engine, &p->mixObj, 0, nullptr, nullptr);
    SL_CHECK("create output mix");
    r = (*p->mixObj)->Realize(p->mixObj, SL_BOOLEAN_FALSE);
    SL_CHECK("realize output mix");

    p->slBufFrames = std::max(256, p->rate / 50); // 20 ms
    p->slBufs.assign((size_t)p->slBufFrames * p->frameBytes * p->slBufCount, 0);

    SLDataLocator_AndroidSimpleBufferQueue loc = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, (SLuint32)p->slBufCount};
    SLAndroidDataFormat_PCM_EX fmt = {};
    fmt.formatType = SL_ANDROID_DATAFORMAT_PCM_EX;
    fmt.numChannels = (SLuint32)p->channels;
    fmt.sampleRate = (SLuint32)p->rate * 1000; // milliHz
    fmt.bitsPerSample = p->isFloat ? 32 : 16;
    fmt.containerSize = fmt.bitsPerSample;
    fmt.channelMask = p->channels == 1 ? SL_SPEAKER_FRONT_CENTER : (SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT);
    fmt.endianness = SL_BYTEORDER_LITTLEENDIAN;
    fmt.representation = p->isFloat ? SL_ANDROID_PCM_REPRESENTATION_FLOAT : SL_ANDROID_PCM_REPRESENTATION_SIGNED_INT;
    SLDataSource src = {&loc, &fmt};
    SLDataLocator_OutputMix outLoc = {SL_DATALOCATOR_OUTPUTMIX, p->mixObj};
    SLDataSink sink = {&outLoc, nullptr};

    const SLInterfaceID ids[] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE};
    const SLboolean req[] = {SL_BOOLEAN_TRUE};
    r = (*p->engine)->CreateAudioPlayer(p->engine, &p->playerObj, &src, &sink, 1, ids, req);
    SL_CHECK("create player");
    r = (*p->playerObj)->Realize(p->playerObj, SL_BOOLEAN_FALSE);
    SL_CHECK("realize player");
    r = (*p->playerObj)->GetInterface(p->playerObj, SL_IID_PLAY, &p->play);
    SL_CHECK("play interface");
    r = (*p->playerObj)->GetInterface(p->playerObj, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &p->bq);
    SL_CHECK("buffer queue interface");
    r = (*p->bq)->RegisterCallback(p->bq, slCallback, p);
    SL_CHECK("register callback");
    slPrime(p);
#undef SL_CHECK

    p->info = std::string("OpenSL ES · ") + (p->isFloat ? "32-bit float" : "16-bit") + " · " + khz(p->rate) + " · " +
              chans(p->channels) + " · " + std::to_string(p->slBufCount) + " × 20 ms buffers";
    return "";
}

void closeStream(Player* p) {
    if (p->stream) {
        AAudioStream_requestStop(p->stream);
        AAudioStream_close(p->stream);
        p->stream = nullptr;
    }
    if (p->playerObj) {
        (*p->playerObj)->Destroy(p->playerObj);
        p->playerObj = nullptr;
        p->play = nullptr;
        p->bq = nullptr;
    }
    if (p->mixObj) {
        (*p->mixObj)->Destroy(p->mixObj);
        p->mixObj = nullptr;
    }
    if (p->engineObj) {
        (*p->engineObj)->Destroy(p->engineObj);
        p->engineObj = nullptr;
        p->engine = nullptr;
    }
    p->started = false;
    p->disconnected.store(false);
}

void pauseStream(Player* p) {
    if (p->stream) {
        AAudioStream_requestPause(p->stream);
        aaudio_stream_state_t next = AAUDIO_STREAM_STATE_UNINITIALIZED;
        AAudioStream_waitForStateChange(p->stream, AAUDIO_STREAM_STATE_PAUSING, &next, 200 * 1000000LL);
    }
    if (p->play) (*p->play)->SetPlayState(p->play, SL_PLAYSTATE_PAUSED);
    p->started = false;
}

Player* P(jlong h) { return reinterpret_cast<Player*>(h); }

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_example_mediaplayer_NativeAudio_nCreate(JNIEnv*, jclass, jint api) {
    auto* p = new Player();
    p->api = api;
    return reinterpret_cast<jlong>(p);
}

JNIEXPORT void JNICALL Java_com_example_mediaplayer_NativeAudio_nDestroy(JNIEnv*, jclass, jlong h) {
    closeStream(P(h));
    delete P(h);
}

/** Returns null on success, else an error message. */
JNIEXPORT jstring JNICALL Java_com_example_mediaplayer_NativeAudio_nOpen(
    JNIEnv* env, jclass, jlong h, jint rate, jint channels, jint inEnc, jint ringMs, jint deviceId) {
    Player* p = P(h);
    closeStream(p);
    p->deviceId = deviceId;
    p->rate = rate;
    p->channels = channels;
    p->inEnc = inEnc;
    p->isFloat = inEnc != IN_I16;
    p->frameBytes = (size_t)channels * (p->isFloat ? 4 : 2);
    p->inFrameBytes = (size_t)channels * inBytesPerSample(inEnc);
    p->ring.init((size_t)rate * ringMs / 1000 * p->frameBytes);
    p->framesPlayed.store(0);
    p->underruns.store(0);
    std::string err = p->api == API_AAUDIO ? openAAudio(p) : openOpenSL(p);
    if (!err.empty()) {
        LOGE("%s", err.c_str());
        closeStream(p);
        return env->NewStringUTF(err.c_str());
    }
    return nullptr;
}

JNIEXPORT void JNICALL Java_com_example_mediaplayer_NativeAudio_nClose(JNIEnv*, jclass, jlong h) { closeStream(P(h)); }

JNIEXPORT void JNICALL Java_com_example_mediaplayer_NativeAudio_nStart(JNIEnv*, jclass, jlong h) {
    Player* p = P(h);
    if (p->stream) AAudioStream_requestStart(p->stream);
    if (p->play) (*p->play)->SetPlayState(p->play, SL_PLAYSTATE_PLAYING);
    p->started = true;
}

JNIEXPORT void JNICALL Java_com_example_mediaplayer_NativeAudio_nPause(JNIEnv*, jclass, jlong h) { pauseStream(P(h)); }

/** Drops everything buffered and resets the play position. Leaves the stream paused. */
JNIEXPORT void JNICALL Java_com_example_mediaplayer_NativeAudio_nFlush(JNIEnv*, jclass, jlong h) {
    Player* p = P(h);
    pauseStream(p);
    if (p->stream) AAudioStream_requestFlush(p->stream);
    if (p->play) {
        (*p->play)->SetPlayState(p->play, SL_PLAYSTATE_STOPPED);
        (*p->bq)->Clear(p->bq);
    }
    {
        std::lock_guard<std::mutex> lock(p->flushLock);
        p->ring.reset();
        p->framesPlayed.store(0);
    }
    if (p->bq) slPrime(p);
}

/** Takes whole frames from [buffer] while there is room; returns the number of bytes taken. */
JNIEXPORT jint JNICALL Java_com_example_mediaplayer_NativeAudio_nWrite(
    JNIEnv* env, jclass, jlong h, jobject buffer, jint offset, jint length) {
    Player* p = P(h);
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!base || p->frameBytes == 0) return 0;
    size_t frames = std::min((size_t)length / p->inFrameBytes, p->ring.writable() / p->frameBytes);
    if (frames == 0) return 0;
    convertAndWrite(p, base + offset, frames);
    return (jint)(frames * p->inFrameBytes);
}

JNIEXPORT jlong JNICALL Java_com_example_mediaplayer_NativeAudio_nFramesPlayed(JNIEnv*, jclass, jlong h) {
    return P(h)->framesPlayed.load(std::memory_order_acquire);
}

JNIEXPORT void JNICALL Java_com_example_mediaplayer_NativeAudio_nSetVolume(JNIEnv*, jclass, jlong h, jfloat v) {
    P(h)->volume.store(v);
}

JNIEXPORT jboolean JNICALL Java_com_example_mediaplayer_NativeAudio_nIsDisconnected(JNIEnv*, jclass, jlong h) {
    return P(h)->disconnected.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_example_mediaplayer_NativeAudio_nInfo(JNIEnv* env, jclass, jlong h) {
    return env->NewStringUTF(P(h)->info.c_str());
}

JNIEXPORT jint JNICALL Java_com_example_mediaplayer_NativeAudio_nUnderruns(JNIEnv*, jclass, jlong h) {
    return P(h)->underruns.load();
}

} // extern "C"

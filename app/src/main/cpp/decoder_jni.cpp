// FLAC / WAV decoding for the demo app, via dr_flac and dr_wav.
// Output is interleaved int32 left-justified — exactly what UsbAudioOutput takes,
// so decoded samples reach the DAC unchanged.
#include <jni.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>

#define DR_FLAC_IMPLEMENTATION
#include "dr_flac.h"
#define DR_WAV_IMPLEMENTATION
#include "dr_wav.h"

namespace {

struct Decoder {
    drflac* flac = nullptr;
    drwav wav{};
    bool isWav = false;
    int fd = -1;

    ~Decoder() {
        if (flac) drflac_close(flac);
        if (isWav) drwav_uninit(&wav);
        if (fd >= 0) ::close(fd);
    }
};

Decoder* decoder(jlong h) { return reinterpret_cast<Decoder*>(h); }

// Read straight from the descriptor we were given. Re-opening it by path
// (/proc/self/fd/N) fails for files picked through Android's storage access layer.
int fdOf(void* user) { return static_cast<Decoder*>(user)->fd; }

size_t onRead(void* user, void* out, size_t bytes) {
    size_t total = 0;
    while (total < bytes) {
        const ssize_t n = ::read(fdOf(user), static_cast<uint8_t*>(out) + total, bytes - total);
        if (n <= 0) break;
        total += size_t(n);
    }
    return total;
}

off64_t seekTo(void* user, int offset, int origin) {
    const int whence = origin == 0 ? SEEK_SET : origin == 1 ? SEEK_CUR : SEEK_END;
    return lseek64(fdOf(user), offset, whence);
}

drflac_bool32 onSeekFlac(void* user, int offset, drflac_seek_origin origin) {
    return seekTo(user, offset, origin == DRFLAC_SEEK_SET ? 0 : origin == DRFLAC_SEEK_CUR ? 1 : 2) >= 0;
}

drwav_bool32 onSeekWav(void* user, int offset, drwav_seek_origin origin) {
    return seekTo(user, offset, origin == DRWAV_SEEK_SET ? 0 : origin == DRWAV_SEEK_CUR ? 1 : 2) >= 0;
}

drflac_bool32 onTellFlac(void* user, drflac_int64* cursor) {
    const off64_t pos = lseek64(fdOf(user), 0, SEEK_CUR);
    *cursor = pos;
    return pos >= 0;
}

drwav_bool32 onTellWav(void* user, drwav_int64* cursor) {
    const off64_t pos = lseek64(fdOf(user), 0, SEEK_CUR);
    *cursor = pos;
    return pos >= 0;
}

}  // namespace

#define JNI_FN(name) Java_com_freeaudiobypasser_app_AudioDecoder_##name

extern "C" {

// Takes ownership of `fd`. Returns 0 when the file is neither FLAC nor WAV.
JNIEXPORT jlong JNICALL JNI_FN(nativeOpen)(JNIEnv*, jclass, jint fd) {
    auto* d = new Decoder();
    d->fd = fd;
    d->flac = drflac_open(onRead, onSeekFlac, onTellFlac, d, nullptr);
    if (!d->flac) {
        lseek64(fd, 0, SEEK_SET);
        d->isWav = drwav_init(&d->wav, onRead, onSeekWav, onTellWav, d, nullptr);
    }
    if (!d->flac && !d->isWav) {
        delete d;
        return 0;
    }
    return reinterpret_cast<jlong>(d);
}

// Returns sampleRate, channels, bitsPerSample, totalFrames.
JNIEXPORT jlongArray JNICALL JNI_FN(nativeInfo)(JNIEnv* env, jclass, jlong h) {
    Decoder* d = decoder(h);
    jlong v[4];
    if (d->flac) {
        v[0] = d->flac->sampleRate;
        v[1] = d->flac->channels;
        v[2] = d->flac->bitsPerSample;
        v[3] = jlong(d->flac->totalPCMFrameCount);
    } else {
        v[0] = d->wav.sampleRate;
        v[1] = d->wav.channels;
        v[2] = d->wav.bitsPerSample;
        v[3] = jlong(d->wav.totalPCMFrameCount);
    }
    jlongArray out = env->NewLongArray(4);
    env->SetLongArrayRegion(out, 0, 4, v);
    return out;
}

// Fills the direct buffer with up to maxFrames frames; returns frames decoded (0 at the end).
JNIEXPORT jint JNICALL JNI_FN(nativeRead)(JNIEnv* env, jclass, jlong h, jobject buffer, jint maxFrames) {
    Decoder* d = decoder(h);
    auto* out = static_cast<drflac_int32*>(env->GetDirectBufferAddress(buffer));
    if (!out) return -1;
    if (d->flac) return jint(drflac_read_pcm_frames_s32(d->flac, drflac_uint64(maxFrames), out));
    return jint(drwav_read_pcm_frames_s32(&d->wav, drwav_uint64(maxFrames), out));
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeSeek)(JNIEnv*, jclass, jlong h, jlong frame) {
    Decoder* d = decoder(h);
    if (d->flac) return drflac_seek_to_pcm_frame(d->flac, drflac_uint64(frame)) ? JNI_TRUE : JNI_FALSE;
    return drwav_seek_to_pcm_frame(&d->wav, drwav_uint64(frame)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeClose)(JNIEnv*, jclass, jlong h) { delete decoder(h); }

}  // extern "C"

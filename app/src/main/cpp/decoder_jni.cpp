// FLAC / WAV decoding for the demo app, via dr_flac and dr_wav.
// Output is interleaved int32 left-justified — exactly what UsbAudioOutput takes,
// so decoded samples reach the DAC unchanged.
#include <jni.h>
#include <unistd.h>

#include <cstdio>
#include <string>

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

}  // namespace

#define JNI_FN(name) Java_com_freeaudiobypasser_app_AudioDecoder_##name

extern "C" {

// Takes ownership of `fd`. Returns 0 when the file is neither FLAC nor WAV.
JNIEXPORT jlong JNICALL JNI_FN(nativeOpen)(JNIEnv*, jclass, jint fd) {
    auto* d = new Decoder();
    d->fd = fd;
    const std::string path = "/proc/self/fd/" + std::to_string(fd);
    d->flac = drflac_open_file(path.c_str(), nullptr);
    if (!d->flac) d->isWav = drwav_init_file(&d->wav, path.c_str(), nullptr);
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

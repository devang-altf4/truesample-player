// JNI glue for com.freeaudiobypasser.usbaudio.NativeBridge.
#include <jni.h>

#include <string>
#include <vector>

#include "Log.h"
#include "UsbStreamer.h"

namespace {

void throwUsbAudio(JNIEnv* env, const std::string& message) {
    jclass cls = env->FindClass("com/freeaudiobypasser/usbaudio/UsbAudioException");
    if (cls) env->ThrowNew(cls, message.c_str());
}

UsbStreamer* streamer(jlong handle) { return reinterpret_cast<UsbStreamer*>(handle); }

jintArray toIntArray(JNIEnv* env, const std::vector<jint>& values) {
    jintArray array = env->NewIntArray(jsize(values.size()));
    env->SetIntArrayRegion(array, 0, jsize(values.size()), values.data());
    return array;
}

}  // namespace

#define JNI_FN(name) Java_com_freeaudiobypasser_usbaudio_NativeBridge_##name

extern "C" {

JNIEXPORT jlong JNICALL JNI_FN(nativeOpen)(JNIEnv* env, jobject, jint fd, jbyteArray raw) {
    const jsize length = env->GetArrayLength(raw);
    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(raw, 0, length, reinterpret_cast<jbyte*>(bytes.data()));

    auto* s = new UsbStreamer();
    std::string error;
    if (!s->open(fd, bytes.data(), bytes.size(), error)) {
        delete s;
        throwUsbAudio(env, error);
        return 0;
    }
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jstring JNICALL JNI_FN(nativeDescribe)(JNIEnv* env, jobject, jlong h) {
    return env->NewStringUTF(uac::describe(streamer(h)->device()).c_str());
}

// Flattened per output: iface, alt, endpoint, channels, subslot, bits, syncType,
// maxPacketBytes, continuous, minRate, maxRate, rateCount, rates...
JNIEXPORT jintArray JNICALL JNI_FN(nativeFormats)(JNIEnv* env, jobject, jlong h) {
    std::vector<jint> out;
    for (const uac::OutputFormat& f : streamer(h)->device().outputs) {
        out.insert(out.end(), {f.interfaceNumber, f.altSetting, f.endpointAddress, f.channels, f.subslotBytes,
                               f.bitResolution, jint(f.syncType), f.maxPacketBytes, f.continuousRates ? 1 : 0,
                               jint(f.minRate), jint(f.maxRate), jint(f.rates.size())});
        for (uint32_t r : f.rates) out.push_back(jint(r));
    }
    return toIntArray(env, out);
}

// Returns outputIndex, deviceRate, subslotBytes, bitResolution, bitPerfect, outputRate, resampled.
// modeIndex/modeRate pick a fixed output mode; modeIndex < 0 means automatic.
JNIEXPORT jintArray JNICALL JNI_FN(nativeStart)(JNIEnv* env, jobject, jlong h, jint rate, jint bits, jint channels,
                                               jint quality, jint modeIndex, jint modeRate) {
    UsbStreamer::StreamInfo info;
    std::string error;
    UsbStreamer::Mode mode{modeIndex, uint32_t(modeRate)};
    if (!streamer(h)->start(uint32_t(rate), uint32_t(bits), uint32_t(channels), int(quality),
                            modeIndex >= 0 ? &mode : nullptr, info, error)) {
        throwUsbAudio(env, error);
        return nullptr;
    }
    return toIntArray(env, {info.outputIndex, jint(info.deviceRate), jint(info.subslotBytes),
                            jint(info.bitResolution), info.bitPerfect ? 1 : 0, jint(info.outputRate),
                            info.resampled ? 1 : 0});
}

JNIEXPORT jint JNICALL JNI_FN(nativeWrite)(JNIEnv* env, jobject, jlong h, jobject buffer, jint frames) {
    auto* pcm = static_cast<const int32_t*>(env->GetDirectBufferAddress(buffer));
    if (!pcm) {
        throwUsbAudio(env, "write() needs a direct ByteBuffer");
        return -1;
    }
    return jint(streamer(h)->write(pcm, size_t(frames)));
}

JNIEXPORT void JNICALL JNI_FN(nativeDrain)(JNIEnv*, jobject, jlong h) { streamer(h)->drain(); }

JNIEXPORT void JNICALL JNI_FN(nativeStop)(JNIEnv*, jobject, jlong h) { streamer(h)->stop(); }

JNIEXPORT void JNICALL JNI_FN(nativeClose)(JNIEnv*, jobject, jlong h) { delete streamer(h); }

// Returns available, min, max, res, cur (1/256 dB).
JNIEXPORT jintArray JNICALL JNI_FN(nativeVolumeRange)(JNIEnv* env, jobject, jlong h) {
    const UsbStreamer::VolumeRange r = streamer(h)->volumeRange();
    return toIntArray(env, {r.available ? 1 : 0, r.min, r.max, r.res, r.cur});
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeSetVolume)(JNIEnv*, jobject, jlong h, jint value) {
    return streamer(h)->setVolume(int16_t(value)) ? JNI_TRUE : JNI_FALSE;
}

// Returns framesSent, underruns, transferErrors, disconnected.
JNIEXPORT jlongArray JNICALL JNI_FN(nativeStats)(JNIEnv* env, jobject, jlong h) {
    const UsbStreamer::Stats s = streamer(h)->stats();
    const jlong values[4] = {jlong(s.framesSent), jlong(s.underruns), jlong(s.transferErrors), s.disconnected ? 1 : 0};
    jlongArray array = env->NewLongArray(4);
    env->SetLongArrayRegion(array, 0, 4, values);
    return array;
}

}  // extern "C"

extern "C" JNIEXPORT void JNICALL JNI_FN(nativeSetLogFile)(JNIEnv* env, jobject, jstring path) {
    if (!path) {
        ualog::setFile(nullptr);
        return;
    }
    const char* p = env->GetStringUTFChars(path, nullptr);
    ualog::setFile(p);
    env->ReleaseStringUTFChars(path, p);
}

// Returns outputRate, dacBits, resampled, bitPerfect — or null if the DAC cannot play it at all.
extern "C" JNIEXPORT jintArray JNICALL JNI_FN(nativePlan)(JNIEnv* env, jobject, jlong h, jint rate, jint bits,
                                                        jint channels, jint modeIndex, jint modeRate) {
    UsbStreamer::Plan p;
    std::string error;
    UsbStreamer::Mode mode{modeIndex, uint32_t(modeRate)};
    if (!streamer(h)->plan(uint32_t(rate), uint32_t(bits), uint32_t(channels), modeIndex >= 0 ? &mode : nullptr, p,
                           error)) {
        return nullptr;
    }
    return toIntArray(env, {jint(p.outputRate), jint(p.dacBits), p.resampling ? 1 : 0, p.lossless ? 1 : 0});
}

// Flattened per mode: outputIndex, sampleRate, bitResolution, subslotBytes, channels.
extern "C" JNIEXPORT jintArray JNICALL JNI_FN(nativeModes)(JNIEnv* env, jobject, jlong h) {
    std::vector<jint> out;
    for (const UsbStreamer::Mode& m : streamer(h)->modes()) {
        out.insert(out.end(), {m.outputIndex, jint(m.sampleRate), jint(m.bitResolution), jint(m.subslotBytes),
                               jint(m.channels)});
    }
    return toIntArray(env, out);
}

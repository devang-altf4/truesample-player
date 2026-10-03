// Audio decoding for the demo app. FLAC and WAV use dr_flac/dr_wav (verified
// bit-exact); every other format goes through a small, audio-only FFmpeg build.
// Output is always interleaved int32, left-justified — exactly what
// UsbAudioOutput takes, so lossless samples reach the DAC unchanged.
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>

#define DR_FLAC_IMPLEMENTATION
#include "dr_flac.h"
#define DR_WAV_IMPLEMENTATION
#include "dr_wav.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
}

namespace {

constexpr int kIoBufferSize = 64 * 1024;

struct FfmpegStream {
    AVFormatContext* format = nullptr;
    AVCodecContext* codec = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* frame = nullptr;
    int streamIndex = -1;
    int frameOffset = 0;   // frames of `frame` already handed out
    bool frameValid = false;
    bool inputDone = false;  // demuxer reached the end; decoder is being drained
    bool decoderDone = false;
};

struct Decoder {
    int fd = -1;
    drflac* flac = nullptr;
    drwav wav{};
    bool isWav = false;
    FfmpegStream* ff = nullptr;
    // Reported format
    int sampleRate = 0;
    int channels = 0;
    int bits = 0;
    int64_t totalFrames = 0;
    bool lossy = false;
    std::string codecName;

    ~Decoder() {
        if (flac) drflac_close(flac);
        if (isWav) drwav_uninit(&wav);
        if (ff) {
            av_frame_free(&ff->frame);
            av_packet_free(&ff->packet);
            avcodec_free_context(&ff->codec);
            if (ff->format) {
                AVIOContext* io = ff->format->pb;
                avformat_close_input(&ff->format);
                if (io) {
                    av_freep(&io->buffer);
                    avio_context_free(&io);
                }
            }
            delete ff;
        }
        if (fd >= 0) ::close(fd);
    }
};

Decoder* decoder(jlong h) { return reinterpret_cast<Decoder*>(h); }

// ---- I/O straight from the descriptor we were given. Re-opening it by path
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

int ffRead(void* user, uint8_t* buf, int size) {
    const ssize_t n = ::read(fdOf(user), buf, size_t(size));
    if (n == 0) return AVERROR_EOF;
    return n < 0 ? AVERROR(errno) : int(n);
}

int64_t ffSeek(void* user, int64_t offset, int whence) {
    if (whence & AVSEEK_SIZE) {
        struct stat st{};
        return fstat(fdOf(user), &st) == 0 ? int64_t(st.st_size) : AVERROR(errno);
    }
    return lseek64(fdOf(user), offset, whence & ~AVSEEK_FORCE);
}

// ---- FFmpeg ----------------------------------------------------------------------------

bool openFfmpeg(Decoder* d) {
    auto* ff = new FfmpegStream();
    d->ff = ff;
    auto* ioBuffer = static_cast<uint8_t*>(av_malloc(kIoBufferSize));
    AVIOContext* io = avio_alloc_context(ioBuffer, kIoBufferSize, 0, d, ffRead, nullptr, ffSeek);
    if (!io) {
        av_free(ioBuffer);
        return false;
    }
    ff->format = avformat_alloc_context();
    ff->format->pb = io;
    ff->format->flags |= AVFMT_FLAG_CUSTOM_IO;
    if (avformat_open_input(&ff->format, nullptr, nullptr, nullptr) < 0) {
        // avformat_open_input frees the context on failure, but not our custom I/O.
        av_freep(&io->buffer);
        avio_context_free(&io);
        return false;
    }
    if (avformat_find_stream_info(ff->format, nullptr) < 0) return false;
    const AVCodec* codec = nullptr;
    ff->streamIndex = av_find_best_stream(ff->format, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
    if (ff->streamIndex < 0 || !codec) return false;
    const AVStream* stream = ff->format->streams[ff->streamIndex];
    ff->codec = avcodec_alloc_context3(codec);
    if (!ff->codec || avcodec_parameters_to_context(ff->codec, stream->codecpar) < 0) return false;
    if (avcodec_open2(ff->codec, codec, nullptr) < 0) return false;
    ff->packet = av_packet_alloc();
    ff->frame = av_frame_alloc();

    d->sampleRate = ff->codec->sample_rate;
    d->channels = ff->codec->ch_layout.nb_channels;
    d->codecName = codec->name;
    const AVSampleFormat fmt = av_get_packed_sample_fmt(ff->codec->sample_fmt);
    const int raw = stream->codecpar->bits_per_raw_sample > 0 ? stream->codecpar->bits_per_raw_sample
                                                              : ff->codec->bits_per_raw_sample;
    switch (fmt) {
        case AV_SAMPLE_FMT_U8: d->bits = 8; break;
        case AV_SAMPLE_FMT_S16: d->bits = 16; break;
        case AV_SAMPLE_FMT_S32: d->bits = (raw > 0 && raw <= 32) ? raw : 32; break;
        default: d->bits = 32; d->lossy = true; break;  // float output (e.g. DSD converted to PCM)
    }
    // Ask FFmpeg's codec database rather than guessing from the sample format:
    // some lossy decoders output integers that would otherwise look lossless.
    const AVCodecDescriptor* desc = avcodec_descriptor_get(ff->codec->codec_id);
    if (desc && (desc->props & AV_CODEC_PROP_LOSSY) && !(desc->props & AV_CODEC_PROP_LOSSLESS)) {
        d->lossy = true;
        d->bits = 32;  // decoded audio has no true bit depth; never call it bit-perfect
    }
    if (stream->duration > 0 && d->sampleRate > 0) {
        d->totalFrames = av_rescale_q(stream->duration, stream->time_base, AVRational{1, d->sampleRate});
    } else if (ff->format->duration > 0) {
        d->totalFrames = av_rescale(ff->format->duration, d->sampleRate, AV_TIME_BASE);
    }
    return d->sampleRate > 0 && d->channels > 0;
}

int32_t toInt32(const AVFrame* f, AVSampleFormat fmt, bool planar, int channel, int index, int channels) {
    const int i = planar ? index : index * channels + channel;
    const uint8_t* base = f->extended_data[planar ? channel : 0];
    switch (fmt) {
        case AV_SAMPLE_FMT_U8: return int32_t(uint32_t(base[i] - 128) << 24);
        case AV_SAMPLE_FMT_S16: return int32_t(uint32_t(reinterpret_cast<const int16_t*>(base)[i]) << 16);
        case AV_SAMPLE_FMT_S32: return reinterpret_cast<const int32_t*>(base)[i];  // already left-justified
        case AV_SAMPLE_FMT_S64: return int32_t(reinterpret_cast<const int64_t*>(base)[i] >> 32);
        case AV_SAMPLE_FMT_FLT:
        case AV_SAMPLE_FMT_DBL: {
            const double x = fmt == AV_SAMPLE_FMT_FLT ? reinterpret_cast<const float*>(base)[i]
                                                      : reinterpret_cast<const double*>(base)[i];
            const double scaled = std::nearbyint(std::clamp(x, -1.0, 1.0) * 2147483648.0);
            return int32_t(std::clamp(scaled, -2147483648.0, 2147483647.0));
        }
        default: return 0;
    }
}

int readFfmpeg(Decoder* d, int32_t* out, int maxFrames) {
    FfmpegStream* ff = d->ff;
    const int channels = d->channels;
    int written = 0;
    while (written < maxFrames) {
        if (ff->frameValid && ff->frameOffset < ff->frame->nb_samples) {
            const auto fmt = AVSampleFormat(ff->frame->format);
            const AVSampleFormat packed = av_get_packed_sample_fmt(fmt);
            const bool planar = av_sample_fmt_is_planar(fmt);
            const int n = std::min(maxFrames - written, ff->frame->nb_samples - ff->frameOffset);
            for (int k = 0; k < n; ++k) {
                for (int c = 0; c < channels; ++c) {
                    out[(written + k) * channels + c] =
                        toInt32(ff->frame, packed, planar, c, ff->frameOffset + k, channels);
                }
            }
            ff->frameOffset += n;
            written += n;
            continue;
        }
        if (ff->decoderDone) break;
        ff->frameValid = false;
        const int rc = avcodec_receive_frame(ff->codec, ff->frame);
        if (rc == 0) {
            ff->frameValid = true;
            ff->frameOffset = 0;
            continue;
        }
        if (rc == AVERROR_EOF) {
            ff->decoderDone = true;
            break;
        }
        if (rc != AVERROR(EAGAIN)) break;  // decoding error: stop here
        if (ff->inputDone) {
            ff->decoderDone = true;
            break;
        }
        const int readRc = av_read_frame(ff->format, ff->packet);
        if (readRc < 0) {
            ff->inputDone = true;
            avcodec_send_packet(ff->codec, nullptr);  // drain buffered frames
            continue;
        }
        if (ff->packet->stream_index == ff->streamIndex) avcodec_send_packet(ff->codec, ff->packet);
        av_packet_unref(ff->packet);
    }
    return written;
}

}  // namespace

#define JNI_FN(name) Java_com_truesample_player_AudioDecoder_##name

extern "C" {

// Takes ownership of `fd`. Returns 0 when the file can't be decoded.
JNIEXPORT jlong JNICALL JNI_FN(nativeOpen)(JNIEnv*, jclass, jint fd) {
    av_log_set_level(AV_LOG_ERROR);
    auto* d = new Decoder();
    d->fd = fd;
    d->flac = drflac_open(onRead, onSeekFlac, onTellFlac, d, nullptr);
    if (d->flac) {
        d->sampleRate = int(d->flac->sampleRate);
        d->channels = d->flac->channels;
        d->bits = d->flac->bitsPerSample;
        d->totalFrames = int64_t(d->flac->totalPCMFrameCount);
        d->codecName = "flac";
        return reinterpret_cast<jlong>(d);
    }
    lseek64(fd, 0, SEEK_SET);
    d->isWav = drwav_init(&d->wav, onRead, onSeekWav, onTellWav, d, nullptr);
    if (d->isWav && (d->wav.translatedFormatTag == DR_WAVE_FORMAT_PCM)) {
        d->sampleRate = int(d->wav.sampleRate);
        d->channels = d->wav.channels;
        d->bits = d->wav.bitsPerSample;
        d->totalFrames = int64_t(d->wav.totalPCMFrameCount);
        d->codecName = "pcm";
        return reinterpret_cast<jlong>(d);
    }
    if (d->isWav) {  // float or compressed WAV: let FFmpeg handle it
        drwav_uninit(&d->wav);
        d->isWav = false;
    }
    lseek64(fd, 0, SEEK_SET);
    if (openFfmpeg(d)) return reinterpret_cast<jlong>(d);
    delete d;
    return 0;
}

// Returns sampleRate, channels, bitsPerSample, totalFrames, lossy (0/1).
JNIEXPORT jlongArray JNICALL JNI_FN(nativeInfo)(JNIEnv* env, jclass, jlong h) {
    Decoder* d = decoder(h);
    const jlong v[5] = {d->sampleRate, d->channels, d->bits, d->totalFrames, d->lossy ? 1 : 0};
    jlongArray out = env->NewLongArray(5);
    env->SetLongArrayRegion(out, 0, 5, v);
    return out;
}

JNIEXPORT jstring JNICALL JNI_FN(nativeCodec)(JNIEnv* env, jclass, jlong h) {
    return env->NewStringUTF(decoder(h)->codecName.c_str());
}

// Fills the direct buffer with up to maxFrames frames; returns frames decoded (0 at the end).
JNIEXPORT jint JNICALL JNI_FN(nativeRead)(JNIEnv* env, jclass, jlong h, jobject buffer, jint maxFrames) {
    Decoder* d = decoder(h);
    auto* out = static_cast<int32_t*>(env->GetDirectBufferAddress(buffer));
    if (!out) return -1;
    if (d->flac) return jint(drflac_read_pcm_frames_s32(d->flac, drflac_uint64(maxFrames), out));
    if (d->isWav) return jint(drwav_read_pcm_frames_s32(&d->wav, drwav_uint64(maxFrames), out));
    return readFfmpeg(d, out, maxFrames);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeSeek)(JNIEnv*, jclass, jlong h, jlong frame) {
    Decoder* d = decoder(h);
    if (d->flac) return drflac_seek_to_pcm_frame(d->flac, drflac_uint64(frame)) ? JNI_TRUE : JNI_FALSE;
    if (d->isWav) return drwav_seek_to_pcm_frame(&d->wav, drwav_uint64(frame)) ? JNI_TRUE : JNI_FALSE;
    FfmpegStream* ff = d->ff;
    const AVStream* s = ff->format->streams[ff->streamIndex];
    const int64_t ts = av_rescale_q(frame, AVRational{1, d->sampleRate}, s->time_base);
    if (av_seek_frame(ff->format, ff->streamIndex, ts, AVSEEK_FLAG_BACKWARD) < 0) return JNI_FALSE;
    avcodec_flush_buffers(ff->codec);
    ff->frameValid = false;
    ff->inputDone = ff->decoderDone = false;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI_FN(nativeClose)(JNIEnv*, jclass, jlong h) { delete decoder(h); }

}  // extern "C"

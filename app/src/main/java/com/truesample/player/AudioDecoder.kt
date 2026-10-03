package com.truesample.player

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer

/** Decodes audio files (FLAC/WAV natively, everything else via FFmpeg) to interleaved int32 left-justified PCM. */
class AudioDecoder private constructor(private var handle: Long) : Closeable {
    val sampleRate: Int
    val channels: Int
    val bitsPerSample: Int
    val totalFrames: Long
    /** True for lossy codecs (MP3, AAC, Vorbis, Opus) and DSD, which decode to floating point. */
    val lossy: Boolean
    /** FFmpeg's codec name, e.g. "flac", "mp3", "alac". */
    val codec: String

    init {
        val info = nativeInfo(handle)
        sampleRate = info[0].toInt()
        channels = info[1].toInt()
        bitsPerSample = info[2].toInt()
        totalFrames = info[3]
        lossy = info[4] != 0L
        codec = nativeCodec(handle)
    }

    /** Decodes up to [maxFrames] frames into the direct [buffer]; returns 0 at the end of the file. */
    fun read(buffer: ByteBuffer, maxFrames: Int): Int {
        check(handle != 0L) { "decoder is closed" }
        return nativeRead(handle, buffer, maxFrames)
    }

    fun seek(frame: Long): Boolean = nativeSeek(handle, frame)

    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0L
    }

    companion object {
        init {
            System.loadLibrary("decoder")
        }

        /** Takes ownership of [pfd]. */
        fun open(pfd: ParcelFileDescriptor): AudioDecoder {
            val handle = nativeOpen(pfd.detachFd())
            if (handle == 0L) throw IOException("This file can't be decoded")
            return AudioDecoder(handle)
        }

        @JvmStatic private external fun nativeOpen(fd: Int): Long
        @JvmStatic private external fun nativeInfo(handle: Long): LongArray
        @JvmStatic private external fun nativeCodec(handle: Long): String
        @JvmStatic private external fun nativeRead(handle: Long, buffer: ByteBuffer, maxFrames: Int): Int
        @JvmStatic private external fun nativeSeek(handle: Long, frame: Long): Boolean
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}

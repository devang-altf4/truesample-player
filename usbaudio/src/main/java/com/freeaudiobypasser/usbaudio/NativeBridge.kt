package com.freeaudiobypasser.usbaudio

import java.nio.ByteBuffer

internal object NativeBridge {
    init {
        System.loadLibrary("usbaudio")
    }

    external fun nativeOpen(fd: Int, rawDescriptors: ByteArray): Long
    external fun nativeDescribe(handle: Long): String
    external fun nativeFormats(handle: Long): IntArray
    external fun nativeStart(handle: Long, sampleRate: Int, bitsPerSample: Int, channels: Int, quality: Int): IntArray
    external fun nativeWrite(handle: Long, pcm: ByteBuffer, frames: Int): Int
    external fun nativeDrain(handle: Long)
    external fun nativeStop(handle: Long)
    external fun nativeClose(handle: Long)
    external fun nativeVolumeRange(handle: Long): IntArray
    external fun nativeSetVolume(handle: Long, value256: Int): Boolean
    external fun nativeStats(handle: Long): LongArray
    external fun nativeSetLogFile(path: String?)
    external fun nativePlan(handle: Long, sampleRate: Int, bitsPerSample: Int, channels: Int): IntArray?
}

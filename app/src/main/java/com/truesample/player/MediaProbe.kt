package com.truesample.player

import android.os.ParcelFileDescriptor

/** Tags read from a file. Empty strings become null. */
data class Tags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val year: String? = null,
    val genre: String? = null,
)

data class ProbeResult(val format: SourceFormat, val codec: String, val tags: Tags)

/** Reads a file's real format, tags and cover art through FFmpeg, without decoding audio. */
object MediaProbe {
    init {
        System.loadLibrary("decoder")
    }

    /** Takes ownership of [pfd]. */
    fun probe(pfd: ParcelFileDescriptor): ProbeResult? {
        val v = nativeProbe(pfd.detachFd()) ?: return null
        fun text(i: Int) = v[i].trim().takeIf { it.isNotEmpty() }
        return ProbeResult(
            format = SourceFormat(
                sampleRate = v[0].toInt(),
                bitsPerSample = v[2].toInt(),
                channels = v[1].toInt(),
                totalFrames = v[3].toLong(),
                lossy = v[4] == "1",
            ),
            codec = v[5],
            tags = Tags(
                title = text(6),
                artist = text(7),
                album = text(8),
                albumArtist = text(9),
                trackNumber = leadingNumber(v[10]),
                discNumber = leadingNumber(v[11]),
                year = text(12)?.take(4),
                genre = text(13),
            ),
        )
    }

    /** Takes ownership of [pfd]. Embedded cover art as JPEG/PNG bytes, or null. */
    fun coverArt(pfd: ParcelFileDescriptor): ByteArray? = nativeCoverArt(pfd.detachFd())

    /** "3/12" -> 3, "03" -> 3, "" -> 0. */
    private fun leadingNumber(s: String) = s.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: 0

    @JvmStatic private external fun nativeProbe(fd: Int): Array<String>?
    @JvmStatic private external fun nativeCoverArt(fd: Int): ByteArray?
}

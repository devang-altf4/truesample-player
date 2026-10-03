package com.truesample.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import java.util.concurrent.ConcurrentHashMap

/**
 * Album covers: Android's own thumbnail for indexed music, else the picture embedded in the
 * file (FLAC, MP3, M4A, Ogg, WavPack, DSF...), else a cover.jpg/folder.jpg next to it.
 * Cached per album, so a whole album costs one lookup.
 */
class ArtworkLoader(private val context: Context) {

    private val memory = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = ConcurrentHashMap.newKeySet<String>()

    /** Blocking; call off the main thread. */
    fun load(track: Track, sizePx: Int): Bitmap? {
        val bucket = when {
            sizePx <= 200 -> 200  // list rows
            sizePx <= 420 -> 420  // album tiles
            else -> 720  // player and notification
        }
        val key = (track.albumKey.ifEmpty { track.uri.toString() }) + "@" + bucket
        memory.get(key)?.let { return it }
        if (key in missing) return null
        val bitmap = runCatching { thumbnail(track, bucket) }.getOrNull()
            ?: runCatching { embedded(track, bucket) }.getOrNull()
            ?: track.folderCover?.let { runCatching { fromUri(it, bucket) }.getOrNull() }
        if (bitmap == null) missing += key else memory.put(key, bitmap)
        return bitmap
    }

    private fun thumbnail(track: Track, size: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < 29 || track.source != TrackSource.MEDIA_STORE) return null
        return context.contentResolver.loadThumbnail(track.uri, Size(size, size), null)
    }

    private fun embedded(track: Track, size: Int): Bitmap? {
        val pfd = context.contentResolver.openFileDescriptor(track.uri, "r") ?: return null
        val bytes = MediaProbe.coverArt(pfd) ?: return null
        return decodeSampled(size) { opts -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }
    }

    private fun fromUri(uri: Uri, size: Int): Bitmap? = decodeSampled(size) { opts ->
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Decodes at roughly [size] pixels instead of the full (often 3000 px) image. */
    private fun decodeSampled(size: Int, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        return decode(BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

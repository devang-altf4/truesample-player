package com.truesample.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class Track(
    val uri: Uri,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val fileName: String,
) {
    val codec: String get() = fileName.substringAfterLast('.', "").uppercase().ifEmpty { "AUDIO" }
}

/** What the file actually contains, read from its header. */
data class SourceFormat(
    val sampleRate: Int,
    val bitsPerSample: Int,
    val channels: Int,
    val totalFrames: Long,
    /** Lossy codecs (and DSD) decode to floating point, so "bits" is not meaningful for them. */
    val lossy: Boolean = false,
) {
    val durationMs: Long get() = if (sampleRate > 0) totalFrames * 1000 / sampleRate else 0
}

/** Finds the music on the phone and reads each file's real format in the background. */
class MusicLibrary(private val context: Context) {

    private val probeExecutor = Executors.newSingleThreadExecutor()
    private val formats = ConcurrentHashMap<Uri, Result<SourceFormat>>()

    /** Tracks indexed by Android's media store, plus files in the app's own Music folder. Blocking. */
    fun load(includeMediaStore: Boolean): List<Track> {
        val tracks = mutableListOf<Track>()
        if (includeMediaStore) tracks += queryMediaStore()
        val known = tracks.map { it.fileName }.toSet()
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?.listFiles { f -> f.extension.lowercase() in EXTENSIONS }
            ?.filter { it.name !in known }
            ?.forEach { tracks += Track(Uri.fromFile(it), it.nameWithoutExtension, null, 0, it.name) }
        return tracks.sortedBy { it.title.lowercase() }
    }

    /** Null while the header has not been read yet. */
    fun format(track: Track): Result<SourceFormat>? = formats[track.uri]

    /** Reads headers of tracks not probed yet, calling [onUpdate] on the probe thread as results arrive. */
    fun probe(tracks: List<Track>, onUpdate: () -> Unit) {
        for (track in tracks) {
            if (formats.containsKey(track.uri)) continue
            probeExecutor.execute {
                if (formats.containsKey(track.uri)) return@execute
                formats[track.uri] = runCatching { readFormat(track.uri) }
                onUpdate()
            }
        }
    }

    fun readFormat(uri: Uri): SourceFormat {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("cannot open")
        return AudioDecoder.open(pfd).use {
            SourceFormat(it.sampleRate, it.bitsPerSample, it.channels, it.totalFrames, it.lossy)
        }
    }

    fun shutdown() = probeExecutor.shutdownNow()

    private fun queryMediaStore(): List<Track> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        // Everything Android marks as music, plus hi-fi formats it may not recognise.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0" +
            EXTENSIONS.joinToString("") { " OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.$it'" }
        val tracks = mutableListOf<Track>()
        context.contentResolver.query(collection, projection, selection, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(4) ?: continue
                val artist = c.getString(2)?.takeUnless { it == MediaStore.UNKNOWN_STRING }
                tracks += Track(
                    uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                    title = c.getString(1)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                    artist = artist,
                    durationMs = c.getLong(3),
                    fileName = name,
                )
            }
        }
        return tracks
    }

    companion object {
        /** Formats the decoder handles (FFmpeg plus dr_flac/dr_wav). */
        private val EXTENSIONS = listOf(
            "flac", "wav", "w64", "aif", "aiff", "aifc", "caf", "m4a", "alac", "mp4", "aac", "mp3", "mp2",
            "ogg", "oga", "opus", "mka", "ape", "wv", "tak", "dsf", "dff",
        )
    }
}

package com.truesample.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

enum class TrackSource { MEDIA_STORE, FOLDER, APP_FOLDER, PICKED }

data class Track(
    val uri: Uri,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val trackNumber: Int,
    val discNumber: Int,
    val year: String?,
    val durationMs: Long,
    val fileName: String,
    /** Human-readable folder, e.g. "Music/Albums/Kind of Blue". */
    val folder: String,
    val source: TrackSource,
    val sizeBytes: Long = 0,
    val modified: Long = 0,
    /** A cover.jpg / folder.jpg next to the file, for folder sources. */
    val folderCover: Uri? = null,
) {
    val codec: String get() = fileName.substringAfterLast('.', "").uppercase().ifEmpty { "AUDIO" }

    /** Groups tracks into albums: same album title and album artist (or artist). */
    val albumKey: String
        get() = if (album.isNullOrBlank()) "" else "${(albumArtist ?: artist).orEmpty().lowercase()}|${album.lowercase()}"
}

data class Album(val key: String, val title: String, val artist: String?, val year: String?, val tracks: List<Track>)

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

/**
 * Finds music (Android's media index and/or folders the user picked), reads each file's real
 * format and tags in the background, and caches the results on disk so big libraries load fast.
 */
class MusicLibrary(private val context: Context, private val log: (String) -> Unit) {

    private val probeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "library-probe") }
    private val results = ConcurrentHashMap<Uri, Result<ProbeResult>>()
    private val cacheFile = File(context.filesDir, "library-cache.json")
    private val cache = ConcurrentHashMap<String, JSONObject>()
    private val unsaved = AtomicInteger()
    private val failuresLogged = AtomicInteger()

    init {
        probeExecutor.execute { loadCache() }
    }

    /** Blocking. Tracks from the chosen sources, de-duplicated by file name and size. */
    fun load(includeMediaStore: Boolean, folders: List<Uri>): List<Track> {
        val tracks = mutableListOf<Track>()
        if (includeMediaStore) tracks += runCatching { queryMediaStore() }.getOrDefault(emptyList())
        for (tree in folders) tracks += runCatching { scanTree(tree) }.getOrDefault(emptyList())
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?.listFiles { f -> f.extension.lowercase() in EXTENSIONS }
            ?.forEach {
                tracks += Track(
                    Uri.fromFile(it), it.nameWithoutExtension, null, null, null, 0, 0, null, 0, it.name,
                    "App folder", TrackSource.APP_FOLDER, it.length(), it.lastModified(),
                )
            }
        return tracks.distinctBy { it.fileName.lowercase() to it.sizeBytes }
    }

    /** The probed format, null while not read yet. */
    fun format(track: Track): Result<SourceFormat>? = results[track.uri]?.map { it.format }

    /** Fills in tags read from the file for tracks Android hasn't indexed. */
    fun resolve(track: Track): Track {
        if (track.source == TrackSource.MEDIA_STORE) return track
        val tags = results[track.uri]?.getOrNull()?.tags ?: return track
        return track.copy(
            title = tags.title ?: track.title,
            artist = tags.artist ?: track.artist,
            album = tags.album ?: track.album,
            albumArtist = tags.albumArtist ?: track.albumArtist,
            trackNumber = if (tags.trackNumber > 0) tags.trackNumber else track.trackNumber,
            discNumber = if (tags.discNumber > 0) tags.discNumber else track.discNumber,
            year = tags.year ?: track.year,
            durationMs = track.durationMs.takeIf { it > 0 } ?: results[track.uri]?.getOrNull()?.format?.durationMs ?: 0,
        )
    }

    /** Reads files not probed yet (cache first). [onUpdate] runs on the probe thread, now and then. */
    fun probe(tracks: List<Track>, onUpdate: () -> Unit) {
        for (track in tracks) {
            if (results.containsKey(track.uri)) continue
            probeExecutor.execute {
                if (results.containsKey(track.uri)) return@execute
                val key = cacheKey(track)
                val cached = cache[key]?.let { runCatching { fromJson(it) }.getOrNull() }
                val result = if (cached != null) {
                    Result.success(cached)
                } else {
                    runCatching {
                        val pfd = context.contentResolver.openFileDescriptor(track.uri, "r") ?: error("cannot open")
                        MediaProbe.probe(pfd) ?: error("unreadable")
                    }.onSuccess {
                        cache[key] = toJson(it)
                        unsaved.incrementAndGet()
                    }.onFailure {
                        if (failuresLogged.incrementAndGet() <= 20) log("Could not read ${track.fileName}: $it")
                    }
                }
                results[track.uri] = result
                if (cached == null) onUpdate()
            }
        }
        probeExecutor.execute {
            saveCache()
            onUpdate()
        }
    }

    fun shutdown() = probeExecutor.shutdownNow()

    // ---- Android's media index ---------------------------------------------------------------

    private fun queryMediaStore(): List<Track> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.Audio.Media.RELATIVE_PATH) else add(MediaStore.Audio.Media.DATA)
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.Audio.Media.ALBUM_ARTIST)
        }
        // Everything Android marks as music, plus hi-fi formats it doesn't recognise as music. Matching
        // common extensions like .m4a would pull in call recordings and voice notes, so those rely on the flag.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0" +
            UNRECOGNISED_HIFI.joinToString("") { " OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.$it'" }
        val tracks = mutableListOf<Track>()
        context.contentResolver.query(collection, columns.toTypedArray(), selection, null, null)?.use { c ->
            fun str(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getString(it) }
                ?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }
            fun long(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L
            while (c.moveToNext()) {
                val name = str(MediaStore.Audio.Media.DISPLAY_NAME) ?: continue
                val trackField = long(MediaStore.Audio.Media.TRACK).toInt()  // disc * 1000 + track
                val folder = if (Build.VERSION.SDK_INT >= 29) {
                    str(MediaStore.Audio.Media.RELATIVE_PATH)?.trimEnd('/') ?: ""
                } else {
                    str(MediaStore.Audio.Media.DATA)?.substringBeforeLast('/')?.substringAfter("/0/") ?: ""
                }
                tracks += Track(
                    uri = ContentUris.withAppendedId(collection, long(MediaStore.Audio.Media._ID)),
                    title = str(MediaStore.Audio.Media.TITLE) ?: name.substringBeforeLast('.'),
                    artist = str(MediaStore.Audio.Media.ARTIST),
                    // Android names untagged files' album after their folder; don't treat that as an album.
                    album = str(MediaStore.Audio.Media.ALBUM)?.takeUnless { it == folder.substringAfterLast('/') && trackField == 0 },
                    albumArtist = if (Build.VERSION.SDK_INT >= 30) str(MediaStore.Audio.Media.ALBUM_ARTIST) else null,
                    trackNumber = trackField % 1000,
                    discNumber = trackField / 1000,
                    year = long(MediaStore.Audio.Media.YEAR).takeIf { it > 0 }?.toString(),
                    durationMs = long(MediaStore.Audio.Media.DURATION),
                    fileName = name,
                    folder = folder,
                    source = TrackSource.MEDIA_STORE,
                    sizeBytes = long(MediaStore.Audio.Media.SIZE),
                    modified = long(MediaStore.Audio.Media.DATE_MODIFIED),
                )
            }
        }
        return tracks
    }

    // ---- Folders the user picked (storage access framework) ----------------------------------

    private fun scanTree(tree: Uri): List<Track> {
        val tracks = mutableListOf<Track>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = rootId.substringAfterLast(':').ifEmpty { rootId }
        val pending = ArrayDeque(listOf(rootId to rootName))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        var visited = 0
        while (pending.isNotEmpty() && visited < MAX_FOLDERS) {
            val (dirId, dirPath) = pending.removeFirst()
            visited++
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
            val files = mutableListOf<Track>()
            var cover: Uri? = null
            context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.addLast(id to "$dirPath/$name")
                        continue
                    }
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    if (ext in COVER_EXTENSIONS && name.substringBeforeLast('.').lowercase() in COVER_NAMES) {
                        cover = uri
                    } else if (ext in EXTENSIONS) {
                        files += Track(
                            uri, name.substringBeforeLast('.'), null, null, null, 0, 0, null, 0, name, dirPath,
                            TrackSource.FOLDER, c.getLong(3), c.getLong(4),
                        )
                    }
                }
            }
            tracks += if (cover != null) files.map { it.copy(folderCover = cover) } else files
        }
        return tracks
    }

    // ---- Disk cache of probe results ----------------------------------------------------------

    private fun cacheKey(t: Track) = "${t.uri}|${t.sizeBytes}|${t.modified}"

    private fun toJson(r: ProbeResult) = JSONObject().apply {
        put("rate", r.format.sampleRate); put("bits", r.format.bitsPerSample); put("ch", r.format.channels)
        put("frames", r.format.totalFrames); put("lossy", r.format.lossy); put("codec", r.codec)
        r.tags.title?.let { put("title", it) }; r.tags.artist?.let { put("artist", it) }
        r.tags.album?.let { put("album", it) }; r.tags.albumArtist?.let { put("albumArtist", it) }
        put("track", r.tags.trackNumber); put("disc", r.tags.discNumber)
        r.tags.year?.let { put("year", it) }; r.tags.genre?.let { put("genre", it) }
    }

    private fun fromJson(o: JSONObject) = ProbeResult(
        SourceFormat(o.getInt("rate"), o.getInt("bits"), o.getInt("ch"), o.getLong("frames"), o.getBoolean("lossy")),
        o.optString("codec"),
        Tags(
            title = o.optString("title").ifEmpty { null }, artist = o.optString("artist").ifEmpty { null },
            album = o.optString("album").ifEmpty { null }, albumArtist = o.optString("albumArtist").ifEmpty { null },
            trackNumber = o.optInt("track"), discNumber = o.optInt("disc"),
            year = o.optString("year").ifEmpty { null }, genre = o.optString("genre").ifEmpty { null },
        ),
    )

    private fun loadCache() = runCatching {
        if (!cacheFile.exists()) return@runCatching
        val root = JSONObject(cacheFile.readText())
        for (key in root.keys()) cache[key] = root.getJSONObject(key)
    }

    private fun saveCache() {
        if (unsaved.getAndSet(0) == 0) return
        runCatching {
            val root = JSONObject()
            for ((k, v) in cache) root.put(k, v)
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(cacheFile)
        }
    }

    companion object {
        /** Formats the decoder handles (FFmpeg plus dr_flac/dr_wav). */
        val EXTENSIONS = setOf(
            "flac", "wav", "w64", "aif", "aiff", "aifc", "caf", "m4a", "alac", "mp4", "aac", "mp3", "mp2",
            "ogg", "oga", "opus", "mka", "ape", "wv", "tak", "dsf", "dff",
        )
        /** Hi-fi formats Android's media index doesn't flag as music. */
        private val UNRECOGNISED_HIFI = listOf("ape", "wv", "tak", "dsf", "dff", "w64", "aif", "aiff", "aifc", "caf", "mka")
        private val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val COVER_NAMES = setOf("cover", "folder", "front", "album", "albumart")
        private const val MAX_FOLDERS = 5000

        /** Albums from tracks, each sorted by disc then track number. */
        fun albums(tracks: List<Track>): List<Album> = tracks
            .filter { it.albumKey.isNotEmpty() }
            .groupBy { it.albumKey }
            .map { (key, list) ->
                val sorted = list.sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.title.lowercase() }))
                val first = sorted.first()
                val artists = list.mapNotNull { it.albumArtist ?: it.artist }.distinct()
                Album(
                    key = key,
                    title = first.album!!,
                    artist = first.albumArtist ?: if (artists.size == 1) artists[0] else "Various artists",
                    year = list.firstNotNullOfOrNull { it.year },
                    tracks = sorted,
                )
            }
            .sortedBy { it.title.lowercase() }
    }
}

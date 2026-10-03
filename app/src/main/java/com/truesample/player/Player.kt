package com.truesample.player

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.truesample.usbaudio.OutputMode
import com.truesample.usbaudio.PlaybackPlan
import com.truesample.usbaudio.UsbAudioException
import com.truesample.usbaudio.UsbAudioOutput
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * The playback engine: owns the DAC, the library, the play queue and the playback thread.
 * One instance lives for the whole app process, so playback continues in the background
 * while [PlaybackService] shows the media notification.
 */
class Player(private val app: Application) : PlayerActions {

    /** The activity, for things that need its result launchers. */
    interface Host {
        fun pickFile()
        fun pickFolder()
        fun requestMusicPermission()
        fun requestNotificationPermission()
    }

    fun interface Listener {
        fun onPlayerChanged()
    }

    val state = PlayerState()
    var host: Host? = null

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val usbManager = app.getSystemService(UsbManager::class.java)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val library = MusicLibrary(app) { message -> main.post { log(message) } }
    private val artworkLoader = ArtworkLoader(app)
    private val wakeLock = app.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrueSample:playback")
        .apply { setReferenceCounted(false) }

    private var output: UsbAudioOutput? = null
    private val planCache = HashMap<Pair<Triple<Int, Int, Int>, OutputMode?>, PlaybackPlan?>()
    private var userVolumeDb: Float? = null
    private var lastVolumeSent = Float.NaN

    private var baseTracks: List<Track> = emptyList()
    private val pickedTracks = mutableListOf<Track>()
    private var lastLibraryLoad = 0L
    private var refreshPosted = false

    private var queue: List<Track> = emptyList()
    private var queueIndex = -1
    private var selectionContext: List<Track> = emptyList()
    private var playThread: Thread? = null
    private var generation = 0
    @Volatile private var playing = false
    @Volatile private var stopRequested = false
    @Volatile private var outputRate = 0
    @Volatile private var startPositionMs = 0L
    @Volatile private var durationMs = 0L
    private var pausedAtMs = 0L
    private var afterConnect: (() -> Unit)? = null

    private val audioPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                        openDac(device)
                        if (output != null) afterConnect?.invoke()
                    } else {
                        state.notice = "USB permission was denied, so the app can't use your DAC."
                    }
                    afterConnect = null
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null && device.deviceName == output?.device?.deviceName) {
                        log("DAC unplugged.")
                        pause()
                        closeDac()
                        state.notice = "The DAC was unplugged."
                        notifyListeners()
                    }
                    refreshDac()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDac()
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (playing) updateProgress()
            main.postDelayed(this, 250)
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(app, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        UsbAudioOutput.setLogFile(File(app.filesDir, "usbaudio.log"))
        log("--- app started ---", toScreen = false)
        state.eq = EqSettings.decode(prefs.getString(PREF_EQ, null))
        state.includeAllMusic = prefs.getBoolean(PREF_ALL_MUSIC, true)
        state.hideShortClips = prefs.getBoolean(PREF_HIDE_SHORT, true)
        state.folders = prefs.getStringSet(PREF_FOLDERS, emptySet()).orEmpty().map { folderSource(Uri.parse(it)) }
            .sortedBy { it.name.lowercase() }
        refreshDac()
        main.post(ticker)
    }

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)
    private fun notifyListeners() = listeners.forEach { it.onPlayerChanged() }

    /** Current playback position, for the notification and lock screen. */
    val positionMs: Long get() = if (playing) livePositionMs() else pausedAtMs
    val trackDurationMs: Long get() = durationMs

    // ---- PlayerActions: playback ---------------------------------------------------------

    override fun select(track: Track, context: List<Track>) {
        state.notice = null
        if (playing && state.current?.uri == track.uri) return  // already playing: don't restart it
        if (playing) {  // tapping another song while music plays starts it straight away
            startQueue(context, track, 0)
        } else {
            state.selected = track
            selectionContext = context
        }
    }

    override fun play(track: Track, context: List<Track>) {
        state.notice = null
        startQueue(context, track, 0)
    }

    override fun togglePlay() {
        if (playing) {
            pause()
            return
        }
        val selected = state.selected
        val current = state.current
        when {
            current != null && (selected == null || selected.uri == current.uri) -> playIndex(queueIndex, pausedAtMs)
            selected != null -> startQueue(selectionContext, selected, 0)
        }
    }

    override fun next() {
        if (queueIndex + 1 < queue.size) playIndex(queueIndex + 1, 0)
    }

    override fun previous() {
        if (positionMs > 3000 || queueIndex <= 0) seekTo(0) else playIndex(queueIndex - 1, 0)
    }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0, maxOf(durationMs - 500, 0))
        if (playing) {
            playIndex(queueIndex, target)  // restarts the stream at the new position
        } else if (state.current != null) {
            pausedAtMs = target
            state.progress = Progress(target, durationMs, 0)
            notifyListeners()
        }
    }

    fun pause() {
        if (!playing) return
        pausedAtMs = livePositionMs()
        stopThread()
        state.isPlaying = false
        state.progress = Progress(pausedAtMs, durationMs, 0)
        wakeLock.release()
        notifyListeners()
    }

    /** Stops playback and unloads the song (notification "close", app removed from recents). */
    fun stop() {
        stopThread()
        state.isPlaying = false
        state.current = null
        state.progress = null
        pausedAtMs = 0
        wakeLock.release()
        if (host == null) releaseDacIfIdle()  // nothing on screen either: let Android have the DAC
        notifyListeners()
    }

    /**
     * Hands the DAC back to Android when nothing is playing. Called when the app is closed: if the
     * process were later killed while holding the DAC, Android's own driver would not come back
     * until the DAC is replugged.
     */
    fun releaseDacIfIdle() {
        if (playing || output == null) return
        closeDac()
        log("Gave the DAC back to Android (app closed).")
        notifyListeners()
    }

    private fun startQueue(list: List<Track>, track: Track, startMs: Long) {
        val index = list.indexOfFirst { it.uri == track.uri }
        queue = if (index >= 0) list else listOf(track)
        playIndex(maxOf(index, 0), startMs)
    }

    private fun playIndex(index: Int, startMs: Long) {
        if (index !in queue.indices) return
        val out = output
        if (out == null) {
            connectDac { playIndex(index, startMs) }
            return
        }
        host?.requestNotificationPermission()
        stopThread()
        val track = queue[index]
        queueIndex = index
        val gen = ++generation
        state.current = track
        state.selected = track
        state.notice = null
        state.hasNext = index + 1 < queue.size
        state.hasPrevious = true
        stopRequested = false
        playing = true
        state.isPlaying = true
        startPositionMs = startMs
        pausedAtMs = startMs
        durationMs = track.durationMs
        outputRate = 0
        state.progress = Progress(startMs, durationMs, 0)
        wakeLock.acquire(6 * 60 * 60 * 1000L)  // USB audio is streamed by the CPU: keep it awake while playing
        if (!PlaybackService.running) {
            // Starting a foreground service from the background is refused on newer Android; when the
            // service is already up (e.g. auto-advancing with the screen off) it just updates itself.
            runCatching { ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java)) }
                .onFailure { log("Could not start the playback service: ${it.message}") }
        }
        loadCurrentArt(track)
        notifyListeners()
        val mode = state.chosenMode
        playThread = thread(name = "playback") { playbackLoop(gen, out, track, startMs, mode) }
    }

    private fun playbackLoop(gen: Int, out: UsbAudioOutput, track: Track, startMs: Long, mode: OutputMode?) {
        var finished = false
        try {
            val pfd = app.contentResolver.openFileDescriptor(track.uri, "r") ?: error("the file is no longer there")
            AudioDecoder.open(pfd).use { dec ->
                val info = out.start(dec.sampleRate, dec.bitsPerSample, dec.channels, mode = mode)
                if (dec.sampleRate > 0) durationMs = dec.totalFrames * 1000 / dec.sampleRate
                if (startMs > 0 && !dec.seek(startMs * dec.sampleRate / 1000)) {
                    startPositionMs = 0  // this file can't seek: it plays from the start, so show that
                }
                outputRate = info.outputRate
                ui(gen) {
                    log("Playing ${track.title} from ${formatDuration(startMs)}: ${dec.sampleRate} Hz " +
                        "${dec.bitsPerSample}-bit -> ${info.outputRate} Hz ${info.format.bitResolution}-bit" +
                        (if (info.bitPerfect) " (bit-perfect)" else " (converted)"))
                }
                val chunkFrames = 4096
                val buffer = ByteBuffer.allocateDirect(chunkFrames * dec.channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                while (!stopRequested) {
                    val n = dec.read(buffer, chunkFrames)
                    if (n <= 0) {
                        out.drain()
                        finished = !stopRequested
                        break
                    }
                    if (out.write(buffer, n) < 0) break
                }
                out.stop()
                val stats = out.stats()
                ui(gen) {
                    if (stats.disconnected) state.notice = "The DAC was unplugged."
                    log("Sent ${stats.framesSent} frames, underruns ${stats.underruns}, USB errors ${stats.transferErrors}")
                }
            }
        } catch (e: UsbAudioException) {
            ui(gen) {
                state.notice = "Can't play this song: ${e.message}"
                log(e.message ?: "error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "playback failed", e)
            ui(gen) {
                state.notice = "Playback stopped: ${e.message}"
                log("Playback error: $e")
            }
        } finally {
            if (!stopRequested) {
                // Ended by itself (end of song or an error), not by pause/seek/skip.
                val done = finished
                ui(gen) { onPlaybackEnded(done) }
            }
        }
    }

    private fun onPlaybackEnded(finished: Boolean) {
        playing = false
        if (finished && queueIndex + 1 < queue.size) {
            playIndex(queueIndex + 1, 0)
            return
        }
        state.isPlaying = false
        pausedAtMs = if (finished) 0 else livePositionMs()
        state.progress = Progress(pausedAtMs, durationMs, 0)
        wakeLock.release()
        notifyListeners()
    }

    private fun stopThread() {
        stopRequested = true
        output?.stop()
        playThread?.join(3000)
        playThread = null
        playing = false
    }

    private fun livePositionMs(): Long {
        val out = output
        if (!playing || out == null || outputRate == 0) return pausedAtMs
        val sent = runCatching { out.stats().framesSent }.getOrDefault(0)
        return startPositionMs + sent * 1000 / outputRate
    }

    private fun updateProgress() {
        val out = output ?: return
        val stats = runCatching { out.stats() }.getOrNull() ?: return
        state.progress = Progress(livePositionMs(), durationMs, stats.underruns)
    }

    private fun loadCurrentArt(track: Track) {
        state.currentArt = null
        thread(name = "artwork") {
            val art = artworkLoader.load(track, 720)
            main.post {
                if (state.current?.uri == track.uri) {
                    state.currentArt = art
                    notifyListeners()
                }
            }
        }
    }

    // ---- PlayerActions: DAC, volume, EQ ---------------------------------------------------

    override fun toggleDac() {
        if (output == null) {
            connectDac(null)
        } else {
            pause()
            closeDac()
            log("Gave the DAC back to Android.")
            notifyListeners()
        }
    }

    override fun chooseMode(mode: OutputMode?) {
        if (mode == state.chosenMode) return
        state.chosenMode = mode
        output?.device?.let { prefs.edit().putString(modeKey(it), mode?.let(::encodeMode)).apply() }
        log("Output mode: " + (mode?.let { "${it.sampleRate} Hz, ${it.bitsPerSample}-bit" } ?: "Auto"))
        if (playing) playIndex(queueIndex, livePositionMs())  // apply straight away
    }

    override fun setVolume(db: Float) {
        userVolumeDb = db
        if (!lastVolumeSent.isNaN() && abs(db - lastVolumeSent) < 0.25f) return
        if (output?.setVolumeDb(db) == true) lastVolumeSent = db
    }

    override fun setEqualizer(settings: EqSettings) {
        state.eq = settings
        prefs.edit().putString(PREF_EQ, settings.encode()).apply()
        output?.setEqualizer(settings.bands, settings.autoPreampDb)
    }

    override fun formatOf(track: Track): Result<SourceFormat>? = library.format(track)

    override fun planFor(format: SourceFormat): PlaybackPlan? {
        val out = output ?: return null
        val mode = state.chosenMode
        return planCache.getOrPut(Triple(format.sampleRate, format.bitsPerSample, format.channels) to mode) {
            out.plan(format.sampleRate, format.bitsPerSample, format.channels, mode)
        }
    }

    override fun artwork(track: Track, sizePx: Int): Bitmap? = artworkLoader.load(track, sizePx)

    private fun findDac(): UsbDevice? = usbManager.deviceList.values.firstOrNull { UsbAudioOutput.isUsbAudioOutput(it) }

    private fun refreshDac() {
        val connected = output
        val found = findDac()
        state.dac = when {
            connected != null -> DacStatus.InUse(connected.device.productName ?: "USB DAC")
            found != null -> DacStatus.Available(found.productName ?: "USB DAC")
            else -> DacStatus.Absent
        }
    }

    private fun connectDac(then: (() -> Unit)?) {
        val device = findDac()
        if (device == null) {
            state.notice = "Plug in your USB DAC first."
            return
        }
        if (usbManager.hasPermission(device)) {
            openDac(device)
            if (output != null) then?.invoke()
        } else {
            afterConnect = then
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
            val pending = PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_MUTABLE)
            usbManager.requestPermission(device, pending)
        }
    }

    private fun openDac(device: UsbDevice) {
        if (output != null) return
        try {
            val out = UsbAudioOutput.open(usbManager, device)
            output = out
            File(app.filesDir, "dac-descriptors-%04x-%04x.bin".format(device.vendorId, device.productId))
                .writeBytes(out.rawDescriptors)
            log("Took control of ${device.productName} (VID %04x PID %04x)".format(device.vendorId, device.productId))
            log(out.description.trimEnd())
            log("Modes: " + out.outputModes.joinToString { "${it.sampleRate}/${it.bitsPerSample}" })
            state.modes = out.outputModes
            val saved = prefs.getString(modeKey(device), null)
            state.chosenMode = out.outputModes.firstOrNull { encodeMode(it) == saved }
            userVolumeDb?.let { out.setVolumeDb(it) }  // the library starts quiet; restore the user's level
            lastVolumeSent = Float.NaN
            state.volume = out.volumeRange?.let { VolumeState(it.minDb, it.maxDb, it.currentDb) }
            out.setEqualizer(state.eq.bands, state.eq.autoPreampDb)
        } catch (e: UsbAudioException) {
            state.notice = "Could not connect to the DAC: ${e.message}"
            log("Could not connect: ${e.message}")
        }
        planCache.clear()
        refreshDac()
    }

    private fun closeDac() {
        output?.close()  // restores the DAC's own volume before handing it back to Android
        output = null
        planCache.clear()
        state.modes = emptyList()
        state.chosenMode = null
        state.volume = null
        refreshDac()
    }

    // ---- PlayerActions: library -----------------------------------------------------------

    fun reloadLibrary(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastLibraryLoad < 30_000) return
        lastLibraryLoad = now
        val granted = ContextCompat.checkSelfPermission(app, audioPermission) == PackageManager.PERMISSION_GRANTED
        state.musicPermission = granted || !state.includeAllMusic
        val includeMediaStore = state.includeAllMusic && granted
        val folders = state.folders.map { it.uri }
        state.libraryLoading = true
        thread(name = "library") {
            val tracks = runCatching { library.load(includeMediaStore, folders) }.getOrElse {
                main.post { log("Could not read your music library: ${it.message}") }
                emptyList()
            }
            main.post {
                baseTracks = pickedTracks + tracks.filter { t -> pickedTracks.none { it.uri == t.uri } }
                publishTracks()
                state.libraryLoading = false
                library.probe(baseTracks) { main.post(::scheduleRefresh) }
            }
        }
    }

    private fun scheduleRefresh() {
        if (refreshPosted) return
        refreshPosted = true
        main.postDelayed({
            refreshPosted = false
            state.formatsVersion++
            publishTracks()
        }, 400)
    }

    private fun publishTracks() {
        val resolved = baseTracks.map(library::resolve)
            .filterNot { state.hideShortClips && it.source == TrackSource.MEDIA_STORE && it.durationMs in 1 until SHORT_CLIP_MS }
            .sortedBy { it.title.lowercase() }
        state.tracks = resolved
        state.albums = MusicLibrary.albums(resolved)
    }

    fun onFilePicked(uri: Uri) {
        val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
        val track = Track(uri, name.substringBeforeLast('.'), null, null, null, 0, 0, null, 0, name, "Opened file",
            TrackSource.PICKED)
        pickedTracks.removeAll { it.uri == uri }
        pickedTracks.add(0, track)
        baseTracks = listOf(track) + baseTracks.filter { it.uri != uri }
        publishTracks()
        library.probe(listOf(track)) { main.post(::scheduleRefresh) }
        state.tab = LibraryTab.SONGS
        select(track, listOf(track))
    }

    fun onFolderPicked(tree: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (state.folders.none { it.uri == tree }) {
            state.folders = (state.folders + folderSource(tree)).sortedBy { it.name.lowercase() }
            saveFolders()
            log("Added music folder ${folderSource(tree).name}")
        }
        reloadLibrary(force = true)
    }

    override fun openFile() {
        host?.pickFile()
    }

    override fun allowMusic() {
        host?.requestMusicPermission()
    }

    override fun addFolder() {
        host?.pickFolder()
    }

    override fun removeFolder(folder: FolderSource) {
        runCatching {
            app.contentResolver.releasePersistableUriPermission(folder.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        state.folders = state.folders.filter { it.uri != folder.uri }
        saveFolders()
        reloadLibrary(force = true)
    }

    override fun setIncludeAllMusic(include: Boolean) {
        state.includeAllMusic = include
        prefs.edit().putBoolean(PREF_ALL_MUSIC, include).apply()
        if (include && ContextCompat.checkSelfPermission(app, audioPermission) != PackageManager.PERMISSION_GRANTED) {
            host?.requestMusicPermission()
        }
        reloadLibrary(force = true)
    }

    override fun setHideShortClips(hide: Boolean) {
        state.hideShortClips = hide
        prefs.edit().putBoolean(PREF_HIDE_SHORT, hide).apply()
        publishTracks()
    }

    private fun saveFolders() =
        prefs.edit().putStringSet(PREF_FOLDERS, state.folders.map { it.uri.toString() }.toSet()).apply()

    private fun folderSource(tree: Uri): FolderSource {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrDefault(tree.toString())
        val name = id.substringAfterLast(':').ifEmpty { if (id.startsWith("primary")) "Internal storage" else id }
        return FolderSource(tree, name)
    }

    // ---- Diagnostics and logging ----------------------------------------------------------

    override fun shareDiagnostics() {
        val report = buildString {
            appendLine("TrueSample Player ${BuildConfig.VERSION_NAME} diagnostics")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("DAC: ${state.dac}")
            appendLine("Equalizer: ${state.eq.mode}, ${state.eq.bands.size} active bands")
            usbManager.deviceList.values.forEach {
                appendLine("USB device: ${it.productName} VID %04x PID %04x".format(it.vendorId, it.productId))
            }
            app.filesDir.listFiles { f -> f.name.startsWith("dac-descriptors-") }?.forEach { f ->
                appendLine()
                appendLine("=== ${f.name}")
                appendLine(f.readBytes().joinToString(" ") { "%02x".format(it) })
            }
            for (name in listOf("usbaudio.log", "app.log")) {
                val lines = File(app.filesDir, name).takeIf { it.exists() }?.readLines().orEmpty()
                appendLine()
                appendLine("=== $name (last ${minOf(lines.size, 1500)} lines)")
                lines.takeLast(1500).forEach(::appendLine)
            }
        }
        val dir = File(app.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, "truesample-diagnostics.txt").apply { writeText(report) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "TrueSample Player diagnostics")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun log(message: String, toScreen: Boolean = true) {
        Log.i(TAG, message)
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        runCatching { File(app.filesDir, "app.log").appendText("$time $message\n") }
        if (!toScreen) return
        state.log.add(message)
        if (state.log.size > 400) state.log.removeRange(0, state.log.size - 400)
    }

    /** Runs [block] on the main thread unless a newer playback has started since. */
    private fun ui(gen: Int, block: () -> Unit) {
        main.post { if (gen == generation) block() }
    }

    companion object {
        private const val TAG = "TrueSample"
        private const val ACTION_USB_PERMISSION = "com.truesample.player.USB_PERMISSION"
        private const val PREF_EQ = "eq"
        private const val PREF_ALL_MUSIC = "include_all_music"
        private const val PREF_FOLDERS = "folders"
        private const val PREF_HIDE_SHORT = "hide_short_clips"
        private const val SHORT_CLIP_MS = 30_000L

        private fun modeKey(device: UsbDevice) = "mode_%04x_%04x".format(device.vendorId, device.productId)

        private fun encodeMode(mode: OutputMode) =
            "${mode.formatIndex}:${mode.sampleRate}:${mode.bitsPerSample}:${mode.channels}"
    }
}

package com.freeaudiobypasser.app

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.freeaudiobypasser.app.ui.HifiTheme
import com.freeaudiobypasser.app.ui.PlayerScreen
import com.freeaudiobypasser.usbaudio.OutputMode
import com.freeaudiobypasser.usbaudio.PlaybackPlan
import com.freeaudiobypasser.usbaudio.UsbAudioException
import com.freeaudiobypasser.usbaudio.UsbAudioOutput
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : ComponentActivity(), PlayerActions {

    private val state = PlayerState()
    private lateinit var usbManager: UsbManager
    private lateinit var library: MusicLibrary
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private var output: UsbAudioOutput? = null
    private val planCache = HashMap<Pair<Triple<Int, Int, Int>, OutputMode?>, PlaybackPlan?>()
    private var userVolumeDb: Float? = null
    private var lastVolumeSent = Float.NaN

    private val pickedTracks = mutableListOf<Track>()  // opened with "Open file"
    private var playThread: Thread? = null
    @Volatile private var playing = false
    @Volatile private var stopRequested = false
    @Volatile private var outputRate = 0
    @Volatile private var playingDurationMs = 0L
    private var pendingPlay = false  // play as soon as USB permission is granted

    private val audioPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private val requestAudioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        reloadLibrary()
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        openDac(device)
                        if (pendingPlay && output != null) play()
                    } else {
                        state.notice = "USB permission was denied, so the app can't use your DAC."
                    }
                    pendingPlay = false
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null && device.deviceName == output?.device?.deviceName) {
                        log("DAC unplugged.")
                        stopPlayback()
                        closeDac()
                        state.notice = "The DAC was unplugged."
                    }
                    refreshDac()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDac()
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            main.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        usbManager = getSystemService(UsbManager::class.java)
        library = MusicLibrary(this)
        setContent { HifiTheme { PlayerScreen(state, this) } }

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        UsbAudioOutput.setLogFile(File(filesDir, "usbaudio.log"))
        log("--- app started ---", toScreen = false)
        refreshDac()
        main.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        reloadLibrary()
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        unregisterReceiver(usbReceiver)
        stopPlayback()
        closeDac()
        library.shutdown()
        super.onDestroy()
    }

    // ---- PlayerActions -------------------------------------------------------------------

    override fun toggleDac() {
        if (output == null) {
            connectDac(thenPlay = false)
        } else {
            stopPlayback()
            closeDac()
            log("Gave the DAC back to Android.")
        }
    }

    override fun chooseMode(mode: OutputMode?) {
        if (mode == state.chosenMode) return
        state.chosenMode = mode
        output?.device?.let { prefs.edit().putString(modeKey(it), mode?.let(::encodeMode)).apply() }
        log("Output mode: " + (mode?.let { "${it.sampleRate} Hz, ${it.bitsPerSample}-bit" } ?: "Auto"))
        if (playing) {  // apply straight away
            stopPlayback()
            play()
        }
    }

    override fun select(track: Track) {
        val wasPlaying = playing
        state.selected = track
        state.notice = null
        if (wasPlaying) {  // switching songs while music plays starts the new one
            stopPlayback()
            play()
        }
    }

    override fun togglePlay() = if (playing) stopPlayback() else play()

    override fun setVolume(db: Float) {
        userVolumeDb = db
        if (!lastVolumeSent.isNaN() && abs(db - lastVolumeSent) < 0.25f) return
        if (output?.setVolumeDb(db) == true) lastVolumeSent = db
    }

    override fun openFile() =
        pickFile.launch(arrayOf("audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/*"))

    override fun allowMusic() = requestAudioPermission.launch(audioPermission)

    override fun shareDiagnostics() {
        val report = buildString {
            appendLine("Free Audio Bypasser ${BuildConfig.VERSION_NAME} diagnostics")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("DAC: ${state.dac}")
            usbManager.deviceList.values.forEach {
                appendLine("USB device: ${it.productName} VID %04x PID %04x".format(it.vendorId, it.productId))
            }
            filesDir.listFiles { f -> f.name.startsWith("dac-descriptors-") }?.forEach { f ->
                appendLine()
                appendLine("=== ${f.name}")
                appendLine(f.readBytes().joinToString(" ") { "%02x".format(it) })
            }
            for (name in listOf("usbaudio.log", "app.log")) {
                val lines = File(filesDir, name).takeIf { it.exists() }?.readLines().orEmpty()
                appendLine()
                appendLine("=== $name (last ${minOf(lines.size, 1500)} lines)")
                lines.takeLast(1500).forEach(::appendLine)
            }
        }
        val dir = File(cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, "free-audio-bypasser-diagnostics.txt").apply { writeText(report) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Free Audio Bypasser diagnostics")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share diagnostics"))
    }

    override fun formatOf(track: Track): Result<SourceFormat>? = library.format(track)

    override fun planFor(format: SourceFormat): PlaybackPlan? {
        val out = output ?: return null
        val mode = state.chosenMode
        return planCache.getOrPut(Triple(format.sampleRate, format.bitsPerSample, format.channels) to mode) {
            out.plan(format.sampleRate, format.bitsPerSample, format.channels, mode)
        }
    }

    // ---- Library -------------------------------------------------------------------------

    private fun reloadLibrary() {
        val granted = ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED
        state.musicPermission = granted
        thread(name = "library") {
            val tracks = runCatching { library.load(includeMediaStore = granted) }.getOrElse {
                ui { log("Could not read your music library: ${it.message}") }
                emptyList()
            }
            ui {
                state.tracks = pickedTracks + tracks.filter { t -> pickedTracks.none { it.uri == t.uri } }
                library.probe(state.tracks) { ui { state.formatsVersion++ } }
            }
        }
    }

    private fun onFilePicked(uri: Uri) {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
        val track = Track(uri, name.substringBeforeLast('.'), null, 0, name)
        pickedTracks.removeAll { it.uri == uri }
        pickedTracks.add(0, track)
        state.tracks = listOf(track) + state.tracks.filter { it.uri != uri }
        library.probe(listOf(track)) { ui { state.formatsVersion++ } }
        select(track)
    }

    // ---- Playback ------------------------------------------------------------------------

    private fun play() {
        val track = state.selected ?: return
        if (playing) return
        val out = output
        if (out == null) {
            connectDac(thenPlay = true)
            return
        }
        stopRequested = false
        playing = true
        state.isPlaying = true
        state.notice = null
        state.progress = null
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val mode = state.chosenMode
        playThread = thread(name = "playback") { playbackLoop(out, track, mode) }
    }

    private fun playbackLoop(out: UsbAudioOutput, track: Track, mode: OutputMode?) {
        try {
            val pfd = contentResolver.openFileDescriptor(track.uri, "r") ?: error("the file is no longer there")
            AudioDecoder.open(pfd).use { dec ->
                val info = out.start(dec.sampleRate, dec.bitsPerSample, dec.channels, mode = mode)
                outputRate = info.outputRate
                playingDurationMs = if (dec.sampleRate > 0) dec.totalFrames * 1000 / dec.sampleRate else 0
                ui {
                    log("Playing ${track.title}: ${dec.sampleRate} Hz ${dec.bitsPerSample}-bit → " +
                        "${info.outputRate} Hz ${info.format.bitResolution}-bit" +
                        (if (info.bitPerfect) " (bit-perfect)" else " (converted)") +
                        if (info.deviceRate != 0) ", DAC confirms ${info.deviceRate} Hz" else "")
                }

                val chunkFrames = 4096
                val buffer = ByteBuffer.allocateDirect(chunkFrames * dec.channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                var finished = false
                while (!stopRequested) {
                    val n = dec.read(buffer, chunkFrames)
                    if (n <= 0) {
                        out.drain()
                        finished = true
                        break
                    }
                    if (out.write(buffer, n) < 0) break
                }
                out.stop()
                val stats = out.stats()
                ui {
                    when {
                        stats.disconnected -> state.notice = "The DAC was unplugged."
                        finished && stats.underruns > 0 -> state.notice = "Finished with ${stats.underruns} dropouts."
                    }
                    log("Sent ${stats.framesSent} frames, underruns ${stats.underruns}, USB errors ${stats.transferErrors}")
                }
            }
        } catch (e: UsbAudioException) {
            ui {
                state.notice = "Can't play this song: ${e.message}"
                log(e.message ?: "error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "playback failed", e)
            ui {
                state.notice = "Playback stopped: ${e.message}"
                log("Playback error: $e")
            }
        } finally {
            playing = false
            ui {
                if (!playing) {  // a new song may already be playing if the user switched tracks
                    state.isPlaying = false
                    state.progress = null
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    private fun stopPlayback() {
        stopRequested = true
        output?.stop()
        playThread?.join(3000)
        playThread = null
        playing = false
        state.isPlaying = false
        state.progress = null
    }

    private fun updateProgress() {
        val out = output
        if (out == null || !playing || outputRate == 0) return
        val stats = runCatching { out.stats() }.getOrNull() ?: return
        // framesSent counts frames at the DAC's rate, which differs from the file's when converting.
        state.progress = Progress(stats.framesSent * 1000 / outputRate, playingDurationMs, stats.underruns)
    }

    // ---- DAC -----------------------------------------------------------------------------

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

    private fun connectDac(thenPlay: Boolean) {
        val device = findDac()
        if (device == null) {
            state.notice = "Plug in your USB DAC first."
            return
        }
        if (usbManager.hasPermission(device)) {
            openDac(device)
            if (thenPlay && output != null) play()
        } else {
            pendingPlay = thenPlay
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
            val pending = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE)
            usbManager.requestPermission(device, pending)
        }
    }

    private fun openDac(device: UsbDevice) {
        if (output != null) return
        try {
            val out = UsbAudioOutput.open(usbManager, device)
            output = out
            File(filesDir, "dac-descriptors-%04x-%04x.bin".format(device.vendorId, device.productId))
                .writeBytes(out.rawDescriptors)
            log("Took control of ${device.productName} (VID %04x PID %04x)".format(device.vendorId, device.productId))
            log(out.description.trimEnd())
            log("Modes: " + out.outputModes.joinToString { "${it.sampleRate}/${it.bitsPerSample}" })
            state.modes = out.outputModes
            val saved = prefs.getString(modeKey(device), null)
            state.chosenMode = out.outputModes.firstOrNull { encodeMode(it) == saved }
            setupVolume(out)
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

    private fun setupVolume(out: UsbAudioOutput) {
        userVolumeDb?.let { out.setVolumeDb(it) }  // the library starts quiet; restore the user's level
        lastVolumeSent = Float.NaN
        state.volume = out.volumeRange?.let { VolumeState(it.minDb, it.maxDb, it.currentDb) }
    }

    // ---- Helpers -------------------------------------------------------------------------

    private fun log(message: String, toScreen: Boolean = true) {
        Log.i(TAG, message)
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        runCatching { File(filesDir, "app.log").appendText("$time $message\n") }
        if (!toScreen) return
        state.log.add(message)
        if (state.log.size > 400) state.log.removeRange(0, state.log.size - 400)
    }

    private fun ui(block: () -> Unit) {
        main.post(block)
    }

    companion object {
        private const val TAG = "FreeAudioBypasser"
        private const val ACTION_USB_PERMISSION = "com.freeaudiobypasser.app.USB_PERMISSION"

        private fun modeKey(device: UsbDevice) = "mode_%04x_%04x".format(device.vendorId, device.productId)

        private fun encodeMode(mode: OutputMode) =
            "${mode.formatIndex}:${mode.sampleRate}:${mode.bitsPerSample}:${mode.channels}"
    }
}

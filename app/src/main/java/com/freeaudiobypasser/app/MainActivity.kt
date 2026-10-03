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
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.freeaudiobypasser.usbaudio.PlaybackPlan
import com.freeaudiobypasser.usbaudio.UsbAudioException
import com.freeaudiobypasser.usbaudio.UsbAudioOutput
import com.freeaudiobypasser.usbaudio.VolumeRange
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var library: MusicLibrary
    private lateinit var adapter: TrackAdapter

    private lateinit var dacText: TextView
    private lateinit var connectButton: Button
    private lateinit var nowTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var positionText: TextView
    private lateinit var playButton: Button
    private lateinit var volumeText: TextView
    private lateinit var volumeBar: SeekBar
    private lateinit var libraryTitle: TextView
    private lateinit var permissionButton: Button
    private lateinit var emptyText: TextView
    private lateinit var logToggle: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private val main = Handler(Looper.getMainLooper())
    private var output: UsbAudioOutput? = null
    private var volumeRange: VolumeRange? = null
    private var userVolumeDb: Float? = null
    private val planCache = HashMap<Triple<Int, Int, Int>, PlaybackPlan?>()

    private var selected: Track? = null
    private val pickedTracks = mutableListOf<Track>()  // opened with "Open file…"
    private var playThread: Thread? = null
    @Volatile private var isPlaying = false
    @Volatile private var stopRequested = false
    @Volatile private var outputRate = 0
    private var playingFormat: SourceFormat? = null
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
                        showStatus("USB permission was denied, so the app can't use your DAC.")
                    }
                    pendingPlay = false
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null && device.deviceName == output?.device?.deviceName) {
                        log("DAC unplugged.")
                        stopPlayback()
                        closeDac()
                        showStatus("DAC unplugged")
                    }
                    refreshDacLabel()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDacLabel()
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updatePosition()
            main.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usbManager = getSystemService(UsbManager::class.java)
        library = MusicLibrary(this)

        dacText = findViewById(R.id.dacText)
        connectButton = findViewById(R.id.connectButton)
        nowTitle = findViewById(R.id.nowTitle)
        statusText = findViewById(R.id.statusText)
        positionText = findViewById(R.id.positionText)
        playButton = findViewById(R.id.playButton)
        volumeText = findViewById(R.id.volumeText)
        volumeBar = findViewById(R.id.volumeBar)
        libraryTitle = findViewById(R.id.libraryTitle)
        permissionButton = findViewById(R.id.permissionButton)
        emptyText = findViewById(R.id.emptyText)
        logToggle = findViewById(R.id.logToggle)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        adapter = TrackAdapter(library, ::planFor) { output != null }
        val trackList = findViewById<ListView>(R.id.trackList)
        trackList.adapter = adapter
        trackList.setOnItemClickListener { _, _, position, _ -> onTrackTapped(adapter.getItem(position)) }

        connectButton.setOnClickListener {
            if (output == null) {
                connectDac(thenPlay = false)
            } else {
                stopPlayback()
                closeDac()
                log("Gave the DAC back to Android.")
            }
        }
        playButton.setOnClickListener { if (isPlaying) stopPlayback() else play() }
        findViewById<Button>(R.id.chooseButton).setOnClickListener {
            pickFile.launch(arrayOf("audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/*"))
        }
        permissionButton.setOnClickListener { requestAudioPermission.launch(audioPermission) }
        logToggle.setOnClickListener {
            val show = logScroll.visibility != View.VISIBLE
            logScroll.visibility = if (show) View.VISIBLE else View.GONE
            logToggle.text = if (show) "Hide driver log ▴" else "Show driver log ▾"
        }
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) setVolumeFromBar(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        UsbAudioOutput.setLogFile(File(filesDir, "usbaudio.log"))
        log("--- app started ---", toScreen = false)
        refreshDacLabel()
        updatePlayerViews()
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

    // ---- Library -------------------------------------------------------------------------

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED

    private fun reloadLibrary() {
        val granted = hasAudioPermission()
        permissionButton.visibility = if (granted) View.GONE else View.VISIBLE
        thread(name = "library") {
            val tracks = runCatching { library.load(includeMediaStore = granted) }.getOrElse {
                log("Could not read your music library: ${it.message}")
                emptyList()
            }
            ui {
                adapter.tracks = pickedTracks + tracks
                libraryTitle.text = "Your music (${adapter.count})"
                emptyText.visibility = if (adapter.count == 0 && granted) View.VISIBLE else View.GONE
                library.probe(adapter.tracks) { ui { adapter.notifyDataSetChanged(); updatePlayerViews() } }
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
        adapter.tracks = listOf(track) + adapter.tracks.filter { it.uri != uri }
        library.probe(listOf(track)) { ui { adapter.notifyDataSetChanged(); updatePlayerViews() } }
        onTrackTapped(track)
    }

    private fun onTrackTapped(track: Track) {
        val wasPlaying = isPlaying
        selected = track
        adapter.selected = track
        if (wasPlaying) {
            stopPlayback()  // switching songs while music plays starts the new one straight away
            play()
        }
        updatePlayerViews()
    }

    private fun planFor(format: SourceFormat): PlaybackPlan? {
        val out = output ?: return null
        return planCache.getOrPut(Triple(format.sampleRate, format.bitsPerSample, format.channels)) {
            out.plan(format.sampleRate, format.bitsPerSample, format.channels)
        }
    }

    // ---- Player --------------------------------------------------------------------------

    /** Shows the selected song and only offers Play once a song is chosen. */
    private fun updatePlayerViews() {
        val track = selected
        nowTitle.text = track?.title ?: "Tap a song below to choose it"
        playButton.visibility = if (track != null) View.VISIBLE else View.GONE
        playButton.text = if (isPlaying) "■  Stop" else "▶  Play"
        positionText.visibility = if (isPlaying) View.VISIBLE else View.GONE
        if (track != null && !isPlaying) {
            val badge = adapter.badgeFor(track)
            showStatus(
                if (output == null) "${badge.text}\nTap Play — the app will connect to your USB DAC."
                else badge.text,
                badge.color,
            )
        }
    }

    private fun showStatus(text: String, color: Int = STATUS_COLOR) {
        statusText.visibility = View.VISIBLE
        statusText.text = text
        statusText.setTextColor(color)
    }

    private fun play() {
        val track = selected ?: return
        if (isPlaying) return
        val out = output
        if (out == null) {
            connectDac(thenPlay = true)
            return
        }
        stopRequested = false
        isPlaying = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updatePlayerViews()
        playThread = thread(name = "playback") { playbackLoop(out, track) }
    }

    private fun playbackLoop(out: UsbAudioOutput, track: Track) {
        try {
            val pfd = contentResolver.openFileDescriptor(track.uri, "r") ?: throw IllegalStateException("file vanished")
            AudioDecoder.open(pfd).use { dec ->
                val info = out.start(dec.sampleRate, dec.bitsPerSample, dec.channels)
                outputRate = info.outputRate
                playingFormat = SourceFormat(dec.sampleRate, dec.bitsPerSample, dec.channels, dec.totalFrames)
                val dacBits = info.format.bitResolution
                val label = when {
                    info.bitPerfect ->
                        "${khz(info.sampleRate)} · ${info.bitsPerSample}-bit → USB direct · BIT-PERFECT ✓"
                    info.resampled ->
                        "${khz(info.sampleRate)} → ${khz(info.outputRate)} · ${info.bitsPerSample}→$dacBits-bit " +
                            "→ USB direct · CONVERTED"
                    else -> "${khz(info.sampleRate)} · ${info.bitsPerSample}→$dacBits-bit dithered → USB direct"
                }
                // Say plainly why the audio is not bit-perfect.
                val reasons = buildList {
                    if (info.resampled) {
                        add("Your DAC can't play ${khz(info.sampleRate)}, so this song is converted to " +
                            "${khz(info.outputRate)} (high-quality resampler).")
                    }
                    if (dacBits < info.bitsPerSample) {
                        add("Your DAC can't play ${info.bitsPerSample}-bit, so it gets $dacBits-bit with dither.")
                    }
                }
                ui {
                    showStatus((listOf(label) + reasons).joinToString("\n"))
                    log("Playing ${track.title} via alt setting ${info.format.altSetting}: $dacBits-bit in " +
                        "${info.format.subslotBytes}-byte slots at ${info.outputRate} Hz" +
                        if (info.deviceRate != 0) ", DAC confirms ${info.deviceRate} Hz" else "")
                    reasons.forEach { log(it) }
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
                        stats.disconnected -> showStatus("DAC unplugged")
                        finished -> showStatus("Finished · underruns: ${stats.underruns}")
                    }
                    log("Sent ${stats.framesSent} frames, underruns ${stats.underruns}, USB errors ${stats.transferErrors}")
                }
            }
        } catch (e: UsbAudioException) {
            ui {
                showStatus("Can't play this song: ${e.message}")
                log(e.message ?: "error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "playback failed", e)
            ui {
                showStatus("Playback error: ${e.message}")
                log("Playback error: $e")
            }
        } finally {
            isPlaying = false
            ui {
                // A new song may already be playing if the user switched tracks.
                if (!isPlaying) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    playButton.text = "▶  Play"
                    positionText.visibility = View.GONE
                }
            }
        }
    }

    private fun stopPlayback() {
        stopRequested = true
        output?.stop()
        playThread?.join(3000)
        playThread = null
        isPlaying = false
        updatePlayerViews()
    }

    private fun updatePosition() {
        val out = output
        val format = playingFormat
        if (out == null || !isPlaying || format == null || outputRate == 0) return
        val stats = try {
            out.stats()
        } catch (e: IllegalStateException) {
            return
        }
        // framesSent counts frames at the DAC's rate, which differs from the file's when resampling.
        positionText.text = "${TrackAdapter.formatDuration(stats.framesSent * 1000 / outputRate)} / " +
            "${TrackAdapter.formatDuration(format.durationMs)} · underruns ${stats.underruns}"
    }

    // ---- DAC -----------------------------------------------------------------------------

    private fun findDac(): UsbDevice? = usbManager.deviceList.values.firstOrNull { UsbAudioOutput.isUsbAudioOutput(it) }

    private fun refreshDacLabel() {
        val connected = output
        val found = findDac()
        connectButton.visibility = if (connected != null || found != null) View.VISIBLE else View.GONE
        connectButton.text = if (connected == null) "Connect" else "Give back to Android"
        dacText.text = when {
            connected != null -> "🎧 ${connected.device.productName} · in use by this app"
            found != null -> "🎧 ${found.productName} · ready"
            else -> "No USB DAC plugged in"
        }
    }

    private fun connectDac(thenPlay: Boolean) {
        val device = findDac()
        if (device == null) {
            showStatus("Plug in your USB DAC first.")
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
            setupVolume(out)
        } catch (e: UsbAudioException) {
            showStatus("Could not connect to the DAC: ${e.message}")
            log("Could not connect: ${e.message}")
        }
        planCache.clear()
        adapter.notifyDataSetChanged()
        refreshDacLabel()
        updatePlayerViews()
    }

    private fun closeDac() {
        output?.close()  // restores the DAC's own volume before handing it back to Android
        output = null
        volumeRange = null
        planCache.clear()
        volumeText.visibility = View.GONE
        volumeBar.visibility = View.GONE
        adapter.notifyDataSetChanged()
        refreshDacLabel()
        updatePlayerViews()
    }

    private fun setupVolume(out: UsbAudioOutput) {
        userVolumeDb?.let { out.setVolumeDb(it) }  // the library starts quiet; restore the user's level
        val range = out.volumeRange
        volumeRange = range
        volumeText.visibility = View.VISIBLE
        if (range == null) {
            volumeBar.visibility = View.GONE
            volumeText.text = "This DAC has no volume control — output is full scale, use with care"
            return
        }
        volumeBar.visibility = View.VISIBLE
        volumeBar.progress = (((range.currentDb - range.minDb) / (range.maxDb - range.minDb)) * volumeBar.max).toInt()
        volumeText.text = "Volume %.1f dB (set in the DAC, audio data untouched)".format(range.currentDb)
    }

    private fun setVolumeFromBar(progress: Int) {
        val range = volumeRange ?: return
        val db = range.minDb + (range.maxDb - range.minDb) * progress / volumeBar.max
        if (output?.setVolumeDb(db) == true) {
            userVolumeDb = db
            volumeText.text = "Volume %.1f dB (set in the DAC, audio data untouched)".format(db)
        }
    }

    // ---- Helpers -------------------------------------------------------------------------

    private fun log(message: String, toScreen: Boolean = true) {
        Log.i(TAG, message)
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        runCatching { File(filesDir, "app.log").appendText("$time $message\n") }
        if (!toScreen) return
        logText.append(message + "\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun ui(block: () -> Unit) {
        main.post(block)
    }

    companion object {
        private const val TAG = "FreeAudioBypasser"
        private const val ACTION_USB_PERMISSION = "com.freeaudiobypasser.app.USB_PERMISSION"
        private const val STATUS_COLOR = 0xFF2BB3C0.toInt()

        /** 44100 -> "44.1 kHz", 48000 -> "48 kHz". */
        private fun khz(rate: Int) = "${TrackAdapter.khz(rate)} kHz"
    }
}

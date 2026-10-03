package com.freeaudiobypasser.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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
    private lateinit var dacText: TextView
    private lateinit var connectButton: Button
    private lateinit var fileText: TextView
    private lateinit var statusText: TextView
    private lateinit var positionText: TextView
    private lateinit var volumeText: TextView
    private lateinit var volumeBar: SeekBar
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private val main = Handler(Looper.getMainLooper())
    private var output: UsbAudioOutput? = null
    private var volumeRange: VolumeRange? = null
    private var userVolumeDb: Float? = null
    private var fileUri: Uri? = null
    private var fileSampleRate = 0
    @Volatile private var outputRate = 0
    private var fileTotalFrames = 0L
    private var playThread: Thread? = null
    @Volatile private var stopRequested = false

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFileChosen(uri)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                ACTION_USB_PERMISSION ->
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                        openDac(device)
                    } else {
                        log("USB permission was denied.")
                    }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null && device.deviceName == output?.device?.deviceName) {
                        log("DAC unplugged.")
                        stopPlayback()
                        closeDac()
                        statusText.text = "DAC unplugged"
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

        dacText = findViewById(R.id.dacText)
        fileText = findViewById(R.id.fileText)
        statusText = findViewById(R.id.statusText)
        positionText = findViewById(R.id.positionText)
        volumeText = findViewById(R.id.volumeText)
        volumeBar = findViewById(R.id.volumeBar)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        connectButton = findViewById(R.id.connectButton)
        connectButton.setOnClickListener {
            if (output == null) {
                connectDac()
            } else {
                stopPlayback()
                closeDac()
                statusText.text = "Stopped"
                log("Released the DAC back to Android.")
            }
        }
        findViewById<Button>(R.id.chooseButton).setOnClickListener {
            pickFile.launch(arrayOf("audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/*"))
        }
        findViewById<Button>(R.id.playButton).setOnClickListener { play() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopPlayback() }
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
        main.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        showTestTracks()
    }

    /** Files copied to the app's own Music folder (no storage permission needed). */
    private fun showTestTracks() {
        val container = findViewById<LinearLayout>(R.id.testTracks)
        container.removeAllViews()
        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return
        val tracks = dir.listFiles { f -> f.extension.lowercase() in setOf("flac", "wav") }
            ?.sortedBy { it.name.lowercase() }.orEmpty()
        for (track in tracks) {
            container.addView(Button(this).apply {
                text = "▶ ${track.nameWithoutExtension}"
                isAllCaps = false
                setOnClickListener { onFileChosen(Uri.fromFile(track)) }
            })
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        unregisterReceiver(usbReceiver)
        stopPlayback()
        closeDac()
        super.onDestroy()
    }

    private fun findDac(): UsbDevice? = usbManager.deviceList.values.firstOrNull { UsbAudioOutput.isUsbAudioOutput(it) }

    private fun refreshDacLabel() {
        val connected = output
        connectButton.text = if (connected == null) "Connect DAC" else "Disconnect (give DAC back to Android)"
        dacText.text = when {
            connected != null -> "DAC: ${connected.device.productName} — under our control"
            findDac() != null -> "DAC found: ${findDac()?.productName} (tap Connect)"
            else -> "No USB DAC plugged in"
        }
    }

    private fun connectDac() {
        if (output != null) return log("DAC already connected.")
        val device = findDac() ?: return log("No USB audio DAC found. Plug it in first.")
        if (usbManager.hasPermission(device)) {
            openDac(device)
        } else {
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
            val pending = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE)
            usbManager.requestPermission(device, pending)
        }
    }

    private fun openDac(device: UsbDevice) {
        try {
            val out = UsbAudioOutput.open(usbManager, device)
            output = out
            val hex = out.rawDescriptors.joinToString(" ") { "%02x".format(it) }
            Log.i(TAG, "RAW_DESCRIPTORS $hex")
            File(filesDir, "dac-descriptors-%04x-%04x.bin".format(device.vendorId, device.productId))
                .writeBytes(out.rawDescriptors)
            log("Took control of ${device.productName} (VID %04x PID %04x)".format(device.vendorId, device.productId))
            log(out.description.trimEnd())
            log("Raw descriptors (${out.rawDescriptors.size} bytes):\n" + hexDump(out.rawDescriptors))
            setupVolume(out)
        } catch (e: UsbAudioException) {
            log("Could not connect: ${e.message}")
        }
        refreshDacLabel()
    }

    private fun closeDac() {
        output?.close()
        output = null
        volumeRange = null
        volumeBar.isEnabled = false
        volumeText.text = "Volume: (connect the DAC)"
        refreshDacLabel()
    }

    private fun setupVolume(out: UsbAudioOutput) {
        userVolumeDb?.let { out.setVolumeDb(it) }  // the library starts quiet; restore the user's level
        val range = out.volumeRange
        volumeRange = range
        if (range == null) {
            volumeBar.isEnabled = false
            volumeText.text = "Volume: this DAC has no hardware volume — use with care, output is full scale"
            return
        }
        volumeBar.isEnabled = true
        volumeBar.progress = (((range.currentDb - range.minDb) / (range.maxDb - range.minDb)) * volumeBar.max).toInt()
        volumeText.text = "Volume: %.1f dB (hardware, audio data untouched)".format(range.currentDb)
    }

    private fun setVolumeFromBar(progress: Int) {
        val range = volumeRange ?: return
        val db = range.minDb + (range.maxDb - range.minDb) * progress / volumeBar.max
        if (output?.setVolumeDb(db) == true) {
            userVolumeDb = db
            volumeText.text = "Volume: %.1f dB (hardware, audio data untouched)".format(db)
        }
    }

    private fun onFileChosen(uri: Uri) {
        stopPlayback()
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return log("Could not open the file.")
            AudioDecoder.open(pfd).use { dec ->
                fileUri = uri
                fileSampleRate = dec.sampleRate
                fileTotalFrames = dec.totalFrames
                fileText.text = "${displayName(uri)}\n${dec.sampleRate} Hz · ${dec.bitsPerSample}-bit · " +
                    "${dec.channels} ch · ${formatTime(dec.totalFrames / dec.sampleRate.coerceAtLeast(1))}"
            }
        } catch (e: Exception) {
            log("Could not read the file: ${e.message}")
        }
    }

    private fun play() {
        val out = output ?: return log("Connect the DAC first.")
        val uri = fileUri ?: return log("Choose a file first.")
        if (playThread?.isAlive == true) return
        stopRequested = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playThread = thread(name = "playback") { playbackLoop(out, uri) }
    }

    private fun playbackLoop(out: UsbAudioOutput, uri: Uri) {
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("file vanished")
            AudioDecoder.open(pfd).use { dec ->
                val info = out.start(dec.sampleRate, dec.bitsPerSample, dec.channels)
                outputRate = info.outputRate
                val dacBits = info.format.bitResolution
                val label = when {
                    info.bitPerfect ->
                        "${khz(info.sampleRate)} · ${info.bitsPerSample}-bit → USB direct · BIT-PERFECT ✓"
                    info.resampled ->
                        "${khz(info.sampleRate)} → ${khz(info.outputRate)} · ${info.bitsPerSample}→$dacBits-bit " +
                            "→ USB direct · CONVERTED"
                    else ->
                        "${khz(info.sampleRate)} · ${info.bitsPerSample}→$dacBits-bit dithered → USB direct"
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
                    statusText.text = (listOf(label) + reasons).joinToString("\n")
                    log("Playing via alt setting ${info.format.altSetting}: $dacBits-bit in " +
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
                    statusText.text = when {
                        stats.disconnected -> "DAC unplugged"
                        finished -> "Finished · underruns: ${stats.underruns}"
                        else -> "Stopped"
                    }
                    log("Sent ${stats.framesSent} frames, underruns ${stats.underruns}, USB errors ${stats.transferErrors}")
                }
            }
        } catch (e: UsbAudioException) {
            ui {
                statusText.text = "Cannot play this file"
                log(e.message ?: "error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "playback failed", e)
            ui {
                statusText.text = "Playback error"
                log("Playback error: $e")
            }
        } finally {
            ui { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    private fun stopPlayback() {
        stopRequested = true
        output?.stop()
        playThread?.join(3000)
        playThread = null
    }

    private fun updatePosition() {
        val out = output
        if (out == null || playThread?.isAlive != true || fileSampleRate == 0) return
        val stats = try {
            out.stats()
        } catch (e: IllegalStateException) {
            return
        }
        // framesSent counts frames at the DAC's rate, which differs from the file's when resampling.
        val rate = if (outputRate > 0) outputRate else fileSampleRate
        positionText.text = "${formatTime(stats.framesSent / rate)} / " +
            "${formatTime(fileTotalFrames / fileSampleRate)} · underruns ${stats.underruns}"
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

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

        private fun formatTime(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)

        /** 44100 -> "44.1 kHz", 48000 -> "48 kHz". */
        private fun khz(rate: Int): String =
            if (rate % 1000 == 0) "${rate / 1000} kHz" else "%.1f kHz".format(Locale.US, rate / 1000.0)

        private fun hexDump(bytes: ByteArray): String = bytes.toList().chunked(16).joinToString("\n") { row ->
            row.joinToString(" ") { "%02x".format(it) }
        }
    }
}

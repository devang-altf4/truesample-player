package com.truesample.usbaudio

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

/** One playback format a DAC advertises. */
data class DacFormat(
    val interfaceNumber: Int,
    val altSetting: Int,
    val endpointAddress: Int,
    val channels: Int,
    val subslotBytes: Int,
    val bitResolution: Int,
    val syncType: SyncType,
    val maxPacketBytes: Int,
    /** Discrete sample rates, or null when the DAC accepts the continuous [minRate]..[maxRate] range. */
    val sampleRates: List<Int>?,
    val minRate: Int,
    val maxRate: Int,
) {
    enum class SyncType { NONE, ASYNC, ADAPTIVE, SYNC }
}

data class StreamInfo(
    /** The source's sample rate. */
    val sampleRate: Int,
    val bitsPerSample: Int,
    val channels: Int,
    val format: DacFormat,
    /** The rate the DAC runs at; differs from [sampleRate] when [resampled]. */
    val outputRate: Int,
    /** Rate the DAC reports after switching, or 0 if it cannot report one. */
    val deviceRate: Int,
    /** True when the DAC receives the source samples unchanged. */
    val bitPerfect: Boolean,
    /** True when the DAC lacks [sampleRate] and audio is converted to [outputRate]. */
    val resampled: Boolean,
)

/** One way the DAC can run, from [UsbAudioOutput.outputModes]. */
data class OutputMode(
    /** Index into [UsbAudioOutput.formats]. */
    val formatIndex: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val subslotBytes: Int,
    val channels: Int,
)

/** How a source format would play on a DAC, from [UsbAudioOutput.plan]. */
data class PlaybackPlan(
    val outputRate: Int,
    val dacBits: Int,
    val resampled: Boolean,
    val bitPerfect: Boolean,
)

/** One equalizer filter. Frequencies in Hz, gain in dB; [q] sets the width (higher is narrower). */
data class EqBand(val type: Type, val frequencyHz: Float, val gainDb: Float, val q: Float) {
    enum class Type { PEAK, LOW_SHELF, HIGH_SHELF }
}

/** libsamplerate sinc converter used when the DAC lacks the source rate. */
enum class ResampleQuality { BEST, MEDIUM, FAST }

data class VolumeRange(val minDb: Float, val maxDb: Float, val stepDb: Float, val currentDb: Float)

data class StreamStats(val framesSent: Long, val underruns: Long, val transferErrors: Long, val disconnected: Boolean)

/**
 * Plays PCM on a USB Audio Class DAC directly over USB, bypassing Android's
 * audio mixer and resampler. No root needed: the app only needs USB permission
 * for the device.
 *
 * PCM passed to [write] is always interleaved signed 32-bit little-endian,
 * left-justified (a 24-bit sample shifted left by 8, a 16-bit sample by 16).
 *
 * Threading: call [write] and [drain] from one playback thread. [stop] may be
 * called from any thread and makes a blocked [write] return -1. Only call
 * [start] or [close] once the playback thread has finished.
 */
class UsbAudioOutput private constructor(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val claimed: List<UsbInterface>,
    private var handle: Long,
    val rawDescriptors: ByteArray,
) : Closeable {

    private val lock = Any()

    /** Human-readable summary of the DAC's descriptors. */
    val description: String = NativeBridge.nativeDescribe(handle)

    val formats: List<DacFormat> = parseFormats(NativeBridge.nativeFormats(handle))

    /**
     * Every mode the DAC can stream, highest quality first: each playback format at each
     * sample rate it supports, limited to what fits the USB link.
     */
    val outputModes: List<OutputMode> = NativeBridge.nativeModes(handle).toList().chunked(5)
        .map { OutputMode(formatIndex = it[0], sampleRate = it[1], bitsPerSample = it[2], subslotBytes = it[3], channels = it[4]) }
        .distinct()
        .sortedWith(compareByDescending<OutputMode> { it.sampleRate }.thenByDescending { it.bitsPerSample })

    /** The hardware volume range, or null when the DAC has no volume control. */
    val volumeRange: VolumeRange?
        get() = synchronized(lock) {
            checkOpen()
            val r = NativeBridge.nativeVolumeRange(handle)
            if (r[0] == 0) null else VolumeRange(r[1] / 256f, r[2] / 256f, r[3] / 256f, r[4] / 256f)
        }

    /**
     * Selects the matching DAC mode and starts streaming silence until [write] supplies audio.
     * With [mode] null the DAC runs at the source rate when it can (bit-perfect); otherwise
     * audio is converted to [mode], or to the best rate the DAC has. Conversion resamples with
     * [quality], and reducing bit depth uses TPDF dither; [StreamInfo.bitPerfect] is then false.
     * Throws [UsbAudioException] if the DAC (or [mode]) has no output with [channels] channels.
     */
    fun start(
        sampleRate: Int,
        bitsPerSample: Int,
        channels: Int,
        quality: ResampleQuality = ResampleQuality.BEST,
        mode: OutputMode? = null,
    ): StreamInfo = synchronized(lock) {
        checkOpen()
        val r = NativeBridge.nativeStart(
            handle, sampleRate, bitsPerSample, channels, quality.ordinal, mode?.formatIndex ?: -1, mode?.sampleRate ?: 0,
        )
        StreamInfo(
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            channels = channels,
            format = formats[r[0]],
            outputRate = r[5],
            deviceRate = r[1],
            bitPerfect = r[4] == 1,
            resampled = r[6] == 1,
        )
    }

    /**
     * Works out how a source format would be played on this DAC without starting
     * anything, or returns null if the DAC cannot play it at all (e.g. wrong channel count).
     */
    fun plan(
        sampleRate: Int,
        bitsPerSample: Int,
        channels: Int,
        mode: OutputMode? = null,
    ): PlaybackPlan? = synchronized(lock) {
        checkOpen()
        val r = NativeBridge.nativePlan(
            handle, sampleRate, bitsPerSample, channels, mode?.formatIndex ?: -1, mode?.sampleRate ?: 0,
        ) ?: return null
        PlaybackPlan(outputRate = r[0], dacBits = r[1], resampled = r[2] == 1, bitPerfect = r[3] == 1)
    }

    /**
     * Queues [frames] frames from a direct [pcm] buffer, blocking while the
     * internal buffer is full. Returns frames queued, or -1 once stopped or unplugged.
     */
    fun write(pcm: ByteBuffer, frames: Int): Int {
        require(pcm.isDirect) { "pcm must be a direct ByteBuffer" }
        checkOpen()
        return NativeBridge.nativeWrite(handle, pcm, frames)
    }

    /** Blocks until everything written so far has been sent to the DAC. */
    fun drain() {
        checkOpen()
        NativeBridge.nativeDrain(handle)
    }

    fun stop() = synchronized(lock) {
        if (handle != 0L) NativeBridge.nativeStop(handle)
    }

    /**
     * Applies an equalizer after any resampling (an empty [bands] list with 0 dB preamp turns it off).
     * Takes effect within ~10 ms, mid-song included. While it is on, audio is processed, so playback
     * is not bit-perfect. Settings persist across [start] calls until changed.
     */
    fun setEqualizer(bands: List<EqBand>, preampDb: Float) = synchronized(lock) {
        checkOpen()
        val flat = FloatArray(bands.size * 4)
        bands.forEachIndexed { i, b ->
            flat[i * 4] = b.type.ordinal.toFloat()
            flat[i * 4 + 1] = b.frequencyHz
            flat[i * 4 + 2] = b.gainDb
            flat[i * 4 + 3] = b.q
        }
        NativeBridge.nativeSetEqualizer(handle, flat, preampDb)
    }

    /** Sets the DAC's hardware volume, keeping the audio data itself untouched. */
    fun setVolumeDb(db: Float): Boolean = synchronized(lock) {
        checkOpen()
        NativeBridge.nativeSetVolume(handle, (db * 256).toInt().coerceIn(-32768, 32767))
    }

    fun stats(): StreamStats = synchronized(lock) {
        checkOpen()
        val s = NativeBridge.nativeStats(handle)
        StreamStats(s[0], s[1], s[2], s[3] != 0L)
    }

    /** Stops streaming and gives the DAC back to Android. */
    override fun close() = synchronized(lock) {
        if (handle == 0L) return
        NativeBridge.nativeClose(handle)
        handle = 0L
        claimed.forEach { connection.releaseInterface(it) }
        connection.close()
    }

    private fun checkOpen() = check(handle != 0L) { "UsbAudioOutput is closed" }

    companion object {
        /**
         * Takes exclusive control of [device]. The caller must already hold USB
         * permission for it (see [UsbManager.requestPermission]).
         */
        @Throws(UsbAudioException::class)
        fun open(usbManager: UsbManager, device: UsbDevice): UsbAudioOutput {
            val connection = usbManager.openDevice(device)
                ?: throw UsbAudioException("Android refused to open the DAC. Is USB permission granted?")
            val claimed = mutableListOf<UsbInterface>()
            try {
                for (intf in playbackInterfaces(device)) {
                    // force = true detaches Android's own USB audio driver from the interface.
                    if (!connection.claimInterface(intf, true)) {
                        throw UsbAudioException("Could not take over interface ${intf.id} of the DAC.")
                    }
                    claimed += intf
                }
                if (claimed.isEmpty()) throw UsbAudioException("${device.productName ?: "This device"} is not a USB audio DAC.")
                val raw = connection.rawDescriptors
                    ?: throw UsbAudioException("Could not read the DAC's descriptors.")
                val handle = NativeBridge.nativeOpen(connection.fileDescriptor, raw)
                return UsbAudioOutput(device, connection, claimed, handle, raw)
            } catch (e: Exception) {
                claimed.forEach { connection.releaseInterface(it) }
                connection.close()
                throw e
            }
        }

        /** Also append driver logs to [file], or stop when null. Useful on phones that hide app logcat output. */
        fun setLogFile(file: File?) = NativeBridge.nativeSetLogFile(file?.absolutePath)

        /** True when [device] exposes a USB Audio playback interface. */
        fun isUsbAudioOutput(device: UsbDevice): Boolean = playbackInterfaces(device).any {
            it.interfaceSubclass == SUBCLASS_STREAMING
        }

        private const val SUBCLASS_CONTROL = 1
        private const val SUBCLASS_STREAMING = 2

        /** The AudioControl interface plus every streaming interface with an isochronous OUT endpoint. */
        private fun playbackInterfaces(device: UsbDevice): List<UsbInterface> {
            val all = (0 until device.interfaceCount).map { device.getInterface(it) }
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO }
            val streamingOut = all.filter { intf ->
                intf.interfaceSubclass == SUBCLASS_STREAMING &&
                    (0 until intf.endpointCount).map { intf.getEndpoint(it) }.any {
                        it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && it.direction == UsbConstants.USB_DIR_OUT
                    }
            }.map { it.id }.toSet()
            // Each alternate setting is listed separately; claim each interface number once.
            return all.filter { it.interfaceSubclass == SUBCLASS_CONTROL || it.id in streamingOut }
                .distinctBy { it.id }
        }

        private fun parseFormats(flat: IntArray): List<DacFormat> {
            val formats = mutableListOf<DacFormat>()
            var i = 0
            while (i < flat.size) {
                val rateCount = flat[i + 11]
                val continuous = flat[i + 8] == 1
                formats += DacFormat(
                    interfaceNumber = flat[i],
                    altSetting = flat[i + 1],
                    endpointAddress = flat[i + 2],
                    channels = flat[i + 3],
                    subslotBytes = flat[i + 4],
                    bitResolution = flat[i + 5],
                    syncType = DacFormat.SyncType.entries[flat[i + 6]],
                    maxPacketBytes = flat[i + 7],
                    sampleRates = if (continuous) null else flat.copyOfRange(i + 12, i + 12 + rateCount).toList(),
                    minRate = flat[i + 9],
                    maxRate = flat[i + 10],
                )
                i += 12 + rateCount
            }
            return formats
        }
    }
}

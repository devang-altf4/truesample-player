package com.freeaudiobypasser.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.freeaudiobypasser.usbaudio.OutputMode
import com.freeaudiobypasser.usbaudio.PlaybackPlan
import java.util.Locale

sealed interface DacStatus {
    data object Absent : DacStatus
    /** Plugged in, but Android is using it. */
    data class Available(val name: String) : DacStatus
    /** Under this app's exclusive control. */
    data class InUse(val name: String) : DacStatus
}

data class Progress(val positionMs: Long, val durationMs: Long, val underruns: Long)

data class VolumeState(val minDb: Float, val maxDb: Float, val db: Float)

/** Everything the screen shows. Written on the main thread only. */
class PlayerState {
    var dac by mutableStateOf<DacStatus>(DacStatus.Absent)
    /** Modes the connected DAC can run in, highest first. */
    var modes by mutableStateOf<List<OutputMode>>(emptyList())
    /** Null means Auto: each song at its own rate when the DAC supports it. */
    var chosenMode by mutableStateOf<OutputMode?>(null)
    var tracks by mutableStateOf<List<Track>>(emptyList())
    /** Bumped whenever more file headers have been read, so format tags refresh. */
    var formatsVersion by mutableIntStateOf(0)
    var selected by mutableStateOf<Track?>(null)
    var isPlaying by mutableStateOf(false)
    var progress by mutableStateOf<Progress?>(null)
    /** A one-off notice under the signal path (errors, "finished"). */
    var notice by mutableStateOf<String?>(null)
    var volume by mutableStateOf<VolumeState?>(null)
    var musicPermission by mutableStateOf(true)
    var query by mutableStateOf("")
    val log = mutableStateListOf<String>()
}

/** What the screen can ask for. */
interface PlayerActions {
    fun toggleDac()
    fun chooseMode(mode: OutputMode?)
    fun select(track: Track)
    fun togglePlay()
    fun setVolume(db: Float)
    fun openFile()
    fun allowMusic()
    /** Sends the logs and DAC descriptors through Android's share sheet. */
    fun shareDiagnostics()
    /** Null while the file header has not been read yet. */
    fun formatOf(track: Track): Result<SourceFormat>?
    /** How the connected DAC will play [format] in the chosen mode; null with no DAC or if it can't. */
    fun planFor(format: SourceFormat): PlaybackPlan?
}

/** 44100 -> "44.1", 48000 -> "48" (kHz). */
fun khz(rate: Int): String = if (rate % 1000 == 0) "${rate / 1000}" else "%.1f".format(Locale.US, rate / 1000.0)

fun formatDuration(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

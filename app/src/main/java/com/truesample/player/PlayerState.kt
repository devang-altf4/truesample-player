package com.truesample.player

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.truesample.usbaudio.OutputMode
import com.truesample.usbaudio.PlaybackPlan
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

enum class LibraryTab { SONGS, ALBUMS }

/** A folder the user added as a music source. */
data class FolderSource(val uri: Uri, val name: String)

/** Everything the screen shows. Written on the main thread only. */
class PlayerState {
    var dac by mutableStateOf<DacStatus>(DacStatus.Absent)
    /** Modes the connected DAC can run in, highest first. */
    var modes by mutableStateOf<List<OutputMode>>(emptyList())
    /** Null means Auto: each song at its own rate when the DAC supports it. */
    var chosenMode by mutableStateOf<OutputMode?>(null)

    var tracks by mutableStateOf<List<Track>>(emptyList())
    var albums by mutableStateOf<List<Album>>(emptyList())
    /** Bumped whenever more file headers have been read, so format tags refresh. */
    var formatsVersion by mutableIntStateOf(0)
    var tab by mutableStateOf(LibraryTab.SONGS)
    var openAlbum by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var includeAllMusic by mutableStateOf(true)
    /** Hide voice notes and other clips Android files under music. */
    var hideShortClips by mutableStateOf(true)
    var folders by mutableStateOf<List<FolderSource>>(emptyList())
    var musicPermission by mutableStateOf(true)
    var libraryLoading by mutableStateOf(false)

    /** The song shown in the player card: the one playing/paused, or the one just tapped. */
    var selected by mutableStateOf<Track?>(null)
    /** The song loaded for playback (playing or paused). */
    var current by mutableStateOf<Track?>(null)
    var isPlaying by mutableStateOf(false)
    var progress by mutableStateOf<Progress?>(null)
    var hasNext by mutableStateOf(false)
    var hasPrevious by mutableStateOf(false)
    /** A one-off notice under the signal path (errors, "finished"). */
    var notice by mutableStateOf<String?>(null)
    var volume by mutableStateOf<VolumeState?>(null)
    var currentArt by mutableStateOf<Bitmap?>(null)

    var eq by mutableStateOf(EqSettings())
    var showEqualizer by mutableStateOf(false)
    var showSources by mutableStateOf(false)
    val log = mutableStateListOf<String>()
}

/** What the screen can ask for. */
interface PlayerActions {
    fun toggleDac()
    fun chooseMode(mode: OutputMode?)
    /** Tap on a song in [context] (the list it was tapped in, used as the play queue). */
    fun select(track: Track, context: List<Track>)
    /** Starts playing [context] from [track] (e.g. "Play album"). */
    fun play(track: Track, context: List<Track>)
    fun togglePlay()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun setVolume(db: Float)
    fun setEqualizer(settings: EqSettings)
    fun openFile()
    fun allowMusic()
    fun addFolder()
    fun removeFolder(folder: FolderSource)
    fun setIncludeAllMusic(include: Boolean)
    fun setHideShortClips(hide: Boolean)
    /** Sends the logs and DAC descriptors through Android's share sheet. */
    fun shareDiagnostics()
    /** Null while the file header has not been read yet. */
    fun formatOf(track: Track): Result<SourceFormat>?
    /** How the connected DAC will play [format] in the chosen mode; null with no DAC or if it can't. */
    fun planFor(format: SourceFormat): PlaybackPlan?
    /** Blocking cover lookup; call off the main thread. */
    fun artwork(track: Track, sizePx: Int): Bitmap?
}

/** 44100 -> "44.1", 48000 -> "48" (kHz). */
fun khz(rate: Int): String = if (rate % 1000 == 0) "${rate / 1000}" else "%.1f".format(Locale.US, rate / 1000.0)

fun formatDuration(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

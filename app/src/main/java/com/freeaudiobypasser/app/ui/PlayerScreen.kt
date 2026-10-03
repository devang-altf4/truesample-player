package com.freeaudiobypasser.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freeaudiobypasser.app.DacStatus
import com.freeaudiobypasser.app.PlayerActions
import com.freeaudiobypasser.app.PlayerState
import com.freeaudiobypasser.app.Progress
import com.freeaudiobypasser.app.SourceFormat
import com.freeaudiobypasser.app.Track
import com.freeaudiobypasser.app.VolumeState
import com.freeaudiobypasser.app.formatDuration
import com.freeaudiobypasser.app.khz
import com.freeaudiobypasser.usbaudio.OutputMode
import com.freeaudiobypasser.usbaudio.PlaybackPlan
import java.util.Locale

@Composable
fun PlayerScreen(state: PlayerState, actions: PlayerActions) {
    var showLog by remember { mutableStateOf(false) }
    val query = state.query.trim()
    val visibleTracks = remember(state.tracks, query) {
        if (query.isEmpty()) state.tracks
        else state.tracks.filter { it.title.contains(query, true) || it.artist?.contains(query, true) == true }
    }
    val dacInUse = state.dac is DacStatus.InUse

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Hifi.Faceplate)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item { Header(onShowLog = { showLog = true }) }
        item { DacRow(state.dac, actions::toggleDac) }
        if (dacInUse && state.modes.isNotEmpty()) {
            item { ModePicker(state.modes, state.chosenMode, actions::chooseMode) }
        }
        item { NowPlaying(state, actions) }
        item { LibraryHeader(state.tracks.size, actions::openFile) }
        if (state.tracks.size > 6) {
            item { SearchField(state.query) { state.query = it } }
        }
        if (!state.musicPermission) {
            item { PermissionCard(actions::allowMusic) }
        } else if (state.tracks.isEmpty()) {
            item {
                Text(
                    "No music on this phone yet. Copy some over, or use Open file.",
                    style = HifiType.Caption,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
        items(visibleTracks, key = { it.uri.toString() }) { track ->
            val tag = formatTag(track, state, actions)
            TrackRow(
                track = track,
                subtitle = trackSubtitle(track, actions),
                tag = tag,
                selected = track == state.selected,
                playing = state.isPlaying && track == state.selected,
                onClick = { actions.select(track) },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showLog) LogDialog(state.log, onShare = actions::shareDiagnostics) { showLog = false }
}

// ---- Header and DAC ------------------------------------------------------------------------

@Composable
private fun Header(onShowLog: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("FREE AUDIO BYPASSER", style = HifiType.Brand)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onShowLog) { Text("LOG", style = HifiType.Engraved) }
    }
}

@Composable
private fun DacRow(dac: DacStatus, onToggle: () -> Unit) {
    val name: String
    val detail: String
    val lamp: Color
    when (dac) {
        DacStatus.Absent -> {
            name = "No USB DAC"; detail = "Plug a USB DAC or dongle into the phone"; lamp = Hifi.Label.copy(alpha = 0.4f)
        }
        is DacStatus.Available -> {
            name = dac.name; detail = "Plugged in · Android is using it"; lamp = Hifi.Label
        }
        is DacStatus.InUse -> {
            name = dac.name; detail = "Exclusive · Android's mixer bypassed"; lamp = Hifi.Vfd
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Lamp(lamp, glow = dac is DacStatus.InUse)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = HifiType.Body.copy(fontWeight = FontWeight.Medium, color = Hifi.Ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(detail, style = HifiType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val action = when (dac) {
            is DacStatus.InUse -> "Give back"
            is DacStatus.Available -> "Connect"
            DacStatus.Absent -> null
        }
        if (action != null) {
            OutlinedButton(
                onClick = onToggle,
                border = BorderStroke(1.dp, Hifi.Hairline),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) { Text(action, style = HifiType.Caption.copy(color = Hifi.Ink)) }
        }
    }
}

/** An indicator lamp; [glow] adds the halo of a lit bulb. */
@Composable
private fun Lamp(color: Color, size: Dp = 9.dp, glow: Boolean = true) {
    Box(
        Modifier
            .size(size)
            .drawBehind {
                val r = this.size.minDimension
                if (glow) {
                    drawCircle(
                        Brush.radialGradient(listOf(color.copy(alpha = 0.5f), Color.Transparent), center, r * 1.7f),
                        radius = r * 1.7f,
                    )
                }
                drawCircle(color, radius = r / 2)
            },
    )
}

// ---- Output mode ---------------------------------------------------------------------------

private fun modeLabel(m: OutputMode): String =
    "${khz(m.sampleRate)} kHz · ${m.bitsPerSample}-bit" + if (m.channels != 2) " · ${m.channels} ch" else ""

@Composable
private fun ModePicker(modes: List<OutputMode>, chosen: OutputMode?, onChoose: (OutputMode?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text("OUTPUT MODE", style = HifiType.Engraved)
        Spacer(Modifier.height(6.dp))
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .border(1.dp, Hifi.Hairline, shape)
                    .clickable { open = true }
                    .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (chosen == null) {
                    Text("AUTO", style = HifiType.Display.copy(color = Hifi.Vfd))
                    Text("  ·  match each song", style = HifiType.Caption)
                } else {
                    Text(modeLabel(chosen), style = HifiType.Display.copy(color = Hifi.Ink))
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose output mode", tint = Hifi.Label)
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                containerColor = Hifi.Panel,
            ) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Auto · match each song", style = HifiType.Body.copy(color = Hifi.Ink))
                            Text("Bit-perfect whenever the DAC has the song's format", style = HifiType.Caption)
                        }
                    },
                    trailingIcon = { if (chosen == null) Icon(Icons.Filled.Check, null, tint = Hifi.Vfd) },
                    onClick = { open = false; onChoose(null) },
                )
                HorizontalDivider(color = Hifi.Hairline)
                modes.forEachIndexed { index, mode ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(modeLabel(mode), style = HifiType.Display.copy(color = Hifi.Ink))
                                if (index == 0 && modes.size > 1) {
                                    Spacer(Modifier.width(10.dp))
                                    Text("MAX", style = HifiType.Engraved.copy(color = Hifi.Vfd))
                                }
                            }
                        },
                        trailingIcon = { if (mode == chosen) Icon(Icons.Filled.Check, null, tint = Hifi.Vfd) },
                        onClick = { open = false; onChoose(mode) },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val caption = when {
            chosen != null ->
                "Every song is converted to ${modeLabel(chosen)}. Songs already in this format stay bit-perfect."
            modes.size == 1 ->
                "This DAC has one mode, ${modeLabel(modes[0])}. Songs in other formats are converted to it."
            else -> "Each song plays at its own rate when the DAC supports it. Pick a mode to force one."
        }
        Text(caption, style = HifiType.Caption)
    }
}

// ---- Now playing ---------------------------------------------------------------------------

@Composable
private fun NowPlaying(state: PlayerState, actions: PlayerActions) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Hifi.Panel)
            .border(1.dp, Hifi.Hairline, shape)
            .padding(16.dp),
    ) {
        Text(if (state.isPlaying) "NOW PLAYING" else "SELECTED", style = HifiType.Engraved)
        val track = state.selected
        if (track == null) {
            Spacer(Modifier.height(8.dp))
            Text("Choose a song from your library below.", style = HifiType.Body.copy(color = Hifi.Label))
            return@Column
        }
        Spacer(Modifier.height(6.dp))
        Text(track.title, style = HifiType.Title.copy(color = Hifi.Ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(track.artist ?: track.fileName, style = HifiType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)

        // Reading formatsVersion re-runs this once the file's header has been parsed.
        val format = state.formatsVersion.let { actions.formatOf(track)?.getOrNull() }
        val dacInUse = state.dac is DacStatus.InUse
        val plan = if (format != null && dacInUse) actions.planFor(format) else null

        Spacer(Modifier.height(14.dp))
        SignalPath(format, plan, track.codec)
        Spacer(Modifier.height(12.dp))
        Verdict(format, plan, dacInUse, state.chosenMode, track.codec)
        state.notice?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = HifiType.Caption.copy(color = Hifi.Amber))
        }
        val progress = state.progress
        if (state.isPlaying && progress != null) ProgressBar(progress)
        Spacer(Modifier.height(16.dp))
        PlayButton(state.isPlaying, actions::togglePlay)
        if (dacInUse) VolumeRow(state.volume, actions::setVolume)
    }
}

/** "24/48" for lossless files; "MP3 44.1" for lossy ones, where a bit depth means nothing. */
private fun sourceLabel(format: SourceFormat, codec: String, separator: String = "/"): String =
    if (format.lossy) "$codec$separator${khz(format.sampleRate)}" else "${format.bitsPerSample}/${khz(format.sampleRate)}"

private enum class StageLamp { OFF, PASS, ALTER }

private data class Stage(val label: String, val value: String, val lamp: StageLamp)

/**
 * The signature element: the four places audio could be changed on its way to the DAC,
 * each lit green when the audio passes untouched and amber when it is altered.
 */
@Composable
private fun SignalPath(format: SourceFormat?, plan: PlaybackPlan?, codec: String) {
    val off = { label: String -> Stage(label, "—", StageLamp.OFF) }
    val stages = if (format == null || plan == null) {
        listOf(
            format?.let { Stage("SOURCE", sourceLabel(it, codec), StageLamp.PASS) } ?: off("SOURCE"),
            off("RATE"), off("DEPTH"), off("DAC"),
        )
    } else {
        listOf(
            Stage("SOURCE", sourceLabel(format, codec), StageLamp.PASS),
            if (plan.resampled) Stage("RATE", "${khz(format.sampleRate)}→${khz(plan.outputRate)}", StageLamp.ALTER)
            else Stage("RATE", "PASS", StageLamp.PASS),
            if (plan.dacBits < format.bitsPerSample) {
                Stage("DEPTH", if (format.lossy) "→${plan.dacBits}" else "${format.bitsPerSample}→${plan.dacBits}", StageLamp.ALTER)
            } else {
                Stage("DEPTH", "PASS", StageLamp.PASS)
            },
            Stage(
                "DAC",
                "${plan.dacBits}/${khz(plan.outputRate)}",
                if (plan.bitPerfect) StageLamp.PASS else StageLamp.ALTER,
            ),
        )
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        stages.forEachIndexed { index, stage ->
            if (index > 0) Box(Modifier.width(6.dp).height(1.dp).background(Hifi.Hairline))
            StageCell(stage, index, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StageCell(stage: Stage, index: Int, modifier: Modifier) {
    val target = when (stage.lamp) {
        StageLamp.PASS -> Hifi.Vfd
        StageLamp.ALTER -> Hifi.Amber
        StageLamp.OFF -> Hifi.Label.copy(alpha = 0.35f)
    }
    // Stages light up one after another, left to right, like a signal travelling.
    val color by animateColorAsState(target, tween(durationMillis = 260, delayMillis = index * 70), label = "lamp")
    val shape = RoundedCornerShape(7.dp)
    Column(
        modifier
            .clip(shape)
            .background(color.copy(alpha = 0.07f))
            .border(1.dp, color.copy(alpha = 0.5f), shape)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stage.label, style = HifiType.Engraved.copy(fontSize = 7.sp, letterSpacing = 1.sp))
        Spacer(Modifier.height(5.dp))
        Text(stage.value, style = HifiType.DisplaySmall.copy(color = color), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Verdict(
    format: SourceFormat?,
    plan: PlaybackPlan?,
    dacInUse: Boolean,
    chosenMode: OutputMode?,
    codec: String,
) {
    val word: String
    val color: Color
    val detail: String
    when {
        format == null -> {
            word = "READING FILE"; color = Hifi.Label; detail = ""
        }
        !dacInUse -> {
            word = "DAC NOT CONNECTED"; color = Hifi.Label
            detail = "Press Play to take over your USB DAC and see exactly how this song reaches it."
        }
        plan == null -> {
            word = "CAN'T PLAY"; color = Hifi.Fault
            detail = "Your DAC has no mode for a ${format.channels}-channel song."
        }
        format.lossy -> {
            word = "LOSSY SOURCE"; color = Hifi.Amber
            detail = "$codec is a lossy format, so bit-perfect doesn't apply. It's decoded on the phone and sent " +
                "straight to your DAC at ${khz(plan.outputRate)} kHz / ${plan.dacBits}-bit, skipping Android's mixer."
        }
        plan.bitPerfect -> {
            word = "BIT-PERFECT"; color = Hifi.Vfd
            detail = "Your DAC receives the file's samples unchanged."
        }
        else -> {
            word = "CONVERTED"; color = Hifi.Amber
            detail = buildList {
                if (plan.resampled) {
                    add(
                        if (chosenMode != null) "Output mode is set to ${khz(plan.outputRate)} kHz, so this " +
                            "${khz(format.sampleRate)} kHz song is converted."
                        else "Your DAC can't play ${khz(format.sampleRate)} kHz, so it's converted to " +
                            "${khz(plan.outputRate)} kHz."
                    )
                }
                if (plan.dacBits < format.bitsPerSample) {
                    add("${format.bitsPerSample}-bit is reduced to ${plan.dacBits}-bit with dither.")
                }
            }.joinToString(" ")
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Lamp(color, size = 7.dp, glow = color != Hifi.Label)
        Spacer(Modifier.width(8.dp))
        Text(word, style = HifiType.Engraved.copy(fontSize = 10.sp, letterSpacing = 2.sp, color = color))
    }
    if (detail.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(detail, style = HifiType.Caption)
    }
}

@Composable
private fun ProgressBar(progress: Progress) {
    val fraction = if (progress.durationMs > 0) {
        (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
    } else 0f
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Hifi.Hairline)) {
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(Hifi.Vfd))
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatDuration(progress.positionMs), style = HifiType.DisplaySmall.copy(color = Hifi.Ink))
        if (progress.underruns > 0) {
            Text("${progress.underruns} dropouts", style = HifiType.DisplaySmall.copy(color = Hifi.Amber))
        }
        Text(formatDuration(progress.durationMs), style = HifiType.DisplaySmall.copy(color = Hifi.Label))
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (playing) Hifi.Faceplate else Hifi.Vfd,
            contentColor = if (playing) Hifi.Ink else Hifi.Faceplate,
        ),
        border = if (playing) BorderStroke(1.dp, Hifi.Hairline) else null,
    ) {
        if (playing) {
            Box(Modifier.size(12.dp).background(LocalContentColor.current))
        } else {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
        }
        Spacer(Modifier.width(10.dp))
        Text(if (playing) "STOP" else "PLAY", style = HifiType.Brand.copy(color = LocalContentColor.current))
    }
}

@Composable
private fun VolumeRow(volume: VolumeState?, onChange: (Float) -> Unit) {
    Spacer(Modifier.height(14.dp))
    if (volume == null) {
        Text(
            "This DAC has no volume control, so it plays at full level. Turn your IEMs or amp down first.",
            style = HifiType.Caption.copy(color = Hifi.Amber),
        )
        return
    }
    var db by remember(volume.minDb, volume.maxDb) {
        mutableFloatStateOf(volume.db.coerceIn(volume.minDb, volume.maxDb))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("VOL", style = HifiType.Engraved)
        Spacer(Modifier.width(10.dp))
        Slider(
            value = db,
            onValueChange = { db = it; onChange(it) },
            valueRange = volume.minDb..volume.maxDb,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Hifi.Ink,
                activeTrackColor = Hifi.Vfd,
                inactiveTrackColor = Hifi.Hairline,
            ),
        )
        Text(
            "%.1f dB".format(Locale.US, db),
            style = HifiType.DisplaySmall.copy(color = Hifi.Ink),
            textAlign = TextAlign.End,
            modifier = Modifier.width(72.dp),
        )
    }
    Text("Set inside the DAC — the audio data stays untouched.", style = HifiType.Caption)
}

// ---- Library -------------------------------------------------------------------------------

@Composable
private fun LibraryHeader(count: Int, onOpenFile: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("LIBRARY", style = HifiType.Engraved)
        Spacer(Modifier.width(8.dp))
        Text("$count", style = HifiType.DisplaySmall.copy(color = Hifi.Label))
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onOpenFile) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Hifi.Ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Open file", style = HifiType.Caption.copy(color = Hifi.Ink))
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        placeholder = { Text("Search songs or artists", style = HifiType.Caption) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Hifi.Label) },
        singleLine = true,
        textStyle = HifiType.Body,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Hifi.Vfd,
            unfocusedBorderColor = Hifi.Hairline,
            cursorColor = Hifi.Vfd,
            focusedTextColor = Hifi.Ink,
            unfocusedTextColor = Hifi.Ink,
        ),
    )
}

@Composable
private fun PermissionCard(onAllow: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(shape)
            .border(1.dp, Hifi.Hairline, shape)
            .padding(16.dp),
    ) {
        Text("See the music on this phone.", style = HifiType.Body.copy(color = Hifi.Ink))
        Text("The app only reads your music. Nothing leaves the phone.", style = HifiType.Caption)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onAllow,
            colors = ButtonDefaults.buttonColors(containerColor = Hifi.Vfd, contentColor = Hifi.Faceplate),
        ) { Text("Allow access to music") }
    }
}

private data class FormatTag(val text: String, val color: Color)

@Composable
private fun formatTag(track: Track, state: PlayerState, actions: PlayerActions): FormatTag {
    // Reading formatsVersion re-runs this once the file's header has been parsed.
    val result = state.formatsVersion.let { actions.formatOf(track) } ?: return FormatTag("···", Hifi.Label)
    val format = result.getOrNull() ?: return FormatTag("ERR", Hifi.Fault)
    val text = sourceLabel(format, track.codec, separator = " ")
    if (state.dac !is DacStatus.InUse) return FormatTag(text, Hifi.Label)
    val plan = actions.planFor(format)
    return FormatTag(
        text,
        when {
            plan == null -> Hifi.Fault
            plan.bitPerfect -> Hifi.Vfd
            else -> Hifi.Amber
        },
    )
}

private fun trackSubtitle(track: Track, actions: PlayerActions): String {
    val duration = track.durationMs.takeIf { it > 0 } ?: actions.formatOf(track)?.getOrNull()?.durationMs ?: 0
    return listOfNotNull(track.artist, duration.takeIf { it > 0 }?.let(::formatDuration), track.codec)
        .joinToString(" · ")
}

@Composable
private fun TrackRow(
    track: Track,
    subtitle: String,
    tag: FormatTag,
    selected: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Hifi.Panel else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = HifiType.Body.copy(
                    color = Hifi.Ink,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, style = HifiType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Lamp(tag.color, size = 6.dp, glow = playing)
        Spacer(Modifier.width(7.dp))
        Text(tag.text, style = HifiType.DisplaySmall.copy(color = tag.color))
    }
}

@Composable
private fun LogDialog(lines: List<String>, onShare: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = Hifi.Vfd) } },
        dismissButton = { TextButton(onClick = onShare) { Text("Share diagnostics", color = Hifi.Ink) } },
        title = { Text("Driver log", style = HifiType.Title.copy(color = Hifi.Ink)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                items(lines) {
                    Text(it, style = HifiType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                }
            }
        },
        containerColor = Hifi.Panel,
    )
}

package com.truesample.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.truesample.player.Album
import com.truesample.player.LibraryTab
import com.truesample.player.PlayerActions
import com.truesample.player.PlayerState
import com.truesample.player.Track
import com.truesample.player.formatDuration

/** The library section of the main list: tabs, then songs or albums. */
fun LazyListScope.libraryItems(state: PlayerState, actions: PlayerActions) {
    item { LibraryHeader(state, actions) }
    if (state.includeAllMusic && !state.musicPermission) item { PermissionCard(actions::allowMusic) }
    when (state.tab) {
        LibraryTab.SONGS -> songItems(state, actions)
        LibraryTab.ALBUMS -> {
            val open = state.openAlbum?.let { key -> state.albums.firstOrNull { it.key == key } }
            if (open != null) albumDetailItems(open, state, actions) else albumGridItems(state, actions)
        }
    }
}

@Composable
private fun LibraryHeader(state: PlayerState, actions: PlayerActions) {
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LibraryTab.entries.forEach { tab ->
                val count = if (tab == LibraryTab.SONGS) state.tracks.size else state.albums.size
                val selected = tab == state.tab
                Column(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            state.tab = tab
                            state.openAlbum = null
                        }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tab.label, style = HifiType.Engraved.copy(color = if (selected) Hifi.Ink else Hifi.Label))
                        Spacer(Modifier.width(6.dp))
                        Text("$count", style = HifiType.DisplaySmall.copy(color = Hifi.Label))
                    }
                    Spacer(Modifier.height(4.dp))
                    Spacer(
                        Modifier
                            .height(2.dp)
                            .width(if (selected) 28.dp else 0.dp)
                            .background(Hifi.Vfd),
                    )
                }
                Spacer(Modifier.width(14.dp))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { state.showSources = true }) {
                Text("Sources", style = HifiType.Caption.copy(color = Hifi.Ink))
            }
            TextButton(onClick = actions::openFile) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Hifi.Ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(2.dp))
                Text("File", style = HifiType.Caption.copy(color = Hifi.Ink))
            }
        }
        if (state.libraryLoading && state.tracks.isEmpty()) {
            Text("Reading your library…", style = HifiType.Caption, modifier = Modifier.padding(vertical = 10.dp))
        }
    }
}

// ---- Songs ---------------------------------------------------------------------------------

private fun LazyListScope.songItems(state: PlayerState, actions: PlayerActions) {
    val query = state.query.trim()
    val songs = if (query.isEmpty()) state.tracks else state.tracks.filter {
        it.title.contains(query, true) || it.artist?.contains(query, true) == true ||
            it.album?.contains(query, true) == true
    }
    if (state.tracks.size > 6) item { SearchField(state.query) { state.query = it } }
    if (state.tracks.isEmpty() && !state.libraryLoading && state.musicPermission) {
        item {
            Text(
                "No music found yet. Copy some to this phone, add a folder under Sources, or open a file.",
                style = HifiType.Caption,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
    items(songs, key = { "song:" + it.uri }) { track ->
        TrackRow(track, state, actions, numberLabel = null, onClick = { actions.select(track, songs) })
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        placeholder = { Text("Search songs, artists or albums", style = HifiType.Caption) },
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
private fun TrackRow(
    track: Track,
    state: PlayerState,
    actions: PlayerActions,
    numberLabel: String?,
    onClick: () -> Unit,
) {
    val selected = state.selected?.uri == track.uri
    val playing = state.isPlaying && state.current?.uri == track.uri
    val tag = formatTag(track, state, actions)
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Hifi.Panel else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (numberLabel != null) {
            Text(
                numberLabel,
                style = HifiType.DisplaySmall.copy(color = if (playing) Hifi.Vfd else Hifi.Label),
                modifier = Modifier.width(30.dp),
            )
        } else {
            Cover(track, actions, size = 44.dp, corner = 6.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = HifiType.Body.copy(
                    color = if (playing) Hifi.Vfd else Hifi.Ink,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val duration = track.durationMs.takeIf { it > 0 }?.let(::formatDuration)
            Text(
                listOfNotNull(if (numberLabel == null) track.artist else null, duration, track.codec).joinToString(" · "),
                style = HifiType.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Lamp(tag.color, size = 6.dp, glow = playing)
        Spacer(Modifier.width(7.dp))
        Text(tag.text, style = HifiType.DisplaySmall.copy(color = tag.color))
    }
}

// ---- Albums --------------------------------------------------------------------------------

private fun LazyListScope.albumGridItems(state: PlayerState, actions: PlayerActions) {
    if (state.albums.isEmpty() && !state.libraryLoading) {
        item {
            Text(
                "No albums yet. Albums come from your files' album tags.",
                style = HifiType.Caption,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
    items(state.albums.chunked(2), key = { row -> "albums:" + row.first().key }) { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { album -> AlbumTile(album, state, actions, Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun AlbumTile(album: Album, state: PlayerState, actions: PlayerActions, modifier: Modifier) {
    val first = album.tracks.first()
    val tag = formatTag(first, state, actions)
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { state.openAlbum = album.key }
            .padding(4.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Cover(first, actions, size = maxWidth, corner = 8.dp, requestPx = 420)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            album.title,
            style = HifiType.Body.copy(color = Hifi.Ink, fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(album.artist ?: "Unknown artist", style = HifiType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(trackCount(album.tracks.size), style = HifiType.Caption)
            Spacer(Modifier.width(8.dp))
            Lamp(tag.color, size = 5.dp, glow = false)
            Spacer(Modifier.width(5.dp))
            Text(tag.text, style = HifiType.DisplaySmall.copy(color = tag.color), maxLines = 1)
        }
    }
}

private fun LazyListScope.albumDetailItems(album: Album, state: PlayerState, actions: PlayerActions) {
    item(key = "album-header:" + album.key) {
        Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp)) {
            TextButton(onClick = { state.openAlbum = null }) {
                Text("‹  ALL ALBUMS", style = HifiType.Engraved.copy(color = Hifi.Ink))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(album.tracks.first(), actions, size = 128.dp, requestPx = 720)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(album.title, style = HifiType.Title.copy(color = Hifi.Ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(album.artist ?: "Unknown artist", style = HifiType.Caption)
                    val total = album.tracks.sumOf { it.durationMs }
                    Text(
                        listOfNotNull(album.year, trackCount(album.tracks.size), total.takeIf { it > 0 }?.let(::formatDuration))
                            .joinToString(" · "),
                        style = HifiType.Caption,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { actions.play(album.tracks.first(), album.tracks) },
                        colors = ButtonDefaults.buttonColors(containerColor = Hifi.Vfd, contentColor = Hifi.Faceplate),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        TransportGlyph(Transport.PLAY, Hifi.Faceplate, 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("PLAY ALBUM", style = HifiType.Engraved.copy(color = Hifi.Faceplate))
                    }
                }
            }
        }
    }
    val multiDisc = album.tracks.map { it.discNumber }.distinct().size > 1
    items(album.tracks, key = { "album-track:" + it.uri }) { track ->
        val number = when {
            track.trackNumber <= 0 -> "·"
            multiDisc && track.discNumber > 0 -> "${track.discNumber}.${track.trackNumber}"
            else -> "${track.trackNumber}"
        }
        TrackRow(track, state, actions, numberLabel = number, onClick = { actions.select(track, album.tracks) })
    }
}

// ---- Sources and permission ----------------------------------------------------------------

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

@Composable
fun SourcesDialog(state: PlayerState, actions: PlayerActions) {
    val close = { state.showSources = false }
    AlertDialog(
        onDismissRequest = close,
        containerColor = Hifi.Panel,
        title = { Text("Library sources", style = HifiType.Title.copy(color = Hifi.Ink)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("All music on this phone", style = HifiType.Body.copy(color = Hifi.Ink))
                        Text("Everything Android has indexed", style = HifiType.Caption)
                    }
                    Switch(
                        checked = state.includeAllMusic,
                        onCheckedChange = actions::setIncludeAllMusic,
                        colors = SwitchDefaults.colors(checkedTrackColor = Hifi.Vfd, checkedThumbColor = Hifi.Faceplate),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide clips under 30 seconds", style = HifiType.Body.copy(color = Hifi.Ink))
                        Text("Voice notes and sounds Android files under music", style = HifiType.Caption)
                    }
                    Switch(
                        checked = state.hideShortClips,
                        onCheckedChange = actions::setHideShortClips,
                        colors = SwitchDefaults.colors(checkedTrackColor = Hifi.Vfd, checkedThumbColor = Hifi.Faceplate),
                    )
                }
                HorizontalDivider(color = Hifi.Hairline, modifier = Modifier.padding(vertical = 12.dp))
                Text("FOLDERS", style = HifiType.Engraved)
                Spacer(Modifier.height(6.dp))
                if (state.folders.isEmpty()) {
                    Text(
                        "Add a folder to play just that music, including files Android doesn't list " +
                            "(DSF, APE, WavPack). Turn off \"All music\" to see only your folders.",
                        style = HifiType.Caption,
                    )
                }
                val folders = remember(state.folders) { state.folders }
                folders.forEach { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            folder.name,
                            style = HifiType.Body.copy(color = Hifi.Ink),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { actions.removeFolder(folder) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove ${folder.name}", tint = Hifi.Label)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Done", color = Hifi.Vfd) } },
        dismissButton = {
            TextButton(onClick = actions::addFolder) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Hifi.Ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add folder", color = Hifi.Ink)
            }
        },
    )
}

private fun trackCount(n: Int) = if (n == 1) "1 track" else "$n tracks"

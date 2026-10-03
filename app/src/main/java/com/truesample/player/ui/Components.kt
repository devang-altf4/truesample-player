package com.truesample.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.truesample.player.DacStatus
import com.truesample.player.PlayerActions
import com.truesample.player.PlayerState
import com.truesample.player.SourceFormat
import com.truesample.player.Track
import com.truesample.player.khz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** An indicator lamp; [glow] adds the halo of a lit bulb. */
@Composable
fun Lamp(color: Color, size: Dp = 9.dp, glow: Boolean = true) {
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

enum class Transport { PLAY, PAUSE, NEXT, PREVIOUS }

/** Transport glyphs drawn directly (the core icon set has no pause or skip icons). */
@Composable
fun TransportGlyph(kind: Transport, color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        when (kind) {
            Transport.PLAY -> drawPath(
                Path().apply { moveTo(w * 0.28f, h * 0.18f); lineTo(w * 0.84f, h * 0.5f); lineTo(w * 0.28f, h * 0.82f); close() },
                color,
            )
            Transport.PAUSE -> {
                drawRoundRect(color, Offset(w * 0.24f, h * 0.2f), Size(w * 0.17f, h * 0.6f), CornerRadius(w * 0.03f))
                drawRoundRect(color, Offset(w * 0.59f, h * 0.2f), Size(w * 0.17f, h * 0.6f), CornerRadius(w * 0.03f))
            }
            Transport.NEXT -> {
                drawPath(Path().apply { moveTo(w * 0.2f, h * 0.22f); lineTo(w * 0.62f, h * 0.5f); lineTo(w * 0.2f, h * 0.78f); close() }, color)
                drawRect(color, Offset(w * 0.66f, h * 0.22f), Size(w * 0.12f, h * 0.56f))
            }
            Transport.PREVIOUS -> {
                drawRect(color, Offset(w * 0.22f, h * 0.22f), Size(w * 0.12f, h * 0.56f))
                drawPath(Path().apply { moveTo(w * 0.8f, h * 0.22f); lineTo(w * 0.38f, h * 0.5f); lineTo(w * 0.8f, h * 0.78f); close() }, color)
            }
        }
    }
}

/** The app's mark, a sampled waveform: used as the placeholder when a song has no cover. */
@Composable
fun SampleGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val base = h * 0.5f
        val samples = listOf(0.12f, 0.31f, 0.18f, -0.17f, -0.33f)
        drawLine(color.copy(alpha = 0.5f), Offset(0f, base), Offset(w, base), strokeWidth = w * 0.02f)
        samples.forEachIndexed { i, v ->
            val x = w * (0.1f + i * 0.2f)
            val y = base - v * h
            drawLine(color, Offset(x, base), Offset(x, y), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
            drawCircle(color, radius = w * 0.045f, center = Offset(x, y))
        }
    }
}

/** A song's cover, loaded in the background; the waveform mark while loading or when there is none. */
@Composable
fun Cover(
    track: Track?,
    actions: PlayerActions,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
    requestPx: Int? = null,
) {
    val px = requestPx ?: with(LocalDensity.current) { size.roundToPx() }
    val image by produceState<ImageBitmap?>(null, track?.albumKey, track?.uri, px) {
        value = track?.let { t -> withContext(Dispatchers.IO) { actions.artwork(t, px)?.asImageBitmap() } }
    }
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Hifi.Panel)
            .border(1.dp, Hifi.Hairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            SampleGlyph(Hifi.Label.copy(alpha = 0.55f), Modifier.fillMaxSize().padding(size * 0.2f))
        }
    }
}

/** "24/48" for lossless files; "MP3 44.1" for lossy ones, where a bit depth means nothing. */
fun sourceLabel(format: SourceFormat, codec: String, separator: String = "/"): String =
    if (format.lossy) "$codec$separator${khz(format.sampleRate)}" else "${format.bitsPerSample}/${khz(format.sampleRate)}"

data class FormatTag(val text: String, val color: Color)

/** A track's format with a lamp colour: green bit-perfect, amber converted, red unplayable. */
@Composable
fun formatTag(track: Track, state: PlayerState, actions: PlayerActions): FormatTag {
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
            plan.bitPerfect && !state.eq.isOn -> Hifi.Vfd
            else -> Hifi.Amber
        },
    )
}

/**
 * A mixing-desk style vertical fader for the graphic EQ. Drag to set, double-tap to centre.
 * [value] runs from -range to +range dB.
 */
@Composable
fun Fader(value: Float, range: Float, label: String, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val latestOnChange by rememberUpdatedState(onChange)
    var dragValue by remember { mutableFloatStateOf(value) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (value == 0f) "0" else String.format(Locale.US, "%+.1f", value).removeSuffix(".0"),
            style = HifiType.DisplaySmall.copy(color = if (value == 0f) Hifi.Label else Hifi.Amber),
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .width(34.dp)
                .height(176.dp)
                .pointerInput(range) {
                    detectTapGestures(onDoubleTap = { latestOnChange(0f) })
                }
                .pointerInput(range) {
                    detectVerticalDragGestures(
                        onDragStart = { dragValue = value },
                    ) { change, dragAmount ->
                        change.consume()
                        val travel = size.height - 24.dp.toPx()
                        dragValue = (dragValue - dragAmount / travel * 2 * range).coerceIn(-range, range)
                        latestOnChange((dragValue * 2).roundToInt() / 2f)  // 0.5 dB steps
                    }
                },
        ) {
            val cx = size.width / 2
            val top = 12.dp.toPx()
            val bottom = size.height - 12.dp.toPx()
            val zeroY = (top + bottom) / 2
            val y = zeroY - value / range * (bottom - top) / 2
            drawLine(Hifi.Hairline, Offset(cx, top), Offset(cx, bottom), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Hifi.Label, Offset(cx - 9.dp.toPx(), zeroY), Offset(cx + 9.dp.toPx(), zeroY), strokeWidth = 1.dp.toPx())
            if (value != 0f) {
                drawLine(Hifi.Amber, Offset(cx, zeroY), Offset(cx, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            }
            val knobW = 26.dp.toPx()
            val knobH = 13.dp.toPx()
            drawRoundRect(Hifi.Ink, Offset(cx - knobW / 2, y - knobH / 2), Size(knobW, knobH), CornerRadius(3.dp.toPx()))
            drawLine(Hifi.Faceplate, Offset(cx - knobW / 2 + 4.dp.toPx(), y), Offset(cx + knobW / 2 - 4.dp.toPx(), y),
                strokeWidth = 1.5.dp.toPx())
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = HifiType.Engraved.copy(letterSpacing = HifiType.Engraved.letterSpacing * 0.5f), maxLines = 1)
    }
}

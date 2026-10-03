package com.truesample.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.truesample.player.EqMode
import com.truesample.player.EqSettings
import com.truesample.player.PlayerActions
import com.truesample.player.PlayerState
import com.truesample.usbaudio.EqBand
import java.util.Locale
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

private const val MIN_HZ = 20.0
private const val MAX_HZ = 20000.0
private const val CURVE_RANGE_DB = 15.0

@Composable
fun EqualizerScreen(state: PlayerState, actions: PlayerActions) {
    BackHandler { state.showEqualizer = false }
    val eq = state.eq
    val set = actions::setEqualizer

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Hifi.Faceplate)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { state.showEqualizer = false }) {
                    Text("‹", style = HifiType.Title.copy(color = Hifi.Ink))
                }
                Text("EQUALIZER", style = HifiType.Brand)
            }
        }
        item { ModeSwitch(eq.mode) { set(eq.copy(mode = it)) } }
        item { ResponseCurve(eq) }
        item {
            val preamp = eq.autoPreampDb
            Text(
                if (eq.isOn) "Preamp ${String.format(Locale.US, "%.1f", preamp)} dB · set automatically so boosts never clip"
                else "Flat: the equalizer isn't changing anything, so playback can stay bit-perfect.",
                style = HifiType.Caption,
                modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
            )
        }
        when (eq.mode) {
            EqMode.OFF -> item {
                Text(
                    "The equalizer is off. Choose Graphic for ten quick bands, or Parametric to place up to " +
                        "${EqSettings.PARAMETRIC_MAX_BANDS} filters exactly where you want them.",
                    style = HifiType.Body.copy(color = Hifi.Label),
                )
            }
            EqMode.GRAPHIC -> {
                item { Presets(eq, set) }
                item { GraphicFaders(eq, set) }
            }
            EqMode.PARAMETRIC -> {
                itemsIndexed(eq.parametric, key = { i, _ -> "band$i" }) { index, band ->
                    BandCard(index, band, onChange = { updated ->
                        set(eq.copy(parametric = eq.parametric.toMutableList().also { it[index] = updated }))
                    }, onRemove = {
                        set(eq.copy(parametric = eq.parametric.filterIndexed { i, _ -> i != index }))
                    })
                }
                if (eq.parametric.size < EqSettings.PARAMETRIC_MAX_BANDS) {
                    item {
                        TextButton(onClick = {
                            set(eq.copy(parametric = eq.parametric + EqBand(EqBand.Type.PEAK, 1000f, 0f, 1f)))
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = Hifi.Ink, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add band", style = HifiType.Body.copy(color = Hifi.Ink))
                        }
                    }
                }
            }
        }
        item {
            Text(
                "While the equalizer is on, playback isn't bit-perfect: the signal path shows an amber EQ stage.",
                style = HifiType.Caption,
                modifier = Modifier.padding(top = 18.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun ModeSwitch(mode: EqMode, onChange: (EqMode) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(shape)
            .border(1.dp, Hifi.Hairline, shape),
    ) {
        EqMode.entries.forEach { m ->
            val selected = m == mode
            Text(
                m.name,
                style = HifiType.Engraved.copy(color = if (selected) Hifi.Faceplate else Hifi.Ink),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .background(if (selected) (if (m == EqMode.OFF) Hifi.Vfd else Hifi.Amber) else Color.Transparent)
                    .clickable { onChange(m) }
                    .padding(vertical = 12.dp),
            )
        }
    }
}

/** The combined frequency response, on a log-frequency grid like a measurement plot. */
@Composable
private fun ResponseCurve(eq: EqSettings) {
    val shape = RoundedCornerShape(12.dp)
    val curveColor = if (eq.isOn) Hifi.Amber else Hifi.Vfd
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(shape)
            .background(Hifi.Panel)
            .border(1.dp, Hifi.Hairline, shape),
    ) {
        val w = size.width
        val h = size.height
        fun x(f: Double) = (log10(f / MIN_HZ) / log10(MAX_HZ / MIN_HZ)).toFloat() * w
        fun y(db: Double) = (h / 2 - db / CURVE_RANGE_DB * (h / 2 - 12.dp.toPx())).toFloat()
        for (db in listOf(-12.0, -6.0, 0.0, 6.0, 12.0)) {
            drawLine(if (db == 0.0) Hifi.Label.copy(alpha = 0.5f) else Hifi.Hairline, Offset(0f, y(db)), Offset(w, y(db)), 1f)
        }
        for (f in listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)) {
            drawLine(Hifi.Hairline, Offset(x(f), 0f), Offset(x(f), h), 1f)
        }
        val path = Path()
        val fill = Path()
        val steps = 160
        for (i in 0..steps) {
            val f = MIN_HZ * (MAX_HZ / MIN_HZ).pow(i / steps.toDouble())
            val px = x(f)
            val py = y(eq.responseDb(f).coerceIn(-CURVE_RANGE_DB, CURVE_RANGE_DB))
            if (i == 0) {
                path.moveTo(px, py)
                fill.moveTo(px, y(0.0))
            }
            path.lineTo(px, py)
            fill.lineTo(px, py)
        }
        fill.lineTo(w, y(0.0))
        fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(curveColor.copy(alpha = 0.22f), Color.Transparent)))
        drawPath(path, curveColor, style = Stroke(width = 2.dp.toPx()))
        if (eq.mode == EqMode.PARAMETRIC) {
            eq.parametric.forEach { b ->
                drawCircle(Hifi.Ink, radius = 4.dp.toPx(), center = Offset(x(b.frequencyHz.toDouble()), y(b.gainDb.toDouble())))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("20", "100", "1k", "10k", "20k").forEach { Text(it, style = HifiType.DisplaySmall.copy(color = Hifi.Label)) }
    }
}

@Composable
private fun Presets(eq: EqSettings, set: (EqSettings) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 10.dp)) {
        EqSettings.PRESETS.forEach { (name, gains) ->
            val active = eq.graphicGains == gains
            val shape = RoundedCornerShape(16.dp)
            Text(
                name,
                style = HifiType.Caption.copy(color = if (active) Hifi.Faceplate else Hifi.Ink, fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(shape)
                    .background(if (active) Hifi.Amber else Color.Transparent)
                    .border(1.dp, if (active) Hifi.Amber else Hifi.Hairline, shape)
                    .clickable { set(eq.copy(graphicGains = gains)) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun GraphicFaders(eq: EqSettings, set: (EqSettings) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            EqSettings.GRAPHIC_FREQUENCIES.forEachIndexed { i, f ->
                Fader(
                    value = eq.graphicGains[i],
                    range = EqSettings.GRAPHIC_RANGE_DB,
                    label = if (f >= 1000) "${(f / 1000).toInt()}k" else "${f.toInt()}",
                    onChange = { v -> set(eq.copy(graphicGains = eq.graphicGains.toMutableList().also { it[i] = v })) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            "Drag a fader to boost or cut that band by up to ${EqSettings.GRAPHIC_RANGE_DB.toInt()} dB. Double-tap to reset it.",
            style = HifiType.Caption,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun BandCard(index: Int, band: EqBand, onChange: (EqBand) -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(shape)
            .background(Hifi.Panel)
            .border(1.dp, Hifi.Hairline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BAND ${index + 1}", style = HifiType.Engraved)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove band ${index + 1}", tint = Hifi.Label)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(EqBand.Type.LOW_SHELF to "LOW SHELF", EqBand.Type.PEAK to "PEAK", EqBand.Type.HIGH_SHELF to "HIGH SHELF")
                .forEach { (type, label) ->
                    val selected = band.type == type
                    val chip = RoundedCornerShape(14.dp)
                    Text(
                        label,
                        style = HifiType.Engraved.copy(color = if (selected) Hifi.Faceplate else Hifi.Ink),
                        modifier = Modifier
                            .clip(chip)
                            .background(if (selected) Hifi.Amber else Color.Transparent)
                            .border(1.dp, if (selected) Hifi.Amber else Hifi.Hairline, chip)
                            .clickable { onChange(band.copy(type = type)) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
        }
        Spacer(Modifier.height(6.dp))
        // Frequency and Q move on log scales, so the useful ranges get most of the slider.
        LabeledSlider(
            label = "FREQ",
            value = (ln(band.frequencyHz / MIN_HZ) / ln(MAX_HZ / MIN_HZ)).toFloat(),
            readout = formatHz(band.frequencyHz),
        ) { p -> onChange(band.copy(frequencyHz = (MIN_HZ * (MAX_HZ / MIN_HZ).pow(p.toDouble())).toFloat().roundHz())) }
        LabeledSlider(
            label = "GAIN",
            value = (band.gainDb + 15f) / 30f,
            readout = String.format(Locale.US, "%+.1f dB", band.gainDb),
        ) { p -> onChange(band.copy(gainDb = ((p * 30f - 15f) * 2).toInt() / 2f)) }
        LabeledSlider(
            label = "Q",
            value = (ln(band.q / 0.3) / ln(8.0 / 0.3)).toFloat(),
            readout = String.format(Locale.US, "%.2f", band.q),
        ) { p -> onChange(band.copy(q = (0.3 * (8.0 / 0.3).pow(p.toDouble())).toFloat())) }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, readout: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = HifiType.Engraved, modifier = Modifier.width(44.dp))
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Hifi.Ink,
                activeTrackColor = Hifi.Amber,
                inactiveTrackColor = Hifi.Hairline,
            ),
        )
        Text(readout, style = HifiType.DisplaySmall.copy(color = Hifi.Ink), modifier = Modifier.width(76.dp))
    }
}

private fun formatHz(hz: Float): String =
    if (hz >= 1000) String.format(Locale.US, "%.1f kHz", hz / 1000).replace(".0 kHz", " kHz") else "${hz.toInt()} Hz"

/** Rounds to values people would type: 63 Hz, 1.25 kHz, 12 kHz. */
private fun Float.roundHz(): Float = when {
    this < 100 -> Math.round(this).toFloat()
    this < 1000 -> Math.round(this / 5) * 5f
    this < 10000 -> Math.round(this / 50) * 50f
    else -> Math.round(this / 100) * 100f
}

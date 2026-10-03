package com.truesample.player

import com.truesample.usbaudio.EqBand
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class EqMode { OFF, GRAPHIC, PARAMETRIC }

/** The user's equalizer: a 10-band graphic EQ and an up-to-8-band parametric EQ; one is active. */
data class EqSettings(
    val mode: EqMode = EqMode.OFF,
    val graphicGains: List<Float> = List(GRAPHIC_FREQUENCIES.size) { 0f },
    val parametric: List<EqBand> = DEFAULT_PARAMETRIC,
) {
    /** The filters that actually run. */
    val bands: List<EqBand>
        get() = when (mode) {
            EqMode.OFF -> emptyList()
            EqMode.GRAPHIC -> GRAPHIC_FREQUENCIES.zip(graphicGains) { f, g -> EqBand(EqBand.Type.PEAK, f, g, GRAPHIC_Q) }
            EqMode.PARAMETRIC -> parametric
        }.filter { it.gainDb != 0f }

    val isOn: Boolean get() = bands.isNotEmpty()

    /** Combined response in dB at [frequency] (excluding the preamp). */
    fun responseDb(frequency: Double, sampleRate: Double = 48000.0): Double =
        bands.sumOf { bandResponseDb(it, frequency, sampleRate) }

    /** Lowers the level by the biggest boost, so the EQ can never push audio into clipping. */
    val autoPreampDb: Float
        get() {
            if (!isOn) return 0f
            var peak = 0.0
            var f = 20.0
            while (f <= 20000.0) {
                peak = max(peak, responseDb(f))
                f *= 1.02
            }
            return -peak.toFloat()
        }

    fun encode(): String = buildString {
        append(mode.name).append('|')
        append(graphicGains.joinToString(","))
        append('|')
        append(parametric.joinToString(";") { "${it.type.name}:${it.frequencyHz}:${it.gainDb}:${it.q}" })
    }

    companion object {
        val GRAPHIC_FREQUENCIES = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        const val GRAPHIC_Q = 1.41f  // one octave wide, like a classic 10-band graphic EQ
        const val GRAPHIC_RANGE_DB = 12f
        const val PARAMETRIC_MAX_BANDS = 8

        val DEFAULT_PARAMETRIC = listOf(
            EqBand(EqBand.Type.LOW_SHELF, 100f, 0f, 0.71f),
            EqBand(EqBand.Type.PEAK, 1000f, 0f, 1f),
            EqBand(EqBand.Type.HIGH_SHELF, 8000f, 0f, 0.71f),
        )

        val PRESETS: List<Pair<String, List<Float>>> = listOf(
            "Flat" to List(10) { 0f },
            "Bass" to listOf(6f, 5f, 3.5f, 1.5f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Treble" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 1.5f, 3f, 4.5f, 5.5f),
            "V-shape" to listOf(5f, 4f, 2f, 0f, -2f, -2f, 0f, 2f, 4f, 5f),
            "Vocal" to listOf(-2f, -1.5f, 0f, 1f, 2.5f, 3.5f, 3f, 1.5f, 0f, -1f),
            "Warm" to listOf(3f, 2.5f, 2f, 1f, 0f, 0f, -0.5f, -1f, -1.5f, -2f),
        )

        fun decode(text: String?): EqSettings {
            if (text.isNullOrEmpty()) return EqSettings()
            return runCatching {
                val (mode, gains, bands) = text.split('|').let { Triple(it[0], it[1], it.getOrElse(2) { "" }) }
                EqSettings(
                    mode = EqMode.valueOf(mode),
                    graphicGains = gains.split(',').map { it.toFloat() }.take(10).let { g -> g + List(10 - g.size) { 0f } },
                    parametric = bands.split(';').filter { it.isNotBlank() }.map { b ->
                        val p = b.split(':')
                        EqBand(EqBand.Type.valueOf(p[0]), p[1].toFloat(), p[2].toFloat(), p[3].toFloat())
                    }.ifEmpty { DEFAULT_PARAMETRIC },
                )
            }.getOrDefault(EqSettings())
        }

        /** RBJ cookbook magnitude response, matching the native filters. */
        fun bandResponseDb(b: EqBand, frequency: Double, fs: Double): Double {
            if (b.gainDb == 0f || b.frequencyHz >= fs * 0.49) return 0.0
            val a = 10.0.pow(b.gainDb / 40.0)
            val w0 = 2 * PI * b.frequencyHz / fs
            val cw = cos(w0)
            val alpha = sin(w0) / (2 * max(b.q.toDouble(), 0.05))
            val sa = 2 * sqrt(a) * alpha
            val c: DoubleArray = when (b.type) {
                EqBand.Type.LOW_SHELF -> doubleArrayOf(
                    a * ((a + 1) - (a - 1) * cw + sa), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sa),
                    (a + 1) + (a - 1) * cw + sa, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sa,
                )
                EqBand.Type.HIGH_SHELF -> doubleArrayOf(
                    a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa),
                    (a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa,
                )
                EqBand.Type.PEAK -> doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
            }
            val w = 2 * PI * frequency / fs
            val nr = c[0] + c[1] * cos(w) + c[2] * cos(2 * w)
            val ni = -(c[1] * sin(w) + c[2] * sin(2 * w))
            val dr = c[3] + c[4] * cos(w) + c[5] * cos(2 * w)
            val di = -(c[4] * sin(w) + c[5] * sin(2 * w))
            return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
        }
    }
}

package com.freeaudiobypasser.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.freeaudiobypasser.app.R

/**
 * A hi-fi front panel: graphite faceplate, smoked display windows, and two indicator
 * lamps with fixed meanings — VFD green for "audio passes untouched", amber for
 * "audio is altered here".
 */
object Hifi {
    val Faceplate = Color(0xFF14171B)
    val Panel = Color(0xFF1D2227)
    val Hairline = Color(0xFF2A3138)
    val Label = Color(0xFF8A949E)
    val Ink = Color(0xFFECE8E1)
    val Vfd = Color(0xFF57E6C1)
    val Amber = Color(0xFFFFB547)
    val Fault = Color(0xFFFF6B5E)
}

/** Wide faceplate lettering, used only for small engraved-style labels. */
val Michroma = FontFamily(Font(R.font.michroma_regular))

val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
)

/** Display readouts: sample rates, bit depths, times, dB. */
val Readout = FontFamily(Font(R.font.share_tech_mono_regular))

object HifiType {
    val Engraved = TextStyle(fontFamily = Michroma, fontSize = 9.sp, letterSpacing = 1.8.sp, color = Hifi.Label)
    val Brand = TextStyle(fontFamily = Michroma, fontSize = 13.sp, letterSpacing = 2.6.sp, color = Hifi.Ink)
    val Title = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 25.sp)
    val Body = TextStyle(fontFamily = Barlow, fontSize = 15.sp, lineHeight = 20.sp)
    val Caption = TextStyle(fontFamily = Barlow, fontSize = 13.sp, lineHeight = 17.sp, color = Hifi.Label)
    val Display = TextStyle(fontFamily = Readout, fontSize = 15.sp)
    val DisplaySmall = TextStyle(fontFamily = Readout, fontSize = 13.sp)
}

@Composable
fun HifiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Hifi.Vfd,
            onPrimary = Hifi.Faceplate,
            secondary = Hifi.Amber,
            background = Hifi.Faceplate,
            onBackground = Hifi.Ink,
            surface = Hifi.Panel,
            onSurface = Hifi.Ink,
            surfaceVariant = Hifi.Panel,
            onSurfaceVariant = Hifi.Label,
            surfaceContainer = Hifi.Panel,
            outline = Hifi.Hairline,
            error = Hifi.Fault,
        ),
        typography = Typography(
            bodyLarge = HifiType.Body,
            bodyMedium = HifiType.Body,
            bodySmall = HifiType.Caption,
            titleLarge = HifiType.Title,
            labelLarge = HifiType.Body.copy(fontWeight = FontWeight.Medium),
        ),
        content = content,
    )
}

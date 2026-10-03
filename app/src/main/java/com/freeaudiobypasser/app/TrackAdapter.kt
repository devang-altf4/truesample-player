package com.freeaudiobypasser.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.freeaudiobypasser.usbaudio.PlaybackPlan
import java.util.Locale

/** A track's quality badge text and colour. */
data class Badge(val text: String, val color: Int)

class TrackAdapter(
    private val library: MusicLibrary,
    /** How the connected DAC would play a format, or null when no DAC is connected. */
    private val planFor: (SourceFormat) -> PlaybackPlan?,
    private val dacConnected: () -> Boolean,
) : BaseAdapter() {

    var tracks: List<Track> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var selected: Track? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getCount() = tracks.size
    override fun getItem(position: Int) = tracks[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.track_row, parent, false)
        val track = tracks[position]
        val format = library.format(track)?.getOrNull()
        view.findViewById<TextView>(R.id.trackTitle).text = track.title
        val duration = track.durationMs.takeIf { it > 0 } ?: format?.durationMs ?: 0
        view.findViewById<TextView>(R.id.trackSubtitle).text =
            listOfNotNull(track.artist, duration.takeIf { it > 0 }?.let(::formatDuration)).joinToString(" · ")
        val badge = badgeFor(track)
        view.findViewById<TextView>(R.id.trackBadge).apply {
            text = badge.text
            setTextColor(badge.color)
        }
        view.setBackgroundColor(if (track == selected) SELECTED_BG else Color.TRANSPARENT)
        return view
    }

    fun badgeFor(track: Track): Badge {
        val result = library.format(track) ?: return Badge("${track.codec} · reading…", GREY)
        val format = result.getOrNull() ?: return Badge("${track.codec} · can't read this file", RED)
        val source = "${track.codec} ${format.bitsPerSample}/${khz(format.sampleRate)}"
        if (!dacConnected()) return Badge(source, GREY)
        val plan = planFor(format) ?: return Badge("$source · this DAC can't play it", RED)
        return when {
            plan.bitPerfect -> Badge("$source · BIT-PERFECT on this DAC", GREEN)
            plan.resampled -> Badge("$source · converted to ${khz(plan.outputRate)} on this DAC", AMBER)
            else -> Badge("$source · ${format.bitsPerSample}→${plan.dacBits}-bit on this DAC", AMBER)
        }
    }

    companion object {
        private val GREY = Color.parseColor("#9AA5B1")
        private val GREEN = Color.parseColor("#4ADE80")
        private val AMBER = Color.parseColor("#FBBF24")
        private val RED = Color.parseColor("#F87171")
        private val SELECTED_BG = Color.parseColor("#1F3A40")

        /** 44100 -> "44.1", 48000 -> "48" (kHz). */
        fun khz(rate: Int): String =
            if (rate % 1000 == 0) "${rate / 1000}" else "%.1f".format(Locale.US, rate / 1000.0)

        fun formatDuration(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
    }
}

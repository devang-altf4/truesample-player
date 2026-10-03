package com.truesample.player

import android.app.Application

/** Holds the one [Player] shared by the screen and the background playback service. */
class TrueSampleApp : Application() {
    val player: Player by lazy { Player(this) }
}

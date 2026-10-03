package com.truesample.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.truesample.player.ui.HifiTheme
import com.truesample.player.ui.PlayerScreen

/** The screen. Playback itself lives in [Player], so it keeps going when this is closed. */
class MainActivity : ComponentActivity(), Player.Host {

    private val player: Player get() = (application as TrueSampleApp).player
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val requestMusicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        player.reloadLibrary(force = true)
    }
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) player.onFilePicked(uri)
    }
    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) player.onFolderPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        player.host = this
        setContent { HifiTheme { PlayerScreen(player.state, player) } }
    }

    override fun onResume() {
        super.onResume()
        player.host = this
        player.reloadLibrary()
    }

    override fun onDestroy() {
        if (player.host === this) player.host = null
        // Closed with Back while nothing plays: give the DAC back to Android right away.
        if (isFinishing) player.releaseDacIfIdle()
        super.onDestroy()
    }

    override fun pickFile() = pickFileLauncher.launch(arrayOf("audio/*"))

    override fun pickFolder() = pickFolderLauncher.launch(null)

    override fun requestMusicPermission() {
        val permission =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        requestMusicPermission.launch(permission)
    }

    /** Asked once, the first time something plays, so the playback notification can show. */
    override fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || prefs.getBoolean("asked_notifications", false)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

package com.truesample.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Keeps playback alive in the background and shows the media notification (previous, play/pause,
 * next, close). Its MediaSession also drives the lock screen, Android's media controls, and the
 * play/pause buttons on headsets and many USB DACs.
 */
class PlaybackService : Service(), Player.Listener {

    private lateinit var player: Player
    private lateinit var session: MediaSession
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        running = true
        player = (application as TrueSampleApp).player
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows what's playing, with play, pause and skip controls"
                setShowBadge(false)
            },
        )
        session = MediaSession(this, "TrueSample").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (!player.state.isPlaying) player.togglePlay()
                }
                override fun onPause() = player.pause()
                override fun onSkipToNext() = player.next()
                override fun onSkipToPrevious() = player.previous()
                override fun onSeekTo(pos: Long) = player.seekTo(pos)
                override fun onStop() = player.stop()
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        player.addListener(this)
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> player.togglePlay()
            ACTION_NEXT -> player.next()
            ACTION_PREVIOUS -> player.previous()
            ACTION_CLOSE -> player.stop()
        }
        return START_NOT_STICKY
    }

    override fun onPlayerChanged() {
        val track = player.state.current
        if (track == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        updateSession()
        notifications.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away from recents: keep playing music, but don't linger when paused.
        if (!player.state.isPlaying) {
            player.stop()
            player.releaseDacIfIdle()
        }
    }

    override fun onDestroy() {
        running = false
        player.removeListener(this)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        updateSession()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun updateSession() {
        val s = player.state
        val track = s.current ?: s.selected ?: return
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist ?: "")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, player.trackDurationMs)
                .apply { s.currentArt?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (s.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    player.positionMs,
                    if (s.isPlaying) 1f else 0f,
                )
                .build(),
        )
    }

    private fun buildNotification(): Notification {
        val s = player.state
        val track = s.current ?: s.selected
        val subtitle = listOfNotNull(track?.artist, track?.album).joinToString(" · ")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(track?.title ?: "TrueSample Player")
            .setContentText(subtitle)
            .setLargeIcon(s.currentArt)
            .setContentIntent(openAppIntent())
            .setDeleteIntent(serviceIntent(ACTION_CLOSE))
            .setOngoing(s.isPlaying)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_previous, "Previous", ACTION_PREVIOUS))
            .addAction(
                if (s.isPlaying) action(R.drawable.ic_pause, "Pause", ACTION_PLAY_PAUSE)
                else action(R.drawable.ic_play, "Play", ACTION_PLAY_PAUSE),
            )
            .addAction(action(R.drawable.ic_next, "Next", ACTION_NEXT))
            .addAction(action(R.drawable.ic_close, "Close", ACTION_CLOSE))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun action(icon: Int, title: String, action: String) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, serviceIntent(action)).build()

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        /** True while the service exists, so the player doesn't re-request it from the background. */
        @Volatile var running = false
            private set

        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PLAY_PAUSE = "com.truesample.player.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.truesample.player.NEXT"
        private const val ACTION_PREVIOUS = "com.truesample.player.PREVIOUS"
        private const val ACTION_CLOSE = "com.truesample.player.CLOSE"
    }
}

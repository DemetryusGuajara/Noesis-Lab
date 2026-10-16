package com.example.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.i18n.AppLocalizer

/**
 * Foreground Media Playback Service with native Android MediaSession & Notification.MediaStyle:
 * Promotes Noesis Lab into the dedicated Android System Media Player panel at the very top
 * of Quick Settings / Notification Shade and Lock Screen (never lost among regular text notifications).
 */
class NoesisAudioService : Service() {

    private var mediaSession: MediaSession? = null
    private var cachedArtwork: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        createNotificationChannel()
        initMediaSession()
    }

    private fun initMediaSession() {
        val session = MediaSession(this, "NoesisLabMediaSession").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        }
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                MysticSoundEngine.activeInstance?.resumePlayback()
                updateForegroundNotification()
            }

            override fun onPause() {
                MysticSoundEngine.activeInstance?.pausePlayback()
                updateForegroundNotification()
            }

            override fun onStop() {
                MysticSoundEngine.activeInstance?.pausePlayback()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        })

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        session.setSessionActivity(sessionActivityPendingIntent)
        session.isActive = true
        mediaSession = session
    }

    private fun getArtworkBitmap(): Bitmap? {
        if (cachedArtwork != null) return cachedArtwork
        cachedArtwork = runCatching {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 2
            }
            BitmapFactory.decodeResource(resources, R.drawable.img_hero_manifold_1791688052417, opts)
        }.getOrNull()
        return cachedArtwork
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeServiceInstance = this
        if (mediaSession == null) {
            initMediaSession()
        }
        when (intent?.action) {
            ACTION_PAUSE -> {
                MysticSoundEngine.activeInstance?.pausePlayback()
                updateForegroundNotification()
            }
            ACTION_RESUME -> {
                MysticSoundEngine.activeInstance?.resumePlayback()
                updateForegroundNotification()
            }
            ACTION_TOGGLE -> {
                MysticSoundEngine.activeInstance?.togglePlaybackFromNotification()
                updateForegroundNotification()
            }
            ACTION_STOP -> {
                MysticSoundEngine.activeInstance?.pausePlayback()
                mediaSession?.isActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                updateForegroundNotification()
            }
        }
        return START_STICKY
    }

    fun updateForegroundNotification() {
        updateMediaSessionState()
        val notification = buildMediaStyleNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun updateMediaSessionState() {
        val session = mediaSession ?: return
        val engine = MysticSoundEngine.activeInstance
        val isPlaying = engine?.isAudioPlaying() ?: true
        val activeSphereNames = engine?.getActiveSphereNames()?.ifBlank { AppLocalizer.active().uiAudioHarmonyFallback }
            ?: AppLocalizer.active().uiAudioHarmonyFallback

        val playbackStateInt = if (isPlaying) {
            PlaybackState.STATE_PLAYING
        } else {
            PlaybackState.STATE_PAUSED
        }

        val playbackState = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP
            )
            .setState(
                playbackStateInt,
                0L,
                if (isPlaying) 1.0f else 0.0f,
                android.os.SystemClock.elapsedRealtime()
            )
            .build()
        session.setPlaybackState(playbackState)

        val metadataBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, AppLocalizer.active().uiAudioNowPlayingTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, activeSphereNames)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, AppLocalizer.active().uiAudioAlbum)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, -1L)

        getArtworkBitmap()?.let { bmp ->
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp)
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, bmp)
        }

        session.setMetadata(metadataBuilder.build())
        session.isActive = true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                AppLocalizer.active().uiAudioChannelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = AppLocalizer.active().uiAudioChannelDesc
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildMediaStyleNotification(): Notification {
        val engine = MysticSoundEngine.activeInstance
        val isPlaying = engine?.isAudioPlaying() ?: true
        val activeSphereNames = engine?.getActiveSphereNames()?.ifBlank { AppLocalizer.active().uiAudioEtherealHarmony }
            ?: AppLocalizer.active().uiAudioEtherealHarmony

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, NoesisAudioService::class.java).apply {
            this.action = ACTION_TOGGLE
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            1,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, NoesisAudioService::class.java).apply {
            this.action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIconRes = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val strings = AppLocalizer.active()
        val playPauseTitle = if (isPlaying) strings.uiAudioPause else strings.uiAudioPlay
        val statusSubtitle = if (isPlaying) activeSphereNames else "${strings.uiAudioPaused} · $activeSphereNames"

        val toggleAction = Notification.Action.Builder(
            Icon.createWithResource(this, playPauseIconRes),
            playPauseTitle,
            togglePendingIntent
        ).build()

        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            strings.uiAudioStop,
            stopPendingIntent
        ).build()

        val mediaStyle = Notification.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setSmallIcon(R.drawable.ic_notification_sound)
            .setContentTitle(strings.uiAudioNowPlayingTitle)
            .setContentText(statusSubtitle)
            .setSubText(strings.uiAudioScale)
            .setContentIntent(contentPendingIntent)
            .setOngoing(isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setShowWhen(false)
            .addAction(toggleAction)
            .addAction(stopAction)
            .setStyle(mediaStyle)

        getArtworkBitmap()?.let { bmp ->
            builder.setLargeIcon(bmp)
        }

        return builder.build()
    }

    override fun onDestroy() {
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null
        activeServiceInstance = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "noesis_media_player_channel"
        const val NOTIFICATION_ID = 4321

        const val ACTION_START = "com.aistudio.noesislab.ACTION_START_AUDIO"
        const val ACTION_TOGGLE = "com.aistudio.noesislab.ACTION_TOGGLE"
        const val ACTION_PAUSE = "com.aistudio.noesislab.ACTION_PAUSE"
        const val ACTION_RESUME = "com.aistudio.noesislab.ACTION_RESUME"
        const val ACTION_STOP = "com.aistudio.noesislab.ACTION_STOP"

        @Volatile
        var activeServiceInstance: NoesisAudioService? = null

        fun startOrUpdateService(context: Context) {
            val intent = Intent(context, NoesisAudioService::class.java).apply {
                action = ACTION_START
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stopAudioService(context: Context) {
            val intent = Intent(context, NoesisAudioService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching {
                context.startService(intent)
            }
        }
    }
}

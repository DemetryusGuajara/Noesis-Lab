package com.example.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R

/**
 * Foreground Service for persistent ambient audio playback:
 * Guarantees background audio continuity with screen locked and renders
 * a prominent Media Player card in the Android notification shade with Play/Pause
 * and Stop controls.
 */
class NoesisAudioService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeServiceInstance = this
        val action = intent?.action
        when (action) {
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
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Ensure foreground notification is actively bound
                updateForegroundNotification()
            }
        }
        return START_STICKY
    }

    fun updateForegroundNotification() {
        val notification = buildMediaNotification()
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
            // Fallback to notify via manager if foreground transition is constrained
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Noesis Lab · Paisagem Sonora",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controle de reprodução da paisagem sonora meditativa e etérea"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildMediaNotification(): Notification {
        val engine = MysticSoundEngine.activeInstance
        val isPlaying = engine?.isAudioPlaying() ?: true
        val activeSphereNames = engine?.getActiveSphereNames()?.ifBlank { "Harmonia Etérea" } ?: "Harmonia Etérea"

        // Open app when tapping the notification card
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Toggle Play/Pause action
        val toggleIntent = Intent(this, NoesisAudioService::class.java).apply {
            this.action = ACTION_TOGGLE
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            1,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action
        val stopIntent = Intent(this, NoesisAudioService::class.java).apply {
            this.action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actionIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val actionTitle = if (isPlaying) "Pausar" else "Tocar"
        val statusSubtitle = if (isPlaying) "Tocando: $activeSphereNames" else "Pausado · Toque para retomar"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sound)
            .setContentTitle("Noesis Lab · Som Ambiente")
            .setContentText(statusSubtitle)
            .setContentIntent(contentPendingIntent)
            .setOngoing(isPlaying)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .addAction(actionIcon, actionTitle, togglePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stopPendingIntent)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("Noesis Lab · Paisagem Sonora")
                    .bigText("$statusSubtitle\nO som etéreo continua tocando em segundo plano enquanto você descansa ou estuda.")
            )

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        activeServiceInstance = null
    }

    companion object {
        const val CHANNEL_ID = "noesis_soundscape_channel"
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

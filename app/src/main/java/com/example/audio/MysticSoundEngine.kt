package com.example.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

data class SphereAcousticNode(
    val id: Long,
    val name: String,
    val importanceMass: Float, // 0.4f .. 2.5f
    val weightHeaviness: Float, // 0.0f (Leve) .. 1.0f (Pesada)
    val posX: Float,
    val posY: Float
) {
    /**
     * Automatic Pitch & Tension formula (Item 5):
     * - High importance + High heaviness = Deep, low, tense pitch (~110Hz - 180Hz)
     * - Low importance + Low heaviness (light) = Bright, relaxed pitch (~380Hz - 540Hz)
     * - High importance + Low heaviness = Medium-warm resonant pitch (~240Hz - 320Hz)
     */
    fun computeBaseFrequencyHz(): Float {
        val normImp = ((importanceMass - 0.4f) / 2.1f).coerceIn(0f, 1f)
        val normWeight = weightHeaviness.coerceIn(0f, 1f)
        val rawHz = 510f - (normImp * 200f) - (normWeight * 165f)
        return rawHz.coerceIn(115f, 528f)
    }
}

class MediaNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            MysticSoundEngine.ACTION_TOGGLE_PLAYBACK -> {
                MysticSoundEngine.activeInstance?.togglePlaybackFromNotification()
            }
            MysticSoundEngine.ACTION_PAUSE_PLAYBACK -> {
                MysticSoundEngine.activeInstance?.pausePlayback()
            }
        }
    }
}

/**
 * Modular Emotional Synthesizer & Media Player Engine:
 * - No Binaural Beats.
 * - No in-app mute button: behaves like a real Android Media Player with AudioFocus
 *   (automatically pauses when another media app plays audio) and a Notification bar Play/Pause control.
 * - Synthesizes real-time sum/difference frequencies (FM/AM coupling) from the proximity
 *   and sliders (Importance + Heaviness) of all active spheres on the canvas.
 */
class MysticSoundEngine(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var musicJob: Job? = null

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var isPlaying: Boolean = true

    @Volatile
    private var sphereNodes: List<SphereAcousticNode> = emptyList()

    @Volatile
    private var bioArousal: Float = 0.45f

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Another app started playing audio -> pause automatically
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Keep playing softly
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Resume if needed
            }
        }
    }

    init {
        activeInstance = this
        setupNotificationChannel()
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Noesis Lab · Paisagem Sonora",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controle de reprodução em segundo plano do sintetizador de estado mental"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun requestAudioFocus(): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }.getOrDefault(true)
    }

    fun updateMediaNotification() {
        runCatching {
            val openAppIntent = Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val contentPendingIntent = PendingIntent.getActivity(
                appContext,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val toggleIntent = Intent(appContext, MediaNotificationReceiver::class.java).apply {
                action = ACTION_TOGGLE_PLAYBACK
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                appContext,
                1,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val activeNames = sphereNodes.take(4).joinToString(" · ") { it.name }.ifBlank { "Harmonia Modular" }
            val actionTitle = if (isPlaying) "Pausar Som" else "Retomar Som"
            val statusSubtitle = if (isPlaying) "Tocando: $activeNames" else "Pausado · Toque para retomar"

            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Noesis Lab · Sintetizador de Estado")
                .setContentText(statusSubtitle)
                .setContentIntent(contentPendingIntent)
                .setOngoing(isPlaying)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    actionTitle,
                    togglePendingIntent
                )
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    fun togglePlaybackFromNotification() {
        if (isPlaying) {
            pausePlayback()
        } else {
            resumePlayback()
        }
    }

    fun pausePlayback() {
        isPlaying = false
        musicJob?.cancel()
        musicJob = null
        updateMediaNotification()
    }

    fun resumePlayback() {
        if (requestAudioFocus()) {
            isPlaying = true
            startAmbientSynthesizerIfNeeded()
            updateMediaNotification()
        }
    }

    fun updateModularFieldState(
        nodes: List<SphereAcousticNode>,
        arousal: Float
    ) {
        sphereNodes = nodes
        bioArousal = arousal.coerceIn(0f, 1f)
        if (isPlaying) {
            startAmbientSynthesizerIfNeeded()
            updateMediaNotification()
        }
    }

    fun startAmbientSynthesizerIfNeeded() {
        if (!isPlaying) return
        if (musicJob?.isActive == true) return
        requestAudioFocus()
        updateMediaNotification()

        musicJob = scope.launch {
            val sampleRate = 24000
            val chunkSamples = sampleRate / 5 // 200ms buffer
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(chunkSamples * 2)

            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: return@launch

            runCatching { track.play() }

            val nodePhases = DoubleArray(12) { 0.0 }
            val couplingPhases = DoubleArray(12) { 0.0 }
            var lfoBreath = 0.0
            var fxEnv = 0.0
            var fxFreq = 432.0
            var fxPhase = 0.0
            var stepCounter = 0

            val pcmBuffer = ShortArray(chunkSamples)

            try {
                while (isActive && isPlaying) {
                    val nodes = sphereNodes.take(8)
                    val count = nodes.size.coerceAtLeast(1)

                    // Calculate each sphere's individual pitch and proximity-coupled sum/difference tones
                    val baseFreqs = DoubleArray(count)
                    val tensionWeights = DoubleArray(count)
                    val coupledSumDiffFreqs = DoubleArray(count)
                    val couplingStrengths = DoubleArray(count)

                    if (nodes.isEmpty()) {
                        baseFreqs[0] = 256.0
                        tensionWeights[0] = 0.3
                        coupledSumDiffFreqs[0] = 384.0
                        couplingStrengths[0] = 0.3
                    } else {
                        for (i in 0 until count) {
                            val n1 = nodes[i]
                            val f1 = n1.computeBaseFrequencyHz().toDouble()
                            baseFreqs[i] = f1
                            tensionWeights[i] = n1.weightHeaviness.toDouble()

                            // Find nearest sphere to compute modular synthesizer sum/subtraction coupling
                            var nearestDist = 10f
                            var nearestFreq = f1 * 1.5
                            var nearestHeaviness = n1.weightHeaviness
                            for (j in 0 until count) {
                                if (i == j) continue
                                val n2 = nodes[j]
                                val d = hypot(n2.posX - n1.posX, n2.posY - n1.posY)
                                if (d < nearestDist) {
                                    nearestDist = d
                                    nearestFreq = n2.computeBaseFrequencyHz().toDouble()
                                    nearestHeaviness = n2.weightHeaviness
                                }
                            }

                            // Proximity factor: 0.0 (far apart) to 1.0 (touching)
                            val proximity = (1.0f - (nearestDist / 0.65f)).coerceIn(0f, 1f).toDouble()
                            couplingStrengths[i] = proximity

                            // Modular Sum/Difference Frequency synthesis:
                            // Light sensation -> harmonic sum average + fifth; Heavy sensation -> sub-harmonic difference tone
                            val meanF = (f1 + nearestFreq) * 0.5
                            val diffF = kotlin.math.abs(f1 - nearestFreq).coerceAtLeast(32.0)
                            val combinedHeaviness = (n1.weightHeaviness + nearestHeaviness) * 0.5f
                            coupledSumDiffFreqs[i] = if (combinedHeaviness > 0.55f) {
                                // Tense/Heavy -> deeper difference modulation
                                (meanF - diffF * 0.35 * proximity).coerceIn(95.0, 640.0)
                            } else {
                                // Light/Relaxed -> luminous harmonic sum
                                (meanF + diffF * 0.45 * proximity).coerceIn(140.0, 780.0)
                            }
                        }
                    }

                    // Gentle procedural chime/water droplet FX
                    val avgHeaviness = tensionWeights.average()
                    if (stepCounter % 6 == 0 && Random.nextFloat() < (0.42f + bioArousal * 0.3f)) {
                        fxEnv = 1.0
                        val pickFreq = baseFreqs[Random.nextInt(count)]
                        fxFreq = if (avgHeaviness < 0.55) pickFreq * 2.0 else pickFreq * 1.1892
                    }
                    stepCounter++

                    val breathRateHz = 0.14 + bioArousal * 0.20
                    val lfoInc = 2.0 * PI * breathRateHz / sampleRate

                    for (s in 0 until chunkSamples) {
                        val breath = 0.68 + 0.32 * sin(lfoBreath)
                        var mixedSignal = 0.0

                        for (i in 0 until count) {
                            val fBase = baseFreqs[i]
                            val fCoupled = coupledSumDiffFreqs[i]
                            val prox = couplingStrengths[i]
                            val heaviness = tensionWeights[i]

                            val incBase = 2.0 * PI * fBase / sampleRate
                            val incCoupled = 2.0 * PI * fCoupled / sampleRate

                            nodePhases[i] = (nodePhases[i] + incBase) % (2.0 * PI)
                            couplingPhases[i] = (couplingPhases[i] + incCoupled) % (2.0 * PI)

                            // Fundamental + gentle warm overtone + proximity coupled wave
                            val overtoneRatio = if (heaviness > 0.6) 1.1892 else 1.4983
                            val nodeWave = (
                                0.55 * sin(nodePhases[i]) +
                                0.20 * sin(nodePhases[i] * overtoneRatio) +
                                (0.25 * prox) * sin(couplingPhases[i])
                            )
                            mixedSignal += nodeWave / count.toDouble()
                        }

                        var sampleVal = mixedSignal * breath

                        if (fxEnv > 0.002) {
                            val fxInc = 2.0 * PI * fxFreq / sampleRate
                            sampleVal += 0.20 * sin(fxPhase) * fxEnv
                            fxPhase = (fxPhase + fxInc) % (2.0 * PI)
                            fxEnv *= 0.9995
                        }

                        pcmBuffer[s] = (sampleVal * 0.16 * Short.MAX_VALUE).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()

                        lfoBreath = (lfoBreath + lfoInc) % (2.0 * PI)
                    }

                    track.write(pcmBuffer, 0, chunkSamples)
                }
            } catch (_: Throwable) {
                // Ignore audio hardware interruption in test environments
            } finally {
                runCatching {
                    track.stop()
                    track.release()
                }
            }
        }
    }

    fun triggerSphereTouchChord(importanceMass: Float, weightHeaviness: Float, durationMs: Int = 360) {
        if (!isPlaying) return
        scope.launch {
            runCatching {
                val sampleRate = 24000
                val numSamples = (sampleRate * (durationMs / 1000.0)).toInt().coerceAtLeast(1000)
                val pcm = ShortArray(numSamples)

                val normImp = ((importanceMass - 0.4f) / 2.1f).coerceIn(0f, 1f)
                val normWeight = weightHeaviness.coerceIn(0f, 1f)
                val baseFreq = (510f - (normImp * 200f) - (normWeight * 165f)).coerceIn(115f, 528f).toDouble()

                // Light sensation -> Major 3rd + Perfect 5th; Heavy sensation -> Minor 3rd + Fifth
                val thirdRatio = if (normWeight <= 0.5f) 1.25 else 1.1892
                val fifthRatio = 1.4983

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val attack = (i / (sampleRate * 0.010)).coerceAtMost(1.0)
                    val decay = exp(-5.5 * t)
                    val env = attack * decay

                    val signal = (
                        0.52 * sin(2.0 * PI * baseFreq * t) +
                        0.30 * sin(2.0 * PI * (baseFreq * thirdRatio) * t) +
                        0.18 * sin(2.0 * PI * (baseFreq * fifthRatio) * t)
                    ) * env * 0.28

                    pcm[i] = (signal * Short.MAX_VALUE).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(pcm, 0, pcm.size)
                track.play()
                delay(durationMs.toLong() + 60L)
                track.stop()
                track.release()
            }
        }
    }

    fun releaseEngine() {
        notificationManager.cancel(NOTIFICATION_ID)
        musicJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    companion object {
        const val CHANNEL_ID = "noesis_soundscape_channel"
        const val NOTIFICATION_ID = 4321
        const val ACTION_TOGGLE_PLAYBACK = "com.aistudio.noesislab.ACTION_TOGGLE_PLAYBACK"
        const val ACTION_PAUSE_PLAYBACK = "com.aistudio.noesislab.ACTION_PAUSE_PLAYBACK"

        @Volatile
        var activeInstance: MysticSoundEngine? = null
    }
}

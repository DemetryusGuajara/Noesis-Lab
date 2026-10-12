package com.example.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
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
     * Maps each sphere strictly to the Celestial Major Pentatonic Scale (A = 216 Hz fundamental).
     * Every single interval is consonant, sweet, and uplifting.
     * Absolutely ZERO minor seconds, half-step clashes, or atonal horror tones.
     */
    fun computeHarmonicFrequencyHz(): Double {
        // Pure Major Pentatonic scale ratios based on 216 Hz root:
        // Root (1.0 = 216 Hz), Major 2nd (9/8 = 243 Hz), Major 3rd (5/4 = 270 Hz),
        // Perfect 5th (3/2 = 324 Hz), Major 6th (5/3 = 360 Hz), Octave (2.0 = 432 Hz),
        // Octave+2nd (9/4 = 486 Hz), Octave+5th (3.0 = 648 Hz)
        val pentatonicRatios = doubleArrayOf(
            1.0,     // 216 Hz (Root: deep, peaceful, grounded)
            1.125,   // 243 Hz (Major 2nd: airy, suspended, floating)
            1.25,    // 270 Hz (Major 3rd: sweet, warm, luminous)
            1.5,     // 324 Hz (Perfect 5th: infinite, serene, open)
            1.6667,  // 360 Hz (Major 6th: pastoral, tender, gentle)
            2.0,     // 432 Hz (Octave: crystal clear, radiant)
            2.25,    // 486 Hz (Octave + 2nd: shimmering overtone)
            3.0      // 648 Hz (Octave + 5th: celestial bell resonance)
        )

        val lower = name.lowercase()
        val index = when {
            lower.contains("medo") || lower.contains("cautela") -> 3      // Perfect 5th (grounding stability)
            lower.contains("desejo") || lower.contains("paixão") -> 2     // Major 3rd (warmth, yearning)
            lower.contains("tempo") || lower.contains("saudade") -> 1     // Major 2nd (floating, suspended)
            lower.contains("identidade") || lower.contains("eu") -> 0     // Root (foundational centering)
            lower.contains("silêncio") || lower.contains("calma") -> 5    // Octave 432 Hz (pure stillness)
            lower.contains("criatividade") || lower.contains("arte") -> 4 // Major 6th (pastoral expansiveness)
            lower.contains("foco") || lower.contains("força") -> 0        // Root 216 Hz (concentrated clarity)
            lower.contains("justiça") || lower.contains("ordem") -> 3     // Perfect 5th (balance)
            else -> abs(name.hashCode()) % pentatonicRatios.size
        }
        val baseF = 216.0 * pentatonicRatios[index]

        // When importance is high, maintain rich warm register; when light, float up one octave
        return if (importanceMass < 0.85f) baseF * 2.0 else baseF
    }

    fun computeBaseFrequencyHz(): Double = computeHarmonicFrequencyHz()
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
            MysticSoundEngine.ACTION_STOP_PLAYBACK -> {
                MysticSoundEngine.activeInstance?.stopPlayback()
            }
        }
    }
}

/**
 * Ethereal, Meditative & Consonant Ambient Synthesizer:
 * - Master-tuned to the 432Hz / 216Hz celestial pentatonic harmony.
 * - Gentle Eno-esque generative swells: notes breathe in and out at independent slow rhythms.
 * - Absolutely zero dissonance, zero horror clusters, zero funereal organ tones.
 * - Pure consonant pads, soft warm drone bed, and crystal singing bowl chimes.
 * - Synchronized with NoesisAudioService for seamless background play & notification control.
 */
class MysticSoundEngine(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var musicJob: Job? = null

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Background ambient gently ducks
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Automatically ready
            }
        }
    }

    init {
        activeInstance = this
    }

    fun isAudioPlaying(): Boolean = isPlaying

    fun getActiveSphereNames(): String {
        return sphereNodes.take(4).joinToString(" · ") { it.name }
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
        NoesisAudioService.startOrUpdateService(appContext)
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

    fun stopPlayback() {
        isPlaying = false
        musicJob?.cancel()
        musicJob = null
        NoesisAudioService.stopAudioService(appContext)
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
            val chunkSamples = sampleRate / 5 // 200ms buffer chunk
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

            // Individual phase accumulators for up to 8 voices
            val voicePhases = DoubleArray(8) { 0.0 }
            val voiceOvertonePhases = DoubleArray(8) { 0.0 }
            val voiceLfoPhases = DoubleArray(8) { it * 1.618 } // Golden ratio phase distribution

            // Base ground drone phases
            var subBassPhase = 0.0
            var rootDronePhase = 0.0
            var masterBreathPhase = 0.0

            // Tibetan bell chime
            var chimeEnv = 0.0
            var chimeFreq = 432.0
            var chimePhase = 0.0
            var stepCounter = 0

            val pcmBuffer = ShortArray(chunkSamples)

            try {
                while (isActive && isPlaying) {
                    val currentNodes = sphereNodes.take(6)
                    val activeCount = currentNodes.size.coerceAtLeast(1)

                    val freqs = DoubleArray(activeCount)
                    val proximityGains = DoubleArray(activeCount)
                    val heavinessWeights = DoubleArray(activeCount)

                    if (currentNodes.isEmpty()) {
                        freqs[0] = 216.0
                        proximityGains[0] = 0.0
                        heavinessWeights[0] = 0.35
                    } else {
                        for (i in 0 until activeCount) {
                            val n1 = currentNodes[i]
                            freqs[i] = n1.computeHarmonicFrequencyHz()
                            heavinessWeights[i] = n1.weightHeaviness.toDouble().coerceIn(0.0, 1.0)

                            // Proximity between spheres adds lush consonant overtone resonance, never detuning
                            var minDist = 10f
                            for (j in 0 until activeCount) {
                                if (i == j) continue
                                val n2 = currentNodes[j]
                                val d = hypot(n2.posX - n1.posX, n2.posY - n1.posY)
                                if (d < minDist) minDist = d
                            }
                            // Close proximity (under 0.5 screen units) creates a gentle warm acoustic bloom
                            proximityGains[i] = (1.0f - (minDist / 0.55f)).coerceIn(0f, 1f).toDouble()
                        }
                    }

                    // Gentle occasional Tibetan crystal singing bowl ping (every ~12-16 seconds)
                    val chimeScale = doubleArrayOf(432.0, 648.0, 864.0, 648.0)
                    if (stepCounter % 14 == 0 && Random.nextFloat() < (0.28f + bioArousal * 0.15f)) {
                        chimeEnv = 1.0
                        chimeFreq = chimeScale[Random.nextInt(chimeScale.size)]
                    }
                    stepCounter++

                    // Master respiratory cycle (~12 seconds per breath)
                    val masterBreathRateHz = 0.08 + bioArousal * 0.03
                    val masterBreathInc = 2.0 * PI * masterBreathRateHz / sampleRate

                    // Deep grounding drone frequencies: 108 Hz (warm sub) + 216 Hz (peaceful root)
                    val incSub = 2.0 * PI * 108.0 / sampleRate
                    val incRoot = 2.0 * PI * 216.0 / sampleRate

                    for (s in 0 until chunkSamples) {
                        // Master respiratory breathing swell: smooth oscillation between 0.70 and 1.0
                        val masterBreath = 0.75 + 0.25 * sin(masterBreathPhase)

                        // Foundation drone: warm, velvety, non-fatiguing
                        subBassPhase = (subBassPhase + incSub) % (2.0 * PI)
                        rootDronePhase = (rootDronePhase + incRoot) % (2.0 * PI)
                        val warmDrone = (0.08 * sin(subBassPhase) + 0.07 * sin(rootDronePhase)) * masterBreath

                        var padVoicesSum = 0.0

                        for (i in 0 until activeCount) {
                            val f = freqs[i]
                            val heaviness = heavinessWeights[i]
                            val bloom = proximityGains[i]

                            // Voice-specific breathing cycle: notes swell and fade gently at independent rates
                            val voiceBreathPeriodSeconds = 12.0 + (i * 3.7) % 9.0
                            val voiceBreathInc = 2.0 * PI / (voiceBreathPeriodSeconds * sampleRate)
                            voiceLfoPhases[i] = (voiceLfoPhases[i] + voiceBreathInc) % (2.0 * PI)

                            // Smooth raised-cosine swell envelope (0.0 .. 1.0)
                            val rawLfo = 0.5 * (1.0 - cos(voiceLfoPhases[i]))
                            val voiceEnv = rawLfo * rawLfo // Smooth easing so notes stay quiet most of the cycle

                            // Phase advance
                            val incMain = 2.0 * PI * f / sampleRate
                            val incOctave = 2.0 * PI * (f * 2.0) / sampleRate

                            voicePhases[i] = (voicePhases[i] + incMain) % (2.0 * PI)
                            voiceOvertonePhases[i] = (voiceOvertonePhases[i] + incOctave) % (2.0 * PI)

                            // Pure, rounded sinusoidal tone with soft octave overtone
                            val mainTone = sin(voicePhases[i])
                            val octaveTone = sin(voiceOvertonePhases[i]) * (0.08 + (1.0 - heaviness) * 0.06)

                            // Heaviness adds warm cello body; Lightness adds glass sparkle
                            // Proximity bloom swells consonant harmony, not abrasive dissonance
                            val resonanceFactor = 1.0 + bloom * 0.40
                            val voiceSample = (mainTone + octaveTone) * voiceEnv * resonanceFactor

                            padVoicesSum += voiceSample
                        }

                        // Average pad voices so total volume stays controlled and never clips
                        val normalizedPad = (padVoicesSum / activeCount.toDouble()) * 0.45

                        var sampleVal = warmDrone + normalizedPad

                        // Tibetan singing bowl chime with slow exponential decay
                        if (chimeEnv > 0.0005) {
                            val incChime = 2.0 * PI * chimeFreq / sampleRate
                            chimePhase = (chimePhase + incChime) % (2.0 * PI)
                            sampleVal += 0.09 * sin(chimePhase) * chimeEnv
                            chimeEnv *= 0.99972 // Silky smooth exponential decay
                        }

                        // Final soft-clip limiter and conversion to 16-bit PCM
                        val outputAmplitude = (sampleVal * 0.28 * Short.MAX_VALUE).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        pcmBuffer[s] = outputAmplitude.toShort()

                        masterBreathPhase = (masterBreathPhase + masterBreathInc) % (2.0 * PI)
                    }

                    track.write(pcmBuffer, 0, chunkSamples)
                }
            } catch (_: Throwable) {
                // Silently handle audio track interruptions
            } finally {
                runCatching {
                    track.stop()
                    track.release()
                }
            }
        }
    }

    /**
     * Sweet, consonant touch response:
     * Plays a celestial Major chord chime (Root + Major 3rd + Perfect 5th) with gentle decay.
     */
    fun triggerSphereTouchChord(importanceMass: Float, weightHeaviness: Float, durationMs: Int = 420) {
        if (!isPlaying) return
        scope.launch {
            runCatching {
                val sampleRate = 24000
                val numSamples = (sampleRate * (durationMs / 1000.0)).toInt().coerceAtLeast(1000)
                val pcm = ShortArray(numSamples)

                val baseFreq = 216.0 * (if (importanceMass < 0.9f) 2.0 else 1.0)
                val thirdFreq = baseFreq * 1.25 // Major 3rd
                val fifthFreq = baseFreq * 1.50 // Perfect 5th

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val attack = (i / (sampleRate * 0.02)).coerceAtMost(1.0)
                    val decay = exp(-4.2 * t)
                    val env = attack * decay

                    val signal = (
                        0.50 * sin(2.0 * PI * baseFreq * t) +
                        0.30 * sin(2.0 * PI * thirdFreq * t) +
                        0.20 * sin(2.0 * PI * fifthFreq * t)
                    ) * env * 0.24

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
                delay(durationMs.toLong() + 80L)
                track.stop()
                track.release()
            }
        }
    }

    fun releaseEngine() {
        musicJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    companion object {
        const val ACTION_TOGGLE_PLAYBACK = "com.aistudio.noesislab.ACTION_TOGGLE_PLAYBACK"
        const val ACTION_PAUSE_PLAYBACK = "com.aistudio.noesislab.ACTION_PAUSE_PLAYBACK"
        const val ACTION_STOP_PLAYBACK = "com.aistudio.noesislab.ACTION_STOP_PLAYBACK"

        @Volatile
        var activeInstance: MysticSoundEngine? = null
    }
}

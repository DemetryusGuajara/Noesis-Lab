package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generative Harmonic & Synesthetic Soundscape Engine:
 * - Creates an evolving musical chord progression + atmospheric sound effects (water droplets,
 *   warm wind shimmer, crystalline bells, or deep tension pulse) driven directly by the emotions
 *   and concepts on the user's canvas.
 * - Keeps Binaural Beats as an OPTIONAL, toggleable subtle layer rather than the main focus.
 */
class MysticSoundEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var musicJob: Job? = null

    @Volatile
    private var isSoundEnabled: Boolean = true

    @Volatile
    private var isBinauralEnabled: Boolean = false

    @Volatile
    private var targetRootHz: Float = 220f

    @Volatile
    private var targetValence: Float = 0.65f // 0 = tense/minor, 1 = calm/warm major-lydian

    @Volatile
    private var targetArousal: Float = 0.40f // 0 = slow water/ambient pad, 1 = rhythmic/intense

    @Volatile
    private var targetCoherence: Float = 0.80f

    fun setSoundEnabled(enabled: Boolean) {
        isSoundEnabled = enabled
        if (!enabled) {
            stopAmbientDrone()
        } else {
            startAmbientDroneIfNeeded()
        }
    }

    fun setBinauralEnabled(enabled: Boolean) {
        isBinauralEnabled = enabled
    }

    fun updateEmotionalFieldAcoustics(
        meanFrequencyHz: Float,
        arousal: Float,
        valence: Float,
        coherence: Float
    ) {
        // Map mean frequency to a musical root note between 146Hz (D3) and 293Hz (D4)
        targetRootHz = (meanFrequencyHz * 0.45f).coerceIn(130f, 310f)
        targetArousal = arousal.coerceIn(0f, 1f)
        targetValence = valence.coerceIn(0f, 1f)
        targetCoherence = coherence.coerceIn(0f, 1f)
    }

    fun startAmbientDroneIfNeeded() {
        if (!isSoundEnabled) return
        if (musicJob?.isActive == true) return

        musicJob = scope.launch {
            val sampleRate = 24000
            val chunkSamples = sampleRate / 5 // 200ms audio frames
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

            var pRoot = 0.0
            var pThird = 0.0
            var pFifth = 0.0
            var pSeventh = 0.0
            var pBinaural = 0.0
            var pArp = 0.0
            var lfoBreath = 0.0
            var stepCounter = 0

            // Procedural FX droplet / chime state
            var fxEnv = 0.0
            var fxFreq = 528.0
            var fxPhase = 0.0

            val pcmBuffer = ShortArray(chunkSamples)

            try {
                while (isActive && isSoundEnabled) {
                    val root = targetRootHz.toDouble()
                    val valence = targetValence.toDouble()
                    val arousal = targetArousal.toDouble()

                    // Musical Chord Intervals based on emotional valence:
                    // High valence -> Major 9th / Lydian warm ratios (1.0, 1.25, 1.5, 1.875)
                    // Mid valence -> Suspended / Dreamy ratios (1.0, 1.333, 1.5, 1.778)
                    // Low valence -> Minor / Tense ratios (1.0, 1.189, 1.498, 1.782)
                    val thirdRatio = when {
                        valence > 0.62 -> 1.2500 // Major 3rd
                        valence > 0.38 -> 1.3333 // Suspended 4th
                        else -> 1.1892           // Minor 3rd
                    }
                    val fifthRatio = if (valence < 0.25) 1.4142 else 1.4983 // Diminished vs Perfect 5th
                    val seventhRatio = if (valence > 0.55) 1.8750 else 1.7818 // Major 7th vs Minor 7th

                    // Gentle melodic arpeggio note that shifts every few chunks
                    val arpMultipliers = doubleArrayOf(1.0, thirdRatio, fifthRatio, seventhRatio, 2.0)
                    val activeArpMult = arpMultipliers[(stepCounter / 4) % arpMultipliers.size]
                    val fArp = root * activeArpMult * 2.0

                    // Randomly trigger nature/synesthetic FX (water droplet in calm mode, warm shimmer in vibrant mode)
                    if (stepCounter % 6 == 0 && Random.nextFloat() < (0.45f + arousal * 0.35f)) {
                        fxEnv = 1.0
                        fxFreq = if (valence >= 0.45) {
                            // Calm crystalline water droplet / harp harmonic
                            root * arpMultipliers[Random.nextInt(arpMultipliers.size)] * 3.0
                        } else {
                            // Deep resonant pulse FX for tense states
                            root * 1.1892 * 1.5
                        }
                    }
                    stepCounter++

                    val incRoot = 2.0 * PI * root / sampleRate
                    val incThird = 2.0 * PI * (root * thirdRatio) / sampleRate
                    val incFifth = 2.0 * PI * (root * fifthRatio) / sampleRate
                    val incSeventh = 2.0 * PI * (root * seventhRatio) / sampleRate
                    val incArp = 2.0 * PI * fArp / sampleRate

                    // Optional Binaural offset (only active when user toggles it on)
                    val binauralOffsetHz = 5.5 + arousal * 7.0
                    val incBinaural = 2.0 * PI * (root + binauralOffsetHz) / sampleRate

                    val breathRateHz = 0.14 + arousal * 0.25
                    val lfoInc = 2.0 * PI * breathRateHz / sampleRate

                    for (i in 0 until chunkSamples) {
                        val breath = 0.65 + 0.35 * sin(lfoBreath)

                        // Warm generative chord pad
                        var signal = (
                            0.34 * sin(pRoot) +
                            0.24 * sin(pThird) +
                            0.22 * sin(pFifth) +
                            0.12 * sin(pSeventh) +
                            0.08 * sin(pArp)
                        ) * breath

                        // Add optional Binaural Beat layer only if enabled by the user
                        if (isBinauralEnabled) {
                            signal += 0.18 * sin(pBinaural)
                        }

                        // Add procedural Sound FX (droplet / chime / pulse)
                        if (fxEnv > 0.002) {
                            val fxInc = 2.0 * PI * fxFreq / sampleRate
                            signal += 0.22 * sin(fxPhase) * fxEnv
                            fxPhase = (fxPhase + fxInc) % (2.0 * PI)
                            fxEnv *= if (valence >= 0.45) 0.9994 else 0.9996
                        }

                        val finalSample = (signal * 0.13 * Short.MAX_VALUE).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()

                        pcmBuffer[i] = finalSample

                        pRoot = (pRoot + incRoot) % (2.0 * PI)
                        pThird = (pThird + incThird) % (2.0 * PI)
                        pFifth = (pFifth + incFifth) % (2.0 * PI)
                        pSeventh = (pSeventh + incSeventh) % (2.0 * PI)
                        pArp = (pArp + incArp) % (2.0 * PI)
                        pBinaural = (pBinaural + incBinaural) % (2.0 * PI)
                        lfoBreath = (lfoBreath + lfoInc) % (2.0 * PI)
                    }

                    track.write(pcmBuffer, 0, chunkSamples)
                }
            } catch (_: Throwable) {
                // Ignore audio interruption in test environments
            } finally {
                runCatching {
                    track.stop()
                    track.release()
                }
            }
        }
    }

    fun stopAmbientDrone() {
        musicJob?.cancel()
        musicJob = null
    }

    /**
     * Plays a pleasant harmonic chord pluck / sound effect when touching or colliding spheres.
     */
    fun triggerTouchHarmonic(frequencyHz: Float, valence: Float = 0.5f, durationMs: Int = 380) {
        if (!isSoundEnabled) return
        scope.launch {
            runCatching {
                val sampleRate = 24000
                val numSamples = (sampleRate * (durationMs / 1000.0)).toInt().coerceAtLeast(1000)
                val pcm = ShortArray(numSamples)

                val baseFreq = frequencyHz.coerceIn(130f, 960f).toDouble()
                val thirdRatio = if (valence >= 0f) 1.25 else 1.1892
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
                    ) * env * 0.30

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
}

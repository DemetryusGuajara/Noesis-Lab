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

/**
 * Synthesizes responsive touch harmonics, particle collision chimes, and an adaptive
 * ambient drone whose base frequency and binaural beat offset shift with the user's
 * emotional/cognitive biofeedback state and active concept particles.
 */
class MysticSoundEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var droneJob: Job? = null

    @Volatile
    private var isSoundEnabled: Boolean = true

    @Volatile
    private var targetBaseFreqHz: Float = 216f // Half of 432Hz mystical root

    @Volatile
    private var targetBinauralBeatHz: Float = 6.5f // Theta/Alpha border

    @Volatile
    private var targetHarmonicRichness: Float = 0.35f

    fun setSoundEnabled(enabled: Boolean) {
        isSoundEnabled = enabled
        if (!enabled) {
            stopAmbientDrone()
        } else {
            startAmbientDroneIfNeeded()
        }
    }

    fun updateEmotionalFieldAcoustics(
        meanFrequencyHz: Float,
        arousal: Float,
        valence: Float,
        coherence: Float
    ) {
        // Map mean particle frequency down to a warm sub-octave drone (110Hz .. 280Hz)
        targetBaseFreqHz = (meanFrequencyHz * 0.5f).coerceIn(108f, 288f)
        // Arousal controls brainwave entrainment beat: Low arousal -> Theta (4.5Hz), High -> Beta/Gamma (14Hz)
        targetBinauralBeatHz = (4.0f + arousal * 11.0f).coerceIn(3.5f, 16.0f)
        // Valence & coherence control warm fifth harmonic vs tense minor second overtones
        targetHarmonicRichness = ((1f - valence) * 0.5f + (1f - coherence) * 0.3f).coerceIn(0.15f, 0.85f)
    }

    fun startAmbientDroneIfNeeded() {
        if (!isSoundEnabled) return
        if (droneJob?.isActive == true) return

        droneJob = scope.launch {
            val sampleRate = 24000
            val bufferSamples = sampleRate / 5 // 200ms chunks
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(bufferSamples * 2)

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

            var phase1 = 0.0
            var phase2 = 0.0
            var phase3 = 0.0
            var lfoPhase = 0.0
            val pcmBuffer = ShortArray(bufferSamples)

            try {
                while (isActive && isSoundEnabled) {
                    val f1 = targetBaseFreqHz.toDouble()
                    val f2 = f1 + targetBinauralBeatHz.toDouble()
                    val f3 = f1 * 1.498 // Just-intonation fifth
                    val richness = targetHarmonicRichness.toDouble()

                    val inc1 = 2.0 * PI * f1 / sampleRate
                    val inc2 = 2.0 * PI * f2 / sampleRate
                    val inc3 = 2.0 * PI * f3 / sampleRate
                    val lfoInc = 2.0 * PI * 0.18 / sampleRate // Slow breathing wave

                    for (i in 0 until bufferSamples) {
                        val breath = 0.65 + 0.35 * sin(lfoPhase)
                        val wave = (
                            0.45 * sin(phase1) +
                            0.35 * sin(phase2) +
                            richness * 0.20 * sin(phase3)
                        ) * breath * 0.14 // Gentle, non-intrusive volume

                        pcmBuffer[i] = (wave * Short.MAX_VALUE).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()

                        phase1 = (phase1 + inc1) % (2.0 * PI)
                        phase2 = (phase2 + inc2) % (2.0 * PI)
                        phase3 = (phase3 + inc3) % (2.0 * PI)
                        lfoPhase = (lfoPhase + lfoInc) % (2.0 * PI)
                    }

                    track.write(pcmBuffer, 0, bufferSamples)
                }
            } catch (_: Throwable) {
                // Ignore audio hardware interruption in headless test environments
            } finally {
                runCatching {
                    track.stop()
                    track.release()
                }
            }
        }
    }

    fun stopAmbientDrone() {
        droneJob?.cancel()
        droneJob = null
    }

    /**
     * Plays a crystalline harmonic bell / singing bowl resonance when the user touches
     * or collides a concept particle or architectural chamber.
     */
    fun triggerTouchHarmonic(frequencyHz: Float, valence: Float = 0.5f, durationMs: Int = 420) {
        if (!isSoundEnabled) return
        scope.launch {
            runCatching {
                val sampleRate = 24000
                val numSamples = (sampleRate * (durationMs / 1000.0)).toInt().coerceAtLeast(1000)
                val pcm = ShortArray(numSamples)

                val baseFreq = frequencyHz.coerceIn(110f, 1046f).toDouble()
                // Positive valence -> Major/Lydian overtone (1.5x), Negative valence -> Phrygian/Mystic minor (1.189x)
                val overtoneRatio = if (valence >= 0f) 1.498 else 1.1892
                val shimmerRatio = 2.0

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    // Fast attack, exponential singing-bowl decay
                    val attack = (i / (sampleRate * 0.012)).coerceAtMost(1.0)
                    val decay = exp(-4.8 * t)
                    val env = attack * decay

                    val signal = (
                        0.58 * sin(2.0 * PI * baseFreq * t) +
                        0.28 * sin(2.0 * PI * (baseFreq * overtoneRatio) * t) +
                        0.14 * sin(2.0 * PI * (baseFreq * shimmerRatio) * t)
                    ) * env * 0.32

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
}

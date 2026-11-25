package com.windstrom5.rebit.domain.audiophile

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

enum class SignalMode(val label: String, val blurb: String) {
    TONE("Tone", "A pure test tone. Use it to find resonances, check bass extension or verify a driver."),
    SWEEP("Sweep", "A slow 20 Hz → 20 kHz glide. Listen for rattles, dips, buzzes and where your hearing or system rolls off."),
    PINK_NOISE("Pink noise", "Equal energy per octave. The classic signal for level matching and room checks."),
    CHANNEL_CHECK("L / R check", "Short 1 kHz bursts: left, then right. Confirms channels aren't swapped or one side dead."),
    POLARITY("Polarity", "A 100 Hz tone that flips between in-phase and out-of-phase. In-phase should sound fuller and more centred.")
}

enum class Waveform(val label: String) { SINE("Sine"), SQUARE("Square"), TRIANGLE("Triangle") }
enum class ChannelSel(val label: String) { BOTH("Both"), LEFT("Left"), RIGHT("Right") }

data class SignalConfig(
    val mode: SignalMode = SignalMode.TONE,
    val freqHz: Double = 1000.0,
    val waveform: Waveform = Waveform.SINE,
    val channels: ChannelSel = ChannelSel.BOTH,
    val levelDb: Double = DEFAULT_LEVEL_DB
) {
    companion object {
        const val MIN_LEVEL_DB = -60.0
        /** Deliberately capped well below full scale: test tones at 0 dBFS can hurt ears and drivers. */
        const val MAX_LEVEL_DB = -6.0
        const val DEFAULT_LEVEL_DB = -36.0
        const val MIN_FREQ = 20.0
        const val MAX_FREQ = 20_000.0
    }
}

/**
 * Streaming test-signal source: call [fill] repeatedly; phase is continuous across calls, and
 * level changes / start / stop are smoothed (~20 ms) so there are no clicks or pops.
 * Pure Kotlin so it is unit-tested on the JVM; the Android side only feeds the samples to an AudioTrack.
 */
class TestSignalGenerator(val sampleRate: Int = 48_000, seed: Int = 7) {
    private val rng = Random(seed)
    private var phase = 0.0
    private var sweepPhase = 0.0
    private var t = 0L
    private var gain = 0.0
    private val smooth = 1.0 - exp(-1.0 / (0.02 * sampleRate))

    // Paul Kellet's refined pink-noise filter state
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
    private var b3 = 0.0; private var b4 = 0.0; private var b5 = 0.0; private var b6 = 0.0

    companion object {
        const val SWEEP_SECONDS = 20.0
        fun dbToAmp(db: Double) = 10.0.pow(db / 20.0)
    }

    /** Fills [out] with [frames] interleaved stereo samples (L, R, L, R …). */
    fun fill(out: ShortArray, frames: Int, cfg: SignalConfig, running: Boolean) {
        val level = cfg.levelDb.coerceIn(SignalConfig.MIN_LEVEL_DB, SignalConfig.MAX_LEVEL_DB)
        val target = if (running) dbToAmp(level) else 0.0
        val nyquistSafe = sampleRate * 0.45
        val freq = cfg.freqHz.coerceIn(SignalConfig.MIN_FREQ, minOf(SignalConfig.MAX_FREQ, nyquistSafe))
        val sr = sampleRate.toDouble()

        for (i in 0 until frames) {
            gain += (target - gain) * smooth

            var s = 0.0
            var l = 1.0
            var r = 1.0
            when (cfg.mode) {
                SignalMode.TONE -> {
                    s = wave(cfg.waveform, phase)
                    phase = (phase + freq / sr) % 1.0
                    l = if (cfg.channels == ChannelSel.RIGHT) 0.0 else 1.0
                    r = if (cfg.channels == ChannelSel.LEFT) 0.0 else 1.0
                }
                SignalMode.SWEEP -> {
                    val pos = (t % (SWEEP_SECONDS * sr).toLong()) / (SWEEP_SECONDS * sr)
                    val f = SignalConfig.MIN_FREQ * (SignalConfig.MAX_FREQ / SignalConfig.MIN_FREQ).pow(pos)
                    sweepPhase = (sweepPhase + f / sr) % 1.0
                    s = sin(2 * PI * sweepPhase)
                }
                SignalMode.PINK_NOISE -> {
                    s = pink()
                    l = if (cfg.channels == ChannelSel.RIGHT) 0.0 else 1.0
                    r = if (cfg.channels == ChannelSel.LEFT) 0.0 else 1.0
                }
                SignalMode.CHANNEL_CHECK -> {
                    // 2 s cycle: left burst 0–0.5 s, right burst 1.0–1.5 s, otherwise silence.
                    val c = (t % (2 * sampleRate).toLong()) / sr
                    val inLeft = c < 0.5
                    val inRight = c >= 1.0 && c < 1.5
                    val x = if (inLeft) c else if (inRight) c - 1.0 else 0.0
                    val env = if (inLeft || inRight) minOf(1.0, x / 0.01, (0.5 - x) / 0.01).coerceAtLeast(0.0) else 0.0
                    s = sin(2 * PI * phase) * env
                    phase = (phase + 1000.0 / sr) % 1.0
                    l = if (inLeft) 1.0 else 0.0
                    r = if (inRight) 1.0 else 0.0
                }
                SignalMode.POLARITY -> {
                    s = sin(2 * PI * phase)
                    phase = (phase + 100.0 / sr) % 1.0
                    val antiPhase = ((t / (2L * sampleRate)) % 2L) == 1L
                    r = if (antiPhase) -1.0 else 1.0
                }
            }
            t++
            out[2 * i] = toPcm(s * gain * l)
            out[2 * i + 1] = toPcm(s * gain * r)
        }
    }

    private fun toPcm(v: Double): Short = (v * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()

    private fun wave(w: Waveform, p: Double): Double = when (w) {
        Waveform.SINE -> sin(2 * PI * p)
        Waveform.SQUARE -> if (p < 0.5) 1.0 else -1.0
        Waveform.TRIANGLE -> 4.0 * abs(p - floor(p + 0.5)) * 1.0 * -1.0 + 1.0 // -1..1 triangle
    }

    private fun pink(): Double {
        val white = rng.nextDouble() * 2.0 - 1.0
        b0 = 0.99886 * b0 + white * 0.0555179
        b1 = 0.99332 * b1 + white * 0.0750759
        b2 = 0.96900 * b2 + white * 0.1538520
        b3 = 0.86650 * b3 + white * 0.3104856
        b4 = 0.55000 * b4 + white * 0.5329522
        b5 = -0.7616 * b5 - white * 0.0168980
        val out = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
        b6 = white * 0.115926
        return (out * 0.11).coerceIn(-1.0, 1.0)
    }
}

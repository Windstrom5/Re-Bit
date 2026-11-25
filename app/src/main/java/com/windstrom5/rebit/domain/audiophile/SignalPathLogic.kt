package com.windstrom5.rebit.domain.audiophile

/** What is currently between the file and the DAC. Plain values so this stays testable on the JVM. */
data class PathInputs(
    val sourceFormat: String,
    val sourceRateHz: Int,
    val sourceBitDepth: Int,
    val usbDirect: Boolean,
    val eq: Boolean,
    val crossfeed: Boolean,
    val replayGain: Boolean,
    val normalization: Boolean,
    val tubeWarmth: Boolean,
    val dsdRemaster: Boolean,
    val aiProcessing: Boolean,
    val dacName: String?,
    val dacRatesHz: List<Int>,
    val dacBitDepths: List<Int>
)

enum class StageState { CLEAN, MODIFIED, WARNING }

data class PathStage(val name: String, val detail: String, val state: StageState, val penalty: Int = 0)

data class SignalPath(val stages: List<PathStage>, val purity: Int) {
    val verdict: String
        get() = when {
            purity >= 95 -> "Bit-perfect"
            purity >= 70 -> "Lightly processed"
            else -> "Processed"
        }
    val modified: List<PathStage> get() = stages.filter { it.state != StageState.CLEAN }
}

object SignalPathLogic {

    fun khz(hz: Int): String {
        val k = hz / 1000.0
        return (if (k % 1.0 == 0.0) "%.0f".format(k) else "%.1f".format(k)) + " kHz"
    }

    fun analyze(i: PathInputs): SignalPath {
        val stages = ArrayList<PathStage>()

        stages += PathStage("Source file", "${i.sourceFormat} · ${i.sourceBitDepth}-bit · ${khz(i.sourceRateHz)}", StageState.CLEAN)
        stages += PathStage("Decoder", "Lossless decode, no loss of data", StageState.CLEAN)

        // DSP — every active stage alters the samples, so none of them can be "bit-perfect".
        fun dsp(name: String, on: Boolean, what: String, penalty: Int) {
            stages += if (on) PathStage(name, what, StageState.MODIFIED, penalty)
            else PathStage(name, "Bypassed", StageState.CLEAN)
        }
        dsp("Equalizer", i.eq, "Reshapes the frequency response", 25)
        dsp("Crossfeed", i.crossfeed, "Blends channels for headphones", 15)
        dsp("Tube warmth", i.tubeWarmth, "Adds harmonic saturation", 20)
        dsp("DSD remaster", i.dsdRemaster, "Converts PCM to DSD", 30)
        dsp("AI processing", i.aiProcessing, "Alters or upscales the signal", 25)
        dsp("Loudness normalization", i.normalization, "Changes level", 10)
        dsp("ReplayGain", i.replayGain, "Applies a gain offset", 5)

        // Rate / bit-depth handling.
        if (!i.usbDirect) {
            stages += PathStage(
                "Output path", "Android audio mixer — may resample or mix with other sounds",
                StageState.WARNING, 40
            )
        } else {
            val rateOk = i.dacRatesHz.isEmpty() || i.sourceRateHz in i.dacRatesHz
            val bitsOk = i.dacBitDepths.isEmpty() || i.sourceBitDepth in i.dacBitDepths
            stages += when {
                !rateOk -> PathStage(
                    "Sample rate", "${khz(i.sourceRateHz)} isn't supported by the DAC — must be resampled",
                    StageState.WARNING, 30
                )
                !bitsOk -> PathStage(
                    "Bit depth", "${i.sourceBitDepth}-bit isn't supported by the DAC — must be converted",
                    StageState.WARNING, 15
                )
                else -> PathStage("Output path", "USB direct — samples sent untouched", StageState.CLEAN)
            }
        }

        stages += PathStage(
            "DAC",
            i.dacName ?: if (i.usbDirect) "No DAC detected" else "Device speaker / headphone output",
            if (i.usbDirect && i.dacName == null) StageState.WARNING else StageState.CLEAN,
            if (i.usbDirect && i.dacName == null) 20 else 0
        )

        val purity = (100 - stages.sumOf { it.penalty }).coerceIn(0, 100)
        return SignalPath(stages, purity)
    }
}

package com.windstrom5.rebit.domain.audiophile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiophileLogicTest {
    private fun base() = PathInputs(
        "FLAC", 96000, 24, true, false, false, false, false, false, false, false,
        "Topping E30", listOf(44100, 48000, 96000, 192000), listOf(16, 24, 32)
    )

    @Test fun cleanUsbPathIsBitPerfect() {
        val p = SignalPathLogic.analyze(base())
        assertEquals(100, p.purity)
        assertEquals("Bit-perfect", p.verdict)
        assertTrue(p.modified.isEmpty())
    }

    @Test fun dspStagesReducePurity() {
        val p = SignalPathLogic.analyze(base().copy(eq = true, replayGain = true))
        assertEquals(70, p.purity)
        assertEquals("Lightly processed", p.verdict)
        assertEquals(listOf("Equalizer", "ReplayGain"), p.modified.map { it.name })
    }

    @Test fun androidMixerWarns() {
        val p = SignalPathLogic.analyze(base().copy(usbDirect = false, dacName = null))
        assertEquals(60, p.purity)
        assertEquals("Processed", p.verdict)
        assertTrue(p.stages.last().detail.contains("speaker"))
    }

    @Test fun unsupportedRateOrDepthWarns() {
        assertEquals(70, SignalPathLogic.analyze(base().copy(sourceRateHz = 88200)).purity)
        assertEquals(85, SignalPathLogic.analyze(base().copy(dacBitDepths = listOf(16))).purity)
    }

    @Test fun unknownDacCapabilitiesDoNotWarn() {
        assertEquals(100, SignalPathLogic.analyze(base().copy(dacRatesHz = emptyList(), dacBitDepths = emptyList())).purity)
    }

    @Test fun purityFloorsAtZero() {
        val p = SignalPathLogic.analyze(base().copy(usbDirect = false, eq = true, crossfeed = true, tubeWarmth = true, dsdRemaster = true, aiProcessing = true))
        assertEquals(0, p.purity)
    }

    @Test fun libraryInsights() {
        val t = listOf(
            TrackSpec(44100, 16, "FLAC", 200_000, 30_000_000),
            TrackSpec(44100, 16, "FLAC", 200_000, 30_000_000),
            TrackSpec(96000, 24, "FLAC", 300_000, 90_000_000),
            TrackSpec(192000, 24, "WAV", 300_000, 200_000_000),
            TrackSpec(48000, 16, "WAV", 100_000, 10_000_000)
        )
        val ins = LibraryInsights.compute(t)
        assertEquals(40, ins.hiResPercent)
        assertEquals(192000, ins.bestTrack?.sampleRateHz)
        assertEquals(listOf("44.1 kHz", "48 kHz", "96 kHz", "192 kHz"), ins.sampleRates.map { it.label })
        assertEquals(listOf("FLAC" to 3, "WAV" to 2), ins.formats.map { it.label to it.count })
        assertEquals(ResolutionTier.CD, LibraryInsights.tierOf(44100, 16))
        assertEquals(ResolutionTier.HI_RES, LibraryInsights.tierOf(96000, 24))
        assertEquals(0, LibraryInsights.compute(emptyList()).hiResPercent)
        assertEquals("2.0 GB", LibraryInsights.formatSize(2L * (1L shl 30)))
    }
}

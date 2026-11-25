package com.windstrom5.rebit.playback.autoeq

import com.windstrom5.rebit.domain.model.EqFilter
import com.windstrom5.rebit.domain.model.EqFilterType
import com.windstrom5.rebit.playback.dsp.TenBandBiquadEqualizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Real AutoEq output for the Moondrop Aria (oratory1990 measurement). */
private const val ARIA = """Preamp: -3.4 dB
Filter 1: ON LSC Fc 105 Hz Gain -0.2 dB Q 0.70
Filter 2: ON PK Fc 164 Hz Gain -2.6 dB Q 0.92
Filter 3: ON PK Fc 6460 Hz Gain 3.5 dB Q 2.43
Filter 4: ON PK Fc 659 Hz Gain 1.4 dB Q 1.59
Filter 5: ON PK Fc 3108 Hz Gain 0.5 dB Q 1.51
Filter 6: ON HSC Fc 10000 Hz Gain -5.3 dB Q 0.70
Filter 7: ON PK Fc 8665 Hz Gain 2.1 dB Q 2.79
Filter 8: ON PK Fc 1408 Hz Gain -0.5 dB Q 2.30
Filter 9: ON PK Fc 54 Hz Gain 0.9 dB Q 5.32
Filter 10: ON PK Fc 31 Hz Gain -0.4 dB Q 2.21"""

class ParametricEqTextTest {
    @Test fun parsesRealAutoEqPreset() {
        val p = ParametricEqText.parse(ARIA)
        assertEquals(-3.4f, p.preampDb, 0.001f)
        assertEquals(10, p.filters.size)
        assertEquals(0, p.skipped)
        assertEquals(EqFilterType.LOW_SHELF, p.filters.first().type)
        assertEquals(EqFilterType.HIGH_SHELF, p.filters.last().type)
        assertTrue(p.filters.any { it.type == EqFilterType.PEAK && it.q > 2f }) // the narrow treble peak survives
    }

    @Test fun skipsDisabledAndUnsupportedFilters() {
        val text = """
            Preamp: -2,5 dB
            Filter 1: ON PK Fc 1000 Hz Gain 3,0 dB Q 1.0
            Filter 2: OFF PK Fc 2000 Hz Gain 3.0 dB Q 1.0
            Filter 3: ON LP Fc 5000 Hz
            Filter 4: ON HSC Fc 9000 Hz Gain -2.0 dB
        """.trimIndent()
        val p = ParametricEqText.parse(text)
        assertEquals(-2.5f, p.preampDb, 0.001f)
        assertEquals(2, p.filters.size)
        assertEquals(2, p.skipped)
        assertEquals(3.0f, p.filters[0].gainDb, 0.001f)
        assertEquals(0.707f, p.filters[1].q, 0.001f) // shelf default Q
    }
}

class EqMathTest {
    @Test fun peakFilterHasExactGainAtItsCentre() {
        val f = EqFilter(EqFilterType.PEAK, 1000f, 6f, 1.0f)
        assertEquals(6.0, EqMath.filterDb(f, 1000.0), 0.01)
        assertEquals(0.0, EqMath.filterDb(f, 50.0), 0.3)
    }

    @Test fun shelvesReachTheirGainAtTheExtremes() {
        assertEquals(4.0, EqMath.filterDb(EqFilter(EqFilterType.LOW_SHELF, 200f, 4f, 0.7f), 20.0), 0.2)
        assertEquals(-3.0, EqMath.filterDb(EqFilter(EqFilterType.HIGH_SHELF, 4000f, -3f, 0.7f), 20_000.0), 0.3)
    }

    @Test fun safePreampOnlyWhenBoosting() {
        assertEquals(0f, EqMath.safePreampDb(listOf(EqFilter(EqFilterType.PEAK, 1000f, -4f, 1f))), 0f)
        val p = EqMath.safePreampDb(listOf(EqFilter(EqFilterType.PEAK, 1000f, 5f, 1f)))
        assertEquals(-5.3f, p, 0.1f)
    }

    @Test fun realPresetGetsAGoodTenBandApproximation() {
        val curve = EqMath.curve(ParametricEqText.parse(ARIA).filters)
        val fit = EqMath.fitGraphic(curve)
        assertEquals(10, fit.size)
        assertTrue(fit.all { abs(it) <= 12f })
        val err = EqMath.rmsError(EqMath.curve(EqMath.graphicBandFilters(fit)), curve)
        assertTrue("10-band fit RMS $err dB", err < 1.5)
    }

    @Test fun computedPreampMatchesAutoEqs() {
        val p = ParametricEqText.parse(ARIA)
        assertEquals(p.preampDb.toDouble(), EqMath.safePreampDb(p.filters).toDouble(), 0.5)
    }
}

class AutoEqIndexTest {
    private val md = """
        # Index
        - [Moondrop Aria](./oratory1990/in-ear/Moondrop%20Aria) by oratory1990
        - [Moondrop Aria](./crinacle/711%20in-ear/Moondrop%20Aria) by crinacle on 711
        - [1MORE Aero (ANC Off)](./HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)) by HypetheSonics on GRAS RA0045
        - [Sennheiser HD 600](./oratory1990/over-ear/Sennheiser%20HD%20600) by oratory1990
        not a list line
    """.trimIndent()

    @Test fun parsesNamesPathsAndSources() {
        val e = AutoEqIndex.parse(md)
        assertEquals(4, e.size)
        assertTrue(e[0].isInEar); assertTrue(!e[3].isInEar)
        assertEquals("oratory1990", e[0].source); assertNull(e[0].rig)
        assertEquals("711", e[1].rig)
        assertEquals("1MORE%20Aero%20(ANC%20Off)", e[2].path.substringAfterLast('/'))
    }

    @Test fun groupsModelsAndPrefersOratory() {
        val groups = AutoEqIndex.groupByModel(AutoEqIndex.parse(md).filter { it.isInEar })
        val aria = groups.first { it.first().name == "Moondrop Aria" }
        assertEquals(2, aria.size)
        assertEquals("oratory1990", aria.first().source)
    }

    @Test fun searchRequiresAllWords() {
        val groups = AutoEqIndex.groupByModel(AutoEqIndex.parse(md))
        assertEquals(1, AutoEqIndex.search(groups, "moondrop aria").size)
        assertEquals(0, AutoEqIndex.search(groups, "moondrop hd").size)
        assertTrue(AutoEqIndex.search(groups, " ").isEmpty())
    }

    @Test fun buildsPresetUrls() {
        val e = AutoEqIndex.parse(md)
        assertEquals(
            "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/oratory1990/in-ear/Moondrop%20Aria/Moondrop%20Aria%20ParametricEQ.txt",
            AutoEqIndex.presetUrl(e[0])
        )
        assertTrue(AutoEqIndex.presetUrl(e[2], "FixedBandEQ.txt").endsWith("(ANC%20Off)%20FixedBandEQ.txt"))
    }
}

class EqPlannerTest {
    private val iem = ParametricEqText.parse(ARIA).filters

    @Test fun strengthScalesTheGenreOnly() {
        val sig = SoundSignatures.byId("hiphop")
        assertEquals(iem, EqPlanner.combine(iem, sig, 0f))
        val full = EqPlanner.combine(iem, sig, 1f)
        assertEquals(iem.size + sig.filters.size, full.size)
        assertEquals(iem, full.take(iem.size)) // the IEM correction is never altered
    }

    @Test fun neverExceedsTheEngineLimit() {
        assertTrue(SoundSignatures.ALL.all { EqPlanner.combine(iem, it, 1f).size <= EqPlanner.MAX_FILTERS })
        val many = List(20) { EqFilter(EqFilterType.PEAK, 100f + it * 500, 1f + it * 0.1f, 1f) }
        assertEquals(EqPlanner.MAX_FILTERS, EqPlanner.combine(many, SoundSignatures.NEUTRAL, 1f).size)
    }

    @Test fun boostingGenresNeedMorePreamp() {
        val base = EqPlanner.plan(iem, SoundSignatures.NEUTRAL, 1f).preampDb
        val bass = EqPlanner.plan(iem, SoundSignatures.byId("hiphop"), 1f).preampDb
        assertTrue(bass < base)
    }

    @Test fun labelsNameTheModelAndGenre() {
        assertEquals("Moondrop Aria · Rock", EqPlanner.build("Moondrop Aria", iem, SoundSignatures.byId("rock"), 1f).profileName)
        assertEquals("Moondrop Aria", EqPlanner.build("Moondrop Aria", iem, SoundSignatures.NEUTRAL, 1f).profileName)
        assertEquals("Pop (generic IEM)", EqPlanner.build(null, emptyList(), SoundSignatures.byId("pop"), 1f).profileName)
    }
}

class ParametricEngineTest {
    private fun measureDb(eq: TenBandBiquadEqualizer, fs: Int, f: Double): Double {
        eq.reset()
        val buf = FloatArray(2 * fs) { i -> (0.1 * sin(2 * PI * f * (i / 2) / fs)).toFloat() }
        eq.processStereo(buf)
        var acc = 0.0
        for (i in fs / 2 until fs) { val v = buf[2 * i].toDouble(); acc += v * v }
        return 20 * log10(sqrt(acc / (fs - fs / 2)) / (0.1 / sqrt(2.0)))
    }

    @Test fun engineMatchesPredictedResponseAtDifferentSampleRates() {
        val p = ParametricEqText.parse(ARIA)
        for (fs in listOf(48_000, 192_000)) {
            val eq = TenBandBiquadEqualizer(fs)
            eq.applySettings(List(10) { 0f }, p.filters, p.preampDb)
            for (f in listOf(54.0, 300.0, 1000.0, 6460.0, 12_000.0)) {
                val predicted = EqMath.responseDb(p.filters, f, fs.toDouble()) + p.preampDb
                assertEquals("fs=$fs f=$f", predicted, measureDb(eq, fs, f), 0.15)
            }
        }
    }

    @Test fun clearingFiltersReturnsToGraphicMode() {
        val p = ParametricEqText.parse(ARIA)
        val eq = TenBandBiquadEqualizer(48_000)
        eq.applySettings(List(10) { 0f }, p.filters, p.preampDb)
        eq.applySettings(List(10) { 0f }, emptyList(), 0f) // flat graphic EQ
        assertEquals(0.0, measureDb(eq, 48_000, 1000.0), 0.05)
    }
}

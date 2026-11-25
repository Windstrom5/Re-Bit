package com.windstrom5.rebit.domain.eqtrainer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class EqTrainerTest {

    // ── DSP ──

    @Test
    fun peakingBand_hasTheRequestedGainAtItsCentre_andIsFlatFarAway() {
        val boost = PeakingBiquad(1000f, 9f, 1.4f, 48_000)
        assertEquals(9.0, boost.magnitudeDb(1000.0), 0.05)
        assertTrue(abs(boost.magnitudeDb(30.0)) < 0.5)
        assertTrue(abs(boost.magnitudeDb(18_000.0)) < 1.0)

        val cut = PeakingBiquad(250f, -6f, 2f, 44_100)
        assertEquals(-6.0, cut.magnitudeDb(250.0), 0.05)
    }

    @Test
    fun higherQ_meansANarrowerBand() {
        val wide = PeakingBiquad(1000f, 6f, 0.9f, 48_000)
        val narrow = PeakingBiquad(1000f, 6f, 4f, 48_000)
        // an octave away the wide band still lifts noticeably, the narrow one barely does
        assertTrue(wide.magnitudeDb(2000.0) > narrow.magnitudeDb(2000.0) + 1.0)
    }

    @Test
    fun filtering_matchesTheTheoreticalGainOnASine() {
        val rate = 48_000
        val f = PeakingBiquad(1000f, 12f, 1f, rate)
        val n = 48_000
        val x = FloatArray(n * 2) { i -> (0.1 * sin(2.0 * PI * 1000 * (i / 2) / rate)).toFloat() }
        f.processStereo(x)
        // measure the steady-state RMS of the second half
        var s = 0.0
        for (i in n until n * 2 step 2) s += x[i] * x[i]
        val rms = sqrt(s / (n / 2))
        val gainDb = 20 * log10(rms / (0.1 / sqrt(2.0)))
        assertEquals(12.0, gainDb, 0.2)
    }

    @Test
    fun pinkNoise_isQuietAndFallsAsFrequencyRises() {
        val noise = PinkNoise(7)
        val n = 200_000
        val v = FloatArray(n) { noise.next() }
        val rms = sqrt(v.sumOf { (it * it).toDouble() } / n)
        assertEquals(0.05, rms, 0.01) // about -26 dBFS
        assertTrue(v.all { abs(it) < 0.6f }) // headroom for boosts
    }

    // ── Game rules ──

    @Test
    fun newGame_followsDifficultyAndMode() {
        val game = EqTrainerGame(Random(1))
        val boost = game.newGame(EqDifficulty.EASY, EqMode.BOOST, 10).current!!
        assertEquals(9f, boost.gainDb, 0f)
        assertTrue(boost.frequencyHz in EqDifficulty.EASY.frequencies)

        val cut = game.newGame(EqDifficulty.HARD, EqMode.CUT, 10).current!!
        assertEquals(-4f, cut.gainDb, 0f)
        assertEquals(3f, cut.q, 0f)
    }

    @Test
    fun bothMode_usesBothSigns_overManyRounds() {
        val game = EqTrainerGame(Random(3))
        var s = game.newGame(EqDifficulty.BEGINNER, EqMode.BOTH, 40)
        val signs = HashSet<Boolean>()
        while (!s.finished) {
            signs += s.current!!.isBoost
            s = game.answer(s, 500)!!.first
        }
        assertEquals(setOf(true, false), signs)
    }

    @Test
    fun scoring_exactClosePoints_andStreaks() {
        val game = EqTrainerGame(Random(5))
        var s = game.newGame(EqDifficulty.EASY, EqMode.BOOST, 3)
        val freqs = EqDifficulty.EASY.frequencies

        // round 1: exact
        var q = s.current!!
        var (next, r) = game.answer(s, q.frequencyHz)!!
        assertTrue(r.correct); assertEquals(10, r.points)
        assertEquals(1, next.streak)
        s = next

        // round 2: one band off (neighbour in the list)
        q = s.current!!
        val i = freqs.indexOf(q.frequencyHz)
        val neighbour = freqs[if (i > 0) i - 1 else i + 1]
        game.answer(s, neighbour)!!.also { (n2, r2) ->
            assertFalse(r2.correct); assertEquals(1, r2.bandsOff); assertEquals(4, r2.points)
            assertEquals(0, n2.streak); assertEquals(1, n2.bestStreak)
            s = n2
        }

        // round 3: far away
        q = s.current!!
        val far = freqs.maxByOrNull { abs(freqs.indexOf(it) - freqs.indexOf(q.frequencyHz)) }!!
        val (end, r3) = game.answer(s, far)!!
        assertEquals(0, r3.points)
        assertTrue(end.finished)
        assertNull(end.current)
        assertEquals(14, end.score)
        assertEquals(33, end.accuracyPercent) // 1 of 3
        assertNull(game.answer(end, 500)) // no answers after the end
    }

    @Test
    fun neverTheSameBandTwiceInARow() {
        val game = EqTrainerGame(Random(9))
        var s = game.newGame(EqDifficulty.BEGINNER, EqMode.BOOST, 200)
        var last = s.current!!.frequencyHz
        repeat(199) {
            s = game.answer(s, 500)!!.first
            val now = s.current!!.frequencyHz
            assertTrue(now != last)
            last = now
        }
    }

    @Test
    fun perBandStats_andRank() {
        val game = EqTrainerGame(Random(2))
        var s = game.newGame(EqDifficulty.BEGINNER, EqMode.BOOST, 20)
        while (!s.finished) s = game.answer(s, s.current!!.frequencyHz)!!.first // always right
        assertEquals(100, s.accuracyPercent)
        assertEquals("Mastering engineer", s.rank)
        assertEquals(20, s.perBand.values.sumOf { it.second })
        assertTrue(s.perBand.values.all { it.first == it.second })
        assertEquals(20, s.bestStreak)
        assertEquals(200, s.score)
    }

    @Test
    fun labels() {
        assertEquals("125 Hz", formatHz(125))
        assertEquals("1 kHz", formatHz(1000))
        assertEquals("11.2 kHz", formatHz(11200))
        assertEquals("1k", shortHz(1000))
        assertEquals("5.6k", shortHz(5600))
        assertNotNull(EqDifficulty.entries.firstOrNull { it.frequencies.size > 10 })
    }
}

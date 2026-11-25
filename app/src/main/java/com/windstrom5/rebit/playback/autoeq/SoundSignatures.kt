package com.windstrom5.rebit.playback.autoeq

import com.windstrom5.rebit.domain.model.EqFilter
import com.windstrom5.rebit.domain.model.EqFilterType.HIGH_SHELF
import com.windstrom5.rebit.domain.model.EqFilterType.LOW_SHELF
import com.windstrom5.rebit.domain.model.EqFilterType.PEAK
import kotlin.math.abs

/**
 * A genre/taste tuning applied ON TOP of an IEM's measured correction. Because the correction
 * has already flattened the IEM to the Harman target, these adjustments land the same way on any
 * IEM — that is what makes them "per-IEM genre presets" rather than one fixed curve.
 */
data class SoundSignature(
    val id: String,
    val label: String,
    val blurb: String,
    val filters: List<EqFilter>
)

object SoundSignatures {
    private fun ls(fc: Float, g: Float, q: Float = 0.7f) = EqFilter(LOW_SHELF, fc, g, q)
    private fun hs(fc: Float, g: Float, q: Float = 0.7f) = EqFilter(HIGH_SHELF, fc, g, q)
    private fun pk(fc: Float, g: Float, q: Float = 1.0f) = EqFilter(PEAK, fc, g, q)

    val NEUTRAL = SoundSignature("neutral", "Neutral", "The IEM corrected to the Harman target, with no extra tuning.", emptyList())

    val ALL: List<SoundSignature> = listOf(
        NEUTRAL,
        SoundSignature("pop", "Pop", "Forward vocals, a punchy low end and a little sparkle.",
            listOf(ls(100f, 1.5f), pk(3000f, 1.0f), hs(9000f, 1.5f))),
        SoundSignature("rock", "Rock", "Cleaner low-mids, bite in the guitars, a touch of attack.",
            listOf(ls(80f, 1.0f), pk(350f, -1.0f), pk(2800f, 1.5f, 1.1f), pk(5500f, 1.0f, 1.5f))),
        SoundSignature("metal", "Metal", "Tight bass, scooped mids and crisp, aggressive treble.",
            listOf(pk(90f, 1.0f), pk(400f, -1.8f), pk(3500f, 1.5f), hs(8000f, 1.0f))),
        SoundSignature("hiphop", "Hip-Hop", "Deep sub-bass with clean, uncluttered mids.",
            listOf(ls(70f, 3.5f), pk(250f, -1.0f), pk(3000f, 0.8f))),
        SoundSignature("rnb", "R&B / Soul", "Warm, smooth and full, with the edge taken off the treble.",
            listOf(ls(90f, 2.5f), pk(2000f, 0.8f, 0.9f), pk(6000f, -0.8f, 1.5f))),
        SoundSignature("edm", "EDM / Electronic", "Big sub-bass, energetic top end.",
            listOf(ls(60f, 3.5f), pk(120f, 1.0f), pk(5000f, 1.0f), hs(9000f, 2.0f))),
        SoundSignature("kpop", "K-Pop / J-Pop", "Bright, glossy vocals with extra bass weight and air.",
            listOf(ls(100f, 2.0f), pk(3000f, 1.5f, 0.9f), hs(10000f, 2.0f))),
        SoundSignature("jazz", "Jazz", "Warm body, relaxed upper mids, airy cymbals.",
            listOf(pk(200f, 1.2f, 0.8f), pk(3500f, -0.8f), hs(8000f, 1.0f))),
        SoundSignature("classical", "Classical", "Extended and airy, with a touch of low-end foundation.",
            listOf(ls(60f, 1.0f), pk(2500f, -0.5f, 0.8f), hs(10000f, 1.0f))),
        SoundSignature("acoustic", "Acoustic / Folk", "Natural body and presence for guitars and voices.",
            listOf(pk(180f, 1.0f, 0.8f), pk(3000f, 1.0f), hs(9000f, 1.0f))),
        SoundSignature("vocal", "Vocal / Podcast", "Pushes voices forward and trims boominess.",
            listOf(pk(220f, -1.0f), pk(2000f, 2.0f), pk(4000f, 1.2f, 1.2f))),
        SoundSignature("bass", "Bass boost", "More low-end weight without muddying the mids.",
            listOf(ls(80f, 4.0f), pk(200f, -0.5f))),
        SoundSignature("latenight", "Late night", "Loudness compensation for quiet listening: more bass and treble.",
            listOf(ls(100f, 4.0f), hs(9000f, 3.0f))),
        SoundSignature("smooth", "Treble-safe", "Tames harshness and sibilance on bright IEMs.",
            listOf(pk(6000f, -2.0f, 2.0f), hs(8000f, -1.5f)))
    )

    fun byId(id: String?): SoundSignature = ALL.firstOrNull { it.id == id } ?: NEUTRAL

    /** The signature's filters with gains scaled by [strength] (0..1); near-zero filters are dropped. */
    fun scaled(sig: SoundSignature, strength: Float): List<EqFilter> =
        sig.filters.map { it.copy(gainDb = it.gainDb * strength.coerceIn(0f, 1f)) }.filter { abs(it.gainDb) >= 0.05f }
}

package com.windstrom5.rebit.domain.model

/** Filter shapes the parametric equalizer supports (matches AutoEq's PK / LSC / HSC). */
enum class EqFilterType { PEAK, LOW_SHELF, HIGH_SHELF }

/**
 * One parametric EQ filter. A full IEM correction from AutoEq is a list of these (typically 10),
 * each with its own centre frequency and Q — far more precise than fixed octave bands.
 */
data class EqFilter(
    val type: EqFilterType,
    val fcHz: Float,
    val gainDb: Float,
    val q: Float
)

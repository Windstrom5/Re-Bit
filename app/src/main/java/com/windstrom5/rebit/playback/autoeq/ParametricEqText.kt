package com.windstrom5.rebit.playback.autoeq

import com.windstrom5.rebit.domain.model.EqFilter
import com.windstrom5.rebit.domain.model.EqFilterType

data class ParsedParametricEq(val preampDb: Float, val filters: List<EqFilter>, val skipped: Int)

/**
 * Parses Equalizer APO / AutoEq parametric text:
 *
 *     Preamp: -3.4 dB
 *     Filter 1: ON LSC Fc 105 Hz Gain -0.2 dB Q 0.70
 *     Filter 2: ON PK Fc 164 Hz Gain -2.6 dB Q 0.92
 *
 * Only filter kinds the engine can run are kept (peak, low shelf, high shelf); disabled filters
 * and other kinds (low/high pass, notch…) are counted in [ParsedParametricEq.skipped].
 */
object ParametricEqText {
    private val PREAMP = Regex("""Preamp\s*:\s*(-?\d+(?:[.,]\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
    private val FILTER = Regex(
        """Filter\s*\d*\s*:\s*(ON|OFF)\s+(\w+)\s+Fc\s+(\d+(?:[.,]\d+)?)\s*Hz(?:\s+Gain\s+(-?\d+(?:[.,]\d+)?)\s*dB)?(?:\s+Q\s+(\d+(?:[.,]\d+)?))?""",
        RegexOption.IGNORE_CASE
    )

    private fun num(s: String?): Float? = s?.replace(',', '.')?.toFloatOrNull()

    private fun typeOf(code: String): EqFilterType? = when (code.uppercase()) {
        "PK", "PEQ", "PEAK" -> EqFilterType.PEAK
        "LSC", "LS", "LSQ" -> EqFilterType.LOW_SHELF
        "HSC", "HS", "HSQ" -> EqFilterType.HIGH_SHELF
        else -> null
    }

    fun parse(text: String): ParsedParametricEq {
        val preamp = PREAMP.find(text)?.let { num(it.groupValues[1]) } ?: 0f
        val filters = ArrayList<EqFilter>()
        var skipped = 0
        for (line in text.lineSequence()) {
            val m = FILTER.find(line) ?: continue
            val on = m.groupValues[1].equals("ON", ignoreCase = true)
            val type = typeOf(m.groupValues[2])
            val fc = num(m.groupValues[3])
            if (!on || type == null || fc == null || fc <= 0f) { skipped++; continue }
            val gain = num(m.groupValues[4].ifEmpty { null }) ?: 0f
            val q = num(m.groupValues[5].ifEmpty { null }) ?: if (type == EqFilterType.PEAK) 1.0f else 0.707f
            filters += EqFilter(type, fc, gain, q)
        }
        return ParsedParametricEq(preamp, filters, skipped)
    }
}

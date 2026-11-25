package com.windstrom5.rebit.playback.autoeq

import com.windstrom5.rebit.domain.model.EqFilter
import kotlin.math.abs

/** What the browser hands to the player when the user taps Apply. */
data class IemEqApply(
    val profileName: String,
    val filters: List<EqFilter>,
    val preampDb: Float,
    /** 10-band approximation, so the graphic sliders still mean something. */
    val displayGains: List<Float>,
    val signatureId: String,
    val signatureStrength: Float
)

/** Preview of a combined correction (IEM + genre): the curve to draw and the safe preamp. */
data class EqPlan(val filters: List<EqFilter>, val preampDb: Float, val curve: DoubleArray)

/** Combines an IEM's measured correction with a genre signature. Pure, so it is unit-tested. */
object EqPlanner {
    const val MAX_FILTERS = 16

    fun combine(iem: List<EqFilter>, signature: SoundSignature, strength: Float): List<EqFilter> {
        val all = iem + SoundSignatures.scaled(signature, strength)
        if (all.size <= MAX_FILTERS) return all
        // Over the engine's limit: keep the filters that change the sound most.
        return all.sortedByDescending { abs(it.gainDb) }.take(MAX_FILTERS)
    }

    fun plan(iem: List<EqFilter>, signature: SoundSignature, strength: Float): EqPlan {
        val filters = combine(iem, signature, strength)
        return EqPlan(filters, EqMath.safePreampDb(filters), EqMath.curve(filters))
    }

    /** Final payload, including the (slower) 10-band fit; call once on Apply, not per slider move. */
    fun build(modelName: String?, iem: List<EqFilter>, signature: SoundSignature, strength: Float): IemEqApply {
        val p = plan(iem, signature, strength)
        val label = when {
            modelName != null && signature.id != "neutral" -> "$modelName · ${signature.label}"
            modelName != null -> modelName
            else -> "${signature.label} (generic IEM)"
        }
        return IemEqApply(
            profileName = label,
            filters = p.filters,
            preampDb = p.preampDb,
            displayGains = EqMath.fitGraphic(p.curve),
            signatureId = signature.id,
            signatureStrength = strength
        )
    }
}

package com.windstrom5.rebit.domain.eqtrainer

import android.content.Context

/** Remembers the best accuracy per difficulty, for the Games hub. */
class EqTrainerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("eq_trainer", Context.MODE_PRIVATE)

    fun best(difficulty: EqDifficulty): Int = prefs.getInt("best_${difficulty.name}", -1)

    /** Records a finished game of 10+ rounds. Returns true if it is a new best. */
    fun record(difficulty: EqDifficulty, accuracyPercent: Int, rounds: Int): Boolean {
        if (rounds < 10) return false
        val isBest = accuracyPercent > best(difficulty)
        if (isBest) prefs.edit().putInt("best_${difficulty.name}", accuracyPercent).apply()
        prefs.edit().putInt("played_total", prefs.getInt("played_total", 0) + 1).apply()
        return isBest
    }

    val gamesPlayed: Int get() = prefs.getInt("played_total", 0)
}

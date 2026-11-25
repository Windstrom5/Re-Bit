package com.windstrom5.rebit.domain.eqtrainer

import kotlin.math.abs
import kotlin.random.Random

/** How hard the hidden EQ band is to find. Smaller gain, narrower band and more choices = harder. */
enum class EqDifficulty(
    val title: String,
    val gainDb: Float,
    val q: Float,
    val frequencies: List<Int>,
    val blurb: String
) {
    BEGINNER(
        "Beginner", 12f, 0.9f, listOf(125, 500, 2000, 8000),
        "Big, wide change. Four far-apart bands."
    ),
    EASY(
        "Easy", 9f, 1.4f, listOf(125, 250, 500, 1000, 2000, 4000, 8000),
        "Seven octave bands."
    ),
    MEDIUM(
        "Medium", 6f, 2f, listOf(63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000),
        "Nine octave bands, a gentler change."
    ),
    HARD(
        "Hard", 4f, 3f,
        listOf(63, 90, 125, 180, 250, 355, 500, 710, 1000, 1400, 2000, 2800, 4000, 5600, 8000, 11200),
        "Half-octave bands, a subtle narrow change."
    )
}

enum class EqMode(val title: String) { BOOST("Boost"), CUT("Cut"), BOTH("Boost & cut") }

/** The hidden band for one round. [gainDb] is negative for a cut. */
data class EqQuestion(val frequencyHz: Int, val gainDb: Float, val q: Float) {
    val isBoost: Boolean get() = gainDb > 0f
}

data class EqAnswerResult(
    val question: EqQuestion,
    val guessedHz: Int,
    val correct: Boolean,
    /** How many choices away the guess was (0 = exact). */
    val bandsOff: Int,
    val points: Int
)

data class EqGameState(
    val difficulty: EqDifficulty,
    val mode: EqMode,
    val totalRounds: Int,
    val current: EqQuestion?,
    val history: List<EqAnswerResult> = emptyList(),
    val score: Int = 0,
    val streak: Int = 0,
    val bestStreak: Int = 0
) {
    val finished: Boolean get() = history.size >= totalRounds
    val round: Int get() = (history.size + 1).coerceAtMost(totalRounds)
    val correctCount: Int get() = history.count { it.correct }
    val accuracyPercent: Int get() = if (history.isEmpty()) 0 else correctCount * 100 / history.size
    val maxScore: Int get() = totalRounds * EqTrainerGame.POINTS_EXACT

    /** Per choice: (hits, tries) over the rounds where that band was the hidden one. */
    val perBand: Map<Int, Pair<Int, Int>>
        get() = difficulty.frequencies.associateWith { f ->
            val tries = history.filter { it.question.frequencyHz == f }
            tries.count { it.correct } to tries.size
        }

    /** A label for how well you did. */
    val rank: String
        get() = when {
            history.size < totalRounds -> "In progress"
            accuracyPercent >= 90 -> "Mastering engineer"
            accuracyPercent >= 75 -> "Studio ears"
            accuracyPercent >= 50 -> "Trained ear"
            accuracyPercent >= 30 -> "Getting there"
            else -> "Keep training"
        }
}

/** Pure game rules; the random source is injectable so tests are deterministic. */
class EqTrainerGame(private val random: Random = Random.Default) {

    fun newGame(difficulty: EqDifficulty, mode: EqMode, rounds: Int): EqGameState {
        val first = question(difficulty, mode, previous = null)
        return EqGameState(difficulty, mode, rounds.coerceAtLeast(1), first)
    }

    /** Scores [guessedHz] against the current question and moves to the next one. */
    fun answer(state: EqGameState, guessedHz: Int): Pair<EqGameState, EqAnswerResult>? {
        val q = state.current ?: return null
        if (state.finished) return null
        val freqs = state.difficulty.frequencies
        val off = abs(freqs.indexOf(guessedHz) - freqs.indexOf(q.frequencyHz))
        val correct = guessedHz == q.frequencyHz
        val points = when {
            correct -> POINTS_EXACT
            off == 1 -> POINTS_CLOSE
            else -> 0
        }
        val result = EqAnswerResult(q, guessedHz, correct, off, points)
        val streak = if (correct) state.streak + 1 else 0
        val history = state.history + result
        val done = history.size >= state.totalRounds
        val next = if (done) null else question(state.difficulty, state.mode, previous = q)
        return state.copy(
            current = next,
            history = history,
            score = state.score + points,
            streak = streak,
            bestStreak = maxOf(state.bestStreak, streak)
        ) to result
    }

    private fun question(difficulty: EqDifficulty, mode: EqMode, previous: EqQuestion?): EqQuestion {
        val choices = difficulty.frequencies
        // Never the same band twice in a row (there is always more than one choice).
        val pool = choices.filter { it != previous?.frequencyHz }.ifEmpty { choices }
        val sign = when (mode) {
            EqMode.BOOST -> 1f
            EqMode.CUT -> -1f
            EqMode.BOTH -> if (random.nextBoolean()) 1f else -1f
        }
        return EqQuestion(pool[random.nextInt(pool.size)], sign * difficulty.gainDb, difficulty.q)
    }

    companion object {
        const val POINTS_EXACT = 10
        const val POINTS_CLOSE = 4
    }
}

/** "125 Hz", "1 kHz", "11.2 kHz". */
fun formatHz(hz: Int): String = when {
    hz < 1000 -> "$hz Hz"
    hz % 1000 == 0 -> "${hz / 1000} kHz"
    else -> "${hz / 1000}.${(hz % 1000) / 100} kHz"
}

/** Short label for answer buttons: "125", "1k", "11.2k". */
fun shortHz(hz: Int): String = when {
    hz < 1000 -> "$hz"
    hz % 1000 == 0 -> "${hz / 1000}k"
    else -> "${hz / 1000}.${(hz % 1000) / 100}k"
}

package com.windstrom5.rebit.playback.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One completed lyric line's singing grade — see [KaraokeMicScorer.setCurrentLyricLine]. */
data class LineScoreEntry(
    val lineIndex: Int,
    val text: String,
    val grade: String,
    val accuracyPercent: Int
)

/**
 * Live state of the Karaoke Singing Evaluation & Microphone Pitch Tracker.
 */
data class KaraokeScoreState(
    val isMicActive: Boolean = false,
    val hasPermission: Boolean = false,
    val singerPitchHz: Float = 0f,
    val singerNote: String = "--",
    val targetNote: String = "--",
    val pitchDeltaCents: Float = 0f,
    val micLevel: Float = 0f,
    val currentGrade: String = "READY",
    val totalScore: Int = 0,
    val combo: Int = 0,
    val maxCombo: Int = 0,
    val notesHitCount: Int = 0,
    val totalNotesEvaluated: Int = 0,
    /** The lyric line currently active, for display alongside the live pitch
     *  readout — see [KaraokeMicScorer.setCurrentLyricLine]. Empty when no
     *  synced lyrics are available; scoring still works either way, just
     *  without per-line grading (see [lineScores]). */
    val currentLyricText: String = "",
    /** Running accuracy for the *in-progress* line — resets each time the
     *  active line changes, finalized into [lineScores] when it does. */
    val currentLineAccuracyPercent: Int = 0,
    /** Recently completed lines with their grade, most recent last — capped
     *  at [KaraokeMicScorer.MAX_LINE_HISTORY] so a long song doesn't grow
     *  this unboundedly. */
    val lineScores: List<LineScoreEntry> = emptyList()
)

/**
 * Real-time microphone audio analyzer and live singing evaluator.
 * Captures user singing via AudioRecord, detects fundamental frequency (F0),
 * and scores pitch accuracy against reference track musical notes.
 *
 * Per-line grading ties this to the lyrics timeline: [setCurrentLyricLine]
 * is meant to be called (from PlayerViewModel, driven by the same
 * activeLyricIndex the synced-lyrics display already uses) whenever
 * playback crosses into a new line. Doing that finalizes whatever was sung
 * during the *previous* line into a graded [LineScoreEntry] and starts a
 * fresh running average for the new one — this is what turns continuous
 * pitch-matching into something that actually reads as "how did I do on
 * THAT line", the way real karaoke scoring displays work.
 *
 * Thread safety note: [evaluateSingingPitch] runs on this class's own
 * dedicated recording thread, while [setCurrentLyricLine] is expected to be
 * called from the ViewModel (main/coroutine thread) as playback position
 * updates. Both touch the same per-line accumulator fields, so those are
 * guarded by [lineLock] — a plain mutex, not lock-free, but this is called
 * at most a few times a second, so contention was never a real concern here.
 */
class KaraokeMicScorer {

    private val _scoreState = MutableStateFlow(KaraokeScoreState())
    val scoreState: StateFlow<KaraokeScoreState> = _scoreState.asStateFlow()

    private var recordThread: Thread? = null
    private var isRecording = false
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private var accumulatedScore = 0
    private var currentCombo = 0
    private var maxCombo = 0
    private var notesHit = 0
    private var totalEvaluated = 0

    private var lastScoreUpdateTimeMs = 0L

    // ── Per-line accumulator — see class doc comment for the threading note ──
    private val lineLock = Any()
    private var currentLineIndex = -1
    private var currentLineText = ""
    private var currentLineSampleSum = 0f
    private var currentLineSampleCount = 0

    /**
     * Called whenever the actively-displayed lyric line changes (see
     * PlayerViewModel's activeLyricIndex). Finalizes the previous line's
     * running average into a graded [LineScoreEntry] (skipped if nothing
     * was sung during it — e.g. an instrumental gap, or scoring just
     * started) and resets the accumulator for the new line. Safe to call
     * with the same index repeatedly (a no-op after the first time).
     */
    fun setCurrentLyricLine(lineIndex: Int, text: String) {
        synchronized(lineLock) {
            if (lineIndex == currentLineIndex) return
            finalizeCurrentLineLocked()
            currentLineIndex = lineIndex
            currentLineText = text
            currentLineSampleSum = 0f
            currentLineSampleCount = 0
        }
        _scoreState.value = _scoreState.value.copy(currentLyricText = text, currentLineAccuracyPercent = 0)
    }

    /** Must be called with [lineLock] held. */
    private fun finalizeCurrentLineLocked() {
        if (currentLineIndex < 0 || currentLineSampleCount == 0) return
        val avgPercent = (currentLineSampleSum / currentLineSampleCount).roundToInt().coerceIn(0, 100)
        val grade = when {
            avgPercent >= 90 -> "PERFECT"
            avgPercent >= 70 -> "GREAT"
            avgPercent >= 50 -> "GOOD"
            else -> "MISS"
        }
        val entry = LineScoreEntry(currentLineIndex, currentLineText, grade, avgPercent)
        val updatedHistory = (_scoreState.value.lineScores + entry).takeLast(MAX_LINE_HISTORY)
        _scoreState.value = _scoreState.value.copy(lineScores = updatedHistory)
    }

    /**
     * Starts real-time microphone capture and pitch scoring if RECORD_AUDIO permission is granted.
     */
    fun start(
        context: Context,
        getReferencePitch: () -> Float,
        getReferenceNote: () -> String
    ) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            _scoreState.value = _scoreState.value.copy(
                isMicActive = false,
                hasPermission = false,
                currentGrade = "NEED MIC PERMISSION"
            )
            return
        }

        if (isRecording) return
        isRecording = true
        _scoreState.value = _scoreState.value.copy(isMicActive = true, hasPermission = true, currentGrade = "SING INTO MIC 🎤")

        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        recordThread = Thread({
            var audioRecord: AudioRecord? = null
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    isRecording = false
                    _scoreState.value = _scoreState.value.copy(isMicActive = false, currentGrade = "MIC ERROR")
                    return@Thread
                }

                audioRecord.startRecording()
                val audioBuffer = ShortArray(2048)

                while (isRecording && !Thread.currentThread().isInterrupted) {
                    val readSamples = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                    if (readSamples <= 0) continue

                    // 1. Calculate RMS Level for VAD & VU Meter
                    var sumSquares = 0.0
                    for (i in 0 until readSamples) {
                        val norm = audioBuffer[i].toDouble() / 32768.0
                        sumSquares += norm * norm
                    }
                    val rms = sqrt(sumSquares / readSamples).toFloat()
                    val normalizedLevel = (rms * 5.0f).coerceIn(0f, 1f)

                    // 2. Pitch Detection if voice activity detected
                    if (rms > 0.015f) {
                        val pitchHz = detectPitchYin(audioBuffer, readSamples, sampleRate)
                        if (pitchHz in 65f..1200f) {
                            val singerNote = AiVocalIsolator.frequencyToNoteName(pitchHz)
                            val refPitch = getReferencePitch()
                            val refNote = getReferenceNote()

                            evaluateSingingPitch(pitchHz, singerNote, refPitch, refNote, normalizedLevel)
                        } else {
                            _scoreState.value = _scoreState.value.copy(
                                micLevel = normalizedLevel,
                                singerPitchHz = 0f,
                                singerNote = "--"
                            )
                        }
                    } else {
                        // Silence
                        _scoreState.value = _scoreState.value.copy(
                            micLevel = normalizedLevel,
                            singerPitchHz = 0f,
                            singerNote = "--",
                            currentGrade = if (accumulatedScore > 0) "..." else "SING INTO MIC 🎤"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in Karaoke microphone recording thread: ${e.message}", e)
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {}
            }
        }, "KaraokeMicScorerThread").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    /**
     * Evaluates live singer pitch against reference musical key / pitch.
     */
    private fun evaluateSingingPitch(
        singerHz: Float,
        singerNote: String,
        refHz: Float,
        refNote: String,
        micLevel: Float
    ) {
        val now = System.currentTimeMillis()
        if (now - lastScoreUpdateTimeMs < 120) {
            // Update live telemetry without ticking score too fast
            _scoreState.value = _scoreState.value.copy(
                singerPitchHz = singerHz,
                singerNote = singerNote,
                targetNote = refNote,
                micLevel = micLevel
            )
            return
        }
        lastScoreUpdateTimeMs = now

        // Calculate pitch discrepancy in cents (octave invariant)
        val targetHz = if (refHz > 0f) refHz else 440f
        val semitonesDiff = (12.0 * log2(singerHz.toDouble() / targetHz.toDouble())).toFloat()
        val octaveFoldedSemitones = ((semitonesDiff % 1.0f) + 1.0f) % 1.0f
        val centsDist = (if (octaveFoldedSemitones > 0.5f) 1.0f - octaveFoldedSemitones else octaveFoldedSemitones) * 100.0f

        totalEvaluated++
        val grade: String
        val pointsToAdd: Int

        when {
            centsDist <= 28f -> {
                grade = "PERFECT! 🌟"
                pointsToAdd = 100
                currentCombo++
                notesHit++
            }
            centsDist <= 55f -> {
                grade = "GREAT! ✨"
                pointsToAdd = 75
                currentCombo++
                notesHit++
            }
            centsDist <= 95f -> {
                grade = "GOOD! 👍"
                pointsToAdd = 50
                notesHit++
            }
            else -> {
                grade = "TRY AGAIN"
                pointsToAdd = 0
                currentCombo = 0
            }
        }

        if (currentCombo > maxCombo) maxCombo = currentCombo
        accumulatedScore += pointsToAdd

        val normalizedScore = if (totalEvaluated > 0) {
            ((accumulatedScore.toFloat() / (totalEvaluated * 100f)) * 100f).roundToInt().coerceIn(0, 100)
        } else 0

        // Feed the same per-sample point value into the current line's
        // running average — see class doc comment for why this is what
        // turns continuous pitch matching into a per-line grade.
        val lineAccuracyPercent = synchronized(lineLock) {
            if (currentLineIndex >= 0) {
                currentLineSampleSum += pointsToAdd.toFloat()
                currentLineSampleCount++
                (currentLineSampleSum / currentLineSampleCount).roundToInt().coerceIn(0, 100)
            } else {
                0
            }
        }

        _scoreState.value = _scoreState.value.copy(
            singerPitchHz = singerHz,
            singerNote = singerNote,
            targetNote = if (refNote != "--") refNote else singerNote,
            pitchDeltaCents = centsDist,
            micLevel = micLevel,
            currentGrade = grade,
            totalScore = normalizedScore,
            combo = currentCombo,
            maxCombo = maxCombo,
            notesHitCount = notesHit,
            totalNotesEvaluated = totalEvaluated,
            currentLineAccuracyPercent = lineAccuracyPercent
        )
    }

    /**
     * Pitch detection using autocorrelation with difference function and parabolic peak interpolation.
     */
    private fun detectPitchYin(pcm: ShortArray, length: Int, sampleRate: Int): Float {
        val windowSize = length.coerceAtMost(1024)
        if (windowSize < 256) return 0f

        val minLag = (sampleRate / 1100).coerceAtLeast(2) // 1100 Hz max
        val maxLag = (sampleRate / 65).coerceAtMost(windowSize / 2) // 65 Hz min

        val diff = FloatArray(maxLag + 1)
        var runningSum = 0f
        diff[0] = 1f

        for (tau in 1..maxLag) {
            var sum = 0f
            for (j in 0 until windowSize - tau step 2) {
                val delta = pcm[j].toFloat() - pcm[j + tau].toFloat()
                sum += delta * delta
            }
            runningSum += sum
            diff[tau] = if (runningSum > 0f) sum * tau / runningSum else 1f
        }

        val threshold = 0.15f
        var bestTau = -1
        for (tau in minLag..maxLag) {
            if (diff[tau] < threshold) {
                while (tau + 1 <= maxLag && diff[tau + 1] < diff[tau]) {
                    bestTau = tau + 1
                    break
                }
                if (bestTau == -1) bestTau = tau
                break
            }
        }

        if (bestTau == -1) {
            // Find global minimum if below threshold not found
            var minVal = Float.MAX_VALUE
            for (tau in minLag..maxLag) {
                if (diff[tau] < minVal) {
                    minVal = diff[tau]
                    bestTau = tau
                }
            }
            if (minVal > 0.40f) return 0f
        }

        if (bestTau in 1 until maxLag) {
            val s0 = diff[bestTau - 1]
            val s1 = diff[bestTau]
            val s2 = diff[bestTau + 1]
            val interpolatedTau = bestTau + (s2 - s0) / (2f * (2f * s1 - s2 - s0).coerceAtLeast(1e-6f))
            return sampleRate / interpolatedTau
        }

        return sampleRate.toFloat() / bestTau
    }

    fun stop() {
        isRecording = false
        recordThread?.interrupt()
        recordThread = null
        synchronized(lineLock) {
            finalizeCurrentLineLocked()
            currentLineIndex = -1
        }
        _scoreState.value = _scoreState.value.copy(isMicActive = false, currentGrade = "STOPPED")
    }

    fun resetScore() {
        accumulatedScore = 0
        currentCombo = 0
        maxCombo = 0
        notesHit = 0
        totalEvaluated = 0
        synchronized(lineLock) {
            currentLineIndex = -1
            currentLineText = ""
            currentLineSampleSum = 0f
            currentLineSampleCount = 0
        }
        _scoreState.value = _scoreState.value.copy(
            totalScore = 0,
            combo = 0,
            maxCombo = 0,
            notesHitCount = 0,
            totalNotesEvaluated = 0,
            currentGrade = "READY",
            currentLyricText = "",
            currentLineAccuracyPercent = 0,
            lineScores = emptyList()
        )
    }

    companion object {
        private const val TAG = "KaraokeMicScorer"
        /** Caps [KaraokeScoreState.lineScores] so a long song's per-line
         *  history doesn't grow unboundedly — only the most recent lines
         *  are useful for display anyway. */
        const val MAX_LINE_HISTORY = 50
    }
}

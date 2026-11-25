package com.windstrom5.rebit.domain.model

enum class ScanPhase { IDLE, LISTING, READING, ENRICHING, DONE, FAILED }

/** Live state of a library scan, shown in the Library screen and the scan notification. */
data class ScanProgress(
    val phase: ScanPhase = ScanPhase.IDLE,
    /** Audio files found so far while listing folders. */
    val discovered: Int = 0,
    /** New or changed files that need reading, and how many are done. */
    val total: Int = 0,
    val processed: Int = 0,
    /** Files skipped because they were unchanged since the last scan. */
    val unchanged: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val libraryCount: Int = 0,
    val error: String? = null
) {
    val isRunning: Boolean
        get() = phase == ScanPhase.LISTING || phase == ScanPhase.READING || phase == ScanPhase.ENRICHING

    /** 0..1 while reading, or null when the amount of work isn't known yet. */
    val fraction: Float? get() = if (phase == ScanPhase.READING && total > 0) processed.toFloat() / total else null
}

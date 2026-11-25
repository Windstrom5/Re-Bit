package com.windstrom5.rebit.playback

import com.windstrom5.rebit.domain.model.PcmFormat
import java.util.concurrent.atomic.AtomicReference

/**
 * An [AudioDecoder] that can splice the next track onto the current one at the sample level,
 * so the engine's output stream never stops between tracks.
 *
 * How it stays gapless *and* bit-perfect: the engine only ever sees one continuous PCM stream.
 * When the current decoder reports end of stream, this switches to a decoder that was opened and
 * primed ahead of time (see [offerNext]) and keeps returning bytes from it in the very same
 * [read] call — no AudioTrack teardown, no USB altsetting re-negotiation, no silence. Splicing is
 * only done between tracks of identical [PcmFormat]; anything else falls back to the normal
 * stop/start transition, which the caller detects as a plain end of stream (-1).
 *
 * Threading: [read], [seek] and the [onTransition] callback run on the engine's playback thread.
 * [offerNext] / [withdrawNext] are called from the controller's coroutines; the pending slot is
 * an atomic reference, so the two sides never block each other.
 */
class GaplessDecoder(
    initial: AudioDecoder,
    /** The format the engine was started with; only an exact match may be spliced on. */
    val streamFormat: PcmFormat,
    private val onTransition: (Prepared) -> Unit
) : AudioDecoder {

    /** A next-track decoder that is already open (and seeked to 0) and ready to read. */
    class Prepared(
        val decoder: AudioDecoder,
        val format: PcmFormat,
        /** Opaque caller data identifying which track this is (e.g. the queue entry). */
        val tag: Any?
    )

    @Volatile private var current: AudioDecoder = initial
    private val pending = AtomicReference<Prepared?>(null)

    /** Number of track boundaries spliced so far. */
    @Volatile var transitions: Int = 0
        private set

    val hasPendingNext: Boolean get() = pending.get() != null

    /**
     * Queues [next] to be spliced on at end of stream. Returns false (leaving ownership of
     * [next] with the caller, who must close it) if its format doesn't match or a next track is
     * already queued.
     */
    fun offerNext(next: Prepared): Boolean {
        if (next.format != streamFormat) return false
        return pending.compareAndSet(null, next)
    }

    /** Removes and returns the queued next track (the caller closes it), e.g. when the queue changes. */
    fun withdrawNext(): Prepared? = pending.getAndSet(null)

    override fun open(uriString: String): PcmFormat = current.open(uriString)

    override fun read(buffer: ByteArray): Int {
        var guard = 0
        while (true) {
            val n = current.read(buffer)
            if (n != -1) return n
            val next = pending.getAndSet(null) ?: return -1
            val finished = current
            current = next.decoder
            transitions++
            try { finished.close() } catch (_: Throwable) { /* best effort */ }
            onTransition(next)
            // A zero-length next file would otherwise loop forever.
            if (++guard > 8) return -1
        }
    }

    override fun seek(positionMs: Long) = current.seek(positionMs)

    override fun close() {
        withdrawNext()?.let { runCatching { it.decoder.close() } }
        current.close()
    }
}

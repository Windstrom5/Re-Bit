package com.windstrom5.rebit.playback

import com.windstrom5.rebit.domain.model.PcmFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GaplessDecoderTest {

    /** Serves [bytes] in reads of at most [maxRead] bytes, then -1. */
    private class FakeDecoder(private val bytes: ByteArray, private val maxRead: Int = 7) : AudioDecoder {
        var pos = 0
        var closed = false
        override fun open(uriString: String) = PcmFormat(44_100, 16, 2)
        override fun read(buffer: ByteArray): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(buffer.size, maxRead, bytes.size - pos)
            System.arraycopy(bytes, pos, buffer, 0, n)
            pos += n
            return n
        }
        override fun seek(positionMs: Long) { pos = 0 }
        override fun close() { closed = true }
    }

    private val fmt = PcmFormat(44_100, 16, 2)

    private fun drain(d: AudioDecoder): ByteArray {
        val out = ArrayList<Byte>()
        val buf = ByteArray(16)
        while (true) {
            val n = d.read(buf)
            if (n == -1) break
            for (i in 0 until n) out += buf[i]
        }
        return out.toByteArray()
    }

    @Test
    fun `splices the next track into one continuous stream`() {
        val a = FakeDecoder(ByteArray(40) { 1 })
        val b = FakeDecoder(ByteArray(25) { 2 })
        var transitions = 0
        val g = GaplessDecoder(a, fmt) { transitions++ }
        assertTrue(g.offerNext(GaplessDecoder.Prepared(b, fmt, "b")))

        val all = drain(g)
        assertEquals(65, all.size)
        assertTrue(all.take(40).all { it == 1.toByte() })
        assertTrue(all.drop(40).all { it == 2.toByte() })
        assertEquals(1, transitions)
        assertEquals(1, g.transitions)
        assertTrue("finished decoder is closed at the splice", a.closed)
    }

    @Test
    fun `transition callback receives the prepared tag`() {
        val b = FakeDecoder(ByteArray(4))
        var tag: Any? = null
        val g = GaplessDecoder(FakeDecoder(ByteArray(4)), fmt) { tag = it.tag }
        g.offerNext(GaplessDecoder.Prepared(b, fmt, "next-track"))
        drain(g)
        assertEquals("next-track", tag)
    }

    @Test
    fun `ends normally when nothing is queued`() {
        var transitions = 0
        val g = GaplessDecoder(FakeDecoder(ByteArray(10)), fmt) { transitions++ }
        assertEquals(10, drain(g).size)
        assertEquals(0, transitions)
    }

    @Test
    fun `refuses a different format`() {
        val g = GaplessDecoder(FakeDecoder(ByteArray(10)), fmt) {}
        assertFalse(g.offerNext(GaplessDecoder.Prepared(FakeDecoder(ByteArray(4)), PcmFormat(96_000, 24, 2), null)))
        assertFalse(g.hasPendingNext)
    }

    @Test
    fun `refuses a second pending track`() {
        val g = GaplessDecoder(FakeDecoder(ByteArray(10)), fmt) {}
        assertTrue(g.offerNext(GaplessDecoder.Prepared(FakeDecoder(ByteArray(4)), fmt, 1)))
        assertFalse(g.offerNext(GaplessDecoder.Prepared(FakeDecoder(ByteArray(4)), fmt, 2)))
    }

    @Test
    fun `withdrawn track is not played`() {
        val b = FakeDecoder(ByteArray(10) { 9 })
        val g = GaplessDecoder(FakeDecoder(ByteArray(10)), fmt) {}
        g.offerNext(GaplessDecoder.Prepared(b, fmt, null))
        val withdrawn = g.withdrawNext()
        assertTrue(withdrawn?.decoder === b)
        assertEquals(10, drain(g).size)
    }

    @Test
    fun `close also closes a pending next track`() {
        val a = FakeDecoder(ByteArray(10))
        val b = FakeDecoder(ByteArray(10))
        val g = GaplessDecoder(a, fmt) {}
        g.offerNext(GaplessDecoder.Prepared(b, fmt, null))
        g.close()
        assertTrue(a.closed)
        assertTrue(b.closed)
    }

    @Test
    fun `an empty next file does not stall the stream`() {
        val g = GaplessDecoder(FakeDecoder(ByteArray(5)), fmt) {}
        g.offerNext(GaplessDecoder.Prepared(FakeDecoder(ByteArray(0)), fmt, null))
        assertEquals(5, drain(g).size)
    }
}

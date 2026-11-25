package com.windstrom5.rebit.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingLogicTest {

    @Test
    fun qobuzSignature_matchesReferenceMd5() {
        // md5("trackgetFileUrlformat_id27intentstreamtrack_id1234567891700000000SECRET")
        val sig = QobuzSigner.signFileUrl("123456789", 27, 1_700_000_000L, "SECRET")
        assertEquals("5f71379bd038f1e5255fa4901563be64", sig)
    }

    @Test
    fun qobuzSignature_changesWithFormat() {
        val a = QobuzSigner.signFileUrl("1", 6, 100L, "s")
        val b = QobuzSigner.signFileUrl("1", 27, 100L, "s")
        assertFalse(a == b)
    }

    @Test
    fun streamUri_roundTrips() {
        val uri = StreamSourceResolver.buildUri(StreamingProvider.TIDAL, "98765")
        assertEquals("stream://tidal/98765", uri)
        assertTrue(StreamSourceResolver.isStreamUri(uri))
        assertEquals(StreamingProvider.TIDAL to "98765", StreamSourceResolver.parse(uri))
    }

    @Test
    fun streamUri_rejectsMalformedAndLocalPaths() {
        assertNull(StreamSourceResolver.parse("content://media/external/audio/1"))
        assertNull(StreamSourceResolver.parse("stream://qobuz/"))
        assertNull(StreamSourceResolver.parse("stream://unknown/1"))
        assertFalse(StreamSourceResolver.isStreamUri("/storage/emulated/0/Music/a.flac"))
    }

    @Test
    fun qualityLabel_distinguishesHiResFromCdAndLossy() {
        fun t(rate: Int, depth: Int) = StreamTrack(
            StreamingProvider.QOBUZ, "1", "t", "a", null, 1000L, null, rate, depth
        )
        assertEquals("CD 16-bit / 44.1 kHz", t(44_100, 16).qualityLabel)
        assertEquals("24-bit / 96 kHz", t(96_000, 24).qualityLabel)
        assertEquals("24-bit / 88.2 kHz", t(88_200, 24).qualityLabel)
        assertEquals("Lossy", t(44_100, 0).qualityLabel)
    }
}

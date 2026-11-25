package com.windstrom5.rebit.playback

import com.windstrom5.rebit.data.scanner.ScanPlanner
import com.windstrom5.rebit.domain.model.AudioFileFormat
import com.windstrom5.rebit.domain.model.dsdRateLabel
import com.windstrom5.rebit.playback.dsd.DsdContainer
import com.windstrom5.rebit.playback.dsd.DsdFrameReader
import com.windstrom5.rebit.playback.dsd.DsdInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FormatParsingTest {

    // ── helpers ───────────────────────────────────────────────────────────────────────────
    private fun ByteArrayOutputStream.ascii(s: String) = write(s.toByteArray(Charsets.US_ASCII))
    private fun ByteArrayOutputStream.be16(v: Int) { write(v shr 8); write(v) }
    private fun ByteArrayOutputStream.be32(v: Long) { for (s in intArrayOf(24, 16, 8, 0)) write((v shr s).toInt()) }
    private fun ByteArrayOutputStream.be64(v: Long) { be32(v ushr 32); be32(v and 0xFFFFFFFFL) }
    private fun ByteArrayOutputStream.le32(v: Long) { for (s in intArrayOf(0, 8, 16, 24)) write((v shr s).toInt()) }
    private fun ByteArrayOutputStream.le64(v: Long) { le32(v and 0xFFFFFFFFL); le32(v ushr 32) }

    /** 80-bit extended for an integer sample rate. */
    private fun extended(rate: Int): ByteArray {
        val e = 16383 + 31 - Integer.numberOfLeadingZeros(rate)
        val mantissa = rate.toLong() shl (63 - (31 - Integer.numberOfLeadingZeros(rate)))
        val b = ByteArray(10)
        b[0] = (e shr 8).toByte(); b[1] = e.toByte()
        for (i in 0 until 8) b[2 + i] = (mantissa ushr (56 - 8 * i)).toByte()
        return b
    }

    private fun aiff(rate: Int, bits: Int, channels: Int, frames: Int, aifc: String? = null, extraOddChunk: Boolean = false): ByteArray {
        val body = ByteArrayOutputStream()
        body.ascii(if (aifc != null) "AIFC" else "AIFF")
        if (extraOddChunk) { body.ascii("ANNO"); body.be32(3); body.ascii("abc"); body.write(0) }
        body.ascii("COMM")
        body.be32(if (aifc != null) 22 else 18)
        body.be16(channels); body.be32(frames.toLong()); body.be16(bits); body.write(extended(rate))
        if (aifc != null) body.ascii(aifc)
        body.ascii("SSND")
        val bytes = frames * channels * ((bits + 7) / 8)
        body.be32(8L + bytes); body.be32(0); body.be32(0)
        repeat(bytes) { body.write(it) }
        val out = ByteArrayOutputStream()
        out.ascii("FORM"); out.be32(body.size().toLong()); out.write(body.toByteArray())
        return out.toByteArray()
    }

    // ── AIFF ──────────────────────────────────────────────────────────────────────────────
    @Test
    fun `AIFF header is parsed including the 80-bit sample rate`() {
        for (rate in intArrayOf(44_100, 48_000, 96_000, 192_000)) {
            val h = AiffHeader.parse(ByteArrayInputStream(aiff(rate, 24, 2, 1000)))
            assertNotNull(h)
            assertEquals(rate, h!!.sampleRateHz)
            assertEquals(24, h.bitDepth)
            assertEquals(2, h.channels)
            assertEquals(1000L, h.frames)
            assertFalse(h.littleEndian)
        }
    }

    @Test
    fun `AIFF data offset accounts for odd-sized chunks`() {
        val bytes = aiff(44_100, 16, 2, 10, extraOddChunk = true)
        val h = AiffHeader.parse(ByteArrayInputStream(bytes))!!
        // First sample byte is 0 in our synthetic payload.
        assertEquals(0, bytes[h.dataOffset.toInt()].toInt())
        assertEquals(1, bytes[h.dataOffset.toInt() + 1].toInt())
    }

    @Test
    fun `AIFC sowt is little-endian and compressed AIFC is rejected`() {
        assertTrue(AiffHeader.parse(ByteArrayInputStream(aiff(48_000, 16, 2, 4, aifc = "sowt")))!!.littleEndian)
        assertFalse(AiffHeader.parse(ByteArrayInputStream(aiff(48_000, 16, 2, 4, aifc = "NONE")))!!.littleEndian)
        assertNull(AiffHeader.parse(ByteArrayInputStream(aiff(48_000, 16, 2, 4, aifc = "ima4"))))
        assertNull(AiffHeader.parse(ByteArrayInputStream("RIFF0000WAVE".toByteArray())))
    }

    // ── DSF ───────────────────────────────────────────────────────────────────────────────
    private fun dsf(channels: Int, blockSize: Int, bytesPerChannel: Int, lsbFirst: Boolean, fill: (ch: Int, i: Int) -> Int): ByteArray {
        val blocks = (bytesPerChannel + blockSize - 1) / blockSize
        val data = ByteArray(blocks * blockSize * channels)
        for (ch in 0 until channels) for (i in 0 until bytesPerChannel) {
            val group = i / blockSize
            data[(group * channels + ch) * blockSize + i % blockSize] = fill(ch, i).toByte()
        }
        val out = ByteArrayOutputStream()
        out.ascii("DSD "); out.le64(28); out.le64(0); out.le64(0)
        out.ascii("fmt "); out.le64(52)
        out.le32(1); out.le32(0); out.le32(2); out.le32(channels.toLong()); out.le32(2_822_400)
        out.le32(if (lsbFirst) 1 else 8); out.le64(bytesPerChannel * 8L); out.le32(blockSize.toLong()); out.le32(0)
        out.ascii("data"); out.le64(12L + data.size)
        out.write(data)
        return out.toByteArray()
    }

    @Test
    fun `DSF blocks are de-interleaved, bit-reversed and cut at the real length`() {
        val bpc = 10_000 // not a multiple of the 4096 block
        val bytes = dsf(2, 4096, bpc, lsbFirst = true) { ch, i -> if (ch == 0) 0x01 else (i and 0xFF) }
        val input = ByteArrayInputStream(bytes)
        val info = DsdContainer.parse(input)!!
        assertEquals(2_822_400, info.dsdRateHz)
        assertEquals(2, info.channels)
        assertEquals(bpc.toLong(), info.bytesPerChannel)
        assertEquals(92L, info.dataOffset)
        assertEquals(DsdInfo.Layout.DSF_BLOCKS, info.layout)

        val reader = DsdFrameReader(info, input)
        val buf = ByteArray(2 * 20_000)
        var total = 0
        while (true) {
            val n = reader.read(buf.copyOfRange(0, buf.size).also { }, 3000)
            if (n == 0) break
            total += n
        }
        assertEquals(bpc, total)

        // Re-read and check content of the first frames.
        val r2 = DsdFrameReader(info, ByteArrayInputStream(bytes, 92, bytes.size - 92))
        val out = ByteArray(2 * 5000)
        assertEquals(5000, r2.read(out, 5000))
        assertEquals(0x80.toByte(), out[0])                       // 0x01 LSB-first → 0x80 MSB-first
        assertEquals(DsdFrameReader.REVERSE[4097 and 0xFF], out[2 * 4097 + 1]) // crosses into block group 2
    }

    @Test
    fun `DSF seek lands on the right frame`() {
        val bytes = dsf(2, 4096, 9000, lsbFirst = false) { ch, i -> ((i / 7) + ch) and 0xFF }
        val info = DsdContainer.parse(ByteArrayInputStream(bytes))!!
        val frame = 5000L
        val stream = ByteArrayInputStream(bytes)
        DsdContainer.skipFully(stream, DsdFrameReader.seekOffset(info, frame))
        val r = DsdFrameReader(info, stream, frame)
        val out = ByteArray(2)
        assertEquals(1, r.read(out, 1))
        assertEquals((((5000 / 7) + 0) and 0xFF).toByte(), out[0])
        assertEquals((((5000 / 7) + 1) and 0xFF).toByte(), out[1])
    }

    // ── DFF ───────────────────────────────────────────────────────────────────────────────
    @Test
    fun `DFF header and interleaved data are read`() {
        val prop = ByteArrayOutputStream()
        prop.ascii("SND ")
        prop.ascii("FS  "); prop.be64(4); prop.be32(5_644_800)
        prop.ascii("CHNL"); prop.be64(10); prop.be16(2); prop.ascii("SLFTSRGT")
        prop.ascii("CMPR"); prop.be64(14); prop.ascii("DSD "); prop.write(9); prop.ascii("not comp"); prop.write(0)
        val body = ByteArrayOutputStream()
        body.ascii("DSD ")
        body.ascii("FVER"); body.be64(4); body.be32(0x01050000)
        body.ascii("PROP"); body.be64(prop.size().toLong()); body.write(prop.toByteArray())
        body.ascii("DSD "); body.be64(8); body.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val file = ByteArrayOutputStream()
        file.ascii("FRM8"); file.be64(body.size().toLong()); file.write(body.toByteArray())

        val input = ByteArrayInputStream(file.toByteArray())
        val info = DsdContainer.parse(input)!!
        assertEquals(5_644_800, info.dsdRateHz)
        assertEquals(2, info.channels)
        assertEquals(4L, info.bytesPerChannel)
        assertEquals(DsdInfo.Layout.DFF_INTERLEAVED, info.layout)
        assertEquals(128, info.multiple)
        val out = ByteArray(8)
        assertEquals(4, DsdFrameReader(info, input).read(out, 10))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), out)
    }

    // ── DoP ───────────────────────────────────────────────────────────────────────────────
    @Test
    fun `DoP puts the older byte in the middle and alternates markers`() {
        // 4 frames, stereo: L bytes 0x10,0x11,0x12,0x13 ; R bytes 0x20..0x23
        val frames = byteArrayOf(0x10, 0x20, 0x11, 0x21, 0x12, 0x22, 0x13, 0x23)
        val out = DopPacker().packInterleaved(frames, 4, 2)
        assertEquals(12, out.size)
        // sample 0, left: [newer=0x11, older=0x10, 0x05]
        assertArrayEquals(byteArrayOf(0x11, 0x10, 0x05), out.copyOfRange(0, 3))
        assertArrayEquals(byteArrayOf(0x21, 0x20, 0x05), out.copyOfRange(3, 6))
        assertArrayEquals(byteArrayOf(0x13, 0x12, 0xFA.toByte()), out.copyOfRange(6, 9))
        assertArrayEquals(byteArrayOf(0x23, 0x22, 0xFA.toByte()), out.copyOfRange(9, 12))
    }

    // ── misc ──────────────────────────────────────────────────────────────────────────────
    @Test
    fun `ALAC cookie bit depth`() {
        val cookie = byteArrayOf(0, 0, 0x10, 0, 0, 24, 40, 10, 14, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(24, MediaCodecDecoder.alacCookieBitDepth(cookie))
        val withAtom = byteArrayOf(0, 0, 0, 36, 'a'.code.toByte(), 'l'.code.toByte(), 'a'.code.toByte(), 'c'.code.toByte(), 0, 0, 0, 0) + cookie
        assertEquals(24, MediaCodecDecoder.alacCookieBitDepth(withAtom))
        assertEquals(16, MediaCodecDecoder.alacCookieBitDepth(byteArrayOf(1, 2)))
    }

    @Test
    fun `scanner accepts every supported extension`() {
        for (n in listOf("a.flac", "b.WAV", "c.aiff", "d.aif", "e.dsf", "f.DFF", "g.m4a", "h.mp3", "i.opus", "j.ogg"))
            assertTrue(n, ScanPlanner.isSupportedAudio(n))
        assertFalse(ScanPlanner.isSupportedAudio("cover.jpg"))
        assertFalse(ScanPlanner.isSupportedAudio("notes.txt"))
    }

    @Test
    fun `format helpers`() {
        assertEquals("DSD64", dsdRateLabel(2_822_400))
        assertEquals("DSD256", dsdRateLabel(11_289_600))
        assertNull(dsdRateLabel(192_000))
        assertTrue(AudioFileFormat.DSF.isDsd)
        assertFalse(AudioFileFormat.MP3.isLossless)
        assertEquals(AudioFileFormat.AIFF, AudioFileFormat.fromName("aiff"))
        assertEquals(AudioFileFormat.AAC, AudioDecoders.formatForName("x.aac"))
        assertEquals(AudioFileFormat.DFF, AudioDecoders.formatForName("x.dff"))
    }
}

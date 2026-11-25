package com.windstrom5.rebit.data.scanner

import com.windstrom5.rebit.domain.model.ScanPhase
import com.windstrom5.rebit.domain.model.ScanProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScanLogicTest {
    private fun d(path: String, size: Long = 100, date: Long = 5) =
        DiscoveredFile(path, path.substringAfterLast('/'), size, date)

    private fun vorbis(vendor: String, vararg kv: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(i: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array())
        val v = vendor.toByteArray(); le(v.size); out.write(v); le(kv.size)
        for (s in kv) { val b = s.toByteArray(); le(b.size); out.write(b) }
        return out.toByteArray()
    }

    @Test fun unchangedFilesAreNotReread() {
        val existing = mapOf("a" to ExistingStamp(1, 100, 5), "b" to ExistingStamp(2, 100, 5))
        val p = ScanPlanner.plan(existing, listOf(d("a"), d("b", size = 999), d("new")))
        assertEquals(1, p.unchanged)
        assertEquals(listOf("b", "new"), p.toRead.map { it.path })
    }

    @Test fun largeLibraryRescanOnlyReadsWhatChanged() {
        val big = (1..20_000).associate { "p$it" to ExistingStamp(it.toLong(), 10, 1) }
        val found = (1..19_990).map { d("p$it", 10, 1) } + (1..10).map { d("fresh$it", 10, 1) }
        val p = ScanPlanner.plan(big, found)
        assertEquals(10, p.toRead.size)
        assertEquals(19_990, p.unchanged)
        assertEquals(10, p.removedPaths.size)
    }

    @Test fun pruningIsConservative() {
        val existing = mapOf("a" to ExistingStamp(1, 1, 1), "sd:x" to ExistingStamp(2, 1, 1), "gone" to ExistingStamp(3, 1, 1))
        assertEquals(listOf("gone"), ScanPlanner.plan(existing, listOf(d("a")), protectedPrefixes = listOf("sd:")).removedPaths)
        assertTrue(ScanPlanner.plan(existing, emptyList()).removedPaths.isEmpty())
        assertTrue(ScanPlanner.plan(existing, listOf(d("a")), allowPrune = false).removedPaths.isEmpty())
    }

    @Test fun onlyPlayableContainersAreListed() {
        assertTrue(ScanPlanner.isSupportedAudio("A.FLAC") && ScanPlanner.isSupportedAudio("b.wav"))
        // Lossy and DSD formats are playable now too.
        assertTrue(ScanPlanner.isSupportedAudio("c.mp3") && ScanPlanner.isSupportedAudio("d.m4a") && ScanPlanner.isSupportedAudio("e.dsf"))
        assertFalse(ScanPlanner.isSupportedAudio("cover.png") || ScanPlanner.isSupportedAudio("playlist.m3u"))
    }

    @Test fun parsesVorbisComments() {
        val c = VorbisComments.parse(vorbis("libFLAC", "TITLE=Creep", "artist=Radiohead", "ARTIST=ignored"))
        assertEquals("Creep", VorbisComments.title(c))
        assertEquals("Radiohead", VorbisComments.artist(c))
        assertEquals("Muse", VorbisComments.artist(VorbisComments.parse(vorbis("v", "ALBUMARTIST=Muse"))))
    }

    @Test fun corruptTagsNeverCrash() {
        assertTrue(VorbisComments.parse(ByteArray(0)).isEmpty())
        assertTrue(VorbisComments.parse(ByteArray(7) { 0x7F }).isEmpty())
        val evil = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(Int.MAX_VALUE).putInt(Int.MAX_VALUE).array()
        assertTrue(VorbisComments.parse(evil).isEmpty()) // used to overflow p + len
    }

    @Test fun resolvesTitleAndArtist() {
        assertEquals("Creep" to "Radiohead", TagResolver.resolve("x.flac", "Creep", "Radiohead"))
        assertEquals("Hey Jude - Remastered 2015" to null, TagResolver.resolve("x.flac", "Hey Jude - Remastered 2015", null))
        assertEquals("Creep" to "Radiohead", TagResolver.resolve("Creep - Radiohead.flac", null, null))
        assertEquals("Creep" to "Radiohead", TagResolver.resolve("03 - Creep - Radiohead.flac", null, null))
        assertEquals("Song Name" to null, TagResolver.resolve("Song Name.wav", null, "Unknown Artist"))
    }

    @Test fun progressFractionAndRunningState() {
        assertEquals(0.25f, ScanProgress(ScanPhase.READING, total = 200, processed = 50).fraction)
        assertNull(ScanProgress(ScanPhase.LISTING).fraction)
        assertTrue(ScanProgress(ScanPhase.READING).isRunning)
        assertFalse(ScanProgress(ScanPhase.DONE).isRunning)
    }
}

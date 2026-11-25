package com.windstrom5.rebit.playback.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NOTE ON VERIFICATION: romanize() tries Kuromoji (real morphological
 * analysis + word-boundary tokenization) for essentially all Japanese text
 * now, not just kanji-containing lines — falling back to the legacy
 * per-character dictionary (kanji only, no word spacing) if Kuromoji is
 * unavailable. This environment has no network access to actually fetch/run
 * Kuromoji, so its exact tokenization/reading/word-boundary output for these
 * specific strings could not be verified — including which single words it
 * treats as one token vs several. Every assertion below is deliberately a
 * structural/substring check rather than an exact full-string match, for
 * that reason: asserting an exact string neither of us can verify would
 * just be a guess wearing a test's clothes.
 */
class LyricsRomanizerTest {

    @Test
    fun testDetectsNonRomaji() {
        assertTrue(LyricsRomanizer.hasNonRomaji("君の心へ届くまで"))
        assertTrue(LyricsRomanizer.hasNonRomaji("ありがとう"))
        assertTrue(LyricsRomanizer.hasNonRomaji("사랑해요"))
        assertFalse(LyricsRomanizer.hasNonRomaji("Hello World 123!"))
    }

    @Test
    fun testJapaneseHiraganaRomanizationRoughShape() {
        val romanized = LyricsRomanizer.romanize("ありがとう")
        assertTrue(romanized.contains("arigatou") || romanized.replace(" ", "") == "arigatou")
    }

    @Test
    fun testJapaneseKatakanaRomanizationRoughShape() {
        val romanized = LyricsRomanizer.romanize("メロディー")
        assertTrue(romanized.contains("merodi") || romanized.replace(" ", "").startsWith("merodi"))
    }

    @Test
    fun testJapaneseSokuonProducesDoubledConsonant() {
        // ちょっとまって ("wait a moment") — whatever the word boundary,
        // sokuon (っ) doubling the following consonant should still show up
        // as a doubled letter somewhere in the output.
        val romanized = LyricsRomanizer.romanize("ちょっとまって")
        val noSpaces = romanized.replace(" ", "")
        assertTrue("Expected sokuon doubling in: $romanized", noSpaces.contains("tt"))
    }

    @Test
    fun testJapaneseKanjiLyricMap() {
        // 私/夢/愛 are common, unambiguous single-kanji words — any reasonable
        // tokenizer (Kuromoji or the legacy fallback) should read these the
        // same way, so this stays a reasonably safe substring check.
        val romanized = LyricsRomanizer.romanize("私の夢と愛")
        assertTrue(romanized.contains("watashi"))
        assertTrue(romanized.contains("yume"))
        assertTrue(romanized.contains("ai"))
        assertTrue(romanized.none { it.code in 0x4E00..0x9FFF })
    }

    @Test
    fun testKoreanHangulRomanization() {
        // Korean isn't tokenized (Hangul decomposition is exact by
        // construction, no word-boundary ambiguity to resolve), so this one
        // stays a real exact-match.
        val romanized = LyricsRomanizer.romanize("사랑해")
        org.junit.Assert.assertEquals("saranghae", romanized)
    }

    @Test
    fun testTopicParticleWaSoundChangeException() {
        // The topic particle は is pronounced/romanized "wa", not its literal
        // kana reading "ha" — one of the most common grammar patterns in
        // Japanese, and the previous character-lookup approach got this
        // wrong on every single line that used it.
        val romanized = LyricsRomanizer.romanize("私は歌う")
        assertTrue("Expected topic-particle は to romanize as 'wa', got: $romanized", romanized.contains("wa"))
        assertFalse(
            "Did not expect the literal (wrong) reading 'ha' glued to watashi, got: $romanized",
            romanized.replace(" ", "").contains("watashiha")
        )
    }

    @Test
    fun testMultiWordLineGetsWordBoundarySpacing() {
        // The bug this session fixed: romanized output used to be one long
        // unbroken string with no spaces at all, for every line, regardless
        // of how many words it actually contained (a 3-token, clearly
        // multi-word sentence would come out as one glued-together blob).
        // "私は歌う" is unambiguously 3 words (watashi / wa / utau) — the
        // output should contain at least one space.
        val romanized = LyricsRomanizer.romanize("私は歌う")
        assertTrue("Expected word-boundary spacing in a multi-word line, got: $romanized", romanized.contains(" "))
    }

    @Test
    fun testKanjiOutputNeverLeavesRawIdeographs() {
        val samples = listOf("見ないフリしていた", "期待はずれにうんざりだよ", "自分がいちばんわかってる", "何者にもなれないって")
        for (s in samples) {
            val romanized = LyricsRomanizer.romanize(s)
            assertTrue("Expected no raw kanji left in: $romanized (from: $s)", romanized.none { it.code in 0x4E00..0x9FFF })
            assertTrue("Expected non-empty output for: $s", romanized.isNotBlank())
        }
    }
}

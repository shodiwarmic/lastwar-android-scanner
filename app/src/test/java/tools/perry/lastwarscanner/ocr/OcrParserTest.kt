package tools.perry.lastwarscanner.ocr

import org.junit.Assert.*
import org.junit.Test

class OcrParserTest {

    private val parser = OcrParser()

    // ── signalMatches ──────────────────────────────────────────────────────
    // Implementation splits both the signal AND the pre-built token set on
    // [^a-z0-9]+, so punctuation is stripped from both sides before matching.

    @Test
    fun `single-word signal that hits a token returns true`() {
        assertTrue(parser.signalMatches("STRENGTH", setOf("strength", "ranking")))
    }

    @Test
    fun `multi-word signal where every word is present returns true`() {
        assertTrue(parser.signalMatches("Daily Rank", setOf("daily", "rank", "points")))
    }

    @Test
    fun `multi-word signal where one word is missing returns false`() {
        assertFalse(parser.signalMatches("Daily Rank", setOf("daily", "points")))
    }

    @Test
    fun `signal matching is case-insensitive mixed-case`() {
        assertTrue(parser.signalMatches("Daily Rank", setOf("daily", "rank")))
    }

    @Test
    fun `signal matching is case-insensitive all-caps`() {
        assertTrue(parser.signalMatches("DAILY RANK", setOf("daily", "rank")))
    }

    @Test
    fun `signal with trailing punctuation matches because punctuation is stripped`() {
        // "Mon." splits on [^a-z0-9]+ to ["mon"]; token set already has "mon"
        assertTrue(parser.signalMatches("Mon.", setOf("mon", "tue")))
    }

    @Test
    fun `signal punctuation stripped does not create false partial-word match`() {
        // "Mon." → ["mon"]; "monday" is a different token, not a match
        assertFalse(parser.signalMatches("Mon.", setOf("monday", "tue")))
    }

    @Test
    fun `empty signal returns false`() {
        assertFalse(parser.signalMatches("", setOf("daily", "rank")))
    }

    @Test
    fun `punctuation-only signal returns false`() {
        // "..." splits to [] after filtering empties
        assertFalse(parser.signalMatches("...", setOf("daily", "rank")))
    }

    @Test
    fun `signal against empty token set returns false`() {
        assertFalse(parser.signalMatches("Daily", emptySet()))
    }

    @Test
    fun `negative signal matching blocks layout — verified through signalMatches`() {
        // Simulates OCR text containing both page-signal and negative-signal words.
        // In parse(): negativeSignals.any { signalMatches(it, tokens) } == true → layout rejected.
        val tokens = setOf("daily", "rank", "strength", "ranking")
        val pageSignals     = listOf("Daily Rank", "Daily Ranking")
        val negativeSignals = listOf("Strength Ranking")

        val pageHit     = pageSignals.any     { parser.signalMatches(it, tokens) }
        val negativeHit = negativeSignals.any { parser.signalMatches(it, tokens) }

        assertTrue("page signal should match",     pageHit)
        assertTrue("negative signal should match", negativeHit)
        // Combined check used in parse() → layout not selected
        assertFalse("layout must be rejected when negative fires", pageHit && !negativeHit)
    }

    // ── crashSplits (regression guard) ────────────────────────────────────

    @Test
    fun `crashSplits returns candidates sorted by ascending score`() {
        // "Alpha23,456,789" has exactly two valid split boundaries:
        //   index 0: ("Alpha2", "3456789")  — rightmost boundary, smallest score
        //   index 1: ("Alpha",  "23456789") — leftmost boundary,  largest score
        // A third position (i=7) is rejected because the prefix "Alpha23," contains a comma.
        val splits = OcrParser.crashSplits("Alpha23,456,789")
        assertEquals(2, splits.size)
        assertEquals("Alpha2",   splits[0].first)
        assertEquals("3456789",  splits[0].second)
        assertEquals("Alpha",    splits[1].first)
        assertEquals("23456789", splits[1].second)
    }

    @Test
    fun `crashSplits returns empty for pure-digit input`() {
        assertTrue(OcrParser.crashSplits("323045000").isEmpty())
    }

    @Test
    fun `crashSplits returns empty for pure-letter input`() {
        assertTrue(OcrParser.crashSplits("PlayerName").isEmpty())
    }
}

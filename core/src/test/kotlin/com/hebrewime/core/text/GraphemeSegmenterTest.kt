package com.hebrewime.core.text

import java.text.BreakIterator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Expectations here are UAX #29 extended grapheme clusters — deliberately **not** whatever
 * `java.text.BreakIterator` happens to return, because it is measurably wrong about emoji on
 * this JDK. See [platformBreakIteratorStillDisagrees].
 *
 * Denominator: 30 width assertions across 17 inputs.
 */
class GraphemeSegmenterTest {

    private val thumbsUp = "👍"
    private val skinTone = "🏽"
    private val flagIl = "🇮🇱"
    private val ri = "🇮"
    private val family =
        "👨‍👩‍👧‍👦"

    private fun units(s: String) = GraphemeSegmenter.widthInCodeUnits(s, s.length)
    private fun points(s: String) = GraphemeSegmenter.widthInCodePoints(s, s.length)

    @Test
    fun asciiAndPlainHebrewDeleteOneCharacter() {
        assertEquals(1, units("a"))
        assertEquals(1, units("שלום"))
        assertEquals(1, units("ש"))
        assertEquals(1, points("שלום"))
    }

    @Test
    fun hebrewPointedStacksDeleteWhole() {
        // This is the case that matters most for this keyboard. A hardcoded width of 1 would
        // strip the qamats and leave a half-pointed word the user cannot see they still have.
        assertEquals(2, units("בּ"), "bet + dagesh")
        assertEquals(3, units("בָּ"), "bet + dagesh + qamats")
        assertEquals(3, units("אָ֑"), "alef + qamats + etnahta (te'amim)")
        assertEquals(3, points("בָּ"))
        // Only the last cluster goes, not the whole word.
        assertEquals(3, units("שלוםבָּ"))
    }

    @Test
    fun surrogatePairsAreNeverSplit() {
        assertEquals(2, units(thumbsUp), "a surrogate pair is 2 code units")
        assertEquals(1, points(thumbsUp), "but only 1 code point")
    }

    @Test
    fun skinToneModifierStaysWithItsBase() {
        assertEquals(4, units(thumbsUp + skinTone))
        assertEquals(2, points(thumbsUp + skinTone))
    }

    @Test
    fun regionalIndicatorFlagsPairUp() {
        assertEquals(4, units(flagIl), "a flag is one cluster of two regional indicators")
        assertEquals(2, points(flagIl))
        // Three in a row: the first two pair off, so the last cluster is the lone third.
        assertEquals(2, units(flagIl + ri))
        // Four in a row: two flags, so the last cluster is the second pair.
        assertEquals(4, units(flagIl + flagIl))
    }

    @Test
    fun zwjSequencesDeleteAsOneUnit() {
        assertEquals(11, units(family), "family ZWJ sequence is one grapheme")
        assertEquals(7, points(family), "4 pictographs + 3 ZWJ")
        assertEquals(11, units("שלום $family"))
    }

    @Test
    fun combiningMarksAndConjunctsAttach() {
        assertEquals(2, units("é"), "e + combining acute")
        // GB9c (Indic conjunct clusters, added in Unicode 15.1) is NOT implemented -- see
        // the class docs. Without it, virama does not join the two consonants, so this is
        // two clusters and the last one is a single consonant. A Devanagari user would need
        // two backspaces where one is ideal. That is a cosmetic shortfall in a script this
        // keyboard does not target, and it is recorded rather than papered over.
        assertEquals(1, units("न्न"), "devanagari conjunct (GB9c unimplemented)")
        assertEquals(2, units("न्"), "consonant + virama still attach via GB9")
        assertEquals(3, units("1️⃣"), "keycap: digit + VS16 + enclosing keycap")
    }

    @Test
    fun crlfIsOneCluster() {
        assertEquals(2, units("\r\n"))
        assertEquals(1, units("a\n"))
    }

    @Test
    fun emptyAndBoundaryCases() {
        assertEquals(0, units(""))
        assertEquals(0, GraphemeSegmenter.widthInCodeUnits("abc", 0))
        assertEquals(0, GraphemeSegmenter.widthInCodePoints("abc", 0))
        assertEquals(1, GraphemeSegmenter.widthInCodeUnits("abc", 1))
        assertEquals(1, GraphemeSegmenter.widthInCodeUnits("abc", 2))
    }

    @Test
    fun neverReturnsMoreThanIsAvailable() {
        // A backspace width larger than the text would make deleteSurroundingTextInCodePoints
        // eat into whatever precedes it in the editor.
        for (s in listOf("a", "שלום", thumbsUp, family, flagIl, "\r\n")) {
            for (end in 0..s.length) {
                val w = GraphemeSegmenter.widthInCodeUnits(s, end)
                assertTrue(w <= end, "width $w exceeds available $end in ${s.length}-char input")
                assertTrue(w >= 0)
            }
        }
    }

    /**
     * A deliberate tripwire, not an accident.
     *
     * `GraphemeSegmenter` exists because `java.text.BreakIterator` is wrong about emoji on
     * this JDK. If a future JDK fixes it, this test fails — which is the point: it is a prompt
     * to re-measure and decide whether this class is still earning its keep, not a bug.
     */
    @Test
    fun platformBreakIteratorStillDisagrees() {
        fun platformWidth(s: String): Int {
            val it = BreakIterator.getCharacterInstance()
            it.setText(s)
            val prev = it.preceding(s.length)
            return if (prev == BreakIterator.DONE) s.length else s.length - prev
        }
        val disagreements = listOf(thumbsUp + skinTone, flagIl, family)
            .count { platformWidth(it) != units(it) }
        assertTrue(
            disagreements > 0,
            "java.text.BreakIterator now agrees with UAX #29 on all emoji cases tested. " +
                "Re-measure and decide whether GraphemeSegmenter is still needed.",
        )
        // Hebrew, by contrast, the platform has always got right.
        for (s in listOf("בָּ", "אָ֑", "שלום")) {
            assertEquals(units(s), platformWidth(s), "Hebrew must agree: $s")
        }
    }
}

package com.hebrewime.core.lexicon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [HebrewText.stripPoints] must reduce a word exactly the way `scripts/build_lexicon.py`
 * reduced the lexicon. If these two ever diverge, every vocalized lookup silently misses --
 * a failure that produces no error, just a keyboard that underlines correct words.
 *
 * Denominator: 21 assertions, plus 165 generated over the whole U+0591..U+05C7 range.
 */
class HebrewTextTest {

    @Test
    fun stripsNiqqudFromVocalizedVerbForms() {
        // The exact form the source CSV ships, and the form the lexicon actually contains.
        assertEquals("בגדתי", HebrewText.stripPoints("בָּגַדְתִּי"))
        assertEquals("בגדת", HebrewText.stripPoints("בָּגַדְתָּ"))
        assertEquals("בגד", HebrewText.stripPoints("בָּגַד"))
    }

    @Test
    fun stripsTeamimAsWellAsNiqqud() {
        // Cantillation marks sit in the same U+0591..U+05C7 block and must go too.
        val withTeamim = "בְּרֵאשִׁ֖ית"
        assertEquals("בראשית", HebrewText.stripPoints(withTeamim))
    }

    @Test
    fun leavesUnpointedTextUntouched() {
        for (w in listOf("שלום", "מקלדת", "עברית", "ירושלים")) {
            assertEquals(w, HebrewText.stripPoints(w))
        }
    }

    @Test
    fun handlesEmptyAndNonHebrew() {
        assertEquals("", HebrewText.stripPoints(""))
        assertEquals("hello", HebrewText.stripPoints("hello"))
    }

    @Test
    fun letterRangeIncludesFinalForms() {
        // The five final forms live inside U+05D0..U+05EA, so no special case is needed --
        // but a wrong range bound would silently drop every word ending in one.
        for (c in listOf('ך', 'ם', 'ן', 'ף', 'ץ')) {
            assertTrue(HebrewText.isHebrewLetter(c), "final form $c must be a Hebrew letter")
        }
        assertTrue(HebrewText.isHebrewLetter('א'))
        assertTrue(HebrewText.isHebrewLetter('ת'))
    }

    @Test
    fun rejectsNonWords() {
        assertFalse(HebrewText.isHebrewWord(""))
        assertFalse(HebrewText.isHebrewWord("abc"))
        assertFalse(HebrewText.isHebrewWord("שלום world"))
        assertFalse(HebrewText.isHebrewWord("שלום123"))
        // Maqaf is inside the stripped block but is not a letter, so a hyphenated compound
        // is not a single word.
        assertFalse(HebrewText.isHebrewWord("לוחם־חבר"))
        assertTrue(HebrewText.isHebrewWord("שלום"))
    }

    @Test
    fun combiningMarkRangeMatchesTheBuildScript() {
        assertTrue(HebrewText.isCombiningMark('֑'))
        assertTrue(HebrewText.isCombiningMark('ׇ'))
        assertFalse(HebrewText.isCombiningMark('֐'))
        assertFalse(HebrewText.isCombiningMark('׈'))
        assertFalse(HebrewText.isCombiningMark('א'))
    }

    @Test
    fun everyCodePointInTheRangeIsClassifiedTheWayUnicodeClassifiesIt() {
        // Checked against Character.getType -- the JDK's own Unicode tables -- and NOT against
        // a list written here. A hand-written expectation would have agreed with a
        // hand-written implementation and proved nothing, which is exactly how the mirrored
        // keyboard survived M3 through M8.
        var marks = 0
        var punctuation = 0
        for (cp in 0x0591..0x05C7) {
            val c = cp.toChar()
            val isMarkPerUnicode = when (Character.getType(c).toByte()) {
                Character.NON_SPACING_MARK,
                Character.COMBINING_SPACING_MARK,
                Character.ENCLOSING_MARK -> true
                else -> false
            }
            assertEquals(
                isMarkPerUnicode,
                HebrewText.isCombiningMark(c),
                "U+%04X (%s) classified against Character.getType".format(cp, Character.getName(cp)),
            )
            assertEquals(
                !isMarkPerUnicode,
                HebrewText.isHebrewBlockPunctuation(c),
                "U+%04X is either a mark or the punctuation exception, never both".format(cp),
            )
            if (isMarkPerUnicode) marks++ else punctuation++
        }
        assertEquals(55, marks + punctuation, "denominator: U+0591..U+05C7 is 55 code points")
        assertEquals(4, punctuation, "maqaf, paseq, sof pasuq, nun hafukha")
        assertEquals(51, marks)
    }

    @Test
    fun stripPointsStillRemovesTheWholeRangeIncludingThePunctuation() {
        // The split between marks and punctuation deliberately did NOT change what
        // stripPoints removes. Every lexicon lookup in the app depends on this reduction
        // matching scripts/build_lexicon.py, so narrowing it would silently change which
        // words resolve.
        for (cp in 0x0591..0x05C7) {
            assertEquals(
                "שלום",
                HebrewText.stripPoints("של" + cp.toChar() + "ום"),
                "U+%04X must still be stripped".format(cp),
            )
        }
    }
}

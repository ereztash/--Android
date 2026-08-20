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
 * Denominator: 21 assertions.
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
}

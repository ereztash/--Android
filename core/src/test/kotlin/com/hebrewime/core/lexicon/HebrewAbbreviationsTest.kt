package com.hebrewime.core.lexicon

import com.hebrewime.core.input.InputContextBuffer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hebrew abbreviations, and the word-boundary change they required.
 *
 * The operator reported that `ראשי תיבות` got no completion. The cause was two layers deep: the
 * lexicon has no abbreviations in it at all, **and** the input buffer split words on the
 * gershayim, so `כ״כ` was never even seen as one token to look up.
 *
 * Denominator: 11 tests.
 */
class HebrewAbbreviationsTest {

    private fun table(): HebrewAbbreviations =
        File(System.getProperty("abbreviation.file")!!)
            .inputStream().use { HebrewAbbreviations.load(it) }

    @Test
    fun theOperatorsOwnExamplesResolve() {
        val t = table()
        assertEquals("כ״כ", t.canonicalFor("ככ"))
        assertEquals("אח״כ", t.canonicalFor("אחכ"))
    }

    @Test
    fun theTableIsTheOneThatWasBuilt() {
        val t = table()
        assertEquals(861, t.size, "the abbreviation table is not the measured one")
        assertEquals("צה״ל", t.canonicalFor("צהל"))
        assertEquals("וכו׳", t.canonicalFor("וכו"))
    }

    @Test
    fun aKnownAbbreviationIsNotAMisspelling() {
        // The whole point. `צה״ל` is absent from the lexicon and always will be, because the
        // lexicon holds letters. Before this it reached the spelling corrector.
        val t = table()
        assertTrue(t.isKnownAbbreviation("צה״ל"))
        assertTrue(t.isKnownAbbreviation("כ״כ"))
        assertFalse(t.isKnownAbbreviation("ככ"), "the BARE form is not itself an abbreviation")
    }

    @Test
    fun anAmbiguousBareFormResolvesToNothing() {
        // `דר` is both ד״ר and דר׳. Dropped at build time rather than guessed: offering the
        // wrong expansion is worse than offering none.
        assertNull(table().canonicalFor("דר"))
    }

    @Test
    fun marksAreStrippedToTheBareLetters() {
        assertEquals("ככ", HebrewText.stripAbbreviationMarks("כ״כ"))
        assertEquals("וכו", HebrewText.stripAbbreviationMarks("וכו׳"))
        assertEquals("ככ", HebrewText.stripAbbreviationMarks("כ\"כ"), "ASCII quote too")
        assertEquals("שלום", HebrewText.stripAbbreviationMarks("שלום"), "unchanged when clean")
    }

    @Test
    fun bothTheHebrewAndAsciiMarksAreRecognised() {
        // People type the ASCII ones on a phone keyboard; Wikipedia contains both.
        for (c in listOf(HebrewText.GERSHAYIM, HebrewText.GERESH, '"', '\'')) {
            assertTrue(HebrewText.isAbbreviationMark(c), "mark $c not recognised")
        }
        assertFalse(HebrewText.isAbbreviationMark('א'))
    }

    @Test
    fun anAbbreviationIsAWordButNotAPlainHebrewWord() {
        assertTrue(HebrewText.isHebrewWordOrAbbreviation("כ״כ"))
        assertTrue(HebrewText.isHebrewWordOrAbbreviation("וכו׳"))
        assertTrue(HebrewText.isHebrewWordOrAbbreviation("שלום"))
        // isHebrewWord stays strict, because it gates LEXICON lookups and the lexicon holds
        // letters. Loosening it would have made every abbreviation a failed lookup instead.
        assertFalse(HebrewText.isHebrewWord("כ״כ"))
    }

    @Test
    fun aLeadingMarkIsAQuoteAndNotPartOfTheWord() {
        assertFalse(HebrewText.isHebrewWordOrAbbreviation("״שלום"))
        assertFalse(HebrewText.isHebrewWordOrAbbreviation("\"שלום"))
    }

    /**
     * THE BUG BENEATH THE BUG.
     *
     * Even with a table, nothing would work while the buffer split on the gershayim: `כ״כ`
     * arrived as the one-letter word `כ`, and one letter matches nothing useful.
     */
    @Test
    fun anAbbreviationStaysOneWordInTheBuffer() {
        val buffer = InputContextBuffer()
        buffer.reset("", 0)
        for (c in "כ״כ") buffer.onTextCommitted(c.toString())
        assertEquals("כ״כ", buffer.currentWord, "the gershayim split the word in two")
    }

    @Test
    fun typingTheBareFormLeavesItLookupable() {
        val buffer = InputContextBuffer()
        buffer.reset("", 0)
        for (c in "ככ") buffer.onTextCommitted(c.toString())
        assertEquals("ככ", buffer.currentWord)
        assertEquals("כ״כ", table().canonicalFor(buffer.currentWord))
    }

    @Test
    fun aSpaceStillEndsTheWord() {
        // Widening isWordChar must not swallow boundaries: a quote is a word character, a
        // space is still not.
        val buffer = InputContextBuffer()
        buffer.reset("", 0)
        for (c in "כ״כ נוח") buffer.onTextCommitted(c.toString())
        assertEquals("נוח", buffer.currentWord)
        assertEquals(listOf("כ״כ"), buffer.completedWords(1))
    }
}

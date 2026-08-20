package com.hebrewime.core.lexicon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Denominator: 8 tests, 79 grammar strings, 7 named counter-examples. */
class PrefixStripperTest {

    private fun setOf(vararg words: String): WordSet {
        val s = words.toSet()
        return WordSet { it in s }
    }

    @Test
    fun grammarProducesExactlySeventyNineStringsOfAtMostFive() {
        assertEquals(79, PrefixStripper.ALL.size, "prefix grammar size")
        assertEquals(5, PrefixStripper.MAX_PREFIX_LENGTH)
        assertTrue(PrefixStripper.ALL.none { it.isEmpty() }, "empty string must be excluded")
        assertEquals(
            PrefixStripper.ALL.size, PrefixStripper.ALL.distinct().size,
            "grammar must not contain duplicates",
        )
    }

    @Test
    fun longestPrefixIsTriedFirst() {
        // Ordering matters: "kshe" must be tried before "kaf", or the wrong stem is produced.
        val lengths = PrefixStripper.ALL.map { it.length }
        assertEquals(lengths.sortedDescending(), lengths, "ALL must be longest-first")
    }

    @Test
    fun literalHitsShortCircuitBeforeStripping() {
        // The seven words the build spec names as the usual objection to prefix stripping.
        // Every one is a real lexicon entry, so the stripper never gets to run on them.
        val lex = setOf("שמן", "שולחן", "מים", "הר", "לחם", "שמש", "ברזל")
        for (w in listOf("שמן", "שולחן", "מים", "הר", "לחם", "שמש", "ברזל")) {
            val a = PrefixStripper.analyze(w, lex, minStem = 2)
            assertNotNull(a, "$w must be accepted")
            assertTrue(a.isLiteral, "$w must be a LITERAL hit, not a stripped one")
            assertEquals(w, a.stem)
        }
    }

    @Test
    fun stripsRealPrefixes() {
        val lex = setOf("ספר", "בית", "מקלדת")
        // "and-the-book", "in-the-house", "that-in-the-keyboard"
        assertEquals("ספר", PrefixStripper.analyze("והספר", lex, 3)?.stem)
        assertEquals("ו" + "ה", PrefixStripper.analyze("והספר", lex, 3)?.prefix)
        assertEquals("בית", PrefixStripper.analyze("בבית", lex, 3)?.stem)
        assertEquals("מקלדת", PrefixStripper.analyze("שבמקלדת", lex, 3)?.stem)
    }

    @Test
    fun minStemIsRespected() {
        val lex = setOf("ספר")
        assertNotNull(PrefixStripper.analyze("הספר", lex, minStem = 3))
        // At minStem 4 the residual "sefer" is only 3 letters, so it must be rejected.
        assertNull(PrefixStripper.analyze("הספר", lex, minStem = 4))
    }

    @Test
    fun wordShorterThanMinStemIsNeverStripped() {
        val lex = setOf("ספר", "פר")
        assertNull(PrefixStripper.analyze("הפר", lex, minStem = 4))
    }

    @Test
    fun unknownWordIsRejectedAtEveryMinStem() {
        val lex = setOf("ספר")
        for (m in 2..5) {
            assertFalse(PrefixStripper.accepts("זזזזזז", lex, m), "minStem=$m")
        }
    }

    @Test
    fun emptyStringIsRejected() {
        assertNull(PrefixStripper.analyze("", setOf("ספר")))
    }
}

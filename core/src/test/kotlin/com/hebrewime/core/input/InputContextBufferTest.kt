package com.hebrewime.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The buffer's job is not to be right about the editor's contents — it cannot be, because
 * `InputConnection` never tells it whether a command landed. Its job is to be right about
 * *whether it knows*. These tests are mostly about the second thing.
 *
 * Denominator: 12 tests.
 */
class InputContextBufferTest {

    @Test
    fun tracksCommittedTextAndCurrentWord() {
        val b = InputContextBuffer()
        b.reset("", 0)
        b.onTextCommitted("ש")
        b.onTextCommitted("ל")
        b.onTextCommitted("ו")
        b.onTextCommitted("ם")
        assertEquals("שלום", b.currentWord)
        assertEquals("שלום", b.textBefore)
        assertEquals(4, b.expectedCursor)
    }

    @Test
    fun spaceEndsTheCurrentWord() {
        val b = InputContextBuffer()
        b.reset("", 0)
        b.onTextCommitted("שלום")
        b.onTextCommitted(" ")
        assertEquals("", b.currentWord)
        b.onTextCommitted("עולם")
        assertEquals("עולם", b.currentWord)
        assertEquals("שלום עולם", b.textBefore)
    }

    @Test
    fun initialContextComesFromEditorInfoOnce() {
        val b = InputContextBuffer()
        b.reset("כתבתי ", 6)
        assertEquals("כתבתי ", b.textBefore)
        assertEquals("", b.currentWord)
        assertEquals(6, b.expectedCursor)
        assertTrue(b.precedingContextKnown)
    }

    @Test
    fun nullInitialContextMeansUnknownNotEmpty() {
        // M4 withholds initial text for restricted fields. "Withheld" must not be mistaken
        // for "the field was empty" -- that would let the IME treat a password field as a
        // fresh document and start suggesting.
        val b = InputContextBuffer()
        b.reset(null, 12)
        assertFalse(b.precedingContextKnown)
        assertNull(b.textBefore)
        assertEquals(12, b.expectedCursor)
    }

    @Test
    fun deletionShrinksTheBuffer() {
        val b = InputContextBuffer()
        b.reset("", 0)
        b.onTextCommitted("שלום")
        b.onCharsDeleted(1)
        assertEquals("שלו", b.currentWord)
        assertEquals(3, b.expectedCursor)
    }

    @Test
    fun deletingPastWhatWeHoldMarksContextUnknown() {
        val b = InputContextBuffer()
        b.reset(null, 5)
        b.onTextCommitted("אב")
        b.onCharsDeleted(10)
        assertFalse(b.precedingContextKnown)
        assertNull(b.textBefore)
    }

    @Test
    fun matchingSelectionUpdateKeepsContext() {
        val b = InputContextBuffer()
        b.reset("שלום", 4)
        b.onTextCommitted(" עולם")
        assertTrue(b.onSelectionUpdated(9, 9), "cursor 9 was expected")
        assertTrue(b.precedingContextKnown)
        assertEquals("שלום עולם", b.textBefore)
        assertEquals(0, b.desyncCount)
    }

    @Test
    fun unexpectedSelectionUpdateDropsContextRatherThanGuessing() {
        val b = InputContextBuffer()
        b.reset("שלום", 4)
        b.onTextCommitted(" עולם")
        // The user tapped somewhere else, or a commit was silently dropped.
        assertFalse(b.onSelectionUpdated(2, 2))
        assertFalse(b.precedingContextKnown)
        assertNull(b.textBefore)
        assertEquals("", b.currentWord)
        assertEquals(2, b.expectedCursor)
        assertEquals(1, b.desyncCount)
    }

    @Test
    fun aRangeSelectionIsAlwaysADesync() {
        val b = InputContextBuffer()
        b.reset("שלום", 4)
        // Selecting a range is never something this IME did by committing text.
        assertFalse(b.onSelectionUpdated(1, 3))
        assertFalse(b.precedingContextKnown)
    }

    @Test
    fun recoversTheCurrentWordAfterDesyncWithoutRefetching() {
        // After a desync the preceding text stays unknown -- there is no non-blocking way to
        // re-read it without a blocking Binder call -- but characters typed afterwards are known, so the
        // current word is usable again immediately.
        val b = InputContextBuffer()
        b.reset("שלום", 4)
        b.onSelectionUpdated(0, 0)
        assertFalse(b.precedingContextKnown)
        b.onTextCommitted("בית")
        assertEquals("בית", b.currentWord)
        assertTrue(b.currentWordIsHebrew())
        assertNull(b.textBefore, "preceding text must stay unknown after a desync")
    }

    @Test
    fun contextIsCappedButCurrentWordSurvivesTheTrim() {
        val b = InputContextBuffer(maxContext = 10)
        b.reset("", 0)
        b.onTextCommitted("א".repeat(8))
        b.onTextCommitted(" ")
        b.onTextCommitted("שלום")
        assertEquals("שלום", b.currentWord, "trimming the head must not eat the current word")
        assertEquals(10, b.textBefore!!.length)
    }

    @Test
    fun clearForgetsEverything() {
        val b = InputContextBuffer()
        b.reset("שלום", 4)
        b.clear()
        assertFalse(b.precedingContextKnown)
        assertNull(b.textBefore)
        assertEquals("", b.currentWord)
        assertEquals(0, b.expectedCursor)
    }
}

/** Denominator: 9 tests on the previous-word accessor that prediction depends on. */
class PreviousWordTest {

    @Test
    fun exposesTheWordBeforeTheOneBeingTyped() {
        val b = InputContextBuffer()
        b.reset("", 0)
        b.onTextCommitted("שלום עולם ")
        assertEquals("עולם", b.previousWord)
        b.onTextCommitted("יפה")
        assertEquals("עולם", b.previousWord, "still the word before the one in progress")
        assertEquals("יפה", b.currentWord)
    }

    @Test
    fun nullWhenThereIsNoPreviousWord() {
        val b = InputContextBuffer()
        b.reset("", 0)
        assertNull(b.previousWord)
        b.onTextCommitted("שלום")
        assertNull(b.previousWord, "the first word has nothing before it")
    }

    @Test
    fun nullAfterADesyncRatherThanAGuess() {
        // Predicting from a word we are no longer sure precedes the cursor is worse than not
        // predicting at all.
        val b = InputContextBuffer()
        b.reset("שלום עולם ", 10)
        assertEquals("עולם", b.previousWord)
        b.onSelectionUpdated(2, 2)
        assertNull(b.previousWord)
    }

    @Test
    fun nullWhenTheInitialTextWasWithheld() {
        val b = InputContextBuffer()
        b.reset(null, 40)
        assertNull(b.previousWord, "a restricted field must not leak context through this")
    }

    @Test
    fun skipsPunctuationBetweenWords() {
        val b = InputContextBuffer()
        b.reset("", 0)
        b.onTextCommitted("שלום, ")
        assertEquals("שלום", b.previousWord)
    }

    @Test
    fun nullAcrossEverySentenceBoundaryTheBuilderSplitsOn() {
        // The bigram model was never shown a pair straddling one of these, so asking it about
        // one is asking outside its training distribution.
        var checked = 0
        for (c in InputContextBuffer.SENTENCE_BOUNDARY_CHARS) {
            val b = InputContextBuffer()
            b.reset("", 0)
            b.onTextCommitted("שלום$c ")
            assertNull(b.previousWord, "boundary ${c.code.toString(16)} should break the pair")
            checked++
        }
        assertEquals(7, checked, "denominator: every boundary character was exercised")
    }

    @Test
    fun nullAcrossADoubleHyphenButNotASingleOne() {
        val double = InputContextBuffer()
        double.reset("", 0)
        double.onTextCommitted("שלום-- ")
        assertNull(double.previousWord, "the builder splits on --")

        val single = InputContextBuffer()
        single.reset("", 0)
        single.onTextCommitted("שלום- ")
        assertEquals("שלום", single.previousWord, "the builder does NOT split on a lone hyphen")
    }

    @Test
    fun nonHebrewRunsDoNotBreakThePair() {
        // HEBREW_RUN_RE in the builder skips Latin and digits, so the surrounding Hebrew words
        // WERE counted as adjacent. Mirroring the builder means mirroring its non-splits too.
        val b = InputContextBuffer()
        b.reset("", 0)
        b.onTextCommitted("שלום world 42 ")
        assertEquals("שלום", b.previousWord)
    }

    @Test
    fun theBoundarySetIsTheOneTheBuilderActuallyUses() {
        // Pinned against the script itself, not against a copy of it in this test. If someone
        // widens BOUNDARY_RE in build_bigrams.py and rebuilds the model, this goes red rather
        // than the keyboard silently querying the model outside its distribution.
        val script = java.io.File("../scripts/build_bigrams.py")
            .takeIf { it.isFile }
            ?: java.io.File("scripts/build_bigrams.py")
        assertTrue(script.isFile, "cannot locate build_bigrams.py to pin against")

        val line = script.readLines().first { it.startsWith("BOUNDARY_RE") }
        val charClass = Regex("""\[([^]]*)]""").find(line)?.groupValues?.get(1)
        assertNotNull(charClass, "BOUNDARY_RE has no character class: $line")

        val fromScript = buildSet {
            var i = 0
            while (i < charClass.length) {
                if (charClass.startsWith("\\n", i)) {
                    add('\n'); i += 2
                } else if (charClass.startsWith("\\u", i)) {
                    add(charClass.substring(i + 2, i + 6).toInt(16).toChar()); i += 6
                } else {
                    add(charClass[i]); i++
                }
            }
        }
        assertEquals(
            fromScript,
            InputContextBuffer.SENTENCE_BOUNDARY_CHARS,
            "the Kotlin boundary set has drifted from BOUNDARY_RE in build_bigrams.py",
        )
        assertTrue(line.contains("|--"), "the builder also splits on --, and so must the buffer")
    }
}

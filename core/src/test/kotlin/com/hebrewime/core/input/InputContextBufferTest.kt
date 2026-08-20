package com.hebrewime.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // re-read it at minSdk 30 -- but characters typed afterwards are known, so the
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

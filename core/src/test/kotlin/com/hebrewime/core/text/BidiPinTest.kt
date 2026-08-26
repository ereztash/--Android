package com.hebrewime.core.text

import com.hebrewime.core.input.InputContextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ARM-EDGE` is a **presence** in the shipped path, so a test protects it. `GATE-WITHDRAWN-1`
 * exists because an *absence* — a constructor no longer called — is undone by accident with
 * nothing going red; a presence is not, because removing it fails these.
 */
class BidiPinTest {

    private val heh = "ש"

    @Test
    fun pinsOnTheFirstHebrewCharacterOfAKnownEmptyField() {
        assertTrue(BidiPin.shouldPin(heh, textBefore = "", alreadyPinned = false))
    }

    @Test
    fun neverPinsWhenTheContextIsUnknown() {
        // null is "the IME cannot see what is before the cursor". Pinning there would insert a
        // direction mark into the middle of text somebody else wrote.
        assertFalse(BidiPin.shouldPin(heh, textBefore = null, alreadyPinned = false))
    }

    @Test
    fun neverPinsIntoAFieldThatAlreadyHasText() {
        assertFalse(BidiPin.shouldPin(heh, textBefore = "שלום", alreadyPinned = false))
    }

    @Test
    fun neverPinsTwiceInOneSession() {
        assertFalse(BidiPin.shouldPin(heh, textBefore = "", alreadyPinned = true))
    }

    @Test
    fun neverPinsForNonHebrew() {
        // A field the user only types Latin or digits into stays genuinely empty, rather than
        // holding two invisible characters that make a "required field" check pass on nothing.
        assertFalse(BidiPin.shouldPin("a", textBefore = "", alreadyPinned = false))
        assertFalse(BidiPin.shouldPin("5", textBefore = "", alreadyPinned = false))
        assertFalse(BidiPin.shouldPin(" ", textBefore = "", alreadyPinned = false))
    }

    @Test
    fun theMarkIsAFormatCharacterSoStrippingFormatRecoversTheUserSText() {
        assertEquals(Character.FORMAT.toInt(), Character.getType(BidiPin.MARK))
        val committed = BidiPin.leading(heh) + BidiPin.trailing()
        assertEquals(heh, committed.filter { Character.getType(it) != Character.FORMAT.toInt() })
    }

    @Test
    fun theMarkDoesNotJoinTheWordTheBufferIsTracking() {
        // The whole design rests on this: U+200F is not a word character, so the leading mark
        // is a boundary and `currentWord` is the Hebrew the user typed, not the mark plus it.
        // If this ever changes, every lexicon lookup on the first word of a field misses.
        val b = InputContextBuffer()
        b.reset("", 0)
        b.onTextCommitted(BidiPin.leading("של"))
        assertEquals("של", b.currentWord)
        assertTrue(b.currentWordIsHebrew())
    }

    @Test
    fun theBufferAccountsForTheLeadingMarkAndNotTheTrailingOne() {
        // The trailing mark sits AFTER the cursor, so it is not context. Counting it would
        // desync the buffer against the editor by one on the first keystroke of every field.
        val b = InputContextBuffer()
        b.reset("", 0)
        b.onTextCommitted(BidiPin.leading("ש"))
        assertEquals(2, b.expectedCursor)
    }
}

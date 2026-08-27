package com.hebrewime.core.text

import com.hebrewime.core.input.InputContextBuffer
import com.hebrewime.core.privacy.FieldDescriptor
import com.hebrewime.core.privacy.SensitiveFieldPolicy
import com.hebrewime.core.text.AndroidInputTypes as T
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `docs/PRIVACY_POLICY.md` promises that in a password, payment, email, phone or one-time-code
 * field the keyboard **"types the characters and nothing else."**
 *
 * Since `ARM-EDGE` shipped that is a claim about `BidiPin`, which inserts two invisible
 * `U+200F` marks around what the user types. Two of those inside a password would corrupt it,
 * and the user could not see why.
 *
 * ### The promise currently holds by accident
 * Nothing in the shipped path guards the pin. `HebrewImeService.execute` calls
 * `BidiPin.shouldPin` on **every** `CommitText`, with no reference to the session's
 * restriction. What actually saves it is a three-link chain in which no link mentions the
 * other two:
 *
 * 1. `SensitiveFieldPolicy.beginSession` sets `initialTextBeforeCursor = null` when restricted;
 * 2. `InputContextBuffer.reset(null, _)` leaves `precedingContextKnown = false`, so
 *    `textBefore` is `null`;
 * 3. `BidiPin.shouldPin` returns `false` the moment `textBefore` is `null`.
 *
 * Change link 1 from `null` to `""` — which reads as harmless normalisation — and the keyboard
 * starts writing invisible marks into passwords while the privacy policy still says it does
 * not. This test exists so that edit fails here instead of on a user's phone.
 */
class BidiPinSensitiveFieldTest {

    private val hebrew = "ש"

    private fun bufferFor(field: FieldDescriptor, initial: CharSequence?): InputContextBuffer {
        val session = SensitiveFieldPolicy.beginSession(field, cursorPosition = 0) { initial }
        return InputContextBuffer().also {
            it.reset(session.initialTextBeforeCursor, session.cursorPosition)
        }
    }

    @Test
    fun noDirectionMarkIsEverCommittedInARestrictedField() {
        val restricted = listOf(
            "password" to (T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD),
            "visible password" to (T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
            "web password" to (T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_WEB_PASSWORD),
            "email" to (T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            "phone" to T.TYPE_CLASS_PHONE,
        )
        for ((name, inputType) in restricted) {
            val field = FieldDescriptor(inputType = inputType)
            val session = SensitiveFieldPolicy.beginSession(field, 0) { "" }
            assertTrue(session.isRestricted, "$name must classify as restricted")
            assertNull(
                session.initialTextBeforeCursor,
                "$name: a restricted session must expose null, not empty text — link 1 of the " +
                    "chain that keeps direction marks out of passwords",
            )
            val buffer = bufferFor(field, "")
            assertNull(buffer.textBefore, "$name: textBefore must be unknown, not empty")
            assertFalse(
                BidiPin.shouldPin(hebrew, buffer.textBefore, alreadyPinned = false),
                "$name: BidiPin must not fire. docs/PRIVACY_POLICY.md promises this field gets " +
                    "the characters and nothing else, and two U+200F marks are not nothing.",
            )
        }
    }

    /**
     * The positive control. Without it the test above passes just as well against a `shouldPin`
     * that is broken and never fires at all — which would prove nothing about restricted fields
     * and would silently retire `ARM-EDGE`.
     */
    @Test
    fun anOrdinaryEmptyFieldDoesPin() {
        val field = FieldDescriptor(inputType = T.TYPE_CLASS_TEXT)
        val session = SensitiveFieldPolicy.beginSession(field, 0) { "" }
        assertFalse(session.isRestricted, "a plain text field is not restricted")
        val buffer = bufferFor(field, "")
        assertTrue(
            BidiPin.shouldPin(hebrew, buffer.textBefore, alreadyPinned = false),
            "control: an ordinary empty field MUST pin, or the test above is vacuous",
        )
    }

    /**
     * States the regression in the test rather than only in prose: the exact edit that would
     * break the privacy claim, and what it would do.
     */
    @Test
    fun emptyStringInsteadOfNullIsWhatWouldBreakIt() {
        val buffer = InputContextBuffer().also { it.reset("", 0) }
        assertTrue(
            BidiPin.shouldPin(hebrew, buffer.textBefore, alreadyPinned = false),
            "if a restricted session ever exposed \"\" instead of null, this is what would " +
                "happen: the pin fires, and two invisible marks enter the password.",
        )
    }
}

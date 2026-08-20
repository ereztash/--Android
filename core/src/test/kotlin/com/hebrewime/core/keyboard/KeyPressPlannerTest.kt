package com.hebrewime.core.keyboard

import com.hebrewime.core.input.InputContextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/** Denominator: 9 tests. */
class KeyPressPlannerTest {

    private fun bufferWith(text: String): InputContextBuffer =
        InputContextBuffer().apply { reset(text, text.length) }

    @Test
    fun characterKeyCommitsItsOutput() {
        val key = Key("ש", "ש", KeyAction.CHARACTER)
        assertEquals(
            listOf(EditCommand.CommitText("ש")),
            KeyPressPlanner.plan(key, bufferWith("")),
        )
    }

    @Test
    fun shiftUppercasesOnlyWhereCaseExists() {
        assertEquals(
            listOf(EditCommand.CommitText("A")),
            KeyPressPlanner.plan(Key("a", "a", KeyAction.CHARACTER), bufferWith(""), shifted = true),
        )
        // Hebrew has no case; shift must not mangle the letter.
        assertEquals(
            listOf(EditCommand.CommitText("ש")),
            KeyPressPlanner.plan(Key("ש", "ש", KeyAction.CHARACTER), bufferWith(""), shifted = true),
        )
    }

    @Test
    fun backspaceOverPlainHebrewRemovesOneCodePoint() {
        assertEquals(1, KeyPressPlanner.backspaceWidth(bufferWith("שלום")))
    }

    @Test
    fun backspaceOverAPointedLetterRemovesTheWholeStack() {
        // bet + dagesh + qamats. A hardcoded 1 would strip the qamats and leave the letter
        // looking almost identical, so the user presses again and again.
        assertEquals(3, KeyPressPlanner.backspaceWidth(bufferWith("בָּ")))
        assertEquals(3, KeyPressPlanner.backspaceWidth(bufferWith("שלוםבָּ")))
    }

    @Test
    fun backspaceOverEmojiRemovesTheWholeSequence() {
        assertEquals(1, KeyPressPlanner.backspaceWidth(bufferWith("👍")))
        assertEquals(2, KeyPressPlanner.backspaceWidth(bufferWith("👍🏽")))
        assertEquals(2, KeyPressPlanner.backspaceWidth(bufferWith("🇮🇱")))
        assertEquals(
            7,
            KeyPressPlanner.backspaceWidth(
                bufferWith("👨‍👩‍👧‍👦")
            ),
        )
    }

    @Test
    fun backspaceWithUnknownContextFallsBackToOneCodePoint() {
        // Restricted field or post-desync: no context, and no non-blocking way to fetch it.
        val b = InputContextBuffer().apply { reset(null, 20) }
        assertEquals(1, KeyPressPlanner.backspaceWidth(b))
    }

    @Test
    fun backspaceUsesTypedCharactersEvenWhenPrecedingContextIsUnknown() {
        val b = InputContextBuffer().apply { reset(null, 20) }
        b.onTextCommitted("בָּ")
        assertEquals(3, KeyPressPlanner.backspaceWidth(b), "typed characters are known")
    }

    @Test
    fun backspaceOnEmptyBufferStillAsksForOne() {
        // Deleting one code point at position 0 is a no-op in the editor, but returning 0
        // would mean issuing a pointless IPC on every press at the start of a field.
        assertEquals(1, KeyPressPlanner.backspaceWidth(bufferWith("")))
    }

    @Test
    fun functionalKeysMapToTheirCommands() {
        val b = bufferWith("")
        assertEquals(
            listOf(EditCommand.PerformEditorAction),
            KeyPressPlanner.plan(Key("↵", action = KeyAction.ENTER), b),
        )
        assertEquals(
            listOf(EditCommand.ToggleShift),
            KeyPressPlanner.plan(Key("⇧", action = KeyAction.SHIFT), b),
        )
        assertEquals(
            listOf(EditCommand.NextInputMethod),
            KeyPressPlanner.plan(Key("🌐", action = KeyAction.NEXT_INPUT_METHOD), b),
        )
        assertEquals(
            listOf(EditCommand.SwitchLayout("123")),
            KeyPressPlanner.plan(Key("123", "123", KeyAction.SWITCH_LAYOUT), b),
        )
        assertEquals(
            listOf(EditCommand.CommitText(" ")),
            KeyPressPlanner.plan(Key(" ", " ", KeyAction.SPACE), b),
        )
    }
}

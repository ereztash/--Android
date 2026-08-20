package com.hebrewime.core.keyboard

import com.hebrewime.core.input.InputContextBuffer
import com.hebrewime.core.text.GraphemeSegmenter

/**
 * Turns a key press into [EditCommand]s. Pure: no Android types, no I/O, no clock.
 */
object KeyPressPlanner {

    /**
     * @param shifted whether shift is currently latched. Hebrew has no letter case, so this
     *   only affects layouts whose characters have an uppercase form.
     */
    fun plan(key: Key, context: InputContextBuffer, shifted: Boolean = false): List<EditCommand> =
        when (key.action) {
            KeyAction.CHARACTER -> {
                val text = key.output.orEmpty()
                listOf(EditCommand.CommitText(if (shifted) text.uppercase() else text))
            }

            KeyAction.SPACE -> listOf(EditCommand.CommitText(" "))

            KeyAction.ENTER -> listOf(EditCommand.PerformEditorAction)

            KeyAction.SHIFT -> listOf(EditCommand.ToggleShift)

            KeyAction.SWITCH_LAYOUT ->
                listOf(EditCommand.SwitchLayout(key.output.orEmpty()))

            KeyAction.NEXT_INPUT_METHOD -> listOf(EditCommand.NextInputMethod)

            KeyAction.BACKSPACE ->
                listOf(EditCommand.DeleteBeforeCodePoints(backspaceWidth(context)))
        }

    /**
     * How many code points one backspace should remove.
     *
     * Never a hardcoded 1: that splits surrogate pairs, strips a Hebrew word's niqqud one mark
     * at a time while the letters stay put, and leaves half a family emoji behind.
     *
     * When the preceding context is unknown -- after a desync, or in a restricted field where
     * the initial text was deliberately withheld -- there is no non-blocking way to recover it:
     * `getTextBeforeCursor` and `getSurroundingText` are both banned 2000 ms Binder round-trips.
     * The
     * fallback is **one code point**: surrogate-safe, so it can never split a character in
     * half, but not grapheme-aware, so a pointed letter or a ZWJ emoji takes more than one
     * press. That is a deliberate, bounded shortfall rather than a wrong answer, and it only
     * applies to the first press after a desync -- once the user has typed anything, the
     * buffer knows the text again.
     */
    fun backspaceWidth(context: InputContextBuffer): Int {
        val known = context.textBefore ?: context.currentWord
        if (known.isEmpty()) return 1
        return GraphemeSegmenter.widthInCodePoints(known, known.length).coerceAtLeast(1)
    }
}

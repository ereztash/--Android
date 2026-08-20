package com.hebrewime.core.keyboard

/**
 * What the IME should do to the editor, decided without touching the editor.
 *
 * The service's job is reduced to executing these against an `InputConnection`. Everything
 * that involves a decision -- how wide a backspace is, what a key commits, when to switch
 * layout -- happens in `:core` and is unit-tested on the JVM, because none of it needs a
 * device and all of it is easy to get quietly wrong.
 */
sealed interface EditCommand {

    data class CommitText(val text: String) : EditCommand

    /**
     * Delete [codePoints] code points before the cursor.
     *
     * Code points, not UTF-16 code units, because `deleteSurroundingText` counts code units
     * and will split a surrogate pair in half. The service issues this via
     * `deleteSurroundingTextInCodePoints` (API 24+).
     */
    data class DeleteBeforeCodePoints(val codePoints: Int) : EditCommand

    data object PerformEditorAction : EditCommand

    data class SwitchLayout(val layoutId: String) : EditCommand

    data object NextInputMethod : EditCommand

    data object ToggleShift : EditCommand
}

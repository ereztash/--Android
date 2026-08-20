package com.hebrewime.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import com.hebrewime.core.input.InputContextBuffer
import com.hebrewime.core.keyboard.EditCommand
import com.hebrewime.core.keyboard.Key
import com.hebrewime.core.keyboard.KeyPressPlanner
import com.hebrewime.core.keyboard.Layouts
import com.hebrewime.ime.view.KeyboardHostView
import com.hebrewime.ime.view.KeyboardView

/**
 * The Hebrew input method.
 *
 * Four platform traps are avoided here deliberately, and all four compile cleanly, so none
 * would be caught by the compiler or by Lint. `GATE-API-1` bans each statically:
 *
 * 1. **`onCreateInputMethodSessionInterface()` is never overridden.** At targetSdk >= 34 the
 *    compat change `DISALLOW_INPUT_METHOD_INTERFACE_OVERRIDE` throws `LinkageError` from
 *    `onCreate()` -- an instant crash on launch. The method is public and overridable in the
 *    API 36 SDK, so nothing warns you.
 * 2. **No `InputConnection` return value is ever branched on.** `IRemoteInputConnection` is
 *    `oneway`; every method here returns `true` even when the editor dropped the command on a
 *    session-id mismatch. Reconciliation happens in [onUpdateSelection] and nowhere else.
 * 3. **`getTextBeforeCursor()` is never called.** It is a blocking Binder round-trip that can
 *    stall for up to `MAX_WAIT_TIME_MILLIS` (2000 ms). Context is read once from [EditorInfo]
 *    and maintained incrementally by [InputContextBuffer].
 * 4. **Backspace width is never hardcoded to 1.** [KeyPressPlanner] computes a grapheme-correct
 *    width and the delete is issued in code points.
 *
 * `InputMethodManager.invalidateInput()` can force-close a batch edit and destroy the composing
 * span at any moment -- AOSP unwinds up to 16 `endBatchEdit()` calls, then falls back to
 * `restartInput`. Nothing here assumes a batch or a composing region survives across calls.
 */
class HebrewImeService : InputMethodService() {

    // ALL session state lives on the service. onCreateInputView() is not called once -- its
    // javadoc is wrong -- and InputMethodInfo.getConfigChanges() is API 31 while minSdk is 30,
    // so on API 30 the configChanges declaration is not read at all and the view is recreated
    // on every rotation, theme change, locale change and density change.
    private val contextBuffer = InputContextBuffer()
    private var currentLayoutId: String = Layouts.HEBREW
    private var shifted: Boolean = false

    private var keyboardView: KeyboardView? = null

    override fun onCreateInputView(): View {
        val host = KeyboardHostView(this)
        val keyboard = KeyboardView(this).apply {
            setLayout(Layouts.byId(currentLayoutId))
            onKeyPressed = ::handleKey
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        keyboardView = keyboard
        host.addView(keyboard)
        return host
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)

        // M2/M3 FAIL-CLOSED DEFAULT, replaced by SensitiveFieldPolicy in M4.
        //
        // TextView.onCreateInputConnection() calls outAttrs.setInitialSurroundingText(mText)
        // unconditionally -- including for password fields, with no inputType check -- so this
        // service is handed up to 2048 characters of plaintext it never asked for, and holds
        // it the moment it reads `info`. Until the field classification exists, EVERY field is
        // treated as restricted and the initial text is never read. The cost is a keyboard
        // with no left context; the cost of the opposite default is password plaintext in a
        // process that promised to hold none.
        contextBuffer.reset(
            initialTextBeforeCursor = null,
            cursorPosition = info?.initialSelStart ?: 0,
        )
        shifted = false
    }

    override fun onFinishInput() {
        super.onFinishInput()
        contextBuffer.clear()
        shifted = false
    }

    /** The only trustworthy signal about the editor's real state. See the class docs. */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        contextBuffer.onSelectionUpdated(newSelStart, newSelEnd)
    }

    private fun handleKey(key: Key) {
        val commands = KeyPressPlanner.plan(key, contextBuffer, shifted)
        val ic = currentInputConnection ?: return

        // One batch per press, so a multi-command press is a single editor update rather than
        // a visible flicker. The return values of begin/endBatchEdit are meaningless and are
        // deliberately ignored.
        ic.beginBatchEdit()
        try {
            commands.forEach { execute(it, ic) }
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun execute(command: EditCommand, ic: InputConnection) {
        when (command) {
            is EditCommand.CommitText -> {
                ic.commitText(command.text, 1)
                contextBuffer.onTextCommitted(command.text)
                if (shifted) setShift(false)
            }

            is EditCommand.DeleteBeforeCodePoints -> {
                // deleteSurroundingTextInCodePoints, never deleteSurroundingText: the latter
                // counts UTF-16 code units and will split a surrogate pair.
                ic.deleteSurroundingTextInCodePoints(command.codePoints, 0)
                contextBuffer.onCharsDeleted(command.codePoints)
            }

            EditCommand.PerformEditorAction -> {
                val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                if (action == null || action == EditorInfo.IME_ACTION_NONE ||
                    action == EditorInfo.IME_ACTION_UNSPECIFIED
                ) {
                    ic.commitText("\n", 1)
                    contextBuffer.onTextCommitted("\n")
                } else {
                    ic.performEditorAction(action)
                }
            }

            is EditCommand.SwitchLayout -> {
                currentLayoutId = command.layoutId
                keyboardView?.setLayout(Layouts.byId(command.layoutId))
                setShift(false)
            }

            EditCommand.NextInputMethod -> switchToNextInputMethod(false)

            EditCommand.ToggleShift -> setShift(!shifted)
        }
    }

    private fun setShift(value: Boolean) {
        if (shifted != value) {
            shifted = value
            keyboardView?.invalidate()
        }
    }
}

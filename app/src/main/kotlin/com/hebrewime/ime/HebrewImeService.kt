package com.hebrewime.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import com.hebrewime.core.input.InputContextBuffer
import com.hebrewime.ime.view.KeyboardHostView

/**
 * The Hebrew input method.
 *
 * Four platform traps are avoided here on purpose, and all four compile cleanly, so none of
 * them would be caught by the compiler or by Lint. `GATE-API-1` bans each statically:
 *
 * 1. **`onCreateInputMethodSessionInterface()` is never overridden.** At targetSdk >= 34 the
 *    compat change `DISALLOW_INPUT_METHOD_INTERFACE_OVERRIDE` throws `LinkageError` from
 *    `onCreate()` -- an instant crash on launch. The method is public and overridable in the
 *    API 36 SDK, so nothing warns you.
 * 2. **No `InputConnection` return value is ever branched on.** `IRemoteInputConnection` is
 *    `oneway`; `commitText`, `setComposingText`, `deleteSurroundingText`, `beginBatchEdit` and
 *    `endBatchEdit` return `true` even when the editor dropped the command on a session-id
 *    mismatch. Reconciliation happens in [onUpdateSelection], and nowhere else.
 * 3. **`getTextBeforeCursor()` is never called.** It is a blocking Binder round-trip that can
 *    stall for up to `MAX_WAIT_TIME_MILLIS` (2000 ms). Context is read once from
 *    [EditorInfo] and maintained incrementally by [InputContextBuffer].
 * 4. **No state lives in the input View.** See [KeyboardHostView].
 *
 * `InputMethodManager.invalidateInput()` can force-close a batch edit and destroy the
 * composing span at any moment; AOSP unwinds up to 16 `endBatchEdit()` calls and then falls
 * back to `restartInput`. Nothing here assumes a batch or a composing region survives across
 * calls.
 */
class HebrewImeService : InputMethodService() {

    /**
     * All session state lives on the service, never on the view -- see [KeyboardHostView].
     */
    private val contextBuffer = InputContextBuffer()

    override fun onCreateInputView(): View = KeyboardHostView(this)

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)

        // M2 FAIL-CLOSED DEFAULT.
        //
        // TextView.onCreateInputConnection() calls outAttrs.setInitialSurroundingText(mText)
        // unconditionally -- including for password fields, with no inputType check -- so this
        // service is handed up to 2048 characters of plaintext it never asked for, and would
        // hold it in memory the moment it read `info`.
        //
        // The field classification that decides when that text may be used is M4
        // (SensitiveFieldPolicy). Until it exists, EVERY field is treated as restricted and
        // the initial text is never read. That is the safe direction to be wrong in: the cost
        // is a keyboard with no left context, and the cost of the other default is password
        // plaintext in a process that promised not to hold any.
        contextBuffer.reset(initialTextBeforeCursor = null, cursorPosition = info?.initialSelStart ?: 0)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        contextBuffer.clear()
    }

    /**
     * The only trustworthy signal about the editor's real state. See the class docs for why
     * `InputConnection` return values cannot serve this purpose.
     */
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
}

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
import com.hebrewime.core.privacy.FieldDescriptor
import com.hebrewime.core.privacy.SensitiveFieldPolicy
import com.hebrewime.core.privacy.SessionStart
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

    /**
     * What this session is permitted to do. Never null once input has started; defaults to the
     * most restrictive possible value so that any path reaching it before [onStartInput] is
     * safe rather than permissive.
     */
    private var session: SessionStart = SensitiveFieldPolicy.beginSession(
        FieldDescriptor(inputType = UNKNOWN_INPUT_TYPE), 0,
    ) { null }

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

    /**
     * THE PRIVACY BOUNDARY.
     *
     * `TextView.onCreateInputConnection()` calls `outAttrs.setInitialSurroundingText(mText)`
     * **unconditionally**, with no `inputType` check -- password fields included. So by the
     * time this method is entered, `info` may already be holding up to 2048 characters of
     * password plaintext that this app never asked for and cannot refuse.
     *
     * Two things happen here, in this order, and nothing else touches `info`'s text:
     *
     * 1. The initial text is passed to [SensitiveFieldPolicy.beginSession] as a **lazy
     *    provider**. On a restricted field the provider is never invoked, so the plaintext is
     *    never pulled into this process at all. "Read it and then discard it" would already
     *    have copied it into a `CharSequence` for the GC to release whenever it liked, with
     *    any crash or heap dump in between exposing it.
     * 2. `setInitialSurroundingText("")` overwrites the copy the *framework* handed over,
     *    dropping this process's reference to it rather than waiting for `info` to be
     *    collected.
     *
     * `GATE-PRIV-1` enforces statically that the `getInitial*` accessors appear nowhere else
     * in the codebase.
     */
    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        shifted = false

        if (info == null) {
            // No descriptor at all is the most suspicious case there is, not the most benign.
            session = SensitiveFieldPolicy.beginSession(
                FieldDescriptor(inputType = UNKNOWN_INPUT_TYPE), 0,
            ) { null }
            contextBuffer.reset(null, 0)
            return
        }

        val descriptor = FieldDescriptor(
            inputType = info.inputType,
            imeOptions = info.imeOptions,
            hintText = info.hintText,
            fieldName = info.fieldName ?: info.label,
            packageName = info.packageName,
            // EditorInfo carries no maxLength, so the OTP heuristic's "short numeric field"
            // signal is unavailable in production and only the keyword signal applies. Stated
            // rather than quietly assumed: see docs/milestones/M4.md.
            maxLength = null,
        )

        session = SensitiveFieldPolicy.beginSession(descriptor, info.initialSelStart) {
            info.getInitialTextBeforeCursor(MAX_INITIAL_CONTEXT, 0)
        }

        if (session.isRestricted) {
            // Drop the framework's own retained copy, rather than leaving it alive for as long
            // as this EditorInfo is.
            info.setInitialSurroundingText("")
        }

        contextBuffer.reset(session.initialTextBeforeCursor, session.cursorPosition)
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

    /** True when this session may offer suggestions. Consulted by M5's candidate strip. */
    fun maySuggest(): Boolean = session.maySuggest

    /** True when this session may write to the personal dictionary. Consulted by M6. */
    fun mayLearn(): Boolean = session.mayLearn

    private fun setShift(value: Boolean) {
        if (shifted != value) {
            shifted = value
            keyboardView?.invalidate()
        }
    }

    private companion object {
        /**
         * How much left context to read on a non-restricted field.
         *
         * EditorInfo documents 2048 as the memory-efficient length, and nothing this keyboard
         * does needs more than a sentence. Read ONCE here, never per keystroke: per-keystroke
         * reads would mean `getTextBeforeCursor`, a blocking Binder round-trip of up to
         * 2000 ms, which is banned by GATE-API-1.
         */
        const val MAX_INITIAL_CONTEXT = 2048

        /**
         * An input type that matches no known class, so [SensitiveFieldPolicy] fails closed.
         * Used before any field is known and when `EditorInfo` is null.
         */
        const val UNKNOWN_INPUT_TYPE = 0xf
    }
}

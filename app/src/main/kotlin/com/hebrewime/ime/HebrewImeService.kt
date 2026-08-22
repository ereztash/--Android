package com.hebrewime.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import com.hebrewime.core.input.InputContextBuffer
import com.hebrewime.core.keyboard.EditCommand
import com.hebrewime.core.keyboard.Key
import com.hebrewime.core.keyboard.KeyPressPlanner
import com.hebrewime.core.keyboard.Layouts
import com.hebrewime.core.prediction.Prediction
import com.hebrewime.core.prediction.SuggestionKind
import com.hebrewime.core.prediction.TypingContext
import com.hebrewime.core.privacy.FieldDescriptor
import com.hebrewime.core.privacy.SensitiveFieldPolicy
import com.hebrewime.core.privacy.SessionStart
import com.hebrewime.diagnostics.DeviceEvidence
import com.hebrewime.ime.correction.CorrectionController
import com.hebrewime.ime.view.CandidateStripView
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
 *
 * ### This keyboard never replaces anything by itself
 * `CorrectionEngine.shouldAutoReplace` exists, is measured, and is **not called from here**.
 * Every change to the user's text comes from a tap on the candidate strip.
 *
 * The measurement is the argument. On the unbiased golden corpus the shipped configuration
 * auto-replaces 24.25% of misspellings, of which **1.90% are wrong** — text the user meant,
 * silently replaced with text they did not write, possibly noticed only much later. The
 * alternative costs one tap. Those two errors are not comparable, and the operator asked for a
 * keyboard that *offers* corrections, not one that makes them.
 *
 * An undo path used to sit in [execute] to reverse an automatic replacement with one backspace.
 * It was unreachable: nothing ever set the field it read. Dead code that describes a feature
 * the app does not have is worse than no code, because it reads like evidence the feature was
 * considered and handled. Removed, with the decision recorded here instead.
 */
class HebrewImeService : InputMethodService() {

    // ALL session state lives on the service. onCreateInputView() is NOT called once -- its
    // javadoc is wrong: initViews() nulls the input view and re-runs from
    // resetStateForNewConfiguration() on every configuration change not covered by
    // <input-method android:configChanges>. That declaration is honoured at minSdk 31
    // (InputMethodInfo.getConfigChanges() is API 31), but it lists specific changes, not all of
    // them, so anything held in the view is still lost on a change outside the list.
    private val contextBuffer = InputContextBuffer()
    private var currentLayoutId: String = Layouts.HEBREW
    private var shifted: Boolean = false

    private lateinit var correction: CorrectionController

    /**
     * What this session is permitted to do. Initialised to the most restrictive possible value
     * so that any path reaching it before [onStartInput] is safe rather than permissive.
     */
    private var session: SessionStart = restrictedSession()

    private var keyboardView: KeyboardView? = null
    private var candidateStrip: CandidateStripView? = null

    override fun onCreate() {
        super.onCreate()
        correction = CorrectionController(this)
        correction.warmUp()
    }

    override fun onCreateInputView(): View {
        val host = KeyboardHostView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val strip = CandidateStripView(this).apply {
            onCandidateChosen = ::applySuggestion
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val keyboard = KeyboardView(this).apply {
            setLayout(Layouts.byId(currentLayoutId))
            onKeyPressed = ::handleKey
            onLongPressAlternate = ::replaceWithAlternate
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        column.addView(strip)
        column.addView(keyboard)
        host.addView(column)
        candidateStrip = strip
        keyboardView = keyboard
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
     * `GATE-API-1` enforces statically that the `getInitial*` accessors appear nowhere else
     * in the codebase.
     */
    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        shifted = false
        // Results computed for the previous field must never surface in this one -- which
        // matters most when the previous field was a password.
        correction.cancelOutstanding()
        candidateStrip?.setCandidates(emptyList())

        if (info == null) {
            // No descriptor at all is the most suspicious case there is, not the most benign.
            session = restrictedSession()
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

        // Whether the policy ever REACHED for the plaintext, recorded rather than assumed.
        // M4-DEVICE asks exactly this and the only honest answer is one the running keyboard
        // gives: the provider below is lazy, and on a restricted field it must never be
        // invoked. A boolean is the whole record -- the text itself is never touched here and
        // never written anywhere.
        var initialTextRead = false
        session = SensitiveFieldPolicy.beginSession(descriptor, info.initialSelStart) {
            initialTextRead = true
            info.getInitialTextBeforeCursor(MAX_INITIAL_CONTEXT, 0)
        }

        if (session.isRestricted) {
            // Drop the framework's own retained copy, rather than leaving it alive for as long
            // as this EditorInfo is.
            info.setInitialSurroundingText("")
            DeviceEvidence.recordRestrictedField(
                this,
                // maySuggest true on a restricted field would be the policy contradicting
                // itself; it is recorded as a served field so the self-check reports it.
                served = session.maySuggest,
                initialTextTaken = initialTextRead,
            )
        }

        contextBuffer.reset(session.initialTextBeforeCursor, session.cursorPosition)

        if (session.maySuggest) {
            // The settings screen and the keyboard are separate components. Without this, a
            // word the user just added stays underlined until the process restarts -- and,
            // worse for the learning switch, turning learning OFF would not take effect until
            // the IME process happened to die.
            correction.refreshPersonalDictionary()
            correction.refreshLearningState()
            DeviceEvidence.recordDictionaryReload(this)
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        // One focused field is one learning session. That is what makes
        // UserNgramModel.minimumSessions mean "the user came back to this pair later" rather
        // than "the user repeated it in one message".
        correction.endLearningSession()
        contextBuffer.clear()
        correction.cancelOutstanding()
        candidateStrip?.setCandidates(emptyList())
        session = restrictedSession()
        shifted = false
    }

    override fun onDestroy() {
        correction.shutdown()
        super.onDestroy()
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
        if (!contextBuffer.onSelectionUpdated(newSelStart, newSelEnd)) {
            // The cursor moved somewhere this IME did not put it, so any pending suggestion
            // describes a word that is no longer under the cursor.
            correction.cancelOutstanding()
            candidateStrip?.setCandidates(emptyList())
        }
    }

    private fun handleKey(key: Key) {
        // The whole keystroke, finger to committed text: planning, the batch edit across the
        // Binder, the learning hook and the suggestion refresh. This is the only latency a
        // person experiences, and until now nothing measured it on a phone -- the benchmark
        // harness cannot, because it cannot make itself the active IME without
        // WRITE_SECURE_SETTINGS. `System.nanoTime` rather than the wall clock: this is an
        // interval, and the wall clock can step.
        val startedAt = System.nanoTime()
        try {
            handleKeyTimed(key)
        } finally {
            DeviceEvidence.recordKeystroke(this, (System.nanoTime() - startedAt) / 1_000L)
        }
    }

    private fun handleKeyTimed(key: Key) {
        val commands = KeyPressPlanner.plan(key, contextBuffer, shifted)
        val ic = currentInputConnection ?: return
        // Captured before the edit so a word boundary can be detected afterwards: a word that
        // was in progress and is no longer is a word the user finished.
        val wordInProgress = contextBuffer.currentWord

        // One batch per press, so a multi-command press is a single editor update rather than
        // a visible flicker. The return values of begin/endBatchEdit are meaningless and are
        // deliberately ignored.
        ic.beginBatchEdit()
        try {
            commands.forEach { execute(it, ic) }
        } finally {
            ic.endBatchEdit()
        }
        learnCompletedWord(wordInProgress)
        refreshSuggestions()
    }

    /**
     * THE LEARNING BOUNDARY.
     *
     * This is the only place in the app that feeds the adaptive layer, and it is guarded by
     * `session.mayLearn` — the flag `SensitiveFieldPolicy` has exposed since M4 and nothing
     * read until now. `mayLearn` is **stricter than `maySuggest`**: it is false for every
     * restricted field, and additionally false for person-name and postal-address fields, which
     * still get suggestions but are never memorised. Writing those into a persistent model
     * would turn a transient field into a stored record of who someone knows and where they
     * live.
     *
     * The second condition, the user's opt-in, is checked inside
     * [CorrectionController.learn]. The two are deliberately in different places: an opt-in
     * that silently covered password fields would be worthless, and a field policy applied to
     * people who never opted in would be a broken promise. Either one being false is enough.
     *
     * A pair is learned only when a word actually completes — [wordInProgress] was non-empty
     * and the buffer's current word is now empty — so mid-word keystrokes teach nothing and a
     * word abandoned by backspacing teaches nothing either.
     *
     * `GATE-LEARN-2` asserts statically that this guard exists and that no other file calls
     * `learn`.
     */
    private fun learnCompletedWord(wordInProgress: String) {
        if (!session.mayLearn) return
        if (wordInProgress.isEmpty() || contextBuffer.currentWord.isNotEmpty()) return
        val completed = contextBuffer.completedWords(2)
        if (completed.size < 2) return
        // completedWords is most-recent-first, so this is (the word before, the word just
        // finished) -- the order the model is keyed on.
        correction.learn(completed[1], completed[0])
    }

    /**
     * A long press resolved to an alternate character — the gershayim under the quote key.
     *
     * The original was already committed on DOWN, so this removes exactly that and commits the
     * alternate in its place, inside one batch so the editor sees a single update rather than a
     * visible flicker of the wrong character.
     *
     * Deletion is in **code points**, not UTF-16 units, for the same reason every other deletion
     * in this file is: a key whose output is a surrogate pair would otherwise be half-deleted.
     */
    private fun replaceWithAlternate(key: Key, alternate: String) {
        val ic = currentInputConnection ?: return
        val original = key.output ?: return
        ic.beginBatchEdit()
        try {
            ic.deleteSurroundingTextInCodePoints(original.codePointCount(), 0)
            ic.commitText(alternate, 1)
        } finally {
            ic.endBatchEdit()
        }
        contextBuffer.onCharsDeleted(original.length)
        contextBuffer.onTextCommitted(alternate)
        refreshSuggestions()
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

    /**
     * Ask for suggestions for the current input position, if this field allows any.
     *
     * An **empty** current word is a legitimate request, not an early exit: it is exactly the
     * moment after a word boundary when a next-word prediction is what the user wants. Only a
     * restricted field short-circuits, and it does so before either word is handed over.
     *
     * `contextBuffer.previousWord` is null after a desync, in a field whose initial text was
     * withheld, and across a sentence boundary. Each of those is passed through as null rather
     * than as a guess; the engine then simply has no next-word answer.
     */
    private fun refreshSuggestions() {
        if (!session.maySuggest) {
            correction.cancelOutstanding()
            candidateStrip?.setCandidates(emptyList())
            return
        }
        // Three completed words: the one being checked for a real-word error, and one on each
        // side of it. `completedWords` stops at a sentence boundary and returns nothing at all
        // when the preceding context is unknown, so a short list here means "less is known",
        // never "assume the rest".
        val context = TypingContext(
            current = contextBuffer.currentWord,
            completed = contextBuffer.completedWords(CONTEXT_WORDS),
        )
        if (context.current.isEmpty() && context.completed.isEmpty()) {
            correction.cancelOutstanding()
            candidateStrip?.setCandidates(emptyList())
            return
        }
        correction.requestPredictions(context, allowed = session.maySuggest) { predicted ->
            candidateStrip?.setCandidates(predicted)
        }
    }

    /**
     * Apply a suggestion the user tapped.
     *
     * The two cases are genuinely different edits and are not merged:
     *
     * - A [SuggestionKind.NEXT_WORD] is offered when nothing is being typed, so it is
     *   **appended**. Deleting "the current word" here would delete zero characters on a good
     *   day and the preceding space on a bad one.
     * - A completion or correction **replaces** what is under the cursor.
     *
     * Neither case is queued for undo, because neither happened without being asked for. See
     * the class docs on why this keyboard never replaces anything by itself.
     */
    private fun applySuggestion(prediction: Prediction) {
        val ic = currentInputConnection ?: return
        val original = contextBuffer.currentWord

        if (prediction.wordsBack > 0) {
            // Confirm ONLY if the rewrite happened. See applyToEarlierWord's guard.
            if (applyToEarlierWord(prediction, ic)) {
                candidateStrip?.confirmApplied(prediction)
            }
            return
        }

        if (prediction.kind == SuggestionKind.NEXT_WORD) {
            // Offered only when the current word is empty. If something has been typed since
            // the request went out, the prediction describes a position the cursor has left.
            if (original.isNotEmpty()) {
                candidateStrip?.setCandidates(emptyList())
                return
            }
            ic.commitText(prediction.word, 1)
            contextBuffer.onTextCommitted(prediction.word)
            candidateStrip?.setCandidates(emptyList())
            return
        }

        if (original.isEmpty()) return
        // Counted here rather than at the top of this method, because the branches above return
        // without committing anything and an "accepted" count that includes them would be
        // counting taps rather than acceptances.
        com.hebrewime.learning.LearningPreferences.recordAcceptedCompletion(
            this, prediction.fromUserModel,
        )
        ic.beginBatchEdit()
        try {
            ic.deleteSurroundingTextInCodePoints(original.codePointCount(), 0)
            ic.commitText(prediction.word, 1)
        } finally {
            ic.endBatchEdit()
        }
        contextBuffer.onCharsDeleted(original.length)
        contextBuffer.onTextCommitted(prediction.word)
        candidateStrip?.setCandidates(emptyList())
    }

    /**
     * Replace a word that is **not** under the cursor, for a real-word error.
     *
     * `InputConnection` can only delete a run immediately before the cursor, so correcting
     * `אם` in `דיברתי אם המורה ` means deleting `אם המורה ` and committing `עם המורה `. The
     * text between the corrected word and the cursor is carried over **verbatim** from the
     * buffer rather than reconstructed, so whatever the user typed there — spacing,
     * punctuation, a word this IME has no opinion about — survives the edit untouched.
     *
     * Bails out rather than guessing whenever the buffer's view has moved on: if the word at
     * that position is no longer the one the finding was about, the suggestion describes text
     * that is no longer there, and applying it would rewrite something the user did not ask to
     * change.
     */
    /**
     * @return true when the edit was actually made. **The caller must not confirm a rewrite
     *   this refused**: the guard below rejects a suggestion whose target has moved since the
     *   request went out, and telling the user their sentence was fixed when it was not is
     *   worse than saying nothing at all.
     */
    private fun applyToEarlierWord(prediction: Prediction, ic: InputConnection): Boolean {
        val replaced = prediction.replaces
        val tail = contextBuffer.tailFromCompletedWord(prediction.wordsBack)
        if (replaced == null || tail == null || !tail.startsWith(replaced)) {
            candidateStrip?.setCandidates(emptyList())
            return false
        }
        val rewritten = prediction.word + tail.substring(replaced.length)

        ic.beginBatchEdit()
        try {
            ic.deleteSurroundingTextInCodePoints(tail.codePointCount(), 0)
            ic.commitText(rewritten, 1)
        } finally {
            ic.endBatchEdit()
        }
        contextBuffer.onCharsDeleted(tail.length)
        contextBuffer.onTextCommitted(rewritten)
        candidateStrip?.setCandidates(emptyList())
        return true
    }

    private fun String.codePointCount(): Int = codePointCount(0, length)

    private fun restrictedSession(): SessionStart =
        SensitiveFieldPolicy.beginSession(
            FieldDescriptor(inputType = UNKNOWN_INPUT_TYPE), 0,
        ) { null }

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
         * How many completed words to hand the engine.
         *
         * Three: the word being checked for a real-word error, and one on each side of it.
         *
         * It was four for one commit, to feed the distance-2 layer a left neighbour. That
         * layer is withdrawn, nothing reads the fourth word, and scanning for context nobody
         * consumes is work on the input path.
         */
        const val CONTEXT_WORDS = 3

        /**
         * An input type that matches no known class, so [SensitiveFieldPolicy] fails closed.
         * Used before any field is known and when `EditorInfo` is null.
         */
        const val UNKNOWN_INPUT_TYPE = 0xf
    }
}

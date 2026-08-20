package com.hebrewime.core.input

import com.hebrewime.core.lexicon.HebrewText

/**
 * The IME's own model of the text around the cursor.
 *
 * ### Why this exists at all
 * The obvious way to know what the user has typed is to ask the editor, with
 * `getTextBeforeCursor()`. That is a blocking Binder round-trip which can stall for up to
 * `RemoteInputConnection.MAX_WAIT_TIME_MILLIS` (2000 ms), so calling it per keystroke is
 * incompatible with any sub-50 ms budget. It is banned outright by `GATE-API-1`.
 *
 * So the context is read **once**, from `EditorInfo` in `onStartInput`, and maintained
 * incrementally from the IME's own edits after that.
 *
 * ### Why the buffer must be able to say "I don't know"
 * `IRemoteInputConnection` is `oneway`. `commitText`, `setComposingText` and
 * `deleteSurroundingText` all return `true` even when the editor silently dropped the command
 * on a session-id mismatch, so an incremental model built from "commands I sent" is a model of
 * what the editor *should* contain, not what it does contain. The only honest reconciliation
 * signal is `onUpdateSelection`.
 *
 * This class therefore tracks an [expectedCursor] and compares it against every selection
 * update. When they disagree — a dropped command, a tap elsewhere, autofill, an
 * `invalidateInput()` that destroyed the composing span — it does not guess. It drops to
 * [precedingContextKnown] `= false` and says so. Callers must not offer context-dependent
 * corrections while the context is unknown.
 *
 * Recovery is deliberately passive: at the next word boundary the current word starts fresh
 * from characters this IME typed itself, which are known regardless of what precedes them.
 * There is no re-fetch, because the only re-fetch available at minSdk 30 is the banned
 * blocking call.
 *
 * Not thread-safe: confined to the IME's main thread, like `InputConnection` itself.
 */
class InputContextBuffer(
    /**
     * Cap on retained preceding text. `EditorInfo` documents 2048 as the memory-efficient
     * length, and nothing this keyboard does needs more than a sentence of left context.
     */
    private val maxContext: Int = DEFAULT_MAX_CONTEXT,
) {

    private val before = StringBuilder()
    private var wordStart = 0

    /** Where this buffer believes the editor's cursor is. */
    var expectedCursor: Int = 0
        private set

    /**
     * False when a selection update disagreed with [expectedCursor] and the text preceding the
     * current word is therefore unknown. Callers must not use [textBefore] while this is false.
     */
    var precedingContextKnown: Boolean = false
        private set

    /** Number of times the editor's selection disagreed with this buffer's belief. */
    var desyncCount: Int = 0
        private set

    /** Preceding text, or `null` when it is not known. */
    val textBefore: String?
        get() = if (precedingContextKnown) before.toString() else null

    /**
     * The word currently being typed. Always known: it is composed only of characters this IME
     * emitted since the last boundary, whatever happened to the text before it.
     */
    val currentWord: String
        get() = before.substring(wordStart.coerceIn(0, before.length))

    /**
     * Start a new input session from `onStartInput`.
     *
     * @param initialTextBeforeCursor what `EditorInfo.getInitialTextBeforeCursor` returned, or
     *   `null` if unavailable **or deliberately withheld** — see the restricted-field policy in
     *   M4, which discards initial text for password, email, URI, phone and numeric fields
     *   before it reaches this class.
     * @param cursorPosition the editor's reported cursor offset.
     */
    fun reset(initialTextBeforeCursor: CharSequence?, cursorPosition: Int) {
        before.setLength(0)
        if (initialTextBeforeCursor != null) {
            before.append(initialTextBeforeCursor)
            trim()
            precedingContextKnown = true
        } else {
            precedingContextKnown = false
        }
        expectedCursor = cursorPosition.coerceAtLeast(0)
        wordStart = before.length
        recomputeWordStart()
    }

    /** Forget everything. Used when a session ends or a restricted field is entered. */
    fun clear() {
        before.setLength(0)
        wordStart = 0
        expectedCursor = 0
        precedingContextKnown = false
    }

    /** Record text this IME committed. */
    fun onTextCommitted(text: CharSequence) {
        before.append(text)
        expectedCursor += text.length
        trim()
        recomputeWordStart()
    }

    /**
     * Record a deletion of [count] characters before the cursor.
     *
     * [count] is a UTF-16 code-unit count, matching `deleteSurroundingText`. The caller is
     * responsible for having computed a grapheme-correct width (M3); this class only mirrors
     * what was sent.
     */
    fun onCharsDeleted(count: Int) {
        if (count <= 0) return
        val n = minOf(count, before.length)
        before.setLength(before.length - n)
        expectedCursor = (expectedCursor - count).coerceAtLeast(0)
        if (n < count) {
            // We deleted past the start of what we hold, so what remains before the cursor is
            // no longer something we know.
            precedingContextKnown = false
        }
        recomputeWordStart()
    }

    /**
     * Reconcile against `onUpdateSelection`. This is the **only** trustworthy signal about the
     * editor's real state.
     *
     * @return true when the update matched expectation, false when it did not and the
     *   preceding context was dropped.
     */
    fun onSelectionUpdated(newSelStart: Int, newSelEnd: Int): Boolean {
        val collapsed = newSelStart == newSelEnd
        if (collapsed && newSelStart == expectedCursor) return true

        desyncCount++
        // Something moved the cursor that this IME did not do, or a command was dropped.
        // Guessing here is how a keyboard ends up correcting text that is not there.
        before.setLength(0)
        wordStart = 0
        precedingContextKnown = false
        expectedCursor = newSelStart.coerceAtLeast(0)
        return false
    }

    /** True when the current word is a well-formed Hebrew word worth looking up. */
    fun currentWordIsHebrew(): Boolean = HebrewText.isHebrewWord(currentWord)

    private fun trim() {
        if (before.length > maxContext) {
            before.delete(0, before.length - maxContext)
            // Dropping the head means the retained text is a suffix, not the whole context.
            // The word boundary is recomputed below, so the current word stays correct.
        }
    }

    private fun recomputeWordStart() {
        var i = before.length
        while (i > 0 && HebrewText.isHebrewLetter(before[i - 1])) i--
        wordStart = i
    }

    companion object {
        const val DEFAULT_MAX_CONTEXT: Int = 2048
    }
}

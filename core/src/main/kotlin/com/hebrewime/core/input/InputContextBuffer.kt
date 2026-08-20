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
 *
 * There is no re-fetch. At minSdk 31 `InputConnection.getSurroundingText` does exist, but it
 * is the same blocking Binder round-trip as `getTextBeforeCursor` and is banned for the same
 * reason. Using it off the main thread to repair a desync would be defensible -- a desync is
 * rare and not on the keystroke path -- but that is a change to make deliberately, with a
 * measurement behind it, not a thing to slip in because a version bump made the API reachable.
 * Recorded as an option, not taken.
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

    /**
     * The completed word before [currentWord], or null when there is none, it is not known, or
     * a sentence boundary separates it from the cursor.
     *
     * ### Null in three distinct situations, all of them honest
     * 1. **Nothing precedes.** The cursor is at the start of the field.
     * 2. **[precedingContextKnown] is false.** After a desync this buffer does not know what
     *    came before, and a prediction conditioned on a guess is worse than none.
     * 3. **A sentence boundary intervenes.** `scripts/build_bigrams.py` splits the training
     *    text on [SENTENCE_BOUNDARY_CHARS] and the `--` sequence *before* counting pairs, so
     *    the model was never shown a pair that straddles one. Returning the word across a full
     *    stop would be asking the model about input from outside the distribution it was
     *    trained on — not a conservative choice about a risky prediction, but a factual
     *    mismatch between training and inference. The boundary set is pinned to the builder's
     *    by [PreviousWordTest].
     *
     * A non-Hebrew run that is not a boundary — Latin words, digits — does **not** break the
     * pair, because the builder's `HEBREW_RUN_RE` skips over those too and therefore did count
     * the surrounding Hebrew words as adjacent. Mirroring the builder means mirroring both its
     * splits and its non-splits.
     */
    val previousWord: String?
        get() {
            if (!precedingContextKnown) return null
            var end = wordStart.coerceIn(0, before.length)
            while (end > 0 && !isWordChar(before[end - 1])) {
                val c = before[end - 1]
                if (c in SENTENCE_BOUNDARY_CHARS) return null
                if (c == '-' && end >= 2 && before[end - 2] == '-') return null
                end--
            }
            if (end == 0) return null
            var start = end
            while (start > 0 && isWordChar(before[start - 1])) start--
            val word = before.substring(start, end)
            return word.ifEmpty { null }
        }

    /**
     * The last [count] completed words, most recent first, stopping at a sentence boundary.
     *
     * Real-word error detection needs a word on **each** side of the one being checked, and the
     * word being checked is therefore never the one the user is typing — it is the second most
     * recent completed word, whose right-hand neighbour is the most recent one. Measured, that
     * is worth 19.6 points of recall over checking with left context alone; see
     * `docs/CONFUSION_MEASUREMENTS.md`.
     *
     * Returns fewer than [count] words, possibly none, when the text runs out or a boundary
     * intervenes. Empty when [precedingContextKnown] is false: the same rule as [previousWord],
     * for the same reason.
     */
    fun completedWords(count: Int): List<String> {
        if (!precedingContextKnown || count <= 0) return emptyList()
        val out = ArrayList<String>(count)
        var end = wordStart.coerceIn(0, before.length)
        while (out.size < count) {
            while (end > 0 && !isWordChar(before[end - 1])) {
                val c = before[end - 1]
                if (c in SENTENCE_BOUNDARY_CHARS) return out
                if (c == '-' && end >= 2 && before[end - 2] == '-') return out
                end--
            }
            if (end == 0) return out
            var start = end
            while (start > 0 && isWordChar(before[start - 1])) start--
            out.add(before.substring(start, end))
            end = start
        }
        return out
    }

    /**
     * Everything from the start of the [n]th most recent completed word up to the cursor, or
     * null when that word is not known.
     *
     * This is what makes it possible to replace a word that is **not** adjacent to the cursor.
     * `InputConnection` can only delete a run immediately before the cursor, so correcting
     * `אם` in `דיברתי אם המורה ` means deleting `אם המורה ` and committing `עם המורה ` — one
     * batched edit, with the intervening text preserved exactly as the user typed it rather
     * than reconstructed from a model of it.
     *
     * @param n 1 is the most recent completed word.
     */
    fun tailFromCompletedWord(n: Int): String? {
        if (!precedingContextKnown || n <= 0) return null
        var end = wordStart.coerceIn(0, before.length)
        var start = -1
        var found = 0
        while (found < n) {
            while (end > 0 && !isWordChar(before[end - 1])) {
                val c = before[end - 1]
                if (c in SENTENCE_BOUNDARY_CHARS) return null
                if (c == '-' && end >= 2 && before[end - 2] == '-') return null
                end--
            }
            if (end == 0) return null
            start = end
            while (start > 0 && isWordChar(before[start - 1])) start--
            found++
            end = start
        }
        if (start < 0) return null
        return before.substring(start)
    }

    private fun isWordChar(c: Char): Boolean =
        HebrewText.isHebrewLetter(c) || HebrewText.isCombiningMark(c)

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
        // Combining marks count as part of the word. A pointed word is still one word, and
        // scanning back over letters alone would cut it at the first niqqud.
        while (i > 0 && isWordChar(before[i - 1])) i--
        wordStart = i
    }

    companion object {
        const val DEFAULT_MAX_CONTEXT: Int = 2048

        /**
         * Characters the bigram builder splits on, and therefore the characters across which
         * this buffer will not report a previous word.
         *
         * Mirrors `BOUNDARY_RE = re.compile(r"[.!?;:\n\u05c3]|--")` in
         * `scripts/build_bigrams.py`. The `--` sequence is handled separately because it is
         * two characters, and a single hyphen deliberately does not split — the builder does
         * not split on it either.
         */
        val SENTENCE_BOUNDARY_CHARS: Set<Char> =
            setOf('.', '!', '?', ';', ':', '\n', '\u05c3')
    }
}

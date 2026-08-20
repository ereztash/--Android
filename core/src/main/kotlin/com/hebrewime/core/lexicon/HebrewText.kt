package com.hebrewime.core.lexicon

import java.text.Normalizer

/**
 * Hebrew text normalisation, shared by the lexicon build and the runtime.
 *
 * The lexicon is built from unvocalized surface forms, so anything looked up against it must
 * be reduced the same way the build reduced it, or every vocalized word would miss.
 */
object HebrewText {

    /** First Hebrew letter, `alef` (U+05D0). */
    const val FIRST_LETTER: Char = 'א'

    /** Last Hebrew letter, `tav` (U+05EA). Includes the five final forms, which sit inside. */
    const val LAST_LETTER: Char = 'ת'

    /**
     * U+0591..U+05C7, the range holding te'amim (cantillation) and niqqud (points) -- and,
     * mixed in among them, four code points that are punctuation rather than marks.
     */
    private const val FIRST_MARK: Char = '֑'
    private const val LAST_MARK: Char = 'ׇ'

    /**
     * The four punctuation code points inside [FIRST_MARK]..[LAST_MARK].
     *
     * Maqaf is a hyphen (`Pd`); paseq, sof pasuq and nun hafukha are `Po`. None of them is a
     * combining mark, and `HebrewTextTest` checks that claim against `Character.getType`
     * rather than against this list, so the JDK's Unicode tables are the authority and not a
     * comment.
     *
     * They were previously folded into [isCombiningMark], which was harmless for
     * [stripPoints] -- the only caller it was written for -- but wrong for the second caller
     * that later appeared. `InputContextBuffer` asks this class what counts as part of a word,
     * and under the old answer `שלום׃ מה` was ONE word ending in a full stop: no suggestions
     * for either half, and a bigram query across a sentence boundary that the model was never
     * trained on. A predicate borrowed for a job it was not written for.
     */
    /** Gershayim ״ U+05F4 — the mark inside an abbreviation: כ״כ, צה״ל. */
    const val GERSHAYIM: Char = '\u05f4'

    /** Geresh ׳ U+05F3 — the mark ending a shortened word: וכו׳, עמ׳. */
    const val GERESH: Char = '\u05f3'

    private const val MAQAF: Char = '\u05be'
    private const val PASEQ: Char = '\u05c0'
    private const val SOF_PASUQ: Char = '\u05c3'
    private const val NUN_HAFUKHA: Char = '\u05c6'

    /**
     * Gershayim ״ (U+05F4) and geresh ׳ (U+05F3), plus the ASCII `"` and `'` people actually
     * type on a phone.
     *
     * These are the marks of an abbreviation — `כ״כ`, `וכו׳` — and they are the reason
     * [isHebrewWord] alone was never enough: it demands every character be a letter, so any
     * abbreviation failed it and was handed to the spelling corrector as a misspelling.
     */
    fun isAbbreviationMark(c: Char): Boolean =
        c == GERSHAYIM || c == GERESH || c == '"' || c == '\''

    /** The marks removed, leaving the bare letters an abbreviation table is keyed on. */
    fun stripAbbreviationMarks(s: String): String {
        if (s.none { isAbbreviationMark(it) }) return s
        val sb = StringBuilder(s.length)
        for (c in s) if (!isAbbreviationMark(c)) sb.append(c)
        return sb.toString()
    }

    /**
     * True for a Hebrew word that may carry abbreviation marks **inside** it.
     *
     * A leading or trailing mark is not part of the word — that is an opening quote or a
     * closing one, and treating it as part of the token would break every lookup.
     */
    fun isHebrewWordOrAbbreviation(s: CharSequence): Boolean {
        if (s.isEmpty()) return false
        if (isAbbreviationMark(s[0]) || isAbbreviationMark(s[s.length - 1])) {
            // A trailing geresh is the one legitimate exception: `וכו׳` ends with its mark.
            val trailingOnly = !isAbbreviationMark(s[0]) && s.length >= 2
            if (!trailingOnly) return false
        }
        var letters = 0
        for (i in s.indices) {
            val c = s[i]
            if (isHebrewLetter(c)) letters++
            else if (!isAbbreviationMark(c)) return false
        }
        return letters > 0
    }

    fun isHebrewLetter(c: Char): Boolean = c in FIRST_LETTER..LAST_LETTER

    /** True for a real nonspacing mark in the Hebrew block: a ta'am or a point. */
    fun isCombiningMark(c: Char): Boolean =
        c in FIRST_MARK..LAST_MARK && !isHebrewBlockPunctuation(c)

    /** True for the four punctuation code points that share the marks' range. */
    fun isHebrewBlockPunctuation(c: Char): Boolean =
        c == MAQAF || c == PASEQ || c == SOF_PASUQ || c == NUN_HAFUKHA

    /** True when every character is a Hebrew letter and the string is non-empty. */
    fun isHebrewWord(s: CharSequence): Boolean {
        if (s.isEmpty()) return false
        for (i in s.indices) if (!isHebrewLetter(s[i])) return false
        return true
    }

    /**
     * NFC-normalise, then remove every mark in U+0591..U+05C7.
     *
     * NFC first, because a decomposed sequence would otherwise leave a base letter with its
     * mark unmatched by a naive scan. The order matters and matches `scripts/build_lexicon.py`
     * exactly -- if these two ever diverge, lookups silently start missing.
     */
    fun stripPoints(s: String): String {
        if (s.isEmpty()) return s
        val normalized = Normalizer.normalize(s, Normalizer.Form.NFC)
        var needsWork = false
        for (i in normalized.indices) {
            if (isStripped(normalized[i])) { needsWork = true; break }
        }
        if (!needsWork) return normalized
        val sb = StringBuilder(normalized.length)
        for (i in normalized.indices) {
            val c = normalized[i]
            if (!isStripped(c)) sb.append(c)
        }
        return sb.toString()
    }

    /**
     * What [stripPoints] removes: the whole U+0591..U+05C7 range, marks and punctuation alike.
     *
     * Deliberately unchanged by the split above. Stripping maqaf and the rest is what every
     * lexicon lookup in the app already does, so narrowing it here would silently change which
     * words resolve -- a different question from what counts as part of a word, and one that
     * would need its own measurement against the lexicon before anything moved.
     */
    private fun isStripped(c: Char): Boolean = c in FIRST_MARK..LAST_MARK
}

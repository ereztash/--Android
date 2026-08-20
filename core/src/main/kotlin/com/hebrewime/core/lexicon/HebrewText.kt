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
     * U+0591..U+05C7: te'amim (cantillation), niqqud (points), and the handful of
     * punctuation code points that share the block -- maqaf, paseq, sof pasuq, nun hafukha.
     *
     * Stripping the punctuation alongside the points is intentional and harmless: anything
     * left is then required to be pure letters by [isHebrewWord], so a token that was only
     * "a word" because of embedded punctuation is rejected rather than silently joined.
     */
    private const val FIRST_MARK: Char = '֑'
    private const val LAST_MARK: Char = 'ׇ'

    fun isHebrewLetter(c: Char): Boolean = c in FIRST_LETTER..LAST_LETTER

    fun isCombiningMark(c: Char): Boolean = c in FIRST_MARK..LAST_MARK

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
            if (isCombiningMark(normalized[i])) { needsWork = true; break }
        }
        if (!needsWork) return normalized
        val sb = StringBuilder(normalized.length)
        for (i in normalized.indices) {
            val c = normalized[i]
            if (!isCombiningMark(c)) sb.append(c)
        }
        return sb.toString()
    }
}

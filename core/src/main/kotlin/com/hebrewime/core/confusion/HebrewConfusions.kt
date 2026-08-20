package com.hebrewime.core.confusion

import com.hebrewime.core.lexicon.HebrewLexicon

/**
 * Letters that sound the same in Modern Israeli Hebrew, and the real words they turn into.
 *
 * ### The error this exists for
 * `אם` ("if") and `עם` ("with") are both perfectly good words. Nothing in either string is
 * wrong, so a lexicon cannot see the mistake, and edit distance cannot either — every
 * correction engine in the project happily accepts both, correctly. Only the surrounding words
 * carry the information, which is why this is a separate mechanism from correction rather than
 * a wider search inside it.
 *
 * ### Why these pairs and not keyboard neighbours
 * The confusions here are **phonological**, not typographic. Modern Israeli Hebrew has merged
 * distinctions the orthography still writes: `א` and `ע` are both realised as zero or a glottal
 * stop, `ח` and `כ` (without dagesh) are both /x/, `כ` (with dagesh) and `ק` are both /k/, `ת`
 * and `ט` are both /t/, `ב` (without dagesh) and `ו` are both /v/, and `ס` and undotted `ש`
 * (sin) are both /s/. A writer who knows how a word sounds and not how it is spelled picks the
 * wrong letter — and lands on another real word often enough to matter.
 *
 * Keyboard-adjacent slips are a different failure with a different distribution, and they are
 * already handled: when a slip produces a non-word the correction engine catches it. When it
 * produces a real word, only context helps, and folding those pairs in here would mix two
 * error models whose rates were measured separately. Recorded as a deliberate exclusion.
 *
 * ### `ו`/`י` is deliberately NOT in the shipped set
 * It is the largest source of real-word pairs by far — 78,310 against 9,950 for `א`/`ע` — but
 * most of those are not confusions at all. `ktiv male` alternation makes `ו` and `י` genuinely
 * contrastive in a way the other pairs are not, so the overwhelming majority of those pairs are
 * two different words rather than two spellings of one. It stays available as [KTIV_MALE] so
 * the claim can be measured rather than asserted, and the measurement is in
 * `docs/CONFUSION_MEASUREMENTS.md`.
 *
 * ### Nothing is precomputed
 * Variants are generated on demand and checked against the lexicon by binary search. The full
 * inventory is 166,504 ordered pairs and would be a multi-megabyte asset; generating
 * `word.length × pairs` candidates and looking each one up is a few dozen comparisons and
 * costs nothing in the artifact. `GATE-SIZE-1` has 576,837 bytes of headroom and this spends
 * none of it.
 */
object HebrewConfusions {

    /**
     * Letters realised identically in Modern Israeli Hebrew, unpointed.
     *
     * Each entry names the shared realisation, so the reason is in the table and not only in
     * the prose above.
     */
    val HOMOPHONE_PAIRS: List<Pair<Char, Char>> = listOf(
        'א' to 'ע', // zero / glottal stop
        'ח' to 'כ', // /x/
        'כ' to 'ק', // /k/
        'ת' to 'ט', // /t/
        'ב' to 'ו', // /v/
        'ס' to 'ש', // /s/, sin undotted
    )

    /**
     * The `ו`/`י` alternation, kept separate and **off by default**. See the class docs.
     */
    val KTIV_MALE: List<Pair<Char, Char>> = listOf('ו' to 'י')

    /**
     * Final-form errors: writing a medial letter where a final belongs, or the reverse.
     *
     * Orthographic rather than phonological — the two forms sound identical because they *are*
     * the same letter — and a different kind of mistake from picking the wrong letter. Kept
     * separate for the same reason: two error models, two measurements.
     */
    val FINAL_FORMS: List<Pair<Char, Char>> = listOf(
        'כ' to 'ך', 'מ' to 'ם', 'נ' to 'ן', 'פ' to 'ף', 'צ' to 'ץ',
    )

    /**
     * Every real lexicon word one homophone substitution away from [word].
     *
     * @param pairs which confusion table to use. Defaults to [HOMOPHONE_PAIRS].
     * @return words in the lexicon other than [word] itself, in lexicon order. Empty when the
     *   word has no confusable partner, which is the case for roughly two thirds of the
     *   lexicon.
     */
    fun variantsOf(
        word: String,
        lexicon: HebrewLexicon,
        pairs: List<Pair<Char, Char>> = HOMOPHONE_PAIRS,
    ): List<Int> {
        if (word.isEmpty()) return emptyList()
        var out: MutableList<Int>? = null
        val buffer = CharArray(word.length)
        word.toCharArray(buffer)
        for (i in word.indices) {
            val original = buffer[i]
            for ((a, b) in pairs) {
                val swapped = when (original) {
                    a -> b
                    b -> a
                    else -> continue
                }
                buffer[i] = swapped
                val candidate = String(buffer)
                buffer[i] = original
                if (candidate == word) continue
                val index = lexicon.indexOf(candidate)
                if (index >= 0) {
                    val list = out ?: ArrayList<Int>(4).also { out = it }
                    if (index !in list) list.add(index)
                }
            }
        }
        return out ?: emptyList()
    }
}

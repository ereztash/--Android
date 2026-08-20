package com.hebrewime.core.correction

/**
 * Straightforward weighted Damerau-Levenshtein between two strings.
 *
 * This is the **reference** implementation. The engine does not use it on the input path —
 * [LexiconTrie] computes the same quantity incrementally across shared prefixes, which is the
 * only way to search 355,587 words inside an input budget. This exists so that the trie's
 * clever version can be checked against an obvious one: the tests compare them exhaustively
 * over a sample, because a subtly wrong distance produces plausible-looking bad suggestions
 * rather than an error anyone would notice.
 *
 * Optimal string alignment (adjacent transpositions only), not unrestricted Damerau. The
 * difference only shows on strings needing a transposition of characters that were themselves
 * edited in between, which does not occur in single-word typo correction.
 */
object DamerauLevenshtein {

    /** Distance in hundredths; see [EditCostModel.UNIT]. */
    fun distance(a: String, b: String, costs: EditCostModel = NeutralCostModel): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.sumOf { costs.insert(it) }
        if (b.isEmpty()) return a.sumOf { costs.delete(it) }

        val width = b.length + 1
        var twoBack = IntArray(width)
        var oneBack = IntArray(width)
        var current = IntArray(width)

        for (j in 0 until width) {
            oneBack[j] = if (j == 0) 0 else oneBack[j - 1] + costs.insert(b[j - 1])
        }

        for (i in 1..a.length) {
            current[0] = oneBack[0] + costs.delete(a[i - 1])
            for (j in 1..b.length) {
                val substituted =
                    if (a[i - 1] == b[j - 1]) oneBack[j - 1]
                    else oneBack[j - 1] + costs.substitute(a[i - 1], b[j - 1])
                var best = minOf(
                    substituted,
                    oneBack[j] + costs.delete(a[i - 1]),
                    current[j - 1] + costs.insert(b[j - 1]),
                )
                if (i >= 2 && j >= 2 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    val transposed = twoBack[j - 2] + costs.transpose(a[i - 2], a[i - 1])
                    if (transposed < best) best = transposed
                }
                current[j] = best
            }
            val spare = twoBack
            twoBack = oneBack
            oneBack = current
            current = spare
        }
        return oneBack[b.length]
    }
}

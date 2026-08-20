package com.hebrewime.core.correction

/**
 * A flat, array-backed trie over the lexicon, supporting bounded-cost Damerau-Levenshtein
 * search.
 *
 * ### Why a trie and not a scan
 * Brute-force edit distance against 355,587 words is hopeless inside an input budget. A trie
 * shares prefixes, so one dynamic-programming row is computed per *node* rather than per word,
 * and a subtree is abandoned as soon as its best possible cost exceeds the budget.
 *
 * ### Why arrays and not objects
 * A node-per-object trie with a map of children would cost roughly 100 bytes of overhead per
 * node before any data. Here a node is four parallel array slots. Node and edge counts are
 * **measured** by the tests, not estimated.
 *
 * ### Construction exploits the sorted input
 * The word list is already sorted by code point, so the trie is built with a path stack: for
 * each word, descend to the length of its common prefix with the previous word, then append
 * new nodes for the rest. Children are therefore appended in sorted order, and a
 * first-child / next-sibling linkage keeps them so with no per-node map at any point.
 */
class LexiconTrie private constructor(
    /** Character on the edge leading INTO each node. Meaningless for the root. */
    private val charOf: CharArray,
    private val firstChild: IntArray,
    private val nextSibling: IntArray,
    /** Index into the sorted word list when a node ends a word, else [NONE]. */
    private val wordIndex: IntArray,
    val nodeCount: Int,
) {

    /** A candidate found by [search]. [cost] is in hundredths; see [EditCostModel.UNIT]. */
    data class Match(val wordIndex: Int, val cost: Int)

    /**
     * Every word within [maxCost] of [query].
     *
     * @param maxCost budget in hundredths. Two whole edits is `2 * EditCostModel.UNIT`.
     * @param limit stop after this many matches, so a pathological query cannot enumerate a
     *   large part of the lexicon on the input path.
     */
    fun search(
        query: String,
        maxCost: Int,
        costs: EditCostModel,
        limit: Int = DEFAULT_LIMIT,
    ): List<Match> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<Match>()
        val width = query.length + 1

        // Row 0: the cost of deleting each prefix of the query.
        val firstRow = IntArray(width)
        for (i in 1 until width) {
            firstRow[i] = firstRow[i - 1] + costs.delete(query[i - 1])
        }

        var child = firstChild[ROOT]
        while (child != NONE && out.size < limit) {
            descend(child, query, firstRow, null, NO_CHAR, maxCost, costs, out, limit)
            child = nextSibling[child]
        }
        out.sortBy { it.cost }
        return out
    }

    private fun descend(
        node: Int,
        query: String,
        parentRow: IntArray,
        grandparentRow: IntArray?,
        parentChar: Char,
        maxCost: Int,
        costs: EditCostModel,
        out: MutableList<Match>,
        limit: Int,
    ) {
        if (out.size >= limit) return
        val nodeChar = charOf[node]
        val width = query.length + 1
        val row = IntArray(width)
        row[0] = parentRow[0] + costs.insert(nodeChar)

        for (i in 1 until width) {
            val typed = query[i - 1]
            val substituted =
                if (typed == nodeChar) parentRow[i - 1]
                else parentRow[i - 1] + costs.substitute(typed, nodeChar)
            var best = minOf(
                substituted,
                row[i - 1] + costs.delete(typed),
                parentRow[i] + costs.insert(nodeChar),
            )
            // Damerau transposition: the two previous typed characters, swapped.
            if (grandparentRow != null && i >= 2 &&
                typed == parentChar && query[i - 2] == nodeChar
            ) {
                val transposed = grandparentRow[i - 2] + costs.transpose(query[i - 2], typed)
                if (transposed < best) best = transposed
            }
            row[i] = best
        }

        val word = wordIndex[node]
        if (word != NONE && row[width - 1] <= maxCost) {
            out.add(Match(word, row[width - 1]))
        }

        // Prune: if no cell in this row is within budget, no descendant can be either, since
        // every further edit costs at least minimumEditCost.
        var rowMin = Int.MAX_VALUE
        for (v in row) if (v < rowMin) rowMin = v
        if (rowMin > maxCost) return

        var child = firstChild[node]
        while (child != NONE && out.size < limit) {
            descend(child, query, row, parentRow, nodeChar, maxCost, costs, out, limit)
            child = nextSibling[child]
        }
    }

    /**
     * Word indices of every lexicon entry beginning with [prefix].
     *
     * Returned in trie order, which is alphabetical, **not** by frequency -- the caller ranks.
     * That is deliberate: ranking needs the frequency table, which this class does not have,
     * and pushing a frequency index into every node would cost another 2.27 MB of arrays to
     * save a sort of a few thousand ints.
     *
     * @param limit hard cap on results. A one-letter prefix matches a large fraction of the
     *   lexicon, so an unbounded walk would be a real stall on the input path.
     * @return an empty list if the prefix is not present at all.
     */
    fun completions(prefix: String, limit: Int = DEFAULT_LIMIT): List<Int> {
        if (prefix.isEmpty()) return emptyList()
        var node = ROOT
        for (c in prefix) {
            node = childWith(node, c)
            if (node == NONE) return emptyList()
        }
        val out = ArrayList<Int>(minOf(limit, 64))
        collect(node, out, limit)
        return out
    }

    /** Number of lexicon entries beginning with [prefix]. Used to size and measure. */
    fun completionCount(prefix: String): Int {
        val node = nodeFor(prefix) ?: return 0
        var n = 0
        countSubtree(node) { n++ }
        return n
    }

    /**
     * The [limit] highest-scoring completions of [prefix].
     *
     * **Not the first [limit] in trie order.** Measured on the real lexicon, a one-letter
     * prefix has a median of 7,716 completions and a maximum of 54,298, so taking the first N
     * alphabetically would offer the user words beginning `aa…` and never the common ones. The
     * whole subtree is walked and only the top K are retained, which is O(subtree) in time and
     * O(K) in space, with no per-node index array -- that would have cost another 2.27 MB.
     *
     * @param scoreOf higher is better; the frequency table supplies it.
     */
    fun completionsTopK(
        prefix: String,
        limit: Int,
        scoreOf: (Int) -> Int,
    ): List<Int> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val node = nodeFor(prefix) ?: return emptyList()

        // A small insertion-sorted top-K. K is 3 to 8 in practice, so a heap would be slower.
        val bestIds = IntArray(limit) { NONE }
        val bestScores = IntArray(limit) { Int.MIN_VALUE }
        var held = 0

        countSubtree(node) { word ->
            val score = scoreOf(word)
            if (held < limit || score > bestScores[held - 1]) {
                var slot = if (held < limit) held++ else limit - 1
                while (slot > 0 && bestScores[slot - 1] < score) {
                    bestScores[slot] = bestScores[slot - 1]
                    bestIds[slot] = bestIds[slot - 1]
                    slot--
                }
                bestScores[slot] = score
                bestIds[slot] = word
            }
        }
        return (0 until held).map { bestIds[it] }
    }

    private fun nodeFor(prefix: String): Int? {
        var node = ROOT
        for (c in prefix) {
            node = childWith(node, c)
            if (node == NONE) return null
        }
        return node
    }

    /** Visits every word index in the subtree rooted at [node]. Iterative, to bound stack use. */
    private inline fun countSubtree(node: Int, visit: (Int) -> Unit) {
        val stack = IntArray(64)
        var depth = 0
        stack[depth++] = node
        var buffer = stack
        while (depth > 0) {
            val current = buffer[--depth]
            val word = wordIndex[current]
            if (word != NONE) visit(word)
            var child = firstChild[current]
            while (child != NONE) {
                if (depth == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
                buffer[depth++] = child
                child = nextSibling[child]
            }
        }
    }

    private fun childWith(node: Int, c: Char): Int {
        var child = firstChild[node]
        while (child != NONE) {
            if (charOf[child] == c) return child
            child = nextSibling[child]
        }
        return NONE
    }

    private fun collect(node: Int, out: MutableList<Int>, limit: Int) {
        if (out.size >= limit) return
        val word = wordIndex[node]
        if (word != NONE) out.add(word)
        var child = firstChild[node]
        while (child != NONE && out.size < limit) {
            collect(child, out, limit)
            child = nextSibling[child]
        }
    }

    /** Edges in the trie. Every node except the root is entered by exactly one edge. */
    val edgeCount: Int get() = nodeCount - 1

    /** Bytes held by the four arrays. */
    val heapBytes: Long
        get() = nodeCount.toLong() * (Char.SIZE_BYTES + 3L * Int.SIZE_BYTES)

    companion object {
        private const val ROOT = 0
        private const val NONE = -1
        private const val NO_CHAR = ' '
        const val DEFAULT_LIMIT = 256

        /**
         * Build from a **sorted** word list.
         *
         * Sortedness is required, not merely preferred, and is checked. The path-stack
         * construction produces a wrong trie for unsorted input rather than a slow one, and a
         * silently wrong dictionary is far worse than a loud failure.
         */
        fun build(words: List<String>): LexiconTrie {
            // ### Sizing: grow, do not reserve for the worst case
            //
            // The obvious capacity is the total number of characters, since that is the most
            // nodes a trie could possibly need. It is also, for a real dictionary, roughly
            // FOUR TIMES too many -- a trie exists precisely because words share prefixes.
            //
            // Measured on the shipped lexicon: 2,125,923 characters, 567,767 actual nodes, a
            // 3.7x over-allocation. At 18 bytes per node across the five arrays that reserved
            // 37.4 MB to use 10 MB, transiently, inside an IME process -- one of the most
            // heap-constrained processes on Android. Losing that race throws OutOfMemoryError,
            // which the warm-up path catches, leaving a keyboard that types and never
            // suggests.
            //
            // So start near the word count and double. Node count runs about 1.6x words for
            // Hebrew, so this is normally one growth, and the peak is bounded by the growth
            // rather than by the worst case that never happens.
            var capacity = maxOf(16, words.size + 16)
            var charOf = CharArray(capacity)
            var firstChild = IntArray(capacity) { NONE }
            var nextSibling = IntArray(capacity) { NONE }
            var wordIndex = IntArray(capacity) { NONE }
            var lastChild = IntArray(capacity) { NONE }
            var count = 1 // the root

            fun ensure(needed: Int) {
                if (needed <= capacity) return
                val grown = maxOf(capacity * 2, needed)
                charOf = charOf.copyOf(grown)
                firstChild = firstChild.copyOf(grown).also {
                    java.util.Arrays.fill(it, capacity, grown, NONE)
                }
                nextSibling = nextSibling.copyOf(grown).also {
                    java.util.Arrays.fill(it, capacity, grown, NONE)
                }
                wordIndex = wordIndex.copyOf(grown).also {
                    java.util.Arrays.fill(it, capacity, grown, NONE)
                }
                lastChild = lastChild.copyOf(grown).also {
                    java.util.Arrays.fill(it, capacity, grown, NONE)
                }
                capacity = grown
            }

            var path = IntArray(64)
            var previous = ""

            words.forEachIndexed { index, word ->
                require(word >= previous) {
                    "LexiconTrie.build requires sorted input; got '$word' after '$previous'"
                }
                if (path.size < word.length + 1) path = path.copyOf(word.length * 2 + 2)

                var common = 0
                val shared = minOf(word.length, previous.length)
                while (common < shared && word[common] == previous[common]) common++

                var node = if (common == 0) ROOT else path[common]
                ensure(count + (word.length - common))
                for (depth in common until word.length) {
                    val child = count++
                    charOf[child] = word[depth]
                    val last = lastChild[node]
                    if (last == NONE) firstChild[node] = child else nextSibling[last] = child
                    lastChild[node] = child
                    path[depth + 1] = child
                    node = child
                }
                wordIndex[node] = index
                previous = word
            }

            if (count < capacity) {
                charOf = charOf.copyOf(count)
                firstChild = firstChild.copyOf(count)
                nextSibling = nextSibling.copyOf(count)
                wordIndex = wordIndex.copyOf(count)
            }
            return LexiconTrie(charOf, firstChild, nextSibling, wordIndex, count)
        }
    }
}

package com.hebrewime.core.scratch

import com.hebrewime.core.correction.EditCostModel
import com.hebrewime.core.correction.LexiconTrie

/**
 * `E3` — a succinct representation of the same trie, built to be measured, not to be shipped.
 *
 * ### What it replaces, and why those three arrays are the target
 * `LexiconTrie` holds four parallel arrays over 567,767 nodes: `charOf` (`Char`), and
 * `firstChild`, `nextSibling`, `wordIndex` (`Int`). That is 7,948,738 bytes, of which **86% is
 * the three arrays of node ids**. Compressing the labels would be pointless; the ids are the
 * whole cost.
 *
 * ### Two properties of `LexiconTrie.build` make the ids redundant rather than compressible
 * `build` requires sorted input and appends children in order, descending the path stack. That
 * assigns node ids in **DFS pre-order**, and two consequences follow:
 *
 * 1. **`firstChild[v]` is always `v + 1` or `NONE`** — in pre-order the first child immediately
 *    follows its parent — so the array carries one bit of information per node, not 32.
 * 2. **Terminal nodes carry strictly increasing `wordIndex`.** Words arrive sorted and each one
 *    ends at the highest-numbered node created for it, so `wordIndex` of the k-th terminal in id
 *    order is exactly k. The array is a *rank*, not data.
 *
 * `nextSibling[v]` is `v` plus the size of `v`'s subtree, so it too is shape, not data.
 *
 * All three therefore collapse into the tree's **shape**, which balanced parentheses encode in
 * 2 bits per node while preserving pre-order numbering. Preserving pre-order is the point:
 * `LOUDS` would renumber into BFS order, and property 2 would be lost, forcing an explicit
 * 355,587-entry terminal-to-word map that costs more than the shape it saved.
 *
 * ### Why `findClose` is needed at all
 * The search prunes: when no cell of a node's DP row is within budget, its whole subtree is
 * abandoned. Abandoning it requires jumping past it, which is `findClose`. Without pruning the
 * structure would be smaller and the search useless, so the block excess table below is not
 * optional overhead — it is what keeps the succinct form a trie rather than a word list.
 *
 * Built independently from the same sorted word list rather than converted from a `LexiconTrie`,
 * so that an equivalence check compares two constructions and not one structure with itself.
 */
class SuccinctTrie private constructor(
    private val bp: LongArray,
    private val bpBits: Int,
    /** Absolute excess at each block start. Doubles as the rank index: `rank1(i) = (e(i)+i)/2`. */
    private val blockExcess: IntArray,
    /** Minimum absolute excess reached inside each block, for skipping in [findClose]. */
    private val blockMin: IntArray,
    /** Alphabet symbol per node, by pre-order id. Slot 0 is the root and is unused. */
    private val labelOf: ByteArray,
    private val alphabet: CharArray,
    private val terminal: LongArray,
    /** Count of terminals before each 512-bit block of [terminal]. */
    private val terminalRank: IntArray,
    val nodeCount: Int,
    val wordCount: Int,
    private val defect: Defect,
) {

    /** Bytes held by every array in the structure. Arithmetic, not an estimate. */
    val heapBytes: Long
        get() = 8L * bp.size + 4L * blockExcess.size + 4L * blockMin.size +
            labelOf.size.toLong() + 2L * alphabet.size +
            8L * terminal.size + 4L * terminalRank.size

    val alphabetSize: Int get() = alphabet.size

    // ---- bit plumbing -------------------------------------------------------------------

    private fun bpAt(i: Int): Int = ((bp[i ushr 6] ushr (i and 63)) and 1L).toInt()

    /** Number of open parens in `[0, i)`. */
    private fun rank1(i: Int): Int {
        val block = i ushr 9
        var count = (blockExcess[block] + (block shl 9)) shr 1
        var w = block shl 3
        val lastWord = i ushr 6
        while (w < lastWord) { count += java.lang.Long.bitCount(bp[w]); w++ }
        val rem = i and 63
        if (rem != 0) count += java.lang.Long.bitCount(bp[lastWord] and ((1L shl rem) - 1))
        return count
    }

    /** Excess after position `i`, i.e. over `[0, i)`. */
    private fun excess(i: Int): Int = 2 * rank1(i) - i

    /**
     * Position of the close matching the open at [open].
     *
     * Blocks whose minimum excess never reaches the target cannot contain the match and are
     * skipped whole; only the block that does is scanned bit by bit.
     */
    private fun findClose(open: Int): Int {
        val target = excess(open)
        var j = open + 1
        var cur = target + 1
        while (j < bpBits) {
            val block = j ushr 9
            val blockEnd = minOf(bpBits, (block + 1) shl 9)
            if (blockMin[block] > target) {
                cur = blockExcess[block + 1]
                j = blockEnd
                continue
            }
            while (j < blockEnd) {
                cur += if (bpAt(j) == 1) 1 else -1
                if (cur == target) return j
                j++
            }
        }
        error("unbalanced parentheses: no close for $open")
    }

    private fun isTerminal(id: Int): Boolean =
        ((terminal[id ushr 6] ushr (id and 63)) and 1L) == 1L

    /** Index into the sorted word list for a terminal node: its rank among terminals. */
    private fun wordAt(id: Int): Int {
        val block = id ushr 9
        var count = terminalRank[block]
        var w = block shl 3
        val lastWord = id ushr 6
        while (w < lastWord) { count += java.lang.Long.bitCount(terminal[w]); w++ }
        val rem = id and 63
        if (rem != 0) count += java.lang.Long.bitCount(terminal[lastWord] and ((1L shl rem) - 1))
        return count
    }

    private fun charAt(id: Int): Char = alphabet[labelOf[id].toInt() and 0xff]

    // ---- traversal ----------------------------------------------------------------------

    /** Walks the children of the node whose open paren is at [pos], in sorted order. */
    private inline fun forEachChild(pos: Int, visit: (Int) -> Boolean) {
        var p = pos + 1
        while (p < bpBits && bpAt(p) == 1) {
            if (!visit(p)) return
            // PC-1 plants the off-by-one here. `findClose(p)` without the `+ 1` lands on the
            // child's own close paren, which reads as 0, so the walk ends after the first
            // child instead of continuing to its siblings. Chosen because it terminates: a
            // defect that hangs proves nothing about the check.
            p = if (defect == Defect.OFF_BY_ONE_CHILD) findClose(p) else findClose(p) + 1
        }
    }

    fun contains(word: String): Boolean {
        if (word.isEmpty()) return false
        var pos = 0
        for (c in word) {
            val next = childWith(pos, c)
            if (next < 0) return false
            pos = next
        }
        return isTerminal(rank1(pos))
    }

    private fun childWith(pos: Int, c: Char): Int {
        var found = -1
        forEachChild(pos) { child ->
            if (charAt(rank1(child)) == c) { found = child; false } else true
        }
        return found
    }

    /** Mirrors [LexiconTrie.search] exactly, including result order. */
    fun search(
        query: String,
        maxCost: Int,
        costs: EditCostModel,
        limit: Int = LexiconTrie.DEFAULT_LIMIT,
    ): List<LexiconTrie.Match> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<LexiconTrie.Match>()
        val width = query.length + 1
        val firstRow = IntArray(width)
        for (i in 1 until width) firstRow[i] = firstRow[i - 1] + costs.delete(query[i - 1])

        forEachChild(0) { child ->
            descend(child, query, firstRow, null, NO_CHAR, maxCost, costs, out, limit)
            out.size < limit
        }
        out.sortBy { it.cost }
        return out
    }

    private fun descend(
        pos: Int,
        query: String,
        parentRow: IntArray,
        grandparentRow: IntArray?,
        parentChar: Char,
        maxCost: Int,
        costs: EditCostModel,
        out: MutableList<LexiconTrie.Match>,
        limit: Int,
    ) {
        if (out.size >= limit) return
        val id = rank1(pos)
        val nodeChar = charAt(id)
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
            if (grandparentRow != null && i >= 2 &&
                typed == parentChar && query[i - 2] == nodeChar
            ) {
                val transposed = grandparentRow[i - 2] + costs.transpose(query[i - 2], typed)
                if (transposed < best) best = transposed
            }
            row[i] = best
        }

        if (isTerminal(id) && row[width - 1] <= maxCost) {
            out.add(LexiconTrie.Match(wordAt(id), row[width - 1]))
        }

        var rowMin = Int.MAX_VALUE
        for (v in row) if (v < rowMin) rowMin = v
        if (rowMin > maxCost) return

        forEachChild(pos) { child ->
            descend(child, query, row, parentRow, nodeChar, maxCost, costs, out, limit)
            out.size < limit
        }
    }

    companion object {
        private const val NO_CHAR = ' '
        private const val BLOCK_BITS = 512

        /** Planted defects for the positive controls. `NONE` is the honest build. */
        enum class Defect { NONE, OFF_BY_ONE_CHILD, DROP_LAST_TERMINAL, ACCEPT_EXTRA }

        fun build(words: List<String>, defect: Defect = Defect.NONE): SuccinctTrie {
            val symbols = LinkedHashMap<Char, Int>()
            for (w in words) for (c in w) symbols.getOrPut(c) { symbols.size }
            require(symbols.size <= 256) {
                "alphabet is ${symbols.size} symbols; the label array cannot narrow to bytes"
            }
            val alphabet = CharArray(symbols.size)
            for ((c, i) in symbols) alphabet[i] = c

            var bp = LongArray(1024)
            var bpBits = 0
            fun emit(open: Boolean) {
                if ((bpBits ushr 6) >= bp.size) bp = bp.copyOf(bp.size * 2)
                if (open) bp[bpBits ushr 6] = bp[bpBits ushr 6] or (1L shl (bpBits and 63))
                bpBits++
            }

            var labelOf = ByteArray(maxOf(16, words.size))
            var terminalBits = LongArray(maxOf(1, words.size ushr 6) + 1)
            var nodeCount = 0
            fun newNode(symbol: Int): Int {
                if (nodeCount >= labelOf.size) labelOf = labelOf.copyOf(labelOf.size * 2)
                if ((nodeCount ushr 6) >= terminalBits.size) {
                    terminalBits = terminalBits.copyOf(terminalBits.size * 2)
                }
                labelOf[nodeCount] = symbol.toByte()
                emit(true)
                return nodeCount++
            }

            newNode(0) // the root; its label slot is never read
            var path = IntArray(64)
            path[0] = 0
            var depth = 0
            var previous = ""
            var expectedWord = 0

            words.forEachIndexed { index, word ->
                require(word >= previous) {
                    "SuccinctTrie.build requires sorted input; got '$word' after '$previous'"
                }
                if (path.size < word.length + 1) path = path.copyOf(word.length * 2 + 2)

                var common = 0
                val shared = minOf(word.length, previous.length)
                while (common < shared && word[common] == previous[common]) common++

                while (depth > common) { emit(false); depth-- }
                for (d in common until word.length) {
                    path[d + 1] = newNode(symbols[word[d]]!!)
                    depth = d + 1
                }

                val end = path[word.length]
                // Property 2, asserted rather than assumed: the k-th terminal in pre-order id
                // order is word k. If this ever fails, `wordAt` is silently wrong and every
                // size number below it is meaningless.
                check(expectedWord == index) { "terminal rank $expectedWord != word index $index" }
                expectedWord++
                if (!(defect == Defect.DROP_LAST_TERMINAL && index == words.size - 1)) {
                    terminalBits[end ushr 6] = terminalBits[end ushr 6] or (1L shl (end and 63))
                }
                previous = word
            }
            if (defect == Defect.ACCEPT_EXTRA) {
                // Mark one interior node terminal. A whole-lexicon acceptance check cannot see
                // this: every real word is still accepted. Only equivalence can.
                val interior = nodeCount / 2
                terminalBits[interior ushr 6] =
                    terminalBits[interior ushr 6] or (1L shl (interior and 63))
            }
            while (depth >= 0) { emit(false); depth-- }

            bp = bp.copyOf((bpBits + 63) ushr 6)
            labelOf = labelOf.copyOf(nodeCount)
            terminalBits = terminalBits.copyOf(((nodeCount + 63) ushr 6))

            val blocks = (bpBits + BLOCK_BITS - 1) / BLOCK_BITS
            val blockExcess = IntArray(blocks + 1)
            val blockMin = IntArray(blocks)
            var excess = 0
            for (b in 0 until blocks) {
                blockExcess[b] = excess
                var min = Int.MAX_VALUE
                val end = minOf(bpBits, (b + 1) * BLOCK_BITS)
                for (i in b * BLOCK_BITS until end) {
                    excess += if (((bp[i ushr 6] ushr (i and 63)) and 1L) == 1L) 1 else -1
                    if (excess < min) min = excess
                }
                blockMin[b] = min
            }
            blockExcess[blocks] = excess
            check(excess == 0) { "parentheses do not balance: final excess $excess" }

            val tBlocks = (nodeCount + BLOCK_BITS - 1) / BLOCK_BITS
            val terminalRank = IntArray(maxOf(1, tBlocks))
            var seen = 0
            for (b in 0 until tBlocks) {
                terminalRank[b] = seen
                for (w in b * 8 until minOf(terminalBits.size, (b + 1) * 8)) {
                    seen += java.lang.Long.bitCount(terminalBits[w])
                }
            }

            return SuccinctTrie(
                bp, bpBits, blockExcess, blockMin, labelOf, alphabet,
                terminalBits, terminalRank, nodeCount, words.size, defect,
            )
        }
    }
}

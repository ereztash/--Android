package com.hebrewime.core.prediction

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Which words follow which, keyed by lexicon index.
 *
 * ### Why this file has to exist
 * The lexicon was built from a verb list and a **frequency list**. A frequency list records
 * how often each word occurs and nothing at all about order, so the information prediction
 * needs is not merely missing from the artifact — it was never in the sources. It comes from
 * running Wikipedia text instead; see `scripts/build_bigrams.py`.
 *
 * ### Layout
 * ```
 * u32 groupCount
 * repeated groupCount times, sorted ascending by firstWord:
 *   u32 firstWord            lexicon index
 *   u16 n                    number of continuations
 *   repeated n times, sorted by count descending:
 *     u32 secondWord         lexicon index
 *     u8  logCount           round(log2(count + 1) * 8), capped at 255
 * ```
 * Grouping by first word means a lookup is one binary search plus a contiguous read, and the
 * continuations arrive already ordered by likelihood, so the common case — "give me the most
 * likely next word" — is the first element with no sorting at all.
 *
 * Counts are log-scaled to one byte for the same reason the unigram table is: the raw range
 * spans several orders of magnitude, and ranking cannot use that precision.
 */
class BigramModel private constructor(
    private val groupWord: IntArray,
    private val groupStart: IntArray,
    private val continuationWord: IntArray,
    private val continuationLog: ByteArray,
) {

    /** Number of distinct first words that have any recorded continuation. */
    val groupCount: Int get() = groupWord.size

    /** Total stored bigrams. */
    val bigramCount: Int get() = continuationWord.size

    val heapBytes: Long
        get() = groupWord.size.toLong() * 4 + groupStart.size.toLong() * 4 +
            continuationWord.size.toLong() * 4 + continuationLog.size.toLong()

    /**
     * Continuations of [firstWord], most likely first, as `(lexiconIndex, logCount)` pairs.
     *
     * @param limit stop after this many.
     */
    fun continuationsOf(firstWord: Int, limit: Int = 8): List<Pair<Int, Int>> {
        val group = groupIndexOf(firstWord)
        if (group < 0) return emptyList()
        val from = groupStart[group]
        val to = minOf(groupStart[group + 1], from + limit)
        return (from until to).map {
            continuationWord[it] to (continuationLog[it].toInt() and 0xff)
        }
    }

    /**
     * Log-scaled count of the bigram [firstWord] followed by [secondWord], or 0 when the pair
     * was never seen or was pruned.
     *
     * Zero means "no evidence", which is not the same as "impossible" — the model is pruned
     * and the corpus is finite. Callers that treat absence as a negative signal have to say so
     * themselves; this returns evidence, not judgement.
     */
    fun logCountOf(firstWord: Int, secondWord: Int): Int {
        val group = groupIndexOf(firstWord)
        if (group < 0) return 0
        // Continuations are ordered by count, not by word index, so this is a linear scan.
        // Groups are short in practice; the measurement is in BigramModelTest.
        for (i in groupStart[group] until groupStart[group + 1]) {
            if (continuationWord[i] == secondWord) return continuationLog[i].toInt() and 0xff
        }
        return 0
    }

    fun hasContinuations(firstWord: Int): Boolean = groupIndexOf(firstWord) >= 0

    /**
     * Smallest log-count in the table, or 0 when it is empty.
     *
     * Not a curiosity: the builder prunes at a minimum count, and the encoding is
     * `round(log2(count + 1) * 8)`, so this is the smallest evidence any stored pair can carry.
     * Every threshold below it is the same rule — "the corpus saw this pair at all" — and a
     * caller choosing a margin needs to know where that floor is rather than tuning underneath
     * it and believing the number meant something. `BigramFloorTest` pins it.
     */
    fun minimumLogCount(): Int {
        var min = Int.MAX_VALUE
        for (b in continuationLog) {
            val v = b.toInt() and 0xff
            if (v < min) min = v
        }
        return if (min == Int.MAX_VALUE) 0 else min
    }

    private fun groupIndexOf(firstWord: Int): Int {
        var lo = 0
        var hi = groupWord.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = groupWord[mid]
            when {
                v < firstWord -> lo = mid + 1
                v > firstWord -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    companion object {

        /** An empty model, so prediction degrades to unigram ranking rather than crashing. */
        val EMPTY: BigramModel =
            BigramModel(IntArray(0), IntArray(1), IntArray(0), ByteArray(0))

        /**
         * Loads the table, detecting gzip from the stream rather than trusting the filename —
         * AGP transparently gunzips `.gz` assets while packaging, so this is handed gzip on the
         * JVM and plain bytes on device.
         */
        fun load(stream: InputStream): BigramModel {
            val buffered = BufferedInputStream(stream)
            buffered.mark(2)
            val first = buffered.read()
            val second = buffered.read()
            buffered.reset()
            val gzipped = first == 0x1f && second == 0x8b
            val raw = (if (gzipped) GZIPInputStream(buffered) else buffered)
                .use { it.readBytes() }
            if (raw.size < 4) return EMPTY

            var p = 0
            fun u8(): Int = raw[p++].toInt() and 0xff
            fun u16(): Int = u8() or (u8() shl 8)
            fun u32(): Int = u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)

            val groups = u32()
            require(groups >= 0) { "bigram model declares $groups groups" }

            val groupWord = IntArray(groups)
            val groupStart = IntArray(groups + 1)
            // Two passes would mean parsing twice; instead grow as we go, then trim.
            var contWord = IntArray(groups * 4 + 16)
            var contLog = ByteArray(contWord.size)
            var written = 0

            for (g in 0 until groups) {
                groupWord[g] = u32()
                val n = u16()
                groupStart[g] = written
                if (written + n > contWord.size) {
                    val grown = maxOf(contWord.size * 2, written + n)
                    contWord = contWord.copyOf(grown)
                    contLog = contLog.copyOf(grown)
                }
                for (i in 0 until n) {
                    contWord[written] = u32()
                    contLog[written] = u8().toByte()
                    written++
                }
                require(g == 0 || groupWord[g] > groupWord[g - 1]) {
                    "bigram groups are not sorted ascending at $g; binary search would be wrong"
                }
            }
            groupStart[groups] = written

            return BigramModel(
                groupWord,
                groupStart,
                contWord.copyOf(written),
                contLog.copyOf(written),
            )
        }
    }
}

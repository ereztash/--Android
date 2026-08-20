package com.hebrewime.core.lexicon

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * The shipped Hebrew lexicon: 355,587 unvocalized surface forms, held as one UTF-8 blob with
 * an offset index, searched by binary search over raw bytes.
 *
 * ### Why bytes rather than a `Set<String>`
 * A `HashSet<String>` of 355,587 Hebrew words costs roughly 40-50 bytes of object header,
 * hash field and `char[]` padding *per entry* on top of the characters themselves. The blob
 * form stores the same data in 4,607,433 bytes plus a 1,422,348-byte offset array and
 * allocates nothing per lookup. For a process that the system may kill and restart constantly,
 * and that must not stutter on the input path, that tradeoff is the right way round.
 *
 * ### Why the byte order is safe
 * `scripts/build_lexicon.py` sorts by Unicode code point before encoding. UTF-8 preserves
 * code-point order under byte-wise comparison, so the encoded blob is correctly ordered for
 * the unsigned byte comparison in [compareAt]. This is a property of UTF-8, not a coincidence,
 * but it is exactly the kind of thing that breaks silently -- so [HebrewLexiconTest] asserts
 * the loaded blob is actually sorted rather than assuming it.
 *
 * Not thread-confined: after [load] the instance is immutable and safe to share.
 */
class HebrewLexicon private constructor(
    private val blob: ByteArray,
    private val starts: IntArray,
) : WordSet {

    /** Number of words. */
    val size: Int get() = starts.size

    /** Bytes held: the blob plus the offset index. */
    val heapBytes: Long get() = blob.size.toLong() + starts.size.toLong() * Int.SIZE_BYTES

    private fun endOf(i: Int): Int =
        if (i + 1 < starts.size) starts[i + 1] - 1 else blob.size - 1

    /** Unsigned byte comparison of word [i] against [needle]. */
    private fun compareAt(i: Int, needle: ByteArray): Int {
        val start = starts[i]
        val end = endOf(i)
        val len = end - start
        val n = minOf(len, needle.size)
        for (k in 0 until n) {
            val a = blob[start + k].toInt() and 0xff
            val b = needle[k].toInt() and 0xff
            if (a != b) return a - b
        }
        return len - needle.size
    }

    override fun contains(word: String): Boolean = indexOf(word) >= 0

    /** Index of [word], or a negative value if absent. */
    fun indexOf(word: String): Int {
        if (word.isEmpty()) return -1
        val needle = word.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareAt(mid, needle)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -(lo + 1)
    }

    /** The word at [index], decoded. Used by tests and by candidate generation in M5. */
    fun wordAt(index: Int): String {
        require(index in 0 until size) { "index $index out of range 0..${size - 1}" }
        val start = starts[index]
        return String(blob, start, endOf(index) - start, Charsets.UTF_8)
    }

    companion object {
        /**
         * Load from a gzipped, newline-delimited, code-point-sorted UTF-8 word list -- i.e.
         * exactly what `scripts/build_lexicon.py` emits.
         *
         * @throws IllegalArgumentException if the blob does not end with a newline, which
         *   would make the last word's extent ambiguous.
         */
        fun load(stream: InputStream, gzipped: Boolean = true): HebrewLexicon {
            val raw = (if (gzipped) GZIPInputStream(stream) else BufferedInputStream(stream))
                .use { it.readBytes() }
            require(raw.isNotEmpty()) { "lexicon is empty" }
            require(raw[raw.size - 1] == NEWLINE) {
                "lexicon blob must end with a newline; the last word's extent is otherwise " +
                    "ambiguous"
            }

            var count = 0
            for (b in raw) if (b == NEWLINE) count++

            val starts = IntArray(count)
            var w = 0
            starts[w++] = 0
            for (i in raw.indices) {
                if (raw[i] == NEWLINE && i + 1 < raw.size) {
                    starts[w++] = i + 1
                }
            }
            check(w == count) { "counted $count newlines but recorded $w starts" }
            return HebrewLexicon(raw, starts)
        }

        private const val NEWLINE: Byte = '\n'.code.toByte()
    }
}

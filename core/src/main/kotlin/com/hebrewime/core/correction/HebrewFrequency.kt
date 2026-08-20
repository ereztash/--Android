package com.hebrewime.core.correction

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Log-scaled unigram frequency, one byte per word, in the same order as the lexicon.
 *
 * Because the byte at index *i* describes the word at lexicon index *i*, ranking needs no map
 * and no lookup — the trie already returns the index. 355,587 bytes total.
 *
 * The encoding is `round(log2(count + 1) * 8)`, capped at 255. Counts in the source span
 * 5 to 5,020,713, four orders of magnitude, which would need an `Int` each — 1.4 MB — to store
 * directly, for precision that ranking cannot use. Log scaling keeps roughly 9% resolution per
 * step in 355 KB.
 *
 * **Zero means "valid but not attested in the corpus"**, which is 57,425 rare-but-valid verb
 * conjugations that appear in the verb source and not in the subtitle corpus. It is
 * deliberately distinguishable from "attested exactly once", which encodes as 8. Treating
 * those two the same would push every rare conjugation to the bottom of the candidate list —
 * exactly the words §2.2 warns are the ones users notice being wrong.
 */
class HebrewFrequency private constructor(private val bytes: ByteArray) {

    val size: Int get() = bytes.size

    /** Log-scaled frequency of the word at [wordIndex], 0..255. See the class docs for 0. */
    fun logFrequencyOf(wordIndex: Int): Int {
        require(wordIndex in bytes.indices) {
            "word index $wordIndex outside 0..${bytes.size - 1}"
        }
        return bytes[wordIndex].toInt() and 0xff
    }

    /** True when the word appears in the frequency corpus at all. */
    fun isAttested(wordIndex: Int): Boolean = logFrequencyOf(wordIndex) > 0

    companion object {
        /**
         * Loads the table, detecting gzip from the stream rather than trusting the filename.
         *
         * Same reason as [com.hebrewime.core.lexicon.HebrewLexicon.load]: AGP transparently
         * gunzips `.gz` assets while packaging, so this is handed gzip on the JVM and plain
         * bytes on device.
         */
        fun load(stream: InputStream): HebrewFrequency {
            val buffered = BufferedInputStream(stream)
            buffered.mark(2)
            val first = buffered.read()
            val second = buffered.read()
            buffered.reset()
            val gzipped = first == 0x1f && second == 0x8b
            val raw = (if (gzipped) GZIPInputStream(buffered) else buffered)
                .use { it.readBytes() }
            require(raw.isNotEmpty()) { "frequency table is empty" }
            return HebrewFrequency(raw)
        }

        /** For tests. */
        fun of(values: IntArray): HebrewFrequency =
            HebrewFrequency(ByteArray(values.size) { values[it].toByte() })
    }
}

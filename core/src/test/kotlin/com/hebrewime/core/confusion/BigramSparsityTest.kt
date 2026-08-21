package com.hebrewime.core.confusion

import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How much information is actually carried by "the corpus has never seen this pair"?
 *
 * `RealWordErrorDetector` speaks when the typed word has **no** adjacent evidence and an
 * alternative has some. `Config.requireNoSupportForTyped` calls that "absence of evidence,
 * used deliberately, and only in one direction", and the class header argues it is safe
 * because zero means "no evidence" rather than "wrong".
 *
 * That argument is only as good as the table's coverage, which nothing measured until A1
 * produced a precision figure that needed explaining. This test measures it directly, on
 * **unmodified, correctly written** conversational Hebrew:
 *
 * > **37.7% of adjacent word pairs, both words in the lexicon, have never been seen in the
 * > shipped bigram table.** 21.3% of mid-sentence positions have zero evidence on *either*
 * > side.
 *
 * So the detector's trigger condition is true of more than a third of correct Hebrew. It is
 * not evidence of an error, and no threshold placed on top of it can make it into one — which
 * is exactly what the margin sweep over the A1 labels found empirically, from the other end.
 *
 * This is the mechanism behind a measured 12.5% precision floor, and it is a property of
 * corpus size rather than of any constant in this package. See `docs/LABELING_LOG.md`.
 */
class BigramSparsityTest {

    @Test
    fun absenceOfEvidenceIsWeakEvidenceInATableThisSparse() {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        val raw = GZIPInputStream(
            File(File(System.getProperty("eval.dir")!!), "he_conversational_test.txt.gz")
                .inputStream()
        ).use { it.readBytes() }
        val sentences = raw.toString(Charsets.UTF_8).split('\n')
            .filter { it.isNotBlank() }.map { it.split(' ') }

        var pairs = 0
        var unseen = 0
        var positions = 0
        var blind = 0
        for (s in sentences) {
            for (i in 0 until s.size - 1) {
                val a = lexicon.indexOf(s[i])
                val b = lexicon.indexOf(s[i + 1])
                if (a < 0 || b < 0) continue
                pairs++
                if (bigrams.logCountOf(a, b) == 0) unseen++
            }
            for (i in 1 until s.size - 1) {
                val prev = lexicon.indexOf(s[i - 1])
                val cur = lexicon.indexOf(s[i])
                val next = lexicon.indexOf(s[i + 1])
                if (prev < 0 || cur < 0 || next < 0) continue
                positions++
                if (bigrams.logCountOf(prev, cur) == 0 &&
                    bigrams.logCountOf(cur, next) == 0
                ) blind++
            }
        }

        val unseenRate = 100.0 * unseen / pairs
        val blindRate = 100.0 * blind / positions
        println("CORRECT, unmodified conversational Hebrew against the shipped table:")
        println("  adjacent in-lexicon pairs        : $pairs")
        println("  never seen by the corpus         : $unseen = %.1f%%".format(unseenRate))
        println("  mid-sentence positions           : $positions")
        println("  blind on BOTH sides              : $blind = %.1f%%".format(blindRate))
        println("  => the detector's trigger condition holds for %.1f%% of correct Hebrew"
            .format(unseenRate))

        assertTrue(pairs > 20_000, "denominator too small: $pairs")
        // Recorded from the measurement, as a floor and a ceiling rather than a target. A
        // materially LOWER rate would mean the table got much better and the A1 result should
        // be re-derived; a materially higher one would mean it got worse.
        assertTrue(
            unseenRate in 30.0..45.0,
            "unseen-pair rate moved to %.1f%%, outside the 30-45%% band this was measured at. "
                .format(unseenRate) +
                "The A1 precision result rests on this number and would need re-deriving.",
        )
    }
}

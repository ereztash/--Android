package com.hebrewime.core.confusion

import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * P1 verdict: the blind-position prior fallback, measured once with the margin already fixed.
 *
 * 104 was chosen on `confusion_dev` as **the lowest margin at which the fallback's false alarms
 * return to the baseline** — not the margin with the best recall, which was 8 at nearly nine
 * times the false-alarm rate.
 *
 * Reported on `confusion_test` **and** on the conversational slice, because a keyboard is used
 * on conversational text and the prior's shape differs between registers. The rule from
 * `docs/CONFUSION_MEASUREMENTS.md`, fixed before the sweep ran: recall must rise and false
 * alarms must not, **on both**.
 */
class PriorFallbackVerdictTest {

    private companion object { const val CHOSEN_PRIOR_MARGIN = 104 }

    private class Score(val recall: Double, val alarm: Double, val n: Int)

    private fun measure(
        detector: RealWordErrorDetector,
        lexicon: HebrewLexicon,
        sents: List<List<String>>,
    ): Score {
        var caught = 0
        var injected = 0
        var alarms = 0
        var clean = 0
        for (s in sents) {
            for (i in 1 until s.size - 1) {
                val w = s[i]
                if (lexicon.indexOf(w) < 0) continue
                val variants =
                    HebrewConfusions.variantsOf(w, lexicon, HebrewConfusions.HOMOPHONE_PAIRS)
                if (variants.isEmpty()) continue
                clean++
                if (detector.check(s[i - 1], w, s[i + 1]) != null) alarms++
                injected++
                val f = detector.check(s[i - 1], lexicon.wordAt(variants.first()), s[i + 1])
                if (f != null && f.suggested == w) caught++
            }
        }
        return Score(100.0 * caught / injected, 100.0 * alarms / clean, injected)
    }

    private fun slice(name: String): List<List<String>> {
        val raw = GZIPInputStream(
            File(File(System.getProperty("eval.dir")!!), name).inputStream()
        ).use { it.readBytes() }
        return raw.toString(Charsets.UTF_8).split('\n')
            .filter { it.isNotBlank() }.map { it.split(' ') }
    }

    /**
     * Reports the verdict. Opt-in, because the verdict is reached and recorded: **FAILED, not
     * shipped**, and a permanently red build is noise that teaches people to ignore red builds.
     *
     * `-PrunConfusionSweep=1` to re-run it.
     */
    @Test
    fun reportThePriorFallbackVerdict() {
        if (System.getProperty("runConfusionSweep").isNullOrEmpty()) {
            println("skipped; -PrunConfusionSweep=1. Verdict in docs: FAILED, not shipped.")
            return
        }
        measureAndReport()
    }

    /** The fallback must stay off while its verdict stands at FAILED. */
    @Test
    fun theFailedFallbackIsNotEnabledByDefault() {
        assertTrue(
            RealWordErrorDetector.DEFAULT_PRIOR_MARGIN == 0,
            "the blind-position prior fallback failed P1's stopping rule on the encyclopedic " +
                "slice and must stay disabled until the operator decides otherwise",
        )
    }

    private fun measureAndReport() {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }

        val off = RealWordErrorDetector(lexicon, bigrams, BigramModel.EMPTY, frequency)
        val on = RealWordErrorDetector(
            lexicon, bigrams, BigramModel.EMPTY, frequency,
            RealWordErrorDetector.Config(priorMargin = CHOSEN_PRIOR_MARGIN),
        )

        var allPass = true
        println("=".repeat(84))
        println("P1 VERDICT -- blind-position prior fallback, margin $CHOSEN_PRIOR_MARGIN")
        for ((label, file) in listOf(
            "encyclopedic" to "hewiki_confusion_test.txt.gz",
            "conversational" to "he_conversational_test.txt.gz",
        )) {
            val sents = slice(file)
            val before = measure(off, lexicon, sents)
            val after = measure(on, lexicon, sents)
            println("  %-15s n=%-7d before: recall %.2f%% alarms %.3f%%   after: recall %.2f%% alarms %.3f%%"
                .format(label, after.n, before.recall, before.alarm, after.recall, after.alarm))
            println("  %-15s delta   recall %+.2f   alarms %+.4f"
                .format("", after.recall - before.recall, after.alarm - before.alarm))
            if (after.recall <= before.recall) allPass = false
            if (after.alarm > before.alarm) allPass = false
        }
        println("  rule: recall up AND alarms not up, on BOTH slices -> ${if (allPass) "PASS" else "FAIL"}")
        println("=".repeat(84))

        assertTrue(
            allPass,
            "P1 fails its own stopping rule on at least one slice. Per " +
                "docs/CONFUSION_MEASUREMENTS.md that is a result to report, not a margin to " +
                "re-pick -- confusion_test has now been observed for this question.",
        )
    }
}

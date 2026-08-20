package com.hebrewime.core.confusion

import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The S1 verdict: does the distance-2 layer clear the bar that was set before it was built?
 *
 * Measured **once**, on `confusion_test`, with `skipMargin` already fixed at 80 from the dev
 * sweep — and through the **same harness that produced the published 64.58% / 0.26%**, so the
 * comparison is like for like. A wider window measured with a different eligibility rule would
 * be a different question wearing the same numbers.
 *
 * The rule, committed at `26467ae` before the table existed:
 *
 * > Ship only if BOTH hold: recall strictly greater than 64.58%, false alarms at most 0.26%.
 *
 * This test **asserts the rule**, not the outcome. If the layer fails, this test fails, and the
 * layer does not ship — that is the intended behaviour of a stopping rule, not a regression.
 */
class SkipLayerVerdictTest {

    private val evalDir = File(System.getProperty("eval.dir")!!)

    private companion object {
        const val SHIPPED_RECALL = 64.58
        const val SHIPPED_FALSE_ALARM = 0.26
        const val CHOSEN_SKIP_MARGIN = 80
        const val BOTH_SIDES = true
    }

    /**
     * Reports the verdict. Opt-in, because the verdict has already been reached and recorded:
     * the layer **failed** and is not shipped. A permanently red build is not a finding, it is
     * noise that trains people to ignore red builds.
     *
     * `-PrunConfusionSweep=1` to re-run it.
     */
    @Test
    fun reportTheDistance2Verdict() {
        if (System.getProperty("runConfusionSweep").isNullOrEmpty()) {
            println("skipped; -PrunConfusionSweep=1. Verdict recorded in " +
                "docs/CONFUSION_MEASUREMENTS.md: FAILED, not shipped.")
            return
        }
        measureAndReport()
    }

    /**
     * The layer must NOT be wired into production while its verdict stands at FAILED.
     *
     * This is the assertion that actually guards something. The measurement above is history;
     * this is the state of the app.
     */
    @Test
    fun theFailedLayerIsNotWiredIntoProduction() {
        val controller = File("app/src/main/kotlin/com/hebrewime/ime/correction/CorrectionController.kt")
        val source = if (controller.exists()) controller.readText()
        else File("../app/src/main/kotlin/com/hebrewime/ime/correction/CorrectionController.kt").readText()
        assertTrue(
            "he_skipgrams" !in source,
            "the distance-2 layer failed its stopping rule (+243 catches, +4 false alarms, " +
                "against a rule that allowed no increase) and must not be loaded in production " +
                "until the operator decides otherwise",
        )
    }

    private fun measureAndReport() {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        val skip = File(System.getProperty("skipgram.file")!!)
            .inputStream().use { BigramModel.load(it) }

        val (sentences, _) = ConfusionCorpus.sentences(
            File(evalDir, "hewiki_confusion_test.txt.gz")
        )
        val injections = ConfusionCorpus.inject(sentences, lexicon, bothSides = BOTH_SIDES)
        val sites = ConfusionCorpus.sites(sentences, lexicon, bothSides = BOTH_SIDES)

        fun measure(detector: RealWordErrorDetector, wide: Boolean): Pair<Double, Double> {
            var recovered = 0
            for (inj in injections) {
                val s = inj.sentence
                val p = inj.position
                val f = if (wide) detector.checkWide(
                    s.getOrNull(p - 2), s[p - 1], s[p],
                    s.getOrNull(p + 1), s.getOrNull(p + 2),
                ) else detector.check(s[p - 1], s[p], s.getOrNull(p + 1))
                if (f != null && f.suggested == inj.original) recovered++
            }
            var alarms = 0
            for (site in sites) {
                val s = site.sentence
                val p = site.position
                val f = if (wide) detector.checkWide(
                    s.getOrNull(p - 2), s[p - 1], s[p],
                    s.getOrNull(p + 1), s.getOrNull(p + 2),
                ) else detector.check(s[p - 1], s[p], s.getOrNull(p + 1))
                if (f != null) alarms++
            }
            return 100.0 * recovered / injections.size to 100.0 * alarms / sites.size
        }

        val (baseRecall, baseAlarm) = measure(RealWordErrorDetector(lexicon, bigrams), false)
        val wide = RealWordErrorDetector(
            lexicon, bigrams, skip,
            RealWordErrorDetector.Config(skipMargin = CHOSEN_SKIP_MARGIN),
        )
        val (wideRecall, wideAlarm) = measure(wide, true)

        println("=".repeat(78))
        println("S1 VERDICT -- confusion_test, n=${injections.size} injections, "
            + "${sites.size} clean sites")
        println("  adjacent only (shipped) : recall %.2f%%  false %.3f%%"
            .format(baseRecall, baseAlarm))
        println("  + distance-2 (margin %d) : recall %.2f%%  false %.3f%%"
            .format(CHOSEN_SKIP_MARGIN, wideRecall, wideAlarm))
        println("  delta                    : recall %+.2f    false %+.3f"
            .format(wideRecall - baseRecall, wideAlarm - baseAlarm))
        println()
        println("  RULE: recall > %.2f%% AND false <= %.2f%%"
            .format(SHIPPED_RECALL, SHIPPED_FALSE_ALARM))
        println("  recall passes: ${wideRecall > SHIPPED_RECALL}")
        println("  false  passes: ${wideAlarm <= SHIPPED_FALSE_ALARM}")
        println("=".repeat(78))

        // Sanity: the harness must reproduce the published baseline, or the comparison is
        // against a number this run did not actually produce.
        assertEquals(
            SHIPPED_RECALL, baseRecall, 0.5,
            "the harness no longer reproduces the published baseline recall; the verdict " +
                "below would be measured against a figure this run did not produce",
        )

        assertTrue(
            wideRecall > SHIPPED_RECALL,
            "S1 FAILS the recall half of its own stopping rule: %.2f%% is not above %.2f%%. "
                .format(wideRecall, SHIPPED_RECALL) +
                "Per docs/CONFUSION_MEASUREMENTS.md the layer does not ship.",
        )
        assertTrue(
            wideAlarm <= SHIPPED_FALSE_ALARM,
            "S1 FAILS the false-alarm half of its own stopping rule: %.3f%% exceeds %.2f%%. "
                .format(wideAlarm, SHIPPED_FALSE_ALARM) +
                "The threshold does not move to accommodate the feature.",
        )
    }
}

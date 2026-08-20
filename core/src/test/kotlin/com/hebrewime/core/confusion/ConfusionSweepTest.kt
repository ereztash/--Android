package com.hebrewime.core.confusion

import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import java.io.File
import kotlin.test.Test

/**
 * Sweeps the real-word error detector's margin on the **dev** slice and prints the whole
 * surface.
 *
 * Run after the baseline, never before. Every configuration tried is printed, including the
 * ones that are worse, with both columns side by side: recall on injected errors and the
 * false-alarm rate on untouched text. Reporting one without the other would be meaningless —
 * a detector that flags everything has perfect recall.
 *
 * The thresholds chosen from this table are then measured **once** on the test slice, which
 * shares no sentence with this one. Opt-in: `-PrunConfusionSweep=1`.
 */
class ConfusionSweepTest {

    private val evalDir = File(System.getProperty("eval.dir")!!)

    @Test
    fun sweep() {
        if (System.getProperty("runConfusionSweep").isNullOrEmpty()) {
            println("ConfusionSweepTest skipped; run with -PrunConfusionSweep=1")
            return
        }
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }

        val (dev, hash) = ConfusionCorpus.sentences(
            File(evalDir, "hewiki_confusion_dev.txt.gz")
        )
        println("=".repeat(112))
        println("CONFUSION MARGIN SWEEP -- dev slice, sha256 ${hash.take(16)}...")
        println("recall = injected errors flagged with the ORIGINAL word as the suggestion")
        println("false alarm = untouched positions the detector flagged anyway")
        println("=".repeat(112))

        for (bothSides in listOf(true, false)) {
            val injections = ConfusionCorpus.inject(dev, lexicon, bothSides = bothSides)
            val sites = ConfusionCorpus.sites(dev, lexicon, bothSides = bothSides)
            println()
            println(
                "context = ${if (bothSides) "both sides" else "LEFT ONLY (what the app has " +
                    "while the user is still typing)"};  " +
                    "D n=${injections.size}, E n=${sites.size}"
            )
            println(
                "%-8s %-10s | %-9s %-9s %-9s | %-11s %-9s".format(
                    "margin", "typedZero", "recall", "flagged", "wrongFix", "falseAlarm", "n",
                )
            )
            for (requireZero in listOf(false, true)) {
                for (margin in listOf(1, 8, 16, 20, 21, 22, 24, 29, 32, 48, 64)) {
                    val detector = RealWordErrorDetector(
                        lexicon, bigrams,
                        RealWordErrorDetector.Config(
                            margin = margin, requireNoSupportForTyped = requireZero,
                        ),
                    )
                    var flagged = 0
                    var recovered = 0
                    for (inj in injections) {
                        val f = detector.check(
                            inj.sentence[inj.position - 1],
                            inj.sentence[inj.position],
                            // THE POINT OF THE bothSides SWITCH. An earlier version of this
                            // harness passed the following word in both branches and only
                            // varied which positions were eligible, so the "left only" rows
                            // were both-sides scoring under a wrong label -- and the
                            // architecture decision they supported was wrong by 19 points.
                            if (bothSides) inj.sentence.getOrNull(inj.position + 1) else null,
                        ) ?: continue
                        flagged++
                        if (f.suggested == inj.original) recovered++
                    }
                    var alarms = 0
                    for (site in sites) {
                        if (detector.check(
                                site.sentence[site.position - 1],
                                site.sentence[site.position],
                                if (bothSides) site.sentence.getOrNull(site.position + 1)
                                else null,
                            ) != null
                        ) alarms++
                    }
                    fun pct(x: Int, n: Int) = if (n == 0) "n/a" else "%.2f%%".format(100.0 * x / n)
                    println(
                        "%-8d %-10s | %-9s %-9s %-9s | %-11s %-9d".format(
                            margin, requireZero,
                            pct(recovered, injections.size),
                            pct(flagged, injections.size),
                            pct(flagged - recovered, injections.size),
                            pct(alarms, sites.size),
                            sites.size,
                        )
                    )
                }
            }
        }
        println("=".repeat(112))
    }
}

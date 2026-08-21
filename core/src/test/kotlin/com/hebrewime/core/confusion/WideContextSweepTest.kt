package com.hebrewime.core.confusion

import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import java.io.File
import kotlin.test.Test

/**
 * S1 + P1 swept **together**, on the dev slice, in the shape production can actually run.
 *
 * ### Why one sweep and not two
 * Both layers fire on exactly the same positions: those where the adjacent window has no
 * evidence for either candidate. S1 speaks first when distance-2 counts exist; P1 speaks only
 * where they do not. Their separately measured gains therefore **cannot be added** — every
 * position P1 would have won is a position S1 may already have taken. The only honest number
 * for the pair is the pair measured as a pair.
 *
 * ### The shape, which is not the one the first S1 sweep used
 * `HebrewImeService` checks the second most recent completed word. At that moment the word two
 * positions to its **right has not been typed**, and no amount of context reading can produce
 * it. Every figure below is measured with `next2 = null` for that reason. The both-sides row
 * is printed underneath as a **ceiling that production cannot reach**, because the original S1
 * sweep measured that shape and its number is in the docs.
 *
 * ### Harness
 * `ConfusionCorpus`, the same one that produced the published 62.31% / 0.250%, so the rows here
 * are comparable to the numbers already written down. A different eligibility rule would be a
 * different question wearing the same units.
 *
 * Opt-in: `-PrunConfusionSweep=1`.
 */
class WideContextSweepTest {

    private val evalDir = File(System.getProperty("eval.dir")!!)

    @Test
    fun sweep() {
        if (System.getProperty("runConfusionSweep").isNullOrEmpty()) {
            println("skipped; run with -PrunConfusionSweep=1")
            return
        }
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        val skip = File(System.getProperty("skipgram.file")!!)
            .inputStream().use { BigramModel.load(it) }

        val (sentences, _) = ConfusionCorpus.sentences(
            File(evalDir, "hewiki_confusion_dev.txt.gz")
        )
        val injections = ConfusionCorpus.inject(sentences, lexicon, bothSides = true)
        val sites = ConfusionCorpus.sites(sentences, lexicon, bothSides = true)

        // The only honest "off". Three earlier attempts at this baseline were wrong and the
        // reasons are worth keeping: `skipMargin = 0` does not disable the skip layer, it
        // removes its threshold so it fires on everything; and handing it BigramModel.EMPTY
        // with margin 0 does not disable it either, because `0 - 0 < 0` is false and every
        // variant becomes a finding. Off means the adjacent-only entry point, `check`.
        fun measureAdjacent(): Triple<Double, Double, Int> {
            val d = RealWordErrorDetector(lexicon, bigrams)
            var recovered = 0
            for (inj in injections) {
                val s = inj.sentence
                val f = d.check(s[inj.position - 1], s[inj.position],
                    s.getOrNull(inj.position + 1))
                if (f != null && f.suggested == inj.original) recovered++
            }
            var alarms = 0
            for (site in sites) {
                if (d.check(site.sentence[site.position - 1], site.sentence[site.position],
                        site.sentence.getOrNull(site.position + 1)) != null) alarms++
            }
            return Triple(100.0 * recovered / injections.size,
                100.0 * alarms / sites.size, injections.size)
        }

        fun measureWide(
            skipMargin: Int,
            priorMargin: Int,
            rightHandSkip: Boolean,
        ): Pair<Double, Double> {
            val d = RealWordErrorDetector(
                lexicon, bigrams, skip, frequency,
                RealWordErrorDetector.Config(
                    skipMargin = skipMargin, priorMargin = priorMargin,
                ),
            )
            fun call(s: List<String>, p: Int) = d.checkWide(
                s.getOrNull(p - 2), s[p - 1], s[p], s.getOrNull(p + 1),
                if (rightHandSkip) s.getOrNull(p + 2) else null,
            )
            var recovered = 0
            for (inj in injections) {
                val f = call(inj.sentence, inj.position)
                if (f != null && f.suggested == inj.original) recovered++
            }
            var alarms = 0
            for (site in sites) if (call(site.sentence, site.position) != null) alarms++
            return 100.0 * recovered / injections.size to 100.0 * alarms / sites.size
        }

        val (baseRecall, baseAlarm, n) = measureAdjacent()
        println("=".repeat(96))
        println("S1+P1 JOINT SWEEP -- confusion_dev, n=$n injections, ${sites.size} clean sites")
        println("Both layers fire on the SAME blind positions; their gains are not additive.")
        println("next2 = null throughout: the word two to the right does not exist yet when the")
        println("keyboard runs this check. See HebrewImeService.CONTEXT_WORDS.")
        println("%-12s %-12s %-10s %-14s %s"
            .format("skipMargin", "priorMargin", "recall", "false alarms", "delta"))
        println("%-12s %-12s %-10s %-14s %s".format(
            "-", "-", "%.2f%%".format(baseRecall), "%.3f%%".format(baseAlarm),
            "adjacent only -- what ships today"))
        for (sm in listOf(0, 64, 72, 80, 88, 96)) {
            for (pm in listOf(0, 96, 104, 112, 128)) {
                if (sm == 0 && pm == 0) continue
                val (recall, alarm) = measureWide(sm, pm, rightHandSkip = false)
                println("%-12s %-12s %-10s %-14s %s".format(
                    if (sm == 0) "off" else "$sm", if (pm == 0) "off" else "$pm",
                    "%.2f%%".format(recall), "%.3f%%".format(alarm),
                    "recall %+.2f, alarms %+.4f"
                        .format(recall - baseRecall, alarm - baseAlarm)))
            }
        }
        println("-".repeat(96))
        println("CEILING, not reachable in production -- distance-2 on BOTH sides:")
        for (sm in listOf(64, 80, 96)) {
            val (recall, alarm) = measureWide(sm, 104, rightHandSkip = true)
            println("%-12d %-12s %-10s %-14s %s".format(
                sm, "104", "%.2f%%".format(recall), "%.3f%%".format(alarm),
                "recall %+.2f, alarms %+.4f".format(recall - baseRecall, alarm - baseAlarm)))
        }
        println("=".repeat(96))
    }
}

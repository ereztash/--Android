package com.hebrewime.core.confusion

import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How much of real-word error detection is CONTEXT, and how much is just the commoner word?
 *
 * ### The control this project shipped without
 * `RealWordErrorDetector` is described everywhere as context-aware, and its recall is quoted as
 * evidence for that. But `אם`/`עם` is not a symmetric pair: one member is far more common than
 * the other, and a detector that ignored context entirely — *always suggest the commoner
 * variant* — would recover a share of the injected errors for free, because the injection
 * frequently replaces a common word with a rarer one.
 *
 * Without this column, **62.31% cannot be attributed**. It could be context doing the work, or
 * it could be a unigram prior with a context-shaped API around it. Every threshold in
 * `docs/CONFUSION_MEASUREMENTS.md` was chosen without knowing which.
 *
 * The control is given the **most generous** reading available: its margin is swept and the
 * best row is reported, so the comparison is against the strongest frequency-only detector
 * rather than a weak one chosen to lose.
 */
class FrequencyPriorControlTest {

    private val evalDir = File(System.getProperty("eval.dir")!!)
    private companion object {
        const val BOTH_SIDES = true

        /**
         * The shipped detector's own false-alarm rate, which is the budget the control is held
         * to. Comparing a detector at 0.25% against a control at 0.154% would credit context
         * with points that are really the control being run more conservatively.
         */
        const val SHIPPED_FALSE_ALARM = 0.250
    }

    @Test
    fun attributeTheRecallBetweenContextAndPrior() {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }

        val (sentences, _) = ConfusionCorpus.sentences(
            File(evalDir, "hewiki_confusion_test.txt.gz")
        )
        val injections = ConfusionCorpus.inject(sentences, lexicon, bothSides = BOTH_SIDES)
        val sites = ConfusionCorpus.sites(sentences, lexicon, bothSides = BOTH_SIDES)

        /** The control: pick the commonest variant, if it beats the typed word by [margin]. */
        fun frequencyOnly(typed: String, margin: Int): String? {
            val typedIndex = lexicon.indexOf(typed)
            if (typedIndex < 0) return null
            val variants =
                HebrewConfusions.variantsOf(typed, lexicon, HebrewConfusions.HOMOPHONE_PAIRS)
            if (variants.isEmpty()) return null
            val typedFrequency = frequency.logFrequencyOf(typedIndex)
            val best = variants.maxByOrNull { frequency.logFrequencyOf(it) } ?: return null
            if (frequency.logFrequencyOf(best) - typedFrequency < margin) return null
            return lexicon.wordAt(best)
        }

        println("=".repeat(88))
        println("D4 -- FREQUENCY-PRIOR CONTROL, n=${injections.size} injections, "
            + "${sites.size} clean sites")
        println("%-14s %-12s %-14s".format("margin", "recall", "false alarms"))
        var bestRecall = 0.0
        var bestAlarm = 0.0
        var bestMargin = 0
        for (margin in listOf(0, 8, 16, 24, 32, 40, 48, 52, 56, 58, 60, 62, 64)) {
            var caught = 0
            for (inj in injections) {
                if (frequencyOnly(inj.sentence[inj.position], margin) == inj.original) caught++
            }
            var alarms = 0
            for (site in sites) {
                if (frequencyOnly(site.sentence[site.position], margin) != null) alarms++
            }
            val recall = 100.0 * caught / injections.size
            val alarm = 100.0 * alarms / sites.size
            println("%-14d %-12s %-14s".format(margin, "%.2f%%".format(recall),
                "%.3f%%".format(alarm)))
            // MATCHED comparison. The control is credited with the highest recall it reaches
            // WITHOUT exceeding the shipped detector's own false-alarm rate — not with its best
            // row at some looser rate, which would understate it, and not with a much stricter
            // row, which would flatter the shipped detector. Both directions are errors and the
            // second is the tempting one.
            if (alarm <= SHIPPED_FALSE_ALARM && recall > bestRecall) {
                bestRecall = recall; bestAlarm = alarm; bestMargin = margin
            }
        }

        // The shipped detector, same corpus, same run.
        val detector = RealWordErrorDetector(lexicon, bigrams)
        var contextCaught = 0
        for (inj in injections) {
            val f = detector.check(
                inj.sentence[inj.position - 1], inj.sentence[inj.position],
                inj.sentence.getOrNull(inj.position + 1),
            )
            if (f != null && f.suggested == inj.original) contextCaught++
        }
        var contextAlarms = 0
        for (site in sites) {
            if (detector.check(
                    site.sentence[site.position - 1], site.sentence[site.position],
                    site.sentence.getOrNull(site.position + 1),
                ) != null
            ) contextAlarms++
        }
        val contextRecall = 100.0 * contextCaught / injections.size
        val contextAlarm = 100.0 * contextAlarms / sites.size

        println()
        println("  best frequency-only at <=%.3f%% alarms : recall %.2f%%  alarms %.3f%%  (margin %d)"
            .format(SHIPPED_FALSE_ALARM, bestRecall, bestAlarm, bestMargin))
        println("  shipped context detector              : recall %.2f%%  alarms %.3f%%"
            .format(contextRecall, contextAlarm))
        println("  ATTRIBUTABLE TO CONTEXT               : %+.2f points"
            .format(contextRecall - bestRecall))
        println("  available from the PRIOR alone        : %.1f%% of the shipped recall"
            .format(100.0 * bestRecall / contextRecall))
        println("=".repeat(88))

        assertTrue(injections.size > 20_000, "denominator too small")
        assertTrue(
            contextRecall > bestRecall,
            "the context detector does not beat a frequency-only control at a comparable " +
                "false-alarm rate (%.2f%% vs %.2f%%). If that ever holds, the feature is a "
                    .format(contextRecall, bestRecall) +
                "unigram prior with a context-shaped API and the documentation must say so.",
        )
    }
}

package com.hebrewime.core.confusion

import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The joint verdict for S1 (distance-2) and P1 (blind-position prior), measured together.
 *
 * ### Why this file replaces two others
 * `SkipLayerVerdictTest` and `PriorFallbackVerdictTest` each measured one layer against a rule
 * written before it was built, and each reported FAILED — S1 at 243 extra catches for 4 extra
 * false alarms, P1 at 418 for 2. Both were escalated rather than retuned. **The operator then
 * decided to ship both.** They are in git history at `6c56758`; they are not here because the
 * two layers fire on the same positions and measuring them apart answers a question nobody has
 * any more.
 *
 * ### What moved, and who moved it
 * The rule was "recall up, false alarms not up". The operator's decision was permission to
 * spend false alarms for recall. **It turned out not to need spending.** The joint sweep found
 * an operating point — `skipMargin 80, priorMargin 104` — whose false-alarm rate on the dev
 * slice is identical to the adjacent-only baseline at four decimal places, so what ships
 * satisfies the original rule as written. That is a fact about the sweep, not a reason to
 * pretend the escalation did not happen: the point was chosen from a table the operator's
 * decision made it legitimate to choose from.
 *
 * ### Measured in the shape production can run
 * `next2` is always null. `HebrewImeService` checks the second most recent completed word, and
 * the word two to its right **has not been typed yet**. The original S1 verdict measured with
 * both distance-2 neighbours available and its number was therefore a ceiling. The gap is
 * printed below so the difference is visible rather than asserted.
 */
class WideContextVerdictTest {

    private val evalDir = File(System.getProperty("eval.dir")!!)

    private companion object {
        const val SKIP_MARGIN = 80
        const val PRIOR_MARGIN = 104

        /**
         * What the distance-2 table costs a user, counted in the release APK rather than on
         * disk: 672,606 bytes uncompressed, 387,300 as stored. Both are stated because the
         * first is what the budget in `tools/size_budget.json` counts and the second is what
         * gets downloaded.
         */
        const val SKIP_TABLE_APK_BYTES = 387_300

        /**
         * Recorded AFTER the run above, from it, as floors and ceilings — not as targets.
         *
         * Same construction as `ConfusionAccuracyTest`: the measured figures are
         * 63.73% / 0.253% on the encyclopedic test slice, and these leave enough room that
         * ordinary noise does not fail the build while a structural regression does. They are
         * here because the reporting test is opt-in and an opt-in test guards nothing.
         */
        const val SHIPPED_RECALL_FLOOR = 63.0
        const val SHIPPED_ALARM_CEILING = 0.28
    }

    private class Score(
        val recall: Double, val alarm: Double, val n: Int, val sites: Int,
        val caught: Int, val alarmed: Int,
    )

    private fun score(
        detector: RealWordErrorDetector,
        injections: List<ConfusionCorpus.Injection>,
        sites: List<ConfusionCorpus.Site>,
        wide: Boolean,
        bothSidedSkip: Boolean = false,
    ): Score {
        fun call(s: List<String>, p: Int) = if (wide) detector.checkWide(
            s.getOrNull(p - 2), s[p - 1], s[p], s.getOrNull(p + 1),
            if (bothSidedSkip) s.getOrNull(p + 2) else null,
        ) else detector.check(s[p - 1], s[p], s.getOrNull(p + 1))

        var recovered = 0
        for (inj in injections) {
            val f = call(inj.sentence, inj.position)
            if (f != null && f.suggested == inj.original) recovered++
        }
        var alarms = 0
        for (site in sites) if (call(site.sentence, site.position) != null) alarms++
        return Score(
            100.0 * recovered / injections.size, 100.0 * alarms / sites.size,
            injections.size, sites.size, recovered, alarms,
        )
    }

    /**
     * Reports the joint verdict on both test slices. Opt-in with `-PrunConfusionSweep=1`:
     * it loads two corpora and runs six configurations over each.
     */
    @Test
    fun reportTheJointVerdict() {
        if (System.getProperty("runConfusionSweep").isNullOrEmpty()) {
            println("skipped; -PrunConfusionSweep=1. Numbers in docs/CONFUSION_MEASUREMENTS.md.")
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

        fun detector(skipModel: BigramModel, sm: Int, pm: Int) = RealWordErrorDetector(
            lexicon, bigrams, skipModel, frequency,
            RealWordErrorDetector.Config(skipMargin = sm, priorMargin = pm),
        )

        println("=".repeat(96))
        println("S1+P1 JOINT VERDICT -- skipMargin $SKIP_MARGIN, prior $PRIOR_MARGIN, next2 = null")
        for ((label, file) in listOf(
            "encyclopedic" to "hewiki_confusion_test.txt.gz",
            "conversational" to "he_conversational_test.txt.gz",
        )) {
            val (sentences, _) = ConfusionCorpus.sentences(File(evalDir, file))
            val injections = ConfusionCorpus.inject(sentences, lexicon, bothSides = true)
            val sites = ConfusionCorpus.sites(sentences, lexicon, bothSides = true)

            val base = score(RealWordErrorDetector(lexicon, bigrams), injections, sites, false)
            val skipOnly = score(detector(skip, SKIP_MARGIN, 0), injections, sites, true)
            val priorOnly =
                score(detector(BigramModel.EMPTY, 0, PRIOR_MARGIN), injections, sites, true)
            val joint = score(detector(skip, SKIP_MARGIN, PRIOR_MARGIN), injections, sites, true)
            val ceiling = score(
                detector(skip, SKIP_MARGIN, PRIOR_MARGIN), injections, sites, true,
                bothSidedSkip = true,
            )

            println("  $label -- n=${joint.n} injections, ${joint.sites} clean sites")
            // Counts as well as rates. "+0.0029 percentage points" is not a fact anyone can
            // weigh; "two more sites out of 69,494" is.
            fun row(name: String, s: Score) = println(
                "    %-28s recall %6.2f%% (%5d)  alarms %.3f%% (%3d)   %+.2f / %+d catches, %+d alarms"
                    .format(name, s.recall, s.caught, s.alarm, s.alarmed,
                        s.recall - base.recall, s.caught - base.caught,
                        s.alarmed - base.alarmed))
            row("adjacent only (was shipped)", base)
            row("+ distance-2 alone", skipOnly)
            row("+ prior alone", priorOnly)
            row("SHIPPED: both", joint)
            row("ceiling: both, next2 given", ceiling)
            println("    distance-2's marginal contribution over the prior alone: " +
                "%+.2f points, %+d catches -- for %,d bytes in the APK"
                    .format(joint.recall - priorOnly.recall, joint.caught - priorOnly.caught,
                        SKIP_TABLE_APK_BYTES))
            println("    original rule (recall up, alarms not up): " +
                if (joint.recall > base.recall && joint.alarm <= base.alarm) "HOLDS" else "spent")
        }
        println("=".repeat(96))
    }

    /**
     * The shipped configuration, on the test slice, asserted on every run.
     *
     * The report above is opt-in because it runs six configurations over two corpora. This is
     * one configuration over one corpus, and it is the one the app actually runs.
     */
    @Test
    fun theShippedConfigurationHoldsItsRecordedFloors() {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        val skip = File(System.getProperty("skipgram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        val (sentences, _) = ConfusionCorpus.sentences(
            File(evalDir, "hewiki_confusion_test.txt.gz")
        )
        val injections = ConfusionCorpus.inject(sentences, lexicon, bothSides = true)
        val sites = ConfusionCorpus.sites(sentences, lexicon, bothSides = true)
        // Defaults, deliberately: if the shipped constants drift, this must move with them.
        val shipped = RealWordErrorDetector(lexicon, bigrams, skip, frequency)
        val s = score(shipped, injections, sites, wide = true)
        println("SHIPPED on confusion_test: recall %.2f%% (%d/%d), alarms %.3f%% (%d/%d)"
            .format(s.recall, s.caught, s.n, s.alarm, s.alarmed, s.sites))
        assertTrue(
            s.recall >= SHIPPED_RECALL_FLOOR,
            "recall fell to %.2f%%, below the %.2f%% recorded when S1+P1 shipped"
                .format(s.recall, SHIPPED_RECALL_FLOOR),
        )
        assertTrue(
            s.alarm <= SHIPPED_ALARM_CEILING,
            ("false alarms rose to %.3f%%, above the %.2f%% recorded when S1+P1 shipped. " +
                "This is the number the operator spent to ship these layers; it does not get " +
                "spent twice.").format(s.alarm, SHIPPED_ALARM_CEILING),
        )
    }

    /** The shipped defaults are the operating point that was chosen, not something near it. */
    @Test
    fun theShippedDefaultsAreTheChosenOperatingPoint() {
        assertEquals(SKIP_MARGIN, RealWordErrorDetector.DEFAULT_SKIP_MARGIN)
        assertEquals(PRIOR_MARGIN, RealWordErrorDetector.DEFAULT_PRIOR_MARGIN)
    }

    /**
     * The layers are wired into production, which is what the operator decided.
     *
     * This is the inverse of the assertion that stood here while the verdict was FAILED, and it
     * guards the same thing from the other side: that the state of the app matches the state
     * written in the docs. A layer measured and then not wired is the failure this catches.
     */
    @Test
    fun bothLayersAreWiredIntoProduction() {
        fun source(path: String): String {
            val f = File(path)
            return if (f.exists()) f.readText() else File("../$path").readText()
        }
        val controller =
            source("app/src/main/kotlin/com/hebrewime/ime/correction/CorrectionController.kt")
        assertTrue("he_skipgrams" in controller, "the distance-2 table is not loaded")
        assertTrue(
            "frequency" in controller.substringAfter("RealWordErrorDetector("),
            "the detector is built without frequencies, so the prior fallback cannot fire",
        )
        val engine =
            source("core/src/main/kotlin/com/hebrewime/core/prediction/PredictiveEngine.kt")
        assertTrue(
            "checkWide" in engine,
            "the engine still calls check(), so neither layer can ever run in the app",
        )
    }

    /**
     * `skipMargin = 0` must disable the distance-2 layer.
     *
     * A regression test for a defect that published a wrong row in three separate sweeps: with
     * no margin, `candidateSkip - typedSkip < 0` is false for a pair of zeroes and every variant
     * of every blind word becomes a finding.
     */
    @Test
    fun zeroSkipMarginDisablesTheLayerRatherThanUnleashingIt() {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        val skip = File(System.getProperty("skipgram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        val off = RealWordErrorDetector(
            lexicon, bigrams, skip, null,
            RealWordErrorDetector.Config(skipMargin = 0, priorMargin = 0),
        )
        val adjacent = RealWordErrorDetector(lexicon, bigrams)
        val (sentences, _) = ConfusionCorpus.sentences(
            File(evalDir, "hewiki_confusion_test.txt.gz")
        )
        val sites = ConfusionCorpus.sites(sentences, lexicon, bothSides = true).take(20_000)
        var wide = 0
        var narrow = 0
        for (s in sites) {
            val p = s.position
            if (off.checkWide(
                    s.sentence.getOrNull(p - 2), s.sentence[p - 1], s.sentence[p],
                    s.sentence.getOrNull(p + 1), s.sentence.getOrNull(p + 2),
                ) != null
            ) wide++
            if (adjacent.check(
                    s.sentence[p - 1], s.sentence[p], s.sentence.getOrNull(p + 1)
                ) != null
            ) narrow++
        }
        assertEquals(
            narrow, wide,
            "with both margins at 0, checkWide must be indistinguishable from check; it " +
                "flagged $wide sites against $narrow",
        )
    }
}

package com.hebrewime.core.confusion

import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The locked real-word-error numbers, measured on the **test** slice with the thresholds
 * already fixed.
 *
 * The margin and the no-support rule were chosen on `hewiki_confusion_dev.txt.gz`;
 * `scripts/slice_eval_corpus.py` proves that slice and this one share no sentence, and refuses
 * to write them if they do. A threshold tuned on the same sentences it is then reported against
 * is not a measurement, it is a fit.
 *
 * Both numbers appear together in every assertion below. Recall without a false-alarm rate
 * beside it says nothing: a detector that flags every word has perfect recall.
 */
class ConfusionAccuracyTest {

    private val evalDir = File(System.getProperty("eval.dir")!!)

    private companion object {
        const val TEST_SLICE_SHA256 =
            "9fc528ae547f0bfab6d2893383d388a007abd7dbaedcb20e266f934dbc0d32ff"

        /**
         * The shipped mode. The app checks the **second** most recent completed word, so both
         * of its neighbours are known.
         *
         * The first version of the sweep harness reported that left context alone cost only
         * 1.05 points of recall, and this constant was briefly `false` on the strength of it.
         * That harness was wrong: its "left only" branch still passed the following word to
         * the detector and varied only which positions were eligible. Corrected, the gap is
         * **19.64 points** — 44.78% against 64.42% on the dev slice — and it decided the
         * architecture the other way. See docs/CONFUSION_MEASUREMENTS.md.
         */
        const val BOTH_SIDES = true
    }

    private fun fixture(): Pair<HebrewLexicon, BigramModel> {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        return lexicon to bigrams
    }

    private fun testSlice(): List<List<String>> {
        val (sentences, hash) = ConfusionCorpus.sentences(
            File(evalDir, "hewiki_confusion_test.txt.gz")
        )
        assertEquals(TEST_SLICE_SHA256, hash, "the test slice is not the one measured")
        return sentences
    }

    @Test
    fun recallAndFalseAlarmsTogether() {
        val (lexicon, bigrams) = fixture()
        // The SHIPPED configuration, with no overrides.
        val detector = RealWordErrorDetector(lexicon, bigrams)
        val sentences = testSlice()

        val injections = ConfusionCorpus.inject(sentences, lexicon, bothSides = BOTH_SIDES)
        val sites = ConfusionCorpus.sites(sentences, lexicon, bothSides = BOTH_SIDES)
        assertTrue(injections.size > 20_000, "corpus D too small: ${injections.size}")
        assertTrue(sites.size > 30_000, "corpus E too small: ${sites.size}")

        var recovered = 0
        var flagged = 0
        val perPairTotal = HashMap<Pair<Char, Char>, Int>()
        val perPairHit = HashMap<Pair<Char, Char>, Int>()
        for (inj in injections) {
            perPairTotal[inj.pair] = (perPairTotal[inj.pair] ?: 0) + 1
            val f = detector.check(
                inj.sentence[inj.position - 1],
                inj.sentence[inj.position],
                inj.sentence.getOrNull(inj.position + 1),
            ) ?: continue
            flagged++
            if (f.suggested == inj.original) {
                recovered++
                perPairHit[inj.pair] = (perPairHit[inj.pair] ?: 0) + 1
            }
        }
        var alarms = 0
        for (site in sites) {
            if (detector.check(
                    site.sentence[site.position - 1],
                    site.sentence[site.position],
                    site.sentence.getOrNull(site.position + 1),
                ) != null
            ) alarms++
        }

        val recall = 100.0 * recovered / injections.size
        val falseAlarm = 100.0 * alarms / sites.size
        println("corpus D n=${injections.size}: recall %.2f%%, flagged %.2f%%"
            .format(recall, 100.0 * flagged / injections.size))
        println("corpus E n=${sites.size}: false alarm %.2f%% ($alarms sites)"
            .format(falseAlarm))
        // Per pair, so one bad confusion cannot hide inside five good ones.
        for (pair in HebrewConfusions.HOMOPHONE_PAIRS) {
            val n = perPairTotal[pair] ?: 0
            val hit = perPairHit[pair] ?: 0
            println("  ${pair.first}<->${pair.second}: n=$n recall %.2f%%"
                .format(if (n == 0) 0.0 else 100.0 * hit / n))
        }

        // Dev slice measured 64.42% recall at 0.30% false alarms; the floor and the ceiling
        // were set from THAT table, and this slice — which shares no sentence with it — is
        // where they are checked.
        assertTrue(recall >= 60.0, "recall fell to %.2f%% (dev measured 64.42%%)".format(recall))
        assertTrue(
            falseAlarm <= 0.40,
            "false alarms rose to %.2f%% (dev measured 0.30%%). This is the number that governs "
                .format(falseAlarm) +
                "the thresholds: telling a user their correct Hebrew is wrong is a worse " +
                "failure than missing an error.",
        )
    }

    /**
     * What one check costs, since it runs on every keystroke.
     *
     * The detector re-examines the same word while the user types the one after it — the P2
     * position does not move until a word boundary — so the wasted work is real and the
     * question is whether it is worth caching away. Measured rather than assumed.
     *
     * A JVM figure on the build host. It is NOT a device number and no budget is asserted
     * against it here; the input-path budget is measured through `HebrewIme.suggest` by the
     * macrobenchmark, on hardware.
     */
    @Test
    fun measureCheckCost() {
        val (lexicon, bigrams) = fixture()
        val detector = RealWordErrorDetector(lexicon, bigrams)
        val sites = ConfusionCorpus.sites(testSlice(), lexicon, bothSides = BOTH_SIDES)
            .take(20_000)
        // Warm the JIT, or the first thousand calls dominate the average.
        for (site in sites.take(2_000)) {
            detector.check(site.sentence[site.position - 1], site.sentence[site.position],
                site.sentence.getOrNull(site.position + 1))
        }
        val start = System.nanoTime()
        for (site in sites) {
            detector.check(site.sentence[site.position - 1], site.sentence[site.position],
                site.sentence.getOrNull(site.position + 1))
        }
        val perCall = (System.nanoTime() - start) / 1_000.0 / sites.size
        println("real-word check: %.1f us/call over ${sites.size} calls "
            .format(perCall) + "[JVM on build host, NOT a device]")
        assertTrue(perCall < 500.0, "%.1f us/call is far outside the expected range; something "
            .format(perCall) + "structural changed, not a threshold to relax")
    }

    /**
     * CONTROL: every flag must come from context, not from the confusion inventory.
     *
     * The same detector with an empty bigram table has the identical confusion sets and the
     * identical lexicon, and differs only in having no evidence about word order. If it still
     * flagged anything, the findings above would be a property of the inventory rather than of
     * the sentences.
     */
    @Test
    fun withoutContextTheDetectorIsSilent() {
        val (lexicon, _) = fixture()
        val detector = RealWordErrorDetector(lexicon, BigramModel.EMPTY)
        val sentences = testSlice()
        val sites = ConfusionCorpus.sites(sentences, lexicon, bothSides = BOTH_SIDES)
        var flagged = 0
        for (site in sites) {
            if (detector.check(
                    site.sentence[site.position - 1],
                    site.sentence[site.position],
                    site.sentence.getOrNull(site.position + 1),
                ) != null
            ) flagged++
        }
        println("CONTROL no-context: $flagged of ${sites.size} sites flagged")
        assertEquals(
            0, flagged,
            "the detector flagged $flagged sites with no word-order evidence at all; the " +
                "findings would be a property of the confusion inventory, not of context",
        )
    }

    /**
     * POSITIVE CONTROL for the false-alarm count.
     *
     * A 0.28% false-alarm rate is only reassuring if the same counting loop would report a high
     * one. Run against a detector configured to accept any evidence at all — margin 0, and
     * willing to contradict the typed word — it must report a materially higher rate.
     */
    @Test
    fun aPermissiveDetectorIsCaughtByTheSameCount() {
        val (lexicon, bigrams) = fixture()
        val permissive = RealWordErrorDetector(
            lexicon, bigrams, config =
            RealWordErrorDetector.Config(margin = 0, requireNoSupportForTyped = false),
        )
        val sentences = testSlice()
        val sites = ConfusionCorpus.sites(sentences, lexicon, bothSides = BOTH_SIDES)
            .take(20_000)
        var flagged = 0
        for (site in sites) {
            if (permissive.check(
                    site.sentence[site.position - 1],
                    site.sentence[site.position],
                    site.sentence.getOrNull(site.position + 1),
                ) != null
            ) flagged++
        }
        val rate = 100.0 * flagged / sites.size
        println("POSITIVE CONTROL: permissive detector false-alarms at %.2f%%".format(rate))
        assertTrue(
            rate > 3.0,
            "the false-alarm count failed to separate a permissive detector from the shipped " +
                "one (%.2f%%); a 0.28%% result from it would prove nothing".format(rate),
        )
    }

    /**
     * The case the operator actually named, kept as an example rather than as the measurement.
     *
     * One example is an anecdote. It is here because a reader should be able to see the thing
     * working on the sentence they asked about, and because a regression that broke exactly
     * this would be embarrassing to discover from a screenshot.
     */
    @Test
    fun theOperatorsExample() {
        val (lexicon, bigrams) = fixture()
        val d = RealWordErrorDetector(lexicon, bigrams)

        val wrong = d.check("דיברתי", "אם", "המורה")
        assertNotNull(wrong, "אם after דיברתי should be flagged as עם")
        assertEquals("עם", wrong.suggested)
        assertEquals(0, wrong.typedEvidence, "the corpus has never seen דיברתי אם")
        println("דיברתי [אם] -> ${wrong.suggested}, evidence ${wrong.suggestedEvidence}")

        // And the same word, used correctly, is left alone.
        assertNull(
            d.check("לא", "יודע", "אם"),
            "יודע between לא and אם is correct Hebrew and must not flag",
        )
        assertNull(d.check("אני", "עם", "חברים"), "אני עם חברים is correct and must not flag")
    }
}

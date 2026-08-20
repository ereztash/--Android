package com.hebrewime.core.learning

import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import com.hebrewime.core.prediction.PredictiveEngine
import com.hebrewime.core.prediction.TypingContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The locked adaptive-learning numbers, measured on the **test** slice with the interpolation
 * already fixed.
 *
 * The weight and the session floor were chosen on `hewiki_learning_dev.txt.gz`;
 * `scripts/slice_eval_corpus.py` proves that slice and this one share no sentence and refuses
 * to write them if they do.
 *
 * **Every assertion compares against the static baseline on the identical split.** An adaptive
 * accuracy figure on its own says nothing — the question is never "is it good", it is "is it
 * better than the model we already had, on the same sentences, in the same order".
 */
class LearningAccuracyTest {

    private val evalDir = File(System.getProperty("eval.dir")!!)

    private companion object {
        const val TEST_SLICE_SHA256 =
            "d8177a78eace8701da1f5af3371b1b6508db16e4a96a22a2b7656b24006cc2f9"
        const val SENTENCES_PER_USER = 80
        const val HISTORY = 40
    }

    private class Fixture(
        val lexicon: HebrewLexicon,
        val make: (Double, UserNgramModel) -> PredictiveEngine,
    )

    private fun fixture(): Fixture {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val words = ArrayList<String>(lexicon.size)
        for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))
        val trie = LexiconTrie.build(words)
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        val corrections = CorrectionEngine(
            lexicon, trie, frequency, NeutralCostModel, CorrectionEngine.Config(),
        )
        return Fixture(lexicon) { weight, model ->
            PredictiveEngine(
                lexicon, trie, frequency, bigrams, corrections,
                config = PredictiveEngine.Config(userWeight = weight),
                userModel = model,
            )
        }
    }

    private fun blocks(): List<SimulatedUser.Block> {
        val (blocks, hash) = SimulatedUser.load(
            evalDir, "hewiki_learning_test.txt.gz", SENTENCES_PER_USER,
        )
        assertEquals(TEST_SLICE_SHA256, hash, "the test slice is not the one measured")
        return blocks
    }

    @Test
    fun theAdaptiveLayerBeatsStaticOnTheIdenticalSplit() {
        val f = fixture()
        val blocks = blocks()
        assertEquals(120, blocks.size, "denominator: pseudo-users")

        val adaptive = SimulatedUser.run(blocks, f.lexicon, HISTORY, 2) { previous, model ->
            f.make(PredictiveEngine.DEFAULT_USER_WEIGHT, model)
                .predict(TypingContext("", listOf(previous))).map { it.word }
        }
        val static = SimulatedUser.run(blocks, f.lexicon, HISTORY, 2) { previous, _ ->
            f.make(0.0, UserNgramModel.empty())
                .predict(TypingContext("", listOf(previous))).map { it.word }
        }
        println("static   $static")
        println("adaptive $adaptive")
        println(
            "delta    top1 %+.2f (%+d cases)  top3 %+.2f (%+d cases)".format(
                adaptive.pct(adaptive.top1) - static.pct(static.top1),
                adaptive.top1 - static.top1,
                adaptive.pct(adaptive.top3) - static.pct(static.top3),
                adaptive.top3 - static.top3,
            )
        )

        assertEquals(static.attempts, adaptive.attempts, "the splits must be identical")
        assertTrue(adaptive.attempts > 40_000, "denominator too small: ${adaptive.attempts}")
        assertTrue(
            adaptive.top1 > static.top1,
            "the adaptive layer did NOT beat static on top-1. Per the build spec that is a " +
                "result to report to the operator, not a threshold to relax.",
        )
        assertTrue(adaptive.top3 > static.top3, "the adaptive layer did not beat static on top-3")
        // Offer rate reported and asserted, never hit rate alone: a layer that improved accuracy
        // by falling silent on hard cases would look identical on top-1.
        assertTrue(
            adaptive.offered >= static.offered,
            "the adaptive layer offered FEWER suggestions (${adaptive.offered} vs " +
                "${static.offered}); an accuracy gain bought by going quiet is not a gain",
        )
    }

    @Test
    fun theProtectionCostsAccuracyAndIsKeptAnyway() {
        // minimumSessions=1 scores better. It is not what ships, and this test exists so that
        // the price is a committed number rather than a thing someone rediscovers and
        // "optimises" away.
        val f = fixture()
        val blocks = blocks()
        val loose = SimulatedUser.run(blocks, f.lexicon, HISTORY, 1) { previous, model ->
            f.make(PredictiveEngine.DEFAULT_USER_WEIGHT, model)
                .predict(TypingContext("", listOf(previous))).map { it.word }
        }
        val shipped = SimulatedUser.run(blocks, f.lexicon, HISTORY, 2) { previous, model ->
            f.make(PredictiveEngine.DEFAULT_USER_WEIGHT, model)
                .predict(TypingContext("", listOf(previous))).map { it.word }
        }
        println("minimumSessions=1 (NOT shipped) $loose")
        println("minimumSessions=2 (shipped)     $shipped")
        println(
            "the once-seen protection costs top1 %.2f points, top3 %.2f points".format(
                loose.pct(loose.top1) - shipped.pct(shipped.top1),
                loose.pct(loose.top3) - shipped.pct(shipped.top3),
            )
        )
        assertTrue(
            loose.top1 > shipped.top1,
            "if the protection were free it would not be a protection; that it costs accuracy " +
                "is the evidence it actually withholds something",
        )
    }

    @Test
    fun coldStartIsReportedAsACurveNotANumber() {
        val f = fixture()
        val blocks = blocks()
        var previousDelta = -1.0
        val deltas = ArrayList<Pair<Int, Double>>()
        for (history in listOf(0, 10, 40)) {
            val adaptive = SimulatedUser.run(blocks, f.lexicon, history, 2) { previous, model ->
                f.make(PredictiveEngine.DEFAULT_USER_WEIGHT, model)
                    .predict(TypingContext("", listOf(previous))).map { it.word }
            }
            val static = SimulatedUser.run(blocks, f.lexicon, history, 2) { previous, _ ->
                f.make(0.0, UserNgramModel.empty())
                    .predict(TypingContext("", listOf(previous))).map { it.word }
            }
            val delta = adaptive.pct(adaptive.top1) - static.pct(static.top1)
            deltas.add(history to delta)
            println("history=%-3d delta top1 %+.2f  (adaptive %s)".format(history, delta, adaptive))
            previousDelta = delta
        }
        assertEquals(
            0.0, deltas.first().second, 1e-9,
            "with no history the adaptive layer must be exactly static; anything else means " +
                "it is inventing evidence it does not have",
        )
        assertTrue(
            deltas.last().second > deltas[1].second,
            "the benefit must grow with history, or it is not learning: $deltas",
        )
    }
}

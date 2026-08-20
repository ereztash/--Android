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

/**
 * Sweeps the interpolation weight and the session floor on the **dev** slice, and prints every
 * row including the worse ones.
 *
 * The static-only baseline is the first row of every table, because the only question that
 * matters is whether the adaptive layer beats it. If it does not, the honest outcome is to say
 * so and not ship it — a feature that feels adaptive and measures flat is worse than no
 * feature, because it spends privacy budget for nothing.
 *
 * Opt-in: `-PrunLearningSweep=1`.
 */
class LearningSweepTest {

    private val evalDir = File(System.getProperty("eval.dir")!!)

    @Test
    fun sweep() {
        if (System.getProperty("runLearningSweep").isNullOrEmpty()) {
            println("LearningSweepTest skipped; run with -PrunLearningSweep=1")
            return
        }
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

        val (blocks, hash) = SimulatedUser.load(evalDir, "hewiki_learning_dev.txt.gz", 80)
        println("=".repeat(96))
        println("LEARNING SWEEP -- dev slice sha256 ${hash.take(16)}...")
        println("${blocks.size} pseudo-users x 80 sentences; history = first 40, scored on 40")
        println("A Wikipedia article is not a person. See SimulatedUser for what that costs.")
        println("=".repeat(96))

        fun engineFor(weight: Double, model: UserNgramModel) = PredictiveEngine(
            lexicon, trie, frequency, bigrams, corrections,
            config = PredictiveEngine.Config(userWeight = weight),
            userModel = model,
        )

        println("\n--- interpolation weight, minimumSessions=2, history=40 ---")
        println("%-10s %s".format("weight", "result"))
        for (weight in listOf(0.0, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0)) {
            val score = SimulatedUser.run(blocks, lexicon, 40, 2) { previous, model ->
                engineFor(weight, model)
                    .predict(TypingContext("", listOf(previous))).map { it.word }
            }
            println("%-10s %s%s".format(weight, score, if (weight == 0.0) "   <- STATIC ONLY" else ""))
        }

        println("\n--- session floor, weight fixed at the row above ---")
        for (floor in listOf(1, 2, 3, 5)) {
            val score = SimulatedUser.run(blocks, lexicon, 40, floor) { previous, model ->
                engineFor(4.0, model)
                    .predict(TypingContext("", listOf(previous))).map { it.word }
            }
            println("minimumSessions=%-3d %s".format(floor, score))
        }

        println("\n--- cold start: how much history before it beats static ---")
        for (history in listOf(0, 5, 10, 20, 40, 60)) {
            val adaptive = SimulatedUser.run(blocks, lexicon, history, 2) { previous, model ->
                engineFor(4.0, model)
                    .predict(TypingContext("", listOf(previous))).map { it.word }
            }
            val static = SimulatedUser.run(blocks, lexicon, history, 2) { previous, _ ->
                engineFor(0.0, UserNgramModel.empty())
                    .predict(TypingContext("", listOf(previous))).map { it.word }
            }
            println(
                "history=%-4d adaptive %s | static top1 %.2f%% top3 %.2f%% | delta top1 %+.2f top3 %+.2f"
                    .format(
                        history, adaptive,
                        static.pct(static.top1), static.pct(static.top3),
                        adaptive.pct(adaptive.top1) - static.pct(static.top1),
                        adaptive.pct(adaptive.top3) - static.pct(static.top3),
                    )
            )
        }
        println("=".repeat(96))
    }
}

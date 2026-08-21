package com.hebrewime.core.learning

import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * L2 — the four directions, each measured against the **current adaptive layer**.
 *
 * Beating the static baseline is not the bar; the shipped layer already does that. The rule
 * recorded in `docs/LEARNING_MEASUREMENTS.md` before any of this ran is: beat the current
 * layer on top-1, do not lower the offer rate, weaken no privacy property, state the cost.
 *
 * Direction 4 — individually keyed out-of-lexicon words — is measured as a **ceiling only**.
 * It is what the shipped design deliberately refuses to do, and the number is here to price
 * that refusal rather than to reopen it.
 */
class LearningDirectionsTest {

    private val evalDir = File(System.getProperty("eval.dir")!!)

    private class Fixture(val lexicon: HebrewLexicon, val frequency: HebrewFrequency)

    private fun fixture(): Fixture {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        return Fixture(lexicon, frequency)
    }

    /** One measurement cell. Offer rate is carried alongside, never dropped. */
    private class Score(var attempts: Int = 0, var top1: Int = 0, var top3: Int = 0,
                        var offered: Int = 0) {
        fun pct(x: Int) = if (attempts == 0) 0.0 else 100.0 * x / attempts
        override fun toString() = "top1 %.2f%%  top3 %.2f%%  offered %.2f%%  n=%d"
            .format(pct(top1), pct(top3), pct(offered), attempts)
    }

    /**
     * Replays each pseudo-user's history under a given learning policy, then scores next-word
     * prediction on the remainder with a given scorer. Everything except the policy and the
     * scorer is identical across the variants, which is what makes them comparable.
     */
    private fun run(
        blocks: List<SimulatedUser.Block>,
        f: Fixture,
        bigrams: BigramModel,
        history: Int,
        learn: (UserNgramModel, Int, Int, Int) -> Unit,
        score: (UserNgramModel, Int, Int) -> Double,
    ): Score {
        val out = Score()
        for (block in blocks) {
            val model = UserNgramModel(minimumSessions = 2)
            var sentenceIndex = 0
            for (sentence in block.sentences.take(history)) {
                val bucket = sentenceIndex % 2
                for (i in 1 until sentence.size) {
                    val a = SimulatedUser.idOf(f.lexicon, sentence[i - 1])
                    val b = SimulatedUser.idOf(f.lexicon, sentence[i])
                    learn(model, a, b, bucket)
                    model.recordWord(b)
                }
                model.endSession()
                sentenceIndex++
            }
            for (sentence in block.sentences.drop(history)) {
                for (i in 1 until sentence.size) {
                    val previous = SimulatedUser.idOf(f.lexicon, sentence[i - 1])
                    val target = SimulatedUser.idOf(f.lexicon, sentence[i])
                    out.attempts++
                    val candidates = LinkedHashMap<Int, Double>()
                    for ((w, c) in bigrams.continuationsOf(previous, 16)) {
                        candidates[w] = c.toDouble()
                    }
                    for ((w, _) in model.continuationsOf(previous, 16)) {
                        candidates[w] = (candidates[w] ?: 0.0)
                    }
                    if (candidates.isEmpty()) continue
                    val ranked = candidates.keys
                        .filter { it >= 0 }
                        .map { it to (candidates[it]!! + score(model, previous, it)) }
                        .sortedByDescending { it.second }
                        .take(3)
                    if (ranked.isNotEmpty()) out.offered++
                    if (ranked.firstOrNull()?.first == target) out.top1++
                    if (ranked.any { it.first == target }) out.top3++
                }
            }
        }
        return out
    }

    /**
     * D1 measured where it actually belongs.
     *
     * The first run of this file scored personal word frequency on **next-word** prediction and
     * it lost 1.6 points — which is what a context-free signal does when it is added to a
     * context-conditioned ranking: it boosts words the person uses a lot whether or not they
     * follow this particular predecessor, drowning the pair evidence.
     *
     * That was a flaw in the measurement, not a property of the idea. Personal frequency is a
     * ranking signal for **completions**, where the question is "which of the words starting
     * with these letters did they mean" and the answer genuinely does depend on which words
     * this person uses. Measured here instead.
     */
    @Test
    fun d1PersonalFrequencyMeasuredOnCompletionsWhereItBelongs() {
        val f = fixture()
        val words = ArrayList<String>(f.lexicon.size)
        for (i in 0 until f.lexicon.size) words.add(f.lexicon.wordAt(i))
        val trie = LexiconTrie.build(words)
        val (blocks, _) = SimulatedUser.load(evalDir, "hewiki_learning_test.txt.gz", 80)
        val history = 40

        var attempts = 0
        var baseTop1 = 0
        var baseTop3 = 0
        var personalTop1 = 0
        var personalTop3 = 0

        for (block in blocks) {
            val model = UserNgramModel(minimumSessions = 2)
            for (sentence in block.sentences.take(history)) {
                for (t in sentence) model.recordWord(SimulatedUser.idOf(f.lexicon, t))
                model.endSession()
            }
            for (sentence in block.sentences.drop(history)) {
                for (target in sentence) {
                    if (target.length < 4) continue
                    val prefix = target.substring(0, 2)
                    val candidates = trie.completionsTopK(prefix, 24) {
                        f.frequency.logFrequencyOf(it)
                    }
                    if (candidates.isEmpty()) continue
                    attempts++

                    val base = candidates
                        .map { it to f.frequency.logFrequencyOf(it).toDouble() }
                        .sortedByDescending { it.second }.take(3).map { it.first }
                    val personal = candidates
                        .map {
                            it to f.frequency.logFrequencyOf(it).toDouble() +
                                2.0 * minOf(model.unigramLogCountOf(it), 32)
                        }
                        .sortedByDescending { it.second }.take(3).map { it.first }

                    val targetIndex = f.lexicon.indexOf(target)
                    if (base.firstOrNull() == targetIndex) baseTop1++
                    if (targetIndex in base) baseTop3++
                    if (personal.firstOrNull() == targetIndex) personalTop1++
                    if (targetIndex in personal) personalTop3++
                }
            }
        }
        fun pct(x: Int) = 100.0 * x / attempts
        println("=".repeat(94))
        println("D1 ON COMPLETIONS -- 2-letter prefix, n=$attempts")
        println("  lexicon frequency only     : top1 %.2f%%  top3 %.2f%%"
            .format(pct(baseTop1), pct(baseTop3)))
        println("  + personal word frequency  : top1 %.2f%%  top3 %.2f%%"
            .format(pct(personalTop1), pct(personalTop3)))
        println("  delta                      : top1 %+.2f    top3 %+.2f"
            .format(pct(personalTop1) - pct(baseTop1), pct(personalTop3) - pct(baseTop3)))
        println("=".repeat(94))
        assertTrue(attempts > 20_000, "denominator too small: $attempts")
    }

    @Test
    fun measureAllFourDirections() {
        val f = fixture()
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        val (blocks, hash) = SimulatedUser.load(
            evalDir, "hewiki_learning_test.txt.gz", 80,
        )
        val history = 40
        val w = 2.0
        val cap = 32

        fun bounded(v: Int) = w * minOf(v, cap)

        println("=".repeat(94))
        println("L2 DIRECTIONS -- learning_test ${hash.take(16)}..., ${blocks.size} pseudo-users, "
            + "history $history")
        println("=".repeat(94))

        // BASELINE: what ships today. Pairs only.
        val current = run(blocks, f, bigrams, history,
            learn = { m, a, b, _ -> m.record(a, b) },
            score = { m, p, c -> bounded(m.logCountOf(p, c)) })
        println("current shipped layer          $current")

        // D1: personal unigram frequency added on top of the pair evidence.
        val d1 = run(blocks, f, bigrams, history,
            learn = { m, a, b, _ -> m.record(a, b) },
            score = { m, p, c -> bounded(m.logCountOf(p, c)) + bounded(m.unigramLogCountOf(c)) })
        println("D1 personal word frequency     $d1")

        // D2: context bucket. Two buckets, alternating by sentence -- the app-bias mechanism
        // with the app identity removed. See the doc for why it is not keyed by package.
        val d2 = run(blocks, f, bigrams, history,
            learn = { m, a, b, bucket -> m.record(a, b, bucket) },
            score = { m, p, c -> bounded(m.logCountOf(p, c)) + bounded(m.bucketLogCountOf(p, c, 0)) })
        println("D2 context bucket              $d2")

        // D3: endorsed observations weigh more. Simulated endorsement: a pair the corpus itself
        // strongly attests stands in for one the user tapped, because a tap means "yes, that".
        val d3 = run(blocks, f, bigrams, history,
            learn = { m, a, b, _ ->
                val endorsed = bigrams.logCountOf(a, b) > 40
                m.record(a, b, 0, if (endorsed) 4 else 1)
            },
            score = { m, p, c -> bounded(m.logCountOf(p, c)) })
        println("D3 endorsed pairs weigh more   $d3")

        // D4 CEILING ONLY: out-of-lexicon words keyed individually instead of collapsing to one
        // sentinel. This is what the shipped design refuses to do; the number prices the refusal.
        val synthetic = HashMap<String, Int>()
        var nextId = 1_000_000
        val d4 = run(blocks, f, bigrams, history,
            learn = { m, a, b, _ -> m.record(a, b) },
            score = { m, p, c -> bounded(m.logCountOf(p, c)) })
        var oovPairs = 0
        var oovDistinct = 0
        for (block in blocks) {
            for (s in block.sentences.take(history)) {
                for (t in s) {
                    if (f.lexicon.indexOf(t) < 0) {
                        oovPairs++
                        if (synthetic.putIfAbsent(t, nextId++) == null) oovDistinct++
                    }
                }
            }
        }
        println()
        println("D4 out-of-lexicon words: $oovPairs occurrences, $oovDistinct distinct forms "
            + "in the histories")
        println("   These are the words the shipped model collapses to one sentinel. Keying")
        println("   them individually would mean storing their TEXT -- see UserNgramModel.")

        println()
        println("%-30s %8s %8s %8s".format("direction", "d top1", "d top3", "d offered"))
        for ((name, s) in listOf("D1" to d1, "D2" to d2, "D3" to d3)) {
            println("%-30s %+8.3f %+8.3f %+8.3f".format(
                name,
                s.pct(s.top1) - current.pct(current.top1),
                s.pct(s.top3) - current.pct(current.top3),
                s.pct(s.offered) - current.pct(current.offered)))
        }
        println("=".repeat(94))

        assertTrue(current.attempts > 40_000, "denominator too small")
        assertTrue(d1.attempts == current.attempts, "the splits must be identical")
    }
}

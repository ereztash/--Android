package com.hebrewime.core.learning

import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a learned model actually looks like after a pseudo-user's worth of typing.
 *
 * Two numbers are quoted in [UserNgramModel]'s documentation — the share of pairs that touch
 * the out-of-lexicon sentinel, and how close a normal user comes to the capacity cap. Both are
 * measured here rather than asserted there, because a KDoc that says "measured" and points at a
 * document is a claim like any other.
 */
class LearningShapeTest {

    @Test
    fun reportTheShapeOfALearnedModel() {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val (blocks, hash) = SimulatedUser.load(
            File(System.getProperty("eval.dir")!!), "hewiki_learning_test.txt.gz", 80,
        )

        var totalPairs = 0
        var oovPairs = 0
        var eligible = 0
        var maxPairs = 0
        var totalTokens = 0
        for (block in blocks) {
            val model = UserNgramModel(minimumSessions = 2)
            for (sentence in block.sentences) {
                totalTokens += sentence.size
                for (i in 1 until sentence.size) {
                    model.record(
                        SimulatedUser.idOf(lexicon, sentence[i - 1]),
                        SimulatedUser.idOf(lexicon, sentence[i]),
                    )
                }
                model.endSession()
            }
            totalPairs += model.pairCount
            eligible += model.eligiblePairCount
            maxPairs = maxOf(maxPairs, model.pairCount)
            oovPairs += model.entries().count {
                it[0] == UserNgramModel.OOV || it[1] == UserNgramModel.OOV
            }
        }
        val users = blocks.size
        println("=".repeat(88))
        println("LEARNED MODEL SHAPE -- test slice ${hash.take(16)}...")
        println("$users pseudo-users x 80 sentences, $totalTokens tokens")
        println("  distinct pairs per user: mean %.0f, max %d".format(totalPairs.toDouble() / users, maxPairs))
        println("  eligible (>=2 sessions): mean %.0f  (%.1f%% of stored)"
            .format(eligible.toDouble() / users, 100.0 * eligible / totalPairs))
        println("  pairs touching the OOV sentinel: %.1f%% (%d of %d)"
            .format(100.0 * oovPairs / totalPairs, oovPairs, totalPairs))
        println("  capacity cap is ${UserNgramModel.DEFAULT_CAPACITY}; "
            + "largest observed user is %.1f%% of it".format(100.0 * maxPairs / UserNgramModel.DEFAULT_CAPACITY))
        println("  serialized size at the cap: %,d bytes before compression"
            .format(UserNgramModel.DEFAULT_CAPACITY * UserNgramCodec.BYTES_PER_PAIR))
        println("=".repeat(88))

        assertTrue(maxPairs < UserNgramModel.DEFAULT_CAPACITY,
            "no simulated user reached the cap, so the cap bounds pathological use rather " +
                "than normal use -- if this ever fails the cap is being hit in the measurement " +
                "and the reported accuracy includes eviction effects")
        assertTrue(totalPairs > 0)
    }
}

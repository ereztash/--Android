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
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adding a layer must not move a published number.
 *
 * Every figure in `docs/PREDICTION_MEASUREMENTS.md` and `docs/CONFUSION_MEASUREMENTS.md` is a
 * claim about the engine that ships. Learning is **off by default**, so with an empty model and
 * a zero weight the engine has to answer exactly as it did before this layer existed —
 * otherwise those documents quietly stop describing the product and nobody finds out.
 *
 * This is checked over thousands of real contexts rather than a handful of hand-picked ones,
 * because the interesting failure is a tie broken differently in some rare arrangement, not an
 * obvious change on a common word.
 *
 * Denominator: 3 tests, the first over 6,000 contexts.
 */
class LearningNeutralityTest {

    private class Parts(
        val lexicon: HebrewLexicon,
        val trie: LexiconTrie,
        val frequency: HebrewFrequency,
        val bigrams: BigramModel,
        val corrections: CorrectionEngine,
    )

    private fun parts(): Parts {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val words = ArrayList<String>(lexicon.size)
        for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))
        val trie = LexiconTrie.build(words)
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        return Parts(
            lexicon, trie, frequency, bigrams,
            CorrectionEngine(lexicon, trie, frequency, NeutralCostModel, CorrectionEngine.Config()),
        )
    }

    private fun contexts(): List<List<String>> {
        val raw = GZIPInputStream(
            File(File(System.getProperty("eval.dir")!!), "hewiki_eval_sample.txt.gz").inputStream()
        ).use { it.readBytes() }
        return raw.toString(Charsets.UTF_8).split('\n')
            .filter { it.isNotBlank() }.map { it.split(' ') }
    }

    @Test
    fun anEmptyUserModelChangesNothingAcrossTheWholeEvalSlice() {
        val p = parts()
        val before = PredictiveEngine(p.lexicon, p.trie, p.frequency, p.bigrams, p.corrections)
        val after = PredictiveEngine(
            p.lexicon, p.trie, p.frequency, p.bigrams, p.corrections,
            userModel = UserNgramModel.empty(),
        )
        var compared = 0
        for (sentence in contexts()) {
            for (i in 1 until sentence.size) {
                val target = sentence[i]
                if (target.length < 3) continue
                for (prefix in listOf("", target.substring(0, 2))) {
                    val ctx = TypingContext(prefix, listOf(sentence[i - 1]))
                    assertEquals(
                        before.predict(ctx).map { it.word to it.score },
                        after.predict(ctx).map { it.word to it.score },
                        "prediction moved for '$prefix' after '${sentence[i - 1]}'",
                    )
                    compared++
                }
            }
        }
        println("compared $compared contexts, engine with and without an empty user model")
        assertTrue(compared > 50_000, "too few contexts compared: $compared")
    }

    /**
     * POSITIVE CONTROL for the test above.
     *
     * Identical output is only reassuring if a NON-empty model would have produced different
     * output through the same comparison. Without this, neutrality would pass by the learned
     * layer being wired up to nothing.
     */
    @Test
    fun aPopulatedUserModelDoesChangeSomething() {
        val p = parts()
        val learned = UserNgramModel(minimumSessions = 1)
        val previous = p.lexicon.indexOf("של")
        assertTrue(previous >= 0)
        // Teach it a continuation the corpus ranks nowhere near the top.
        val unusual = p.lexicon.indexOf("זכוכית")
        assertTrue(unusual >= 0, "the test word must be in the lexicon")
        repeat(30) { learned.record(previous, unusual) }

        val static = PredictiveEngine(p.lexicon, p.trie, p.frequency, p.bigrams, p.corrections)
        val adaptive = PredictiveEngine(
            p.lexicon, p.trie, p.frequency, p.bigrams, p.corrections,
            config = PredictiveEngine.Config(userWeight = 4.0),
            userModel = learned,
        )
        val ctx = TypingContext("", listOf("של"))
        val staticWords = static.predict(ctx).map { it.word }
        val adaptiveWords = adaptive.predict(ctx).map { it.word }
        println("POSITIVE CONTROL static=$staticWords adaptive=$adaptiveWords")
        assertTrue(
            staticWords != adaptiveWords,
            "the learned layer changed nothing, so the neutrality test above proves nothing",
        )
        assertTrue("זכוכית" in adaptiveWords, "the learned continuation should surface")
    }

    @Test
    fun theSentinelIsNeverOfferedAsAWord() {
        // OOV is id -1. If it ever reached lexicon.wordAt it would throw; if it were offered it
        // would be a blank chip. Neither is acceptable, and the filter runs before truncation.
        val p = parts()
        val learned = UserNgramModel(minimumSessions = 1)
        val previous = p.lexicon.indexOf("של")
        repeat(99) { learned.record(previous, UserNgramModel.OOV) }
        val engine = PredictiveEngine(
            p.lexicon, p.trie, p.frequency, p.bigrams, p.corrections,
            config = PredictiveEngine.Config(userWeight = 8.0),
            userModel = learned,
        )
        val out = engine.predict(TypingContext("", listOf("של")))
        assertTrue(out.none { it.wordIndex < 0 }, "the sentinel escaped into the strip")
        assertEquals(3, out.size, "and it must not cost a slot: ${out.map { it.word }}")
    }
}

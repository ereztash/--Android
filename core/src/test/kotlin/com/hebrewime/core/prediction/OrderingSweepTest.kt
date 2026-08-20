package com.hebrewime.core.prediction

import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * Scores every [PredictiveEngine.Mix] on **both** corpora in one table.
 *
 * ### Why one corpus is not enough here
 * When the typed string is not in the lexicon it is either an unfinished word or a misspelled
 * one, and the strip has three slots that both readings want. Each corpus answers only half
 * the question, and each has a degenerate winner:
 *
 * - The held-out **completion** corpus (`lexicon/eval/`) asks "was the full word offered from
 *   a prefix". A policy that never shows a correction wins it outright.
 * - The golden **typo** corpus (`lexicon/golden/a_uniform.tsv.gz`) asks "was the intended word
 *   offered for a misspelling". A policy that never shows a completion wins that one.
 *
 * So both columns are printed for every policy and the choice is made on the pair. Optimising
 * either column alone would produce a keyboard that is excellent at one of the two things a
 * user actually does and useless at the other.
 *
 * Tagged slow and opt-in: run with `-PrunWeightSweep=1`.
 */
class OrderingSweepTest {

    private val evalDir = File(System.getProperty("eval.dir")!!)
    private val goldenDir = File(System.getProperty("golden.dir")!!)

    @Test
    fun sweep() {
        if (System.getProperty("runWeightSweep") == null) {
            println("OrderingSweepTest skipped; run with -PrunWeightSweep=1")
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

        val sentences = GZIPInputStream(
            File(evalDir, "hewiki_eval_sample.txt.gz").inputStream()
        ).use { it.readBytes() }
            .toString(Charsets.UTF_8).split('\n')
            .filter { it.isNotBlank() }
            .map { it.split(' ') }

        val typos = GZIPInputStream(File(goldenDir, "a_uniform.tsv.gz").inputStream())
            .use { it.readBytes() }
            .toString(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
            .map { it.split('\t') }

        println("=".repeat(104))
        println("ORDERING SWEEP -- completion corpus (held out) vs typo corpus (golden A)")
        println("both columns printed for every policy; neither is optimised alone")
        println("=".repeat(104))
        println(
            "%-28s %-9s %-9s %-9s | %-9s %-9s".format(
                "mix", "p1 top3", "p2 top3", "p3 top3", "typo top1", "typo top3",
            )
        )

        for (mix in PredictiveEngine.Mix.entries) {
            val engine = PredictiveEngine(
                lexicon, trie, frequency, bigrams, corrections,
                PredictiveEngine.Config(mix = mix),
            )

            val attempts = IntArray(4)
            val hits3 = IntArray(4)
            for (s in sentences) {
                for (i in 1 until s.size) {
                    val previous = s[i - 1]
                    val target = s[i]
                    if (target.length < 3) continue
                    for (k in 1..3) {
                        if (target.length <= k || attempts[k] >= LIMIT) continue
                        attempts[k]++
                        if (engine.predict(target.substring(0, k), previous)
                                .any { it.word == target }
                        ) hits3[k]++
                    }
                }
            }

            var typoTop1 = 0
            var typoTop3 = 0
            for ((typed, want) in typos) {
                // No previous word: the typo corpus is a word list, not running text. Passing
                // one would be inventing context the corpus does not have.
                val p = engine.predict(typed, null)
                if (p.firstOrNull()?.word == want) typoTop1++
                if (p.any { it.word == want }) typoTop3++
            }

            fun pct(x: Int, n: Int) = if (n == 0) "n/a" else "%.2f%%".format(100.0 * x / n)
            println(
                "%-28s %-9s %-9s %-9s | %-9s %-9s".format(
                    mix.name,
                    pct(hits3[1], attempts[1]),
                    pct(hits3[2], attempts[2]),
                    pct(hits3[3], attempts[3]),
                    pct(typoTop1, typos.size),
                    pct(typoTop3, typos.size),
                )
            )
        }
        println("=".repeat(104))
        println("completion denominator: ${LIMIT} per prefix length; typo denominator: ${typos.size}")
        println("=".repeat(104))
    }

    private companion object {
        const val LIMIT = 20_000
    }
}

package com.hebrewime.core.prediction

import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.lexicon.PrefixStripper
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * W7 - prefix-aware completion. The arm, its guards, the three predictions and the four-clause
 * adoption rule were committed before this file existed. See `docs/PREFIX_COMPLETION.md`.
 *
 * ### What is under test
 * `PredictiveEngine` references `PrefixStripper` **zero times** and its trie is built from
 * lexicon words, so a prefixed form that is not literally in the lexicon can never be offered
 * as a completion - even though `CorrectionEngine.isValid` accepts it as correctly spelled.
 * `בהתבוללות` is accepted and unsuggestable.
 *
 * ### Two modes, because one of them cannot fail clause 2
 * **`append`** puts prefixed candidates *after* the baseline's, filling to `limit`. Under it a
 * baseline top-1 can only be displaced when the baseline returned nothing, so **clause 2 is
 * trivially satisfied and proves nothing** - and a clause that cannot fail is not a clause.
 * **`interleave`** lets them compete on score, which is what gives clause 2 something to bite
 * on. Both are reported; the rule is applied to both.
 *
 * A caveat the interleave mode cannot escape: a prefixed candidate's score comes from
 * completing the *remainder*, so it is computed over a different string than the baseline's.
 * The two scores are not on one scale, and that is a property of the arm rather than of this
 * harness.
 */
class PrefixCompletionTest {

    private val evalDir = File(System.getProperty("eval.dir")!!)

    private companion object {
        const val CELL_LIMIT = 20_000
        const val LIMIT = 3
    }

    private fun engine(): Pair<PredictiveEngine, HebrewLexicon> {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val words = ArrayList<String>(lexicon.size)
        for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))
        val trie = LexiconTrie.build(words)
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        return PredictiveEngine(
            lexicon, trie, frequency, bigrams,
            CorrectionEngine(lexicon, trie, frequency, NeutralCostModel, CorrectionEngine.Config()),
        ) to lexicon
    }

    private fun read(name: String): List<List<String>>? {
        val f = File(evalDir, name)
        if (!f.isFile) return null
        return GZIPInputStream(f.inputStream()).use { it.readBytes() }
            .toString(Charsets.UTF_8).split('\n')
            .filter { it.isNotBlank() }
            .map { it.split(' ').filter { t -> t.isNotEmpty() } }
    }

    /** The arm. Returns the extra candidates a prefix analysis of [typed] makes reachable. */
    private fun prefixed(e: PredictiveEngine, typed: String, previous: String?): List<Prediction> {
        val out = ArrayList<Prediction>()
        for (p in PrefixStripper.ALL) {
            if (p.length >= typed.length) continue          // nothing left to complete
            if (!typed.startsWith(p)) continue
            val remainder = typed.substring(p.length)
            for (c in e.predict(remainder, previous)) {
                if (c.word.length < remainder.length) continue
                out.add(c.copy(word = p + c.word))
            }
        }
        return out
    }

    private class Cell {
        var attempts = 0
        var baseTop3 = 0
        var baseTop1 = 0
        var armTop3 = 0
        var armTop1 = 0
        var fired = 0
        var top1Broken = 0
        var top3Gained = 0
    }

    private fun run(
        e: PredictiveEngine, sample: List<List<String>>, prefixLength: Int, interleave: Boolean,
    ): Cell {
        val c = Cell()
        for (s in sample) {
            for (i in 1 until s.size) {
                if (c.attempts >= CELL_LIMIT) return c
                val target = s[i]
                if (target.length < 3 || target.length <= prefixLength) continue
                val typed = target.substring(0, prefixLength)
                val base = e.predict(typed, s[i - 1])
                c.attempts++
                val bT3 = base.any { it.word == target }
                val bT1 = base.firstOrNull()?.word == target
                if (bT3) c.baseTop3++
                if (bT1) c.baseTop1++

                val extra = prefixed(e, typed, s[i - 1])
                if (extra.isNotEmpty()) c.fired++
                val merged = if (interleave) {
                    (base + extra).distinctBy { it.word }.sortedByDescending { it.score }.take(LIMIT)
                } else {
                    (base + extra.filter { x -> base.none { it.word == x.word } }).take(LIMIT)
                }
                val aT3 = merged.any { it.word == target }
                val aT1 = merged.firstOrNull()?.word == target
                if (aT3) c.armTop3++
                if (aT1) c.armTop1++
                if (bT1 && !aT1) c.top1Broken++
                if (!bT3 && aT3) c.top3Gained++
            }
        }
        return c
    }

    private fun pct(x: Int, n: Int) = if (n == 0) 0.0 else 100.0 * x / n

    @Test
    fun prefixCompletion() {
        if (System.getProperty("runPrefixCompletion").isNullOrEmpty()) {
            println("skipped; -PrunPrefixCompletion=1"); return
        }
        val (e, _) = engine()
        val slices = listOf(
            "typed" to read("he_typed_raw.txt.gz"),
            "transcribed" to read("he_conversational_test.txt.gz"),
            "encyclopedic" to read("hewiki_eval_sample.txt.gz"),
        ).mapNotNull { (n, s) -> s?.let { n to it } }

        println("W7 - prefix-aware completion. See docs/PREFIX_COMPLETION.md.")
        println("PrefixStripper.ALL = ${PrefixStripper.ALL.size} prefixes, minStem ${PrefixStripper.DEFAULT_MIN_STEM}")
        println()

        val results = LinkedHashMap<String, Cell>()
        for (mode in listOf(false, true)) {
            val name = if (mode) "interleave" else "append"
            println("=".repeat(104))
            println("MODE: $name")
            println("=".repeat(104))
            println("%-16s %4s %10s %10s %9s %10s %10s %9s %9s".format(
                "slice", "pfx", "base top3", "arm top3", "delta", "base top1", "arm top1",
                "fires", "broke t1"))
            for ((sliceName, sample) in slices) {
                for (k in listOf(1, 2, 3)) {
                    val c = run(e, sample, k, mode)
                    results["$name/$sliceName/$k"] = c
                    println("%-16s %4d %9.2f%% %9.2f%% %+8.2f %9.2f%% %9.2f%% %8.1f%% %8.2f%%".format(
                        sliceName, k, pct(c.baseTop3, c.attempts), pct(c.armTop3, c.attempts),
                        pct(c.armTop3, c.attempts) - pct(c.baseTop3, c.attempts),
                        pct(c.baseTop1, c.attempts), pct(c.armTop1, c.attempts),
                        pct(c.fired, c.attempts), pct(c.top1Broken, c.attempts)))
                }
            }
            println()
        }

        println("=".repeat(104))
        println("AGAINST THE PRE-REGISTERED RULE (docs/PREFIX_COMPLETION.md)")
        println("=".repeat(104))
        var anyAdopted = false
        for (mode in listOf("append", "interleave")) {
            fun d(slice: String, k: Int): Double {
                val c = results["$mode/$slice/$k"] ?: return 0.0
                return pct(c.armTop3, c.attempts) - pct(c.baseTop3, c.attempts)
            }
            val t3 = results["$mode/typed/3"]!!
            val c1 = d("typed", 3) >= 0.5
            val c2 = pct(t3.top1Broken, t3.attempts) <= 0.1
            val c3 = d("typed", 1) >= -0.1
            val c4 = d("transcribed", 3) >= -0.1 && d("encyclopedic", 3) >= -0.1
            val fires = pct(t3.fired, t3.attempts)
            val ok = c1 && c2 && c3 && c4
            anyAdopted = anyAdopted || ok
            println("  $mode:")
            println("    1. prefix-3 typed top-3 improves >= 0.50pp      %+.2f  %s".format(d("typed", 3), if (c1) "PASS" else "FAIL"))
            println("    2. <= 0.10pp of top-1-correct positions broken   %.2f  %s%s".format(
                pct(t3.top1Broken, t3.attempts), if (c2) "PASS" else "FAIL",
                if (mode == "append") "   (append CANNOT break top-1; this clause proves nothing here)" else ""))
            println("    3. prefix-1 typed top-3 does not fall > 0.10pp  %+.2f  %s".format(d("typed", 1), if (c3) "PASS" else "FAIL"))
            println("    4. other registers do not regress > 0.10pp      transcribed %+.2f, wiki %+.2f  %s".format(
                d("transcribed", 3), d("encyclopedic", 3), if (c4) "PASS" else "FAIL"))
            println("    W7-P3: arm fires on %.1f%% of positions (bar: < 15%%)  %s".format(
                fires, if (fires < 15.0) "HELD" else "FALSIFIED"))
            println("    -> ${if (ok) "ADOPTED" else "NOT ADOPTED"}")
            println()
        }
        println("  VERDICT: " + if (anyAdopted) "a mode meets every clause"
                                else "NO mode meets every clause - NOTHING IS ADOPTED")
        println("=".repeat(104))
    }
}

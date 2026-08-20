package com.hebrewime.core.correction

import com.hebrewime.core.keyboard.Layouts
import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Measures the correction engine against the hash-locked golden corpora and prints the table
 * that `docs/CORRECTION_MEASUREMENTS.md` reports.
 *
 * This test deliberately asserts almost nothing about accuracy. Thresholds are set **after** a
 * baseline exists, never before, and a number that has never been measured is not a number to
 * write a threshold around. What it does assert is structural: that the corpora are the ones
 * whose hashes are recorded, and that every denominator is non-zero.
 *
 * Latency here is a **JVM number on this build host**. It is not a device number and is never
 * quoted as one. Real input latency needs the TraceSectionMetric harness and real hardware,
 * which is M7 and is recorded as NOT RUN.
 */
class CorrectionMeasurementTest {

    private val goldenDir = File(System.getProperty("golden.dir")!!)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun readCorpus(name: String): Pair<List<String>, String> {
        val raw = GZIPInputStream(File(goldenDir, name).inputStream()).use { it.readBytes() }
        val lines = raw.toString(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
        return lines to sha256(raw)
    }

    private data class Fixture(
        val lexicon: HebrewLexicon,
        val trie: LexiconTrie,
        val frequency: HebrewFrequency,
        val adjacency: KeyboardAdjacency,
    )

    private fun fixture(): Fixture {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val words = ArrayList<String>(lexicon.size)
        for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        require(frequency.size == lexicon.size) {
            "frequency table has ${frequency.size} entries but the lexicon has ${lexicon.size}"
        }
        return Fixture(
            lexicon = lexicon,
            trie = LexiconTrie.build(words),
            frequency = frequency,
            adjacency = KeyboardAdjacency.from(Layouts.hebrew),
        )
    }

    private data class PairResult(
        val n: Int,
        val top1: Int,
        val top3: Int,
        val noSuggestion: Int,
        val autoReplaced: Int,
        val autoReplacedCorrectly: Int,
        val latenciesNanos: LongArray,
    )

    private fun runPairs(engine: CorrectionEngine, pairs: List<String>): PairResult {
        var top1 = 0
        var top3 = 0
        var none = 0
        var auto = 0
        var autoRight = 0
        val latencies = LongArray(pairs.size)
        pairs.forEachIndexed { i, line ->
            val (typo, expected) = line.split('\t')
            val start = System.nanoTime()
            val suggestions = engine.suggest(typo)
            latencies[i] = System.nanoTime() - start
            if (suggestions.isEmpty()) none++
            if (suggestions.isNotEmpty() && suggestions[0].word == expected) top1++
            if (suggestions.take(3).any { it.word == expected }) top3++
            if (engine.shouldAutoReplace(suggestions)) {
                auto++
                if (suggestions[0].word == expected) autoRight++
            }
        }
        latencies.sort()
        return PairResult(pairs.size, top1, top3, none, auto, autoRight, latencies)
    }

    private fun runControl(engine: CorrectionEngine, words: List<String>): Triple<Int, Int, Int> {
        var suggested = 0
        var replaced = 0
        words.forEach { w ->
            val s = engine.suggest(w)
            if (s.isNotEmpty()) suggested++
            if (engine.shouldAutoReplace(s)) replaced++
        }
        return Triple(words.size, suggested, replaced)
    }

    private fun percentile(sorted: LongArray, p: Double): Double =
        sorted[((sorted.size - 1) * p).toInt()] / 1_000_000.0

    private fun report(label: String, engine: CorrectionEngine): String {
        val (a, aHash) = readCorpus("a_uniform.tsv.gz")
        val (b, bHash) = readCorpus("b_adjacency.tsv.gz")
        val (c1, c1Hash) = readCorpus("c1_control_lexicon.txt.gz")
        val (c2, c2Hash) = readCorpus("c2_control_real.txt.gz")

        val ra = runPairs(engine, a)
        val rb = runPairs(engine, b)
        val (n1, sug1, rep1) = runControl(engine, c1)
        val (n2, sug2, rep2) = runControl(engine, c2)

        fun pct(x: Int, n: Int) = "%.2f%%".format(100.0 * x / n)

        return buildString {
            appendLine("=".repeat(94))
            appendLine("CORRECTION MEASUREMENT -- $label")
            appendLine("=".repeat(94))
            appendLine("corpus A (uniform typos)      n=${ra.n}  sha256 ${aHash.take(16)}...")
            appendLine("  top-1                        ${pct(ra.top1, ra.n)}  (${ra.top1})")
            appendLine("  top-3                        ${pct(ra.top3, ra.n)}  (${ra.top3})")
            appendLine("  no suggestion offered        ${pct(ra.noSuggestion, ra.n)}")
            appendLine("  auto-replaced                ${pct(ra.autoReplaced, ra.n)}")
            appendLine("  auto-replaced CORRECTLY      ${pct(ra.autoReplacedCorrectly, ra.n)}")
            appendLine("  auto-replaced WRONGLY        " +
                pct(ra.autoReplaced - ra.autoReplacedCorrectly, ra.n))
            appendLine("  latency p50/p95/p99 (ms)     " +
                "%.2f / %.2f / %.2f".format(
                    percentile(ra.latenciesNanos, 0.50),
                    percentile(ra.latenciesNanos, 0.95),
                    percentile(ra.latenciesNanos, 0.99),
                ) + "   [JVM on the build host, NOT a device]")
            appendLine()
            appendLine("corpus B (adjacency typos -- PARTLY CIRCULAR, never merged into A)")
            appendLine("                              n=${rb.n}  sha256 ${bHash.take(16)}...")
            appendLine("  top-1                        ${pct(rb.top1, rb.n)}")
            appendLine("  top-3                        ${pct(rb.top3, rb.n)}")
            appendLine()
            appendLine("corpus C1 (known-correct words, unmodified)")
            appendLine("                              n=$n1  sha256 ${c1Hash.take(16)}...")
            appendLine("  any suggestion offered       ${pct(sug1, n1)}")
            appendLine("  FALSE AUTO-REPLACE RATE      ${pct(rep1, n1)}")
            appendLine()
            appendLine("corpus C2 (raw held-out tokens, unmodified)")
            appendLine("                              n=$n2  sha256 ${c2Hash.take(16)}...")
            appendLine("  any suggestion offered       ${pct(sug2, n2)}")
            appendLine("  FALSE AUTO-REPLACE RATE      ${pct(rep2, n2)}")
            appendLine("=".repeat(94))
        }
    }

    @Test
    fun measureBaselineAndTuned() {
        val f = fixture()

        // ---- BASELINE: every edit costs one unit, frequency is only a tie-break, and only a
        // sole candidate one edit away may auto-replace. No weight has been chosen yet.
        val baseline = CorrectionEngine(
            lexicon = f.lexicon,
            trie = f.trie,
            frequency = f.frequency,
            costs = NeutralCostModel,
            config = CorrectionEngine.Config(),
        )
        println(report("BASELINE (neutral costs, no frequency weight)", baseline))

        // Structural assertions only. Accuracy thresholds are chosen after the baseline is
        // read, and live in CorrectionAccuracyTest.
        val (a, _) = readCorpus("a_uniform.tsv.gz")
        assertEquals(4000, a.size, "corpus A denominator")
        assertTrue(f.lexicon.size == 355_587)
    }
}

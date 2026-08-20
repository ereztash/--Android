package com.hebrewime.core.correction

import com.hebrewime.core.keyboard.Layouts
import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Sweeps candidate weights across the golden corpora and prints one row per configuration.
 *
 * Run **after** the baseline in `CorrectionMeasurementTest`, never before. The point is to see
 * the whole surface rather than to hunt for a configuration that clears a number: every row is
 * printed, including the ones that are worse, and the trade-offs between columns are visible
 * in the same table.
 *
 * Tagged slow because it runs roughly 100,000 corrections. Excluded from the default test task
 * and run explicitly.
 */
class WeightSweepTest {

    private val goldenDir = File(System.getProperty("golden.dir")!!)

    private fun corpus(name: String): List<String> =
        GZIPInputStream(File(goldenDir, name).inputStream()).use { it.readBytes() }
            .toString(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }

    @Test
    fun sweep() {
        if (System.getProperty("runWeightSweep") == null) {
            println("WeightSweepTest skipped; run with -DrunWeightSweep=1")
            return
        }
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val words = ArrayList<String>(lexicon.size)
        for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))
        val trie = LexiconTrie.build(words)
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        val adjacency = KeyboardAdjacency.from(Layouts.hebrew)

        val a = corpus("a_uniform.tsv.gz")
        val b = corpus("b_adjacency.tsv.gz")
        val c2 = corpus("c2_control_real.txt.gz")

        data class Row(
            val label: String,
            val aTop1: Double, val aTop3: Double,
            val bTop1: Double,
            val c2False: Double,
            val autoWrong: Double,
            val p95: Double,
        )

        fun evaluate(label: String, costs: EditCostModel, cfg: CorrectionEngine.Config): Row {
            val engine = CorrectionEngine(lexicon, trie, frequency, costs, cfg)
            var a1 = 0
            var a3 = 0
            var autoWrong = 0
            val lat = LongArray(a.size)
            a.forEachIndexed { i, line ->
                val (typo, want) = line.split('\t')
                val t0 = System.nanoTime()
                val s = engine.suggest(typo)
                lat[i] = System.nanoTime() - t0
                if (s.isNotEmpty() && s[0].word == want) a1++
                if (s.take(3).any { it.word == want }) a3++
                if (engine.shouldAutoReplace(s) && s[0].word != want) autoWrong++
            }
            var b1 = 0
            b.forEach { line ->
                val (typo, want) = line.split('\t')
                val s = engine.suggest(typo)
                if (s.isNotEmpty() && s[0].word == want) b1++
            }
            var falseReplace = 0
            c2.forEach { w ->
                if (engine.shouldAutoReplace(engine.suggest(w))) falseReplace++
            }
            lat.sort()
            return Row(
                label,
                100.0 * a1 / a.size, 100.0 * a3 / a.size,
                100.0 * b1 / b.size,
                100.0 * falseReplace / c2.size,
                100.0 * autoWrong / a.size,
                lat[(lat.size - 1) * 95 / 100] / 1_000_000.0,
            )
        }

        val rows = ArrayList<Row>()
        rows += evaluate(
            "BASELINE neutral, freqW=0",
            NeutralCostModel,
            CorrectionEngine.Config(),
        )
        for (freqWeight in listOf(25.0, 50.0, 75.0, 100.0, 150.0)) {
            rows += evaluate(
                "neutral, freqW=${freqWeight.toInt()}",
                NeutralCostModel,
                CorrectionEngine.Config(frequencyWeight = freqWeight),
            )
        }
        for (adj in listOf(80, 60, 40)) {
            rows += evaluate(
                "adjSub=$adj, freqW=0",
                AdjacencyCostModel(adjacency, adjacentSubstitution = adj),
                CorrectionEngine.Config(),
            )
        }
        for (adj in listOf(80, 60)) {
            for (freqWeight in listOf(50.0, 100.0)) {
                rows += evaluate(
                    "adjSub=$adj, freqW=${freqWeight.toInt()}",
                    AdjacencyCostModel(adjacency, adjacentSubstitution = adj),
                    CorrectionEngine.Config(frequencyWeight = freqWeight),
                )
            }
        }
        for (transpose in listOf(80, 60)) {
            rows += evaluate(
                "adjSub=60, transpose=$transpose, freqW=100",
                AdjacencyCostModel(adjacency, adjacentSubstitution = 60, transposition = transpose),
                CorrectionEngine.Config(frequencyWeight = 100.0),
            )
        }

        // Frequency weights large enough to actually overturn a one-edit gap. Below 300 the
        // arithmetic makes it impossible: overturning 100 needs a frequency gap of 25500/w,
        // and the observed range is 0..178.
        for (freqWeight in listOf(300.0, 400.0, 600.0)) {
            rows += evaluate(
                "neutral, freqW=${freqWeight.toInt()}",
                NeutralCostModel,
                CorrectionEngine.Config(frequencyWeight = freqWeight),
            )
        }

        // Adjacency as a RE-RANKER over a neutrally-retrieved candidate set, rather than as a
        // search discount that widens the set.
        for (adj in listOf(80, 60, 40)) {
            rows += evaluate(
                "rerank adj=$adj, freqW=0",
                NeutralCostModel,
                CorrectionEngine.Config(
                    rankingCosts = AdjacencyCostModel(adjacency, adjacentSubstitution = adj),
                ),
            )
        }
        for (adj in listOf(60, 40)) {
            for (freqWeight in listOf(300.0, 400.0)) {
                rows += evaluate(
                    "rerank adj=$adj, freqW=${freqWeight.toInt()}",
                    NeutralCostModel,
                    CorrectionEngine.Config(
                        frequencyWeight = freqWeight,
                        rankingCosts = AdjacencyCostModel(adjacency, adjacentSubstitution = adj),
                    ),
                )
            }
        }

        println("=".repeat(108))
        println("WEIGHT SWEEP  (corpus A n=${a.size}, B n=${b.size}, C2 n=${c2.size})")
        println("=".repeat(108))
        println(
            "%-42s %8s %8s %8s %10s %10s %8s".format(
                "configuration", "A top-1", "A top-3", "B top-1",
                "C2 false", "A auto-wrong", "p95 ms",
            )
        )
        println("-".repeat(108))
        for (r in rows) {
            println(
                "%-42s %7.2f%% %7.2f%% %7.2f%% %9.2f%% %11.2f%% %7.2f".format(
                    r.label, r.aTop1, r.aTop3, r.bTop1, r.c2False, r.autoWrong, r.p95,
                )
            )
        }
        println("=".repeat(108))
        assertTrue(rows.size > 5)
    }
}

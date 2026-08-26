package com.hebrewime.core.prediction

import com.hebrewime.core.confusion.RealWordErrorDetector
import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.random.Random
import kotlin.test.Test

/**
 * W1 - the first evaluation slice in this repository that a person typed.
 *
 * The two arms, the pairing rule, the four predictions, the bootstrap rule and the kill
 * condition were committed before this file existed. See `docs/TYPED_REGISTER.md`.
 *
 * ### The engine is the shipped one, built the way `OfferPolicyExperimentTest` builds it
 * Same lexicon, trie, frequency, bigram table and `CorrectionEngine.Config()`. If this fixture
 * drifts from that one, `PC-1` catches it, which is why `PC-1` runs first and gates the rest.
 *
 * ### Why two collectors
 * [collectExact] reproduces `OfferPolicyExperimentTest.collect` byte for byte, truncation
 * included - mid-sentence at 20,000 positions. It exists only to satisfy `PC-1`, because a
 * number compared against a published one must come from the same procedure.
 *
 * [collectBySentence] stops *before* a sentence that would breach the cap, so every sentence it
 * used is whole. The bootstrap resamples sentences, and a half-counted sentence would be a unit
 * that does not exist in the population being resampled. Both numbers are printed for the same
 * slice so the size of that difference is visible rather than assumed negligible.
 */
class TypedRegisterTest {

    private val evalDir = File(System.getProperty("eval.dir")!!)

    private companion object {
        const val CELL_LIMIT = 20_000
        const val BOOTSTRAP = 1_000
        const val SEED = 20260825L

        /** The published cells `PC-1` must land on, to within 0.1 points. */
        const val PUBLISHED_WIKI_PREFIX1_TOP3 = 5.35
        const val PUBLISHED_CONV_PREFIX1_TOP3 = 23.72
        const val PC1_TOLERANCE = 0.1
    }

    private class Fixture(
        val engine: PredictiveEngine,
        val lexicon: HebrewLexicon,
        val detector: RealWordErrorDetector,
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
        return Fixture(
            PredictiveEngine(
                lexicon, trie, frequency, bigrams,
                CorrectionEngine(lexicon, trie, frequency, NeutralCostModel,
                    CorrectionEngine.Config()),
            ),
            lexicon,
            RealWordErrorDetector(lexicon, bigrams),
        )
    }

    private fun read(name: String): List<List<String>>? {
        val f = File(evalDir, name)
        if (!f.isFile) return null
        return GZIPInputStream(f.inputStream()).use { it.readBytes() }
            .toString(Charsets.UTF_8).split('\n')
            .filter { it.isNotBlank() }
            .map { it.split(' ').filter { t -> t.isNotEmpty() } }
    }

    private fun isHebrewToken(t: String) = t.isNotEmpty() && t.all { it in 'א'..'ת' }

    /** Byte-for-byte `OfferPolicyExperimentTest.collect`, truncation included. PC-1 only. */
    private fun collectExact(f: Fixture, sample: List<List<String>>, prefixLength: Int): Pair<Int, Int> {
        var attempts = 0
        var hits3 = 0
        for (s in sample) {
            for (i in 1 until s.size) {
                if (attempts >= CELL_LIMIT) return hits3 to attempts
                val target = s[i]
                if (target.length < 3) continue
                if (prefixLength > 0 && target.length <= prefixLength) continue
                val prefix = if (prefixLength == 0) "" else target.substring(0, prefixLength)
                val p = f.engine.predict(prefix, s[i - 1])
                attempts++
                if (p.any { it.word == target }) hits3++
            }
        }
        return hits3 to attempts
    }

    /** Per-sentence tallies. `hebrewOnly` gives the RAW arm's fair denominator. */
    private class Row(val hits3: Int, val hits1: Int, val attempts: Int,
                      val oov: Int, val flags: Int, val flagAttempts: Int)

    private fun collectBySentence(
        f: Fixture,
        sample: List<List<String>>,
        prefixLength: Int,
        hebrewTargetsOnly: Boolean,
    ): List<Row> {
        val rows = ArrayList<Row>()
        var total = 0
        for (s in sample) {
            if (total >= CELL_LIMIT) break
            var h3 = 0; var h1 = 0; var n = 0; var oov = 0; var flags = 0; var fn = 0
            for (i in 1 until s.size) {
                val target = s[i]
                val previous = s[i - 1]
                if (target.length >= 3 && (prefixLength == 0 || target.length > prefixLength) &&
                    (!hebrewTargetsOnly || isHebrewToken(target))
                ) {
                    val prefix = if (prefixLength == 0) "" else target.substring(0, prefixLength)
                    val p = f.engine.predict(prefix, previous)
                    n++
                    if (p.any { it.word == target }) h3++
                    if (p.firstOrNull()?.word == target) h1++
                    if (f.lexicon.indexOf(target) < 0) oov++
                }
                // The detector's own denominator: it only ever speaks about Hebrew words.
                if (isHebrewToken(target)) {
                    fn++
                    if (f.detector.check(previous, target, s.getOrNull(i + 1)) != null) flags++
                }
            }
            if (n > 0 || fn > 0) {
                rows.add(Row(h3, h1, n, oov, flags, fn))
                total += n
            }
        }
        return rows
    }

    private fun rate(rows: List<Row>, num: (Row) -> Int, den: (Row) -> Int): Double {
        val d = rows.sumOf { den(it) }
        return if (d == 0) Double.NaN else 100.0 * rows.sumOf { num(it) } / d
    }

    /** 95% percentile bootstrap over SENTENCES, the independent unit. Seeded. */
    private fun ci(rows: List<Row>, num: (Row) -> Int, den: (Row) -> Int): Pair<Double, Double> {
        if (rows.isEmpty()) return Double.NaN to Double.NaN
        val rng = Random(SEED)
        val draws = DoubleArray(BOOTSTRAP)
        for (b in 0 until BOOTSTRAP) {
            var nu = 0L; var de = 0L
            repeat(rows.size) {
                val r = rows[rng.nextInt(rows.size)]
                nu += num(r); de += den(r)
            }
            draws[b] = if (de == 0L) Double.NaN else 100.0 * nu / de
        }
        draws.sort()
        return draws[(BOOTSTRAP * 0.025).toInt()] to draws[(BOOTSTRAP * 0.975).toInt()]
    }

    private fun fmt(v: Double) = if (v.isNaN()) "n/a" else "%.2f%%".format(v)

    @Test
    fun typedRegister() {
        if (System.getProperty("runTypedRegister").isNullOrEmpty()) {
            println("skipped; -PrunTypedRegister=1")
            return
        }
        val f = fixture()

        val wiki = read("hewiki_eval_sample.txt.gz")
        val conv = read("he_conversational_test.txt.gz")
        val filt = read("he_typed_filtered.txt.gz")
        val raw = read("he_typed_raw.txt.gz")
        if (wiki == null || conv == null) {
            println("NOT-MEASURED: an existing eval slice is absent"); return
        }
        if (filt == null || raw == null) {
            println("NOT-MEASURED: the typed slice is absent; run scripts/build_typed_corpus.py")
            return
        }
        require(filt.size == raw.size) {
            "the arms are not paired: filtered ${filt.size} lines, raw ${raw.size}"
        }

        println("W1 - the first evaluation slice a person typed. See docs/TYPED_REGISTER.md.")
        println("typed slice: ${raw.size} comments, arms paired line-for-line.")
        println()

        // ---------------- PC-1: reproduce the numbers we are comparing against
        println("PC-1 - the harness must reproduce the published cells to within $PC1_TOLERANCE points")
        val convExact = collectExact(f, conv, 1)
        val convPct = 100.0 * convExact.first / convExact.second
        println("  conversational prefix-1 top-3  ${fmt(convPct)} (n=${convExact.second})" +
            "   published $PUBLISHED_CONV_PREFIX1_TOP3%")
        val wikiCells = LinkedHashMap<String, Double>()
        wikiCells["whole"] = collectExact(f, wiki, 1).let { 100.0 * it.first / it.second }
        wikiCells["dev (even)"] = collectExact(f, wiki.filterIndexed { i, _ -> i % 2 == 0 }, 1)
            .let { 100.0 * it.first / it.second }
        wikiCells["test (odd)"] = collectExact(f, wiki.filterIndexed { i, _ -> i % 2 == 1 }, 1)
            .let { 100.0 * it.first / it.second }
        for ((k, v) in wikiCells) println("  wiki prefix-1 top-3, $k: ${fmt(v)}")
        val wikiMatches = wikiCells.entries.filter {
            kotlin.math.abs(it.value - PUBLISHED_WIKI_PREFIX1_TOP3) <= PC1_TOLERANCE
        }
        val wikiExact = wikiCells.entries.filter {
            kotlin.math.abs(it.value - PUBLISHED_WIKI_PREFIX1_TOP3) < 0.005
        }
        val wikiMatch = wikiMatches.firstOrNull()
        val convOk = kotlin.math.abs(convPct - PUBLISHED_CONV_PREFIX1_TOP3) <= PC1_TOLERANCE
        // Report every cell inside the tolerance, not the first. More than one match is a fact
        // about the published number and reporting only the first would hide it.
        println("  wiki $PUBLISHED_WIKI_PREFIX1_TOP3% is within $PC1_TOLERANCE of: " +
            (if (wikiMatches.isEmpty()) "NO cell measured here"
             else wikiMatches.joinToString(", ") { "'${it.key}'" }) +
            (if (wikiExact.size == 1) "  -- and equals '${wikiExact[0].key}' exactly" else ""))
        val pc1 = convOk && wikiMatch != null
        println("  PC-1: ${if (pc1) "PASS" else "FAIL - this is not the harness that produced them; " +
            "nothing below is comparable and the run is NOT-MEASURED"}")
        println()
        if (!pc1) return

        // ---------------- PC-2: the context must be doing work
        val shuffled = conv.map { s -> s.map { f.lexicon.wordAt(Random(SEED).nextInt(f.lexicon.size)) } }
        val realNext = collectExact(f, conv, 0).let { 100.0 * it.first / it.second }
        val randNext = collectExact(f, conv.mapIndexed { i, s ->
            s.mapIndexed { j, t -> if (j % 2 == 0) t else shuffled[i][j] }
        }, 0).let { 100.0 * it.first / it.second }
        println("PC-2 - next-word top-3 on conversational: real context ${fmt(realNext)}, " +
            "every other word replaced by a random lexicon word ${fmt(randNext)}")
        val pc2 = realNext > randNext * 1.5
        println("  PC-2: ${if (pc2) "PASS - context carries the metric"
                           else "FAIL - a random context scores like the real one; NOT-A-GATE"}")
        println()

        // ---------------- the rows
        data class Cell(val label: String, val rows: List<Row>)
        val cells = listOf(
            Cell("wiki (encyclopedic)", collectBySentence(f, wiki, 1, false)),
            Cell("subtitles (transcribed)", collectBySentence(f, conv, 1, false)),
            Cell("typed FILTERED", collectBySentence(f, filt, 1, false)),
            Cell("typed RAW, he targets", collectBySentence(f, raw, 1, true)),
            Cell("typed RAW, all targets", collectBySentence(f, raw, 1, false)),
        )

        println("=".repeat(112))
        println("W1 - prefix-1 completion top-3, target OOV, and the detector's trigger rate on CORRECT text")
        println("95% percentile bootstrap over SENTENCES (n=$BOOTSTRAP, seed $SEED), the independent unit")
        println("=".repeat(112))
        println("%-26s %9s %22s %9s %9s %10s".format(
            "slice", "top-3", "95% CI", "top-1", "OOV", "flag rate"))
        for (c in cells) {
            val t3 = rate(c.rows, { it.hits3 }, { it.attempts })
            val (lo, hi) = ci(c.rows, { it.hits3 }, { it.attempts })
            println("%-26s %9s %22s %9s %9s %10s".format(
                c.label, fmt(t3), "[${fmt(lo)}, ${fmt(hi)}]",
                fmt(rate(c.rows, { it.hits1 }, { it.attempts })),
                fmt(rate(c.rows, { it.oov }, { it.attempts })),
                fmt(rate(c.rows, { it.flags }, { it.flagAttempts }))))
        }
        println("%-26s %9s".format("", "") + "  sentences/positions: " +
            cells.joinToString("  ") { "${it.label.take(12)}=${it.rows.size}/${it.rows.sumOf { r -> r.attempts }}" })
        println()

        // ---------------- against the four predictions
        fun cell(n: String) = cells.first { it.label == n }
        val wikiT3 = rate(cell("wiki (encyclopedic)").rows, { it.hits3 }, { it.attempts })
        val convT3 = rate(cell("subtitles (transcribed)").rows, { it.hits3 }, { it.attempts })
        val filtT3 = rate(cell("typed FILTERED").rows, { it.hits3 }, { it.attempts })
        val rawT3 = rate(cell("typed RAW, he targets").rows, { it.hits3 }, { it.attempts })
        val convOov = rate(cell("subtitles (transcribed)").rows, { it.oov }, { it.attempts })
        val filtOov = rate(cell("typed FILTERED").rows, { it.oov }, { it.attempts })
        val rawOov = rate(cell("typed RAW, he targets").rows, { it.oov }, { it.attempts })
        val convFlag = rate(cell("subtitles (transcribed)").rows, { it.flags }, { it.flagAttempts })
        val filtFlag = rate(cell("typed FILTERED").rows, { it.flags }, { it.flagAttempts })

        println("=".repeat(112))
        println("AGAINST THE FOUR PREDICTIONS (docs/TYPED_REGISTER.md), every bar re-measured in this run")
        println("=".repeat(112))
        val p1 = filtT3 > minOf(wikiT3, convT3) && filtT3 < maxOf(wikiT3, convT3)
        println("  W1-P1  FILTERED strictly between wiki and subtitles" +
            "%42s".format("${fmt(wikiT3)} .. ${fmt(filtT3)} .. ${fmt(convT3)}") +
            "  ${if (p1) "HELD" else "FALSIFIED"}")
        val p2 = rawT3 <= filtT3 - 3.0
        val delta = "%+.2f".format(rawT3 - filtT3)
        println("  W1-P2  RAW at least 3 points below FILTERED" +
            "%42s".format("${fmt(rawT3)} vs ${fmt(filtT3)}, delta $delta") +
            "  ${if (p2) "HELD" else "FALSIFIED"}")
        val p3 = rawOov >= 2 * convOov && filtOov > convOov
        println("  W1-P3  OOV: RAW >= 2x subtitles, FILTERED > subtitles" +
            "%42s".format("${fmt(convOov)} -> ${fmt(filtOov)} / ${fmt(rawOov)}") +
            "  ${if (p3) "HELD" else "FALSIFIED"}")
        val p4 = filtFlag > convFlag
        println("  W1-P4  detector flag rate higher on typed" +
            "%42s".format("${fmt(convFlag)} -> ${fmt(filtFlag)}") +
            "  ${if (p4) "HELD" else "FALSIFIED"}")
        println()

        // ---------------- the kill condition
        val convCi = ci(cell("subtitles (transcribed)").rows, { it.hits3 }, { it.attempts })
        val inside = filtT3 >= convCi.first && filtT3 <= convCi.second
        println("  KILL CONDITION: FILTERED top-3 ${fmt(filtT3)} vs subtitles 95% CI " +
            "[${fmt(convCi.first)}, ${fmt(convCi.second)}] -> " +
            if (inside) "INSIDE. The subtitle blend already handled register; W1 buys nothing beyond A1."
            else "OUTSIDE. Typed text is a distinct register from transcribed.")
        println("=".repeat(112))
    }
}

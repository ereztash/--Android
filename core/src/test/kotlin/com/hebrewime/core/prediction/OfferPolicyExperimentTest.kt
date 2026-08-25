package com.hebrewime.core.prediction

import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * O1 — should the strip speak at all?
 *
 * Every other prediction measurement in this repository is about **ranking**: given that the
 * strip speaks, is the right word in it. This is the other half. The shipped policy speaks
 * whenever the pruned table has any continuation for the previous word — 86.64% of positions —
 * and is right, top-3, in 10.49% of those.
 *
 * The prediction and the stopping rule were committed before this file existed; see
 * `docs/PREDICTION_MEASUREMENTS.md` **O1**. Three signals were fixed there and no fourth may be
 * tried after seeing a result.
 *
 * ### The slices
 * The committed eval slice is split by sentence index: **even → dev**, the only half a
 * threshold may be chosen on, **odd → test**, reported against. They are disjoint by
 * construction and [assertDisjoint] asserts it rather than inferring it from the rule that
 * produced them. `he_conversational_test.txt.gz` is a register check and selects nothing.
 *
 * ### What this measures and what it does not
 * It measures what the shipped engine returns, at the shipped configuration, on held-out
 * Wikipedia prose and on held-out transcribed dialogue. Neither is phone typing. A threshold
 * chosen here would be a threshold chosen on the wrong register, which is why the rule requires
 * a doubling rather than an improvement.
 */
class OfferPolicyExperimentTest {

    private val evalDir = File(System.getProperty("eval.dir")!!)

    private companion object {
        /** The same committed slice every other prediction number in the document came from. */
        const val SAMPLE_SHA256 =
            "cedfb5be743bc15c2b3db381011e2c74f31f512e9373ed4038f198dcb4b3d299"

        /** Cap per slice, stated rather than hidden, as in `PredictionAccuracyTest`. */
        const val CELL_LIMIT = 20_000

        /** The pre-registered bars. Changing one of these is changing the experiment. */
        const val PRECISION_BAR = 20.0
        const val RETENTION_BAR = 70.0
        const val REPLICATION_SLACK = 3.0

        /** Offer rates the sweep reports at, so the table is readable rather than exhaustive. */
        val OFFER_RATE_GRID = listOf(90, 80, 70, 60, 50, 40, 30, 20, 10, 5)

        /** Margin thresholds for the conjunction. Fixed in the pre-registration. */
        val MARGIN_GRID = listOf(0, 4, 8, 16, 24, 32)
    }

    /**
     * One evaluated position.
     *
     * @param s1 the top prediction's score, or -1 when the engine offered nothing. -1 fails
     *   every threshold, which is the correct treatment: a position the strip is already silent
     *   at cannot be made silent again, and must not be counted as a saving.
     */
    private class Position(val s1: Double, val margin: Double, val hit3: Boolean, val hit1: Boolean)

    private class Fixture(val engine: PredictiveEngine)

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
        // The SHIPPED configuration, exactly as PredictionAccuracyTest builds it.
        return Fixture(
            PredictiveEngine(
                lexicon, trie, frequency, bigrams,
                CorrectionEngine(lexicon, trie, frequency, NeutralCostModel,
                    CorrectionEngine.Config()),
            )
        )
    }

    private fun read(name: String, expectSha: String?): List<List<String>> {
        val raw = GZIPInputStream(File(evalDir, name).inputStream()).use { it.readBytes() }
        if (expectSha != null) {
            val hash = MessageDigest.getInstance("SHA-256").digest(raw)
                .joinToString("") { "%02x".format(it) }
            assertEquals(expectSha, hash, "$name is not the slice these numbers came from")
        }
        return raw.toString(Charsets.UTF_8).split('\n')
            .filter { it.isNotBlank() }
            .map { it.split(' ') }
    }

    /**
     * The split, and the proof of it.
     *
     * Even and odd indices cannot overlap, which is exactly why it is asserted: a rule that
     * cannot fail is not evidence, and the assertion is what turns "they are disjoint by
     * construction" from a sentence into a check that would catch a future edit to the rule.
     */
    private fun assertDisjoint(all: List<List<String>>): Pair<List<List<String>>, List<List<String>>> {
        val devIdx = all.indices.filter { it % 2 == 0 }.toSet()
        val testIdx = all.indices.filter { it % 2 == 1 }.toSet()
        assertTrue(devIdx.intersect(testIdx).isEmpty(), "dev and test share a sentence index")
        assertEquals(all.size, devIdx.size + testIdx.size, "the split lost or duplicated a sentence")
        return devIdx.map { all[it] } to testIdx.map { all[it] }
    }

    /** Runs the shipped engine over one slice and records the signals for each position. */
    private fun collect(
        f: Fixture,
        sample: List<List<String>>,
        prefixLength: Int,
    ): List<Position> {
        val out = ArrayList<Position>()
        for (s in sample) {
            for (i in 1 until s.size) {
                if (out.size >= CELL_LIMIT) return out
                val previous = s[i - 1]
                val target = s[i]
                if (target.length < 3) continue
                if (prefixLength > 0 && target.length <= prefixLength) continue
                val prefix = if (prefixLength == 0) "" else target.substring(0, prefixLength)
                val p = f.engine.predict(prefix, previous)
                val s1 = p.getOrNull(0)?.score ?: -1.0
                val s2 = p.getOrNull(1)?.score ?: 0.0
                out.add(
                    Position(
                        s1 = s1,
                        margin = if (s1 < 0) -1.0 else s1 - s2,
                        hit3 = p.any { it.word == target },
                        hit1 = p.firstOrNull()?.word == target,
                    )
                )
            }
        }
        return out
    }

    /** What one threshold configuration is worth on one slice. */
    private class Outcome(
        val label: String,
        val offered: Int,
        val attempts: Int,
        val hits3: Int,
        val hits1: Int,
        val baselineHits3: Int,
    ) {
        val offerRate get() = 100.0 * offered / attempts
        /** The felt quantity: when it speaks, how often is it worth reading. */
        val precision3 get() = if (offered == 0) 0.0 else 100.0 * hits3 / offered
        val precision1 get() = if (offered == 0) 0.0 else 100.0 * hits1 / offered
        /** The cost: how many of the words a user would have tapped survive the policy. */
        val retention get() = if (baselineHits3 == 0) 0.0 else 100.0 * hits3 / baselineHits3
        /** Coverage over all positions -- the number the rest of the document reports. */
        val coverage3 get() = 100.0 * hits3 / attempts
    }

    private fun evaluate(
        label: String,
        rows: List<Position>,
        baselineHits3: Int,
        accept: (Position) -> Boolean,
    ): Outcome {
        var offered = 0
        var hits3 = 0
        var hits1 = 0
        for (r in rows) {
            if (r.s1 < 0 || !accept(r)) continue
            offered++
            if (r.hit3) hits3++
            if (r.hit1) hits1++
        }
        return Outcome(label, offered, rows.size, hits3, hits1, baselineHits3)
    }

    /** The `s1` threshold that leaves approximately [targetOfferRate] percent of positions offered. */
    private fun thresholdForOfferRate(rows: List<Position>, targetOfferRate: Int): Double {
        val scores = rows.filter { it.s1 >= 0 }.map { it.s1 }.sorted()
        if (scores.isEmpty()) return Double.MAX_VALUE
        val keep = (rows.size * targetOfferRate / 100.0).toInt()
        if (keep >= scores.size) return 0.0
        return scores[scores.size - keep - 1] + 0.0001
    }

    private fun header(title: String) {
        println()
        println("=".repeat(100))
        println(title)
        println("=".repeat(100))
    }

    private fun printRow(o: Outcome) {
        println(
            "%-28s offered %6.2f%%  top-3 among offered %6.2f%%  top-1 %5.2f%%  retention %6.2f%%  coverage %5.2f%%"
                .format(o.label, o.offerRate, o.precision3, o.precision1, o.retention, o.coverage3)
        )
    }

    @Test
    fun offerPolicySweep() {
        if (System.getProperty("runOfferSweep").isNullOrEmpty()) return
        val f = fixture()

        val all = read("hewiki_eval_sample.txt.gz", SAMPLE_SHA256)
        val (devSentences, testSentences) = assertDisjoint(all)
        val conversational = read("he_conversational_test.txt.gz", null)

        for (prefixLength in listOf(0, 1)) {
            val cell = if (prefixLength == 0) "NEXT-WORD (under the O1 rule)"
            else "PREFIX-1 COMPLETION (reported, explicitly NOT under the rule)"
            header("O1 — $cell")

            val dev = collect(f, devSentences, prefixLength)
            val test = collect(f, testSentences, prefixLength)
            val conv = collect(f, conversational, prefixLength)
            println("denominators: dev n=${dev.size}  test n=${test.size}  conversational n=${conv.size}")

            val devBase = dev.count { it.hit3 }
            val testBase = test.count { it.hit3 }
            val convBase = conv.count { it.hit3 }

            println()
            println("-- the shipped policy, for reference")
            printRow(evaluate("dev  shipped", dev, devBase) { true })
            printRow(evaluate("test shipped", test, testBase) { true })
            printRow(evaluate("conv shipped", conv, convBase) { true })

            println()
            println("-- signal 1: s1 (the top prediction's evidence), swept on DEV")
            val s1Outcomes = ArrayList<Pair<Double, Outcome>>()
            for (target in OFFER_RATE_GRID) {
                val tau = thresholdForOfferRate(dev, target)
                val o = evaluate("dev  s1 >= %.2f".format(tau), dev, devBase) { it.s1 >= tau }
                s1Outcomes.add(tau to o)
                printRow(o)
            }

            println()
            println("-- signal 2: margin (s1 - s2), swept on DEV")
            for (target in OFFER_RATE_GRID) {
                val scores = dev.filter { it.s1 >= 0 }.map { it.margin }.sorted()
                val keep = (dev.size * target / 100.0).toInt()
                val tau = if (keep >= scores.size) 0.0 else scores[scores.size - keep - 1] + 0.0001
                printRow(evaluate("dev  margin >= %.2f".format(tau), dev, devBase) { it.margin >= tau })
            }

            println()
            println("-- signal 3: the conjunction s1 >= a AND margin >= b, swept on DEV")
            var best: Triple<Double, Int, Outcome>? = null
            for (target in OFFER_RATE_GRID) {
                val a = thresholdForOfferRate(dev, target)
                for (b in MARGIN_GRID) {
                    val o = evaluate("dev  s1>=%.2f margin>=%d".format(a, b), dev, devBase) {
                        it.s1 >= a && it.margin >= b
                    }
                    printRow(o)
                    if (o.precision3 >= PRECISION_BAR &&
                        (best == null || o.retention > best!!.third.retention)
                    ) {
                        best = Triple(a, b, o)
                    }
                }
            }

            println()
            println("-- the pre-registered rule")
            // The best DEV configuration reaching the precision bar, by retention. s1 alone and
            // the conjunction compete on equal terms; whichever retains more wins the slot.
            val bestS1 = s1Outcomes.filter { it.second.precision3 >= PRECISION_BAR }
                .maxByOrNull { it.second.retention }
            val candidates = ArrayList<Triple<String, Outcome, (Position) -> Boolean>>()
            bestS1?.let { (tau, o) ->
                candidates.add(Triple("s1 >= %.2f".format(tau), o, { p: Position -> p.s1 >= tau }))
            }
            best?.let { (a, b, o) ->
                candidates.add(
                    Triple("s1 >= %.2f AND margin >= %d".format(a, b), o,
                        { p: Position -> p.s1 >= a && p.margin >= b })
                )
            }
            val winner = candidates.maxByOrNull { it.second.retention }

            if (winner == null) {
                println(
                    "NO configuration reaches precision-among-offered >= %.1f%% on dev at any "
                        .format(PRECISION_BAR) +
                        "offer rate in the grid. The rule is NOT met and nothing is adopted."
                )
            } else {
                val (label, devOutcome, accept) = winner
                println("best dev configuration reaching the precision bar: $label")
                printRow(devOutcome)
                val testOutcome = evaluate("test $label", test, testBase, accept)
                val convOutcome = evaluate("conv $label", conv, convBase, accept)
                printRow(testOutcome)
                printRow(convOutcome)
                val retentionOk = devOutcome.retention >= RETENTION_BAR &&
                    testOutcome.retention >= RETENTION_BAR
                val replicates =
                    kotlin.math.abs(testOutcome.precision3 - devOutcome.precision3) <= REPLICATION_SLACK
                println(
                    "rule: dev precision %.2f%% >= %.1f%% ✓ | dev retention %.2f%% >= %.1f%% %s | test replicates within %.1f points %s"
                        .format(
                            devOutcome.precision3, PRECISION_BAR,
                            devOutcome.retention, RETENTION_BAR, if (retentionOk) "✓" else "✗",
                            REPLICATION_SLACK, if (replicates) "✓" else "✗",
                        )
                )
                println(
                    if (retentionOk && replicates) "RULE MET on this cell."
                    else "RULE NOT MET on this cell. Nothing is adopted."
                )
            }
        }
    }
}

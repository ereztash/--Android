package com.hebrewime.core.scratch

import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import com.hebrewime.core.prediction.PredictiveEngine
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * K1 — can Kotlin do the arithmetic inside the budget?
 *
 * The prediction, the five shapes, the two modes, the positive control and the four bars were
 * committed before this file existed. See `docs/INFERENCE_FEASIBILITY.md`.
 *
 * ### What this measures
 * Arithmetic. **Not a model.** Weights are random and nothing is trained, so no accuracy claim
 * of any kind is derivable from anything printed here.
 *
 * ### It is one-sided on purpose
 * This is a 4-core x86 build host with a warm JIT, not a phone. A shape that does not fit here
 * will not fit on a phone; a shape that fits here has proven nothing about one. **A result can
 * kill the branch and cannot bless it.** Passing moves the question to `M7-LAT`.
 *
 * ### Genuine int8, because a float probe would measure the wrong thing
 * Weights are `ByteArray`, the hidden state is `ByteArray`, accumulation is `Int`, and each
 * layer requantises by an arithmetic shift. That is what would actually ship. A float
 * implementation would be both larger in memory and unrepresentative of the instruction mix.
 *
 * The nonlinearity is a clamp rather than tanh. That changes accuracy, which this probe does
 * not measure, and not the instruction count, which it does.
 *
 * ### Weight tying, and a correction to the pre-registration
 * The output projection reuses the input embedding table — `hidden` is projected back to `emb`
 * and dotted against the embeddings. Weight tying is **what makes the registered byte budgets
 * reachable at all**: an untied `vocab × hidden` output matrix would be four times the size of
 * the embedding table at these dimensions and no shape would fit.
 *
 * The pre-registration described shape E's `full-softmax` as "355,587 × 128 ≈ 45 million MACs".
 * That assumed an untied output matrix. Tied, at `emb = 32`, it is **355,587 × 32 ≈ 11.4
 * million**. The control's requirement is unchanged — E must still breach the bar — but the
 * figure quoted in the rule was four times too large and is corrected here rather than left to
 * be discovered.
 */
class InferenceFeasibilityProbe {

    private class Shape(
        val id: String,
        val vocab: Int,
        val emb: Int,
        val hidden: Int,
        val layers: Int,
    ) {
        /** int8, so one parameter is one byte. Tied output; see the class comment. */
        val bytes: Int
            get() {
                var n = vocab * emb                       // embedding table, also the output
                n += emb * hidden                          // hidden -> emb, for scoring
                repeat(layers) {
                    n += hidden * emb                      // input projection
                    n += hidden * hidden                   // recurrent projection
                    n += hidden                            // bias
                }
                return n
            }
    }

    /** One shape's weights, as they would be held on the device. */
    private class Weights(val s: Shape, seed: Long) {
        val embed = ByteArray(s.vocab * s.emb)
        val proj = ByteArray(s.emb * s.hidden)
        val wIh = Array(s.layers) { ByteArray(s.hidden * s.emb) }
        val wHh = Array(s.layers) { ByteArray(s.hidden * s.hidden) }
        val bias = Array(s.layers) { ByteArray(s.hidden) }

        init {
            val r = java.util.Random(seed)
            for (a in listOf(embed, proj)) r.nextBytes(a)
            for (i in 0 until s.layers) {
                r.nextBytes(wIh[i]); r.nextBytes(wHh[i]); r.nextBytes(bias[i])
            }
        }

        val serializedBytes: Int
            get() = embed.size + proj.size +
                wIh.sumOf { it.size } + wHh.sumOf { it.size } + bias.sumOf { it.size }
    }

    private companion object {
        val SHAPES = listOf(
            Shape("A", 8_192, 32, 128, 1),
            Shape("B", 16_384, 32, 128, 1),
            Shape("C", 32_768, 48, 192, 1),
            Shape("D", 65_536, 64, 256, 2),
            Shape("E", 355_587, 32, 128, 1),   // the positive control
        )

        /** Free asset bytes today, and what a model replacing the bigram table would have. */
        const val ASSETS_FREE_NOW = 576_837
        const val ASSETS_IF_TABLE_GOES = 2_426_473

        const val K_CANDIDATES = 8
        const val WARMUP_STEPS = 500
        const val TIMED_STEPS_FAST = 2_000
        const val TIMED_STEPS_SLOW = 300      // full-softmax at the larger shapes
        /** int8 requantisation: accumulate in Int, shift back into int8 range. */
        const val REQUANT_SHIFT = 7
    }

    // --- the arithmetic ---------------------------------------------------------------------

    /** rows x cols int8 matrix times an int8 vector, accumulating in Int. */
    private fun matvec(w: ByteArray, x: ByteArray, rows: Int, cols: Int, out: IntArray) {
        var o = 0
        for (r in 0 until rows) {
            var acc = 0
            val base = r * cols
            for (c in 0 until cols) acc += w[base + c] * x[c]
            out[o++] = acc
        }
    }

    private fun requantiseInto(acc: IntArray, out: ByteArray, n: Int) {
        for (i in 0 until n) {
            var v = acc[i] shr REQUANT_SHIFT
            if (v > 127) v = 127
            if (v < 0) v = 0          // clamp, not tanh: see the class comment
            out[i] = v.toByte()
        }
    }

    /** One keystroke's recurrent step. Returns the hidden state, projected into embedding space. */
    private fun step(
        w: Weights, token: Int, h: Array<ByteArray>, scratch: IntArray,
        embOut: ByteArray, projScratch: IntArray,
    ) {
        val s = w.s
        var input = ByteArray(s.emb)
        System.arraycopy(w.embed, token * s.emb, input, 0, s.emb)
        for (l in 0 until s.layers) {
            matvec(w.wIh[l], input, s.hidden, s.emb, scratch)
            val rec = IntArray(s.hidden)
            matvec(w.wHh[l], h[l], s.hidden, s.hidden, rec)
            for (i in 0 until s.hidden) scratch[i] += rec[i] + w.bias[l][i]
            requantiseInto(scratch, h[l], s.hidden)
            if (l + 1 < s.layers) {
                input = ByteArray(s.emb)
                System.arraycopy(h[l], 0, input, 0, minOf(s.emb, s.hidden))
            }
        }
        // hidden -> emb, so the tied embedding table can score against it
        matvec(w.proj, h[s.layers - 1], s.emb, s.hidden, projScratch)
        requantiseInto(projScratch, embOut, s.emb)
    }

    /** purpose-ג: dot the projected state against k candidate embeddings. */
    private fun scoreK(w: Weights, embOut: ByteArray, cands: IntArray): Int {
        val s = w.s
        var best = Int.MIN_VALUE
        for (c in cands) {
            var acc = 0
            val base = c * s.emb
            for (i in 0 until s.emb) acc += w.embed[base + i] * embOut[i]
            if (acc > best) best = acc
        }
        return best
    }

    /** purpose-א: project over the whole vocabulary. */
    private fun fullSoftmax(w: Weights, embOut: ByteArray, logits: IntArray): Int {
        val s = w.s
        var best = Int.MIN_VALUE
        var o = 0
        for (v in 0 until s.vocab) {
            var acc = 0
            val base = v * s.emb
            for (i in 0 until s.emb) acc += w.embed[base + i] * embOut[i]
            logits[o++] = acc
            if (acc > best) best = acc
        }
        return best
    }

    // --- measurement ------------------------------------------------------------------------

    private fun percentile(sorted: LongArray, p: Double): Double =
        sorted[minOf(sorted.size - 1, (p * sorted.size).toInt())] / 1_000_000.0

    private class Timing(val p50: Double, val p95: Double, val max: Double, val n: Int)

    private fun time(n: Int, body: () -> Unit): Timing {
        repeat(WARMUP_STEPS) { body() }
        val d = LongArray(n)
        for (i in 0 until n) {
            val t0 = System.nanoTime()
            body()
            d[i] = System.nanoTime() - t0
        }
        d.sort()
        return Timing(percentile(d, 0.50), percentile(d, 0.95), d.last() / 1_000_000.0, n)
    }

    private fun usedBytes(): Long {
        System.gc(); Thread.sleep(60); System.gc()
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test
    fun inferenceFeasibility() {
        if (System.getProperty("runInferenceProbe").isNullOrEmpty()) {
            println("skipped; -PrunInferenceProbe=1")
            return
        }
        println("=".repeat(104))
        println("K1 — INFERENCE FEASIBILITY. Arithmetic only; random weights; no accuracy claim.")
        println("Build host, not a phone. A result here can KILL the branch and cannot bless it.")
        println("=".repeat(104))

        // ---- the baseline, measured IN THIS RUN, never the published 2.88 ms ----
        val lexFile = File(System.getProperty("lexicon.file")!!)
        val lexicon = lexFile.inputStream().use { HebrewLexicon.load(it) }
        val words = ArrayList<String>(lexicon.size)
        for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))

        val trieStart = System.nanoTime()
        val trie = LexiconTrie.build(words)
        val trieBuildMs = (System.nanoTime() - trieStart) / 1_000_000.0

        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        val engine = PredictiveEngine(
            lexicon, trie, frequency, bigrams,
            CorrectionEngine(lexicon, trie, frequency, NeutralCostModel, CorrectionEngine.Config()),
        )
        val sample = GZIPInputStream(
            File(File(System.getProperty("eval.dir")!!), "he_conversational_test.txt.gz")
                .inputStream()
        ).use { it.readBytes() }.toString(Charsets.UTF_8)
            .split('\n').filter { it.isNotBlank() }.map { it.split(' ') }
            .filter { it.size > 3 }.take(3_000)

        var q = 0
        val baseline = time(2_000) {
            val s = sample[q++ % sample.size]
            engine.predict(s[1].take(2), s[0])
        }
        println()
        println("BASELINE, this run, this host — the whole current suggestion path")
        println("  predict()      p50 %.3f ms   p95 %.3f ms   max %.3f ms   n=%d"
            .format(baseline.p50, baseline.p95, baseline.max, baseline.n))
        println("  trie build     %.1f ms   (%,d words)".format(trieBuildMs, words.size))
        println("  the latency bar is p95 <= %.3f ms".format(baseline.p95))

        println()
        println("%-3s %8s %5s %7s %6s %13s %8s | %-28s %-28s"
            .format("id", "vocab", "emb", "hidden", "layers", "int8 bytes", "fits?",
                "score-k (purpose ג)", "full-softmax (purpose א)"))
        println("-".repeat(140))

        var anyFittingShapePasses = false
        var controlBreached = false
        val memoryNotes = ArrayList<String>()
        val loadNotes = ArrayList<String>()

        for (s in SHAPES) {
            val bytes = s.bytes
            val fits = when {
                bytes <= ASSETS_FREE_NOW -> "now"
                bytes <= ASSETS_IF_TABLE_GOES -> "if table goes"
                else -> "NO"
            }

            val before = usedBytes()
            val loadStart = System.nanoTime()
            val w = Weights(s, seed = 20260825L + s.id[0].code)
            // Materialise as a cold load would: read the serialised bytes back through a stream.
            val blob = ByteArray(w.serializedBytes)
            ByteArrayInputStream(blob).use { ins ->
                var read = 0
                val buf = ByteArray(1 shl 16)
                while (true) { val n = ins.read(buf); if (n < 0) break; read += n }
                check(read == blob.size)
            }
            val loadMs = (System.nanoTime() - loadStart) / 1_000_000.0
            val after = usedBytes()
            val resident = after - before

            val h = Array(s.layers) { ByteArray(s.hidden) }
            val scratch = IntArray(s.hidden)
            val projScratch = IntArray(s.emb)
            val embOut = ByteArray(s.emb)
            val cands = IntArray(K_CANDIDATES) { (it * 977 + 13) % s.vocab }
            var tok = 0

            val tScore = time(TIMED_STEPS_FAST) {
                step(w, tok++ % s.vocab, h, scratch, embOut, projScratch)
                scoreK(w, embOut, cands)
            }
            val logits = IntArray(s.vocab)
            val slow = if (s.vocab >= 32_768) TIMED_STEPS_SLOW else TIMED_STEPS_FAST
            val tFull = time(slow) {
                step(w, tok++ % s.vocab, h, scratch, embOut, projScratch)
                fullSoftmax(w, embOut, logits)
            }

            println("%-3s %8d %5d %7d %6d %13s %8s | p50 %6.3f p95 %6.3f ms  p50 %6.3f p95 %8.3f ms"
                .format(s.id, s.vocab, s.emb, s.hidden, s.layers, "%,d".format(bytes), fits,
                    tScore.p50, tScore.p95, tFull.p50, tFull.p95))

            if (fits != "NO" && tScore.p95 <= baseline.p95) anyFittingShapePasses = true
            if (s.id == "E" && tFull.p95 > baseline.p95) controlBreached = true

            memoryNotes.add("%s: resident ~%,d bytes against %,d serialised — ratio %.2fx"
                .format(s.id, resident, w.serializedBytes,
                    resident.toDouble() / w.serializedBytes))
            loadNotes.add("%s: materialise %.1f ms against a %.1f ms trie build"
                .format(s.id, loadMs, trieBuildMs))
        }

        println()
        println("MEMORY  (approximate: gc + used-heap delta, and the JVM is not obliged to be exact)")
        memoryNotes.forEach { println("  $it") }
        println()
        println("LOAD  (in-memory materialisation — a LOWER BOUND on a real cold load from disk)")
        loadNotes.forEach { println("  $it") }

        println()
        println("=".repeat(104))
        println("AGAINST THE PRE-REGISTERED RULE")
        println("  kill clause    : a budget-fitting shape reaches score-k p95 <= %.3f ms ... %s"
            .format(baseline.p95, if (anyFittingShapePasses) "yes — NOT killed" else "NO — BRANCH DEAD"))
        println("  control clause : shape E full-softmax breaches the bar ................ %s"
            .format(if (controlBreached) "yes — the harness can fail" else "NO — RUN IS VOID"))
        println("=".repeat(104))
    }
}

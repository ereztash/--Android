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
 * Measures prediction on a corpus **proven disjoint** from the bigram training data.
 *
 * `scripts/build_eval_corpus.py` samples a different phase of the same grid and refuses to
 * write anything if any evaluation byte range intersects a training range. Without that, these
 * numbers would report how well the model memorised its own input, and would look excellent
 * either way.
 *
 * Latency figures are JVM numbers on the build host and are never quoted as device numbers.
 */
class PredictionMeasurementTest {

    private val evalDir = File(System.getProperty("eval.dir")!!)

    private data class Fixture(
        val engine: PredictiveEngine,
        val lexicon: HebrewLexicon,
        val bigrams: BigramModel,
    )

    private fun fixture(bigramWeight: Double): Fixture {
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
        return Fixture(
            PredictiveEngine(
                lexicon, trie, frequency, bigrams, corrections,
                PredictiveEngine.Config(limit = 3, bigramWeight = bigramWeight),
            ),
            lexicon,
            bigrams,
        )
    }

    private fun sentences(): Pair<List<List<String>>, String> {
        val raw = GZIPInputStream(File(evalDir, "hewiki_eval_sample.txt.gz").inputStream())
            .use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it) }
        return raw.toString(Charsets.UTF_8).split('\n')
            .filter { it.isNotBlank() }
            .map { it.split(' ') } to hash
    }

    @Test
    fun measurePrediction() {
        // The committed slice IS the sample; the selection rule lives in
        // scripts/slice_eval_corpus.py, so it is applied in exactly one place.
        val (sample, corpusHash) = sentences()
        assertTrue(sample.size > 3000, "sample too small: ${sample.size}")

        println("=".repeat(96))
        println("PREDICTION MEASUREMENT")
        println("corpus sha256 $corpusHash")
        println("sentences: ${sample.size} (held-out slice, parent proven disjoint)")
        println("=".repeat(96))

        for (weight in listOf(0.0, 0.5, 1.0, 2.0, 4.0)) {
            val f = fixture(weight)
            var nextTop1 = 0
            var nextTop3 = 0
            var nextAttempts = 0
            val compTop1 = IntArray(4)
            val compTop3 = IntArray(4)
            val compAttempts = IntArray(4)
            var offered = 0
            val start = System.nanoTime()
            var calls = 0

            for (s in sample) {
                for (i in 1 until s.size) {
                    val previous = s[i - 1]
                    val target = s[i]
                    if (target.length < 3) continue

                    // Next-word: nothing typed yet.
                    if (nextAttempts < 20000) {
                        nextAttempts++
                        val p = f.engine.predict("", previous)
                        calls++
                        if (p.isNotEmpty()) offered++
                        if (p.firstOrNull()?.word == target) nextTop1++
                        if (p.any { it.word == target }) nextTop3++
                    }

                    // Completion from prefixes of length 1..3.
                    for (k in 1..3) {
                        if (target.length <= k) continue
                        if (compAttempts[k] >= 20000) continue
                        compAttempts[k]++
                        val p = f.engine.predict(target.substring(0, k), previous)
                        calls++
                        if (p.firstOrNull()?.word == target) compTop1[k]++
                        if (p.any { it.word == target }) compTop3[k]++
                    }
                }
            }
            val perCall = (System.nanoTime() - start) / 1_000.0 / calls

            fun pct(x: Int, n: Int) = if (n == 0) "n/a" else "%.2f%%".format(100.0 * x / n)
            println(
                "bigramWeight=%.1f".format(weight) +
                    "  next-word top1 ${pct(nextTop1, nextAttempts)}" +
                    " top3 ${pct(nextTop3, nextAttempts)} (n=$nextAttempts," +
                    " offered ${pct(offered, nextAttempts)})"
            )
            for (k in 1..3) {
                println(
                    "               prefix $k: top1 ${pct(compTop1[k], compAttempts[k])}" +
                        " top3 ${pct(compTop3[k], compAttempts[k])} (n=${compAttempts[k]})"
                )
            }
            println("               %.0f us/call [JVM on build host, NOT a device]"
                .format(perCall))
        }
        println("=".repeat(96))
    }

    @Test
    fun bigramModelLoadsAndIsSane() {
        val f = fixture(0.0)
        val b = f.bigrams
        println(
            "bigram model: ${b.groupCount} groups, ${b.bigramCount} bigrams, " +
                "${b.heapBytes / 1048576.0} MiB"
        )
        assertEquals(532_168, b.bigramCount, "bigram count from BIGRAM_MANIFEST.json")
        assertEquals(54_133, b.groupCount)

        // A very common Hebrew word must have continuations, and they must be real words.
        val of = f.lexicon.indexOf("של")
        assertTrue(of >= 0)
        val conts = b.continuationsOf(of, 5)
        assertTrue(conts.isNotEmpty(), "'של' has no recorded continuations")
        println("  continuations of 'של': " +
            conts.joinToString(", ") { "${f.lexicon.wordAt(it.first)}(${it.second})" })
        // Continuations arrive ordered by count, which is what makes top-1 free.
        val counts = conts.map { it.second }
        assertEquals(counts.sortedDescending(), counts)
    }
}

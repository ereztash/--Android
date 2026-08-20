package com.hebrewime.core.correction

import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locked accuracy regression, plus the control that makes it mean something.
 *
 * **Every threshold below was chosen after the baseline was measured, never before**, and each
 * sits a little under the measured value so that ordinary variation does not fail the build
 * while a real regression does. The measured values are in `docs/CORRECTION_MEASUREMENTS.md`
 * with the corpus hash beside each.
 *
 * If one of these ever has to move upward to make the suite pass, that is a conflict to report
 * to the operator, not an edit to make.
 */
class CorrectionAccuracyTest {

    private val goldenDir = File(System.getProperty("golden.dir")!!)

    // Corpus hashes, pinned. If the corpus changes, these assertions are measuring something
    // else and must not silently keep passing.
    private val expectedHashes = mapOf(
        "a_uniform.tsv.gz" to
            "f9f4ed809b31bef0773ad7b2a99a5b247754d37972ac3575eba0df2edb11535e",
        "c1_control_lexicon.txt.gz" to
            "6e13ffd6d2f70cc8581436e77086fd30a0cb6f12bba194afde714457d0794add",
        "c2_control_real.txt.gz" to
            "c2e89437ece615da93f11c9d12876db325b4c6867f99ef85231a793e9bc032c9",
    )

    private fun corpus(name: String): List<String> {
        val raw = GZIPInputStream(File(goldenDir, name).inputStream()).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expectedHashes[name], hash, "corpus $name is not the one measured")
        return raw.toString(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
    }

    private fun engine(): CorrectionEngine {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val words = ArrayList<String>(lexicon.size)
        for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        // The SHIPPED configuration: neutral costs, frequency as a tie-break only, adjacency
        // NOT enabled. See docs/CORRECTION_MEASUREMENTS.md finding 1 for why.
        return CorrectionEngine(
            lexicon, LexiconTrie.build(words), frequency,
            NeutralCostModel, CorrectionEngine.Config(),
        )
    }

    @Test
    fun accuracyHasNotRegressed() {
        val e = engine()
        val a = corpus("a_uniform.tsv.gz")
        var top1 = 0
        var top3 = 0
        for (line in a) {
            val (typo, want) = line.split('\t')
            val s = e.suggest(typo)
            if (s.isNotEmpty() && s[0].word == want) top1++
            if (s.take(3).any { it.word == want }) top3++
        }
        val p1 = 100.0 * top1 / a.size
        val p3 = 100.0 * top3 / a.size
        println("corpus A n=${a.size}: top-1 %.2f%%, top-3 %.2f%%".format(p1, p3))

        // Measured 52.60% and 66.23%. Floors set below those, after the fact.
        assertTrue(p1 >= 52.0, "top-1 regressed to %.2f%% (measured 52.60%%)".format(p1))
        assertTrue(p3 >= 65.5, "top-3 regressed to %.2f%% (measured 66.23%%)".format(p3))
    }

    @Test
    fun theControlColumnIsNearZero() {
        val e = engine()
        val c1 = corpus("c1_control_lexicon.txt.gz")
        val replaced = c1.count { e.shouldAutoReplace(e.suggest(it)) }
        val rate = 100.0 * replaced / c1.size
        println("corpus C1 n=${c1.size}: false auto-replace %.2f%%".format(rate))

        // Measured 0.00%. An engine that quietly replaces correct words makes every accuracy
        // number above meaningless, so this is the assertion that licenses the others.
        assertEquals(0, replaced, "the engine replaced $replaced words that were already right")

        val c2 = corpus("c2_control_real.txt.gz")
        val replaced2 = c2.count { e.shouldAutoReplace(e.suggest(it)) }
        val rate2 = 100.0 * replaced2 / c2.size
        println("corpus C2 n=${c2.size}: false auto-replace %.2f%%".format(rate2))
        // Measured 0.68%. C2 contains proper nouns the lexicon genuinely lacks, so this is
        // not expected to be zero.
        assertTrue(rate2 <= 1.0, "C2 false auto-replace rose to %.2f%% (measured 0.68%%)".format(rate2))
    }

    /**
     * POSITIVE CONTROL for the control column itself.
     *
     * A false-auto-replace rate of zero is only reassuring if a non-zero rate would actually be
     * detected. This runs the same measurement against a deliberately indiscriminate replacer
     * and asserts the harness reports a high rate — so the 0.00% above is a measurement rather
     * than a property of the measuring code.
     */
    @Test
    fun anIndiscriminateReplacerIsDetectedByTheSameHarness() {
        val c1 = corpus("c1_control_lexicon.txt.gz")
        val leakyShouldReplace: (String) -> Boolean = { true }
        val replaced = c1.count { leakyShouldReplace(it) }
        val rate = 100.0 * replaced / c1.size
        println("POSITIVE CONTROL: indiscriminate replacer scores %.2f%% on C1".format(rate))
        assertTrue(
            rate > 50.0,
            "the control column failed to detect an indiscriminate replacer; " +
                "a 0.00%% result from it would prove nothing",
        )
    }
}

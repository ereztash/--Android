package com.hebrewime.core.confusion

import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The register change, measured where it was supposed to help.
 *
 * Every number this project published before R1 was measured on Hebrew Wikipedia, and every
 * measurement document carried "the register is wrong for phone typing" as a caveat. This is the
 * first test in the repository that evaluates on **conversational** text, and it compares the
 * shipped blended table against the pre-blend Wikipedia-only one **in the same run, on the same
 * sentences**, so the comparison is not between a number and a memory.
 *
 * A prediction was recorded in `docs/CORPUS_REGISTER.md` before the table was built:
 * conversational recall should gain **+8 to +15 points** and its false-alarm rate must not rise.
 * This test asserts that prediction. If the blend does not deliver it, this fails — which is the
 * point of writing a prediction down first.
 */
class ConversationalAccuracyTest {

    private companion object {
        const val SLICE_SHA256 =
            "d4cec6cf2c0241a6dde2d1067ac64a2bb9008ced14b6e22e275c62ece5706109"
        const val MIN_PREDICTED_GAIN = 8.0
        const val MAX_PREDICTED_GAIN = 15.0
    }

    private fun sentences(): List<List<String>> {
        val raw = GZIPInputStream(
            File(File(System.getProperty("eval.dir")!!), "he_conversational_test.txt.gz")
                .inputStream()
        ).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it) }
        assertEquals(SLICE_SHA256, hash, "the conversational slice is not the one measured")
        return raw.toString(Charsets.UTF_8).split('\n')
            .filter { it.isNotBlank() }.map { it.split(' ') }
    }

    private class Score(val recall: Double, val falseAlarm: Double, val n: Int)

    private fun measure(
        detector: RealWordErrorDetector,
        lexicon: HebrewLexicon,
        sents: List<List<String>>,
    ): Score {
        var caught = 0
        var injected = 0
        var alarms = 0
        var clean = 0
        for (s in sents) {
            for (i in 1 until s.size - 1) {
                val w = s[i]
                if (lexicon.indexOf(w) < 0) continue
                val variants =
                    HebrewConfusions.variantsOf(w, lexicon, HebrewConfusions.HOMOPHONE_PAIRS)
                if (variants.isEmpty()) continue

                clean++
                if (detector.check(s[i - 1], w, s[i + 1]) != null) alarms++

                injected++
                val wrong = lexicon.wordAt(variants.first())
                val f = detector.check(s[i - 1], wrong, s[i + 1])
                if (f != null && f.suggested == w) caught++
            }
        }
        return Score(100.0 * caught / injected, 100.0 * alarms / clean, injected)
    }

    @Test
    fun theBlendDeliversTheGainItWasPredictedToDeliver() {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val shipped = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        val wikiOnly = File(System.getProperty("bigram.wikionly.file")!!)
            .inputStream().use { BigramModel.load(it) }

        val sents = sentences()
        val before = measure(RealWordErrorDetector(lexicon, wikiOnly), lexicon, sents)
        val after = measure(RealWordErrorDetector(lexicon, shipped), lexicon, sents)

        println("=".repeat(80))
        println("CONVERSATIONAL REGISTER -- ${sents.size} sentences, n=${after.n} injections")
        println("  wikipedia only (before) : recall %.2f%%  false alarms %.3f%%"
            .format(before.recall, before.falseAlarm))
        println("  25%% subtitles (shipped) : recall %.2f%%  false alarms %.3f%%"
            .format(after.recall, after.falseAlarm))
        println("  delta                   : recall %+.2f    false alarms %+.3f"
            .format(after.recall - before.recall, after.falseAlarm - before.falseAlarm))
        println("=".repeat(80))

        assertTrue(after.n > 3_000, "denominator too small: ${after.n}")
        val gain = after.recall - before.recall
        assertTrue(
            gain >= MIN_PREDICTED_GAIN,
            "the blend gained %.2f points, below the %.1f predicted before it was built. "
                .format(gain, MIN_PREDICTED_GAIN) +
                "Either the port is wrong or the Python experiment did not transfer; report " +
                "it rather than re-pinning this number.",
        )
        assertTrue(
            gain <= MAX_PREDICTED_GAIN * 1.5,
            "the blend gained %.2f points, far more than predicted. Too good is a reason to "
                .format(gain) + "check the harness, not to celebrate.",
        )
        assertTrue(
            after.falseAlarm <= before.falseAlarm,
            "false alarms rose on conversational text (%.3f%% -> %.3f%%); the prediction said "
                .format(before.falseAlarm, after.falseAlarm) + "they must not.",
        )
    }
}

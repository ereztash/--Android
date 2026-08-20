package com.hebrewime.core.confusion

import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How often the adjacent-bigram window has **nothing to say at all**.
 *
 * ### Where this test came from
 * The operator, typing on a phone, wrote `עוגת גבינה אם הרבה שוקולד` and observed that a reader
 * cannot tell whether `אם` or `עם` was meant until the word *after* `הרבה` arrives.
 *
 * That case in fact fires, and the reason is worth being honest about: `עם הרבה` is a common
 * collocation (evidence 51) and `אם הרבה` never occurs (evidence 0), so the statistics reached
 * the right answer **before** the deciding word was typed — by luck of collocation, not by
 * understanding anything.
 *
 * The observation generalises anyway, and this test measures how far. When neither the typed
 * word nor its variant has any evidence on either immediate neighbour, no margin, threshold or
 * weight can help: there is no signal in the window to weigh. Only context further away could
 * decide, and [BigramModel] stores adjacent pairs only, so that context is not representable —
 * a structural limit, not a tuning decision.
 *
 * This is a **characterisation test**. It does not assert an improvement; it pins a number so
 * that a future change claiming to widen the window has something to beat.
 */
class WindowBlindnessTest {

    @Test
    fun measureHowOftenTheAdjacentWindowIsBlind() {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }

        val raw = GZIPInputStream(
            File(File(System.getProperty("eval.dir")!!), "hewiki_confusion_test.txt.gz")
                .inputStream()
        ).use { it.readBytes() }
        val sentences = raw.toString(Charsets.UTF_8).split('\n')
            .filter { it.isNotBlank() }.map { it.split(' ') }

        fun bg(a: Int, b: Int) = if (a < 0 || b < 0) 0 else bigrams.logCountOf(a, b)

        var confusable = 0
        var blind = 0
        for (s in sentences) {
            for (i in 1 until s.size - 1) {
                val wi = lexicon.indexOf(s[i])
                if (wi < 0) continue
                val variants =
                    HebrewConfusions.variantsOf(s[i], lexicon, HebrewConfusions.HOMOPHONE_PAIRS)
                if (variants.isEmpty()) continue
                confusable++
                val l = lexicon.indexOf(s[i - 1])
                val r = lexicon.indexOf(s[i + 1])
                val typed = bg(l, wi) + bg(wi, r)
                val best = variants.maxOf { bg(l, it) + bg(it, r) }
                if (typed == 0 && best == 0) blind++
            }
        }
        val pct = 100.0 * blind / confusable
        println(
            "adjacent window blind on %,d of %,d confusable positions (%.1f%%), %,d sentences"
                .format(blind, confusable, pct, sentences.size)
        )
        assertTrue(confusable > 30_000, "denominator too small: $confusable")
        // Pinned, not aspirational. If a future model widens the window this should FALL, and
        // the test should be updated deliberately with the new measurement beside the old.
        assertTrue(
            pct in 25.0..35.0,
            "blindness moved to %.1f%%; it was 30.2%% when measured. That is a real change in "
                .format(pct) + "what the detector can see and must be explained, not re-pinned.",
        )
    }
}

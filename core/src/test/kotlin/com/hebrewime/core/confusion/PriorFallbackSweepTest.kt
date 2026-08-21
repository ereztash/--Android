package com.hebrewime.core.confusion

import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * P1 — sweeps the blind-position prior margin on the **dev** slice.
 *
 * Every row is printed, including the losing ones. The rule was fixed in
 * `docs/CONFUSION_MEASUREMENTS.md` before this ran.
 *
 * Opt-in: `-PrunConfusionSweep=1`.
 */
class PriorFallbackSweepTest {

    @Test
    fun sweep() {
        if (System.getProperty("runConfusionSweep").isNullOrEmpty()) {
            println("skipped; run with -PrunConfusionSweep=1")
            return
        }
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }

        val raw = GZIPInputStream(
            File(File(System.getProperty("eval.dir")!!), "hewiki_confusion_dev.txt.gz")
                .inputStream()
        ).use { it.readBytes() }
        val sents = raw.toString(Charsets.UTF_8).split('\n')
            .filter { it.isNotBlank() }.map { it.split(' ') }

        fun measure(margin: Int): Triple<Double, Double, Int> {
            val detector = RealWordErrorDetector(
                lexicon, bigrams, BigramModel.EMPTY, frequency,
                RealWordErrorDetector.Config(priorMargin = margin),
            )
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
                    val f = detector.check(s[i - 1], lexicon.wordAt(variants.first()), s[i + 1])
                    if (f != null && f.suggested == w) caught++
                }
            }
            return Triple(100.0 * caught / injected, 100.0 * alarms / clean, injected)
        }

        println("=".repeat(80))
        println("P1 SWEEP -- confusion_dev, blind-position prior margin")
        println("%-10s %-12s %-14s %s".format("margin", "recall", "false alarms", "note"))
        for (margin in listOf(0, 80, 96, 104, 112, 120, 128, 144, 160, 176, 192)) {
            val (recall, alarm, n) = measure(margin)
            println("%-10d %-12s %-14s %s".format(
                margin, "%.2f%%".format(recall), "%.3f%%".format(alarm),
                if (margin == 0) "fallback OFF -- what ships today (n=$n)" else ""))
        }
        println("=".repeat(80))
    }
}

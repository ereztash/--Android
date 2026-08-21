package com.hebrewime.core.confusion

import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * Sweeps [RealWordErrorDetector.Config.skipMargin] on the **dev** slice.
 *
 * Every row is printed, including the ones that lose. The stopping rule for this whole layer
 * was committed before any of this ran; see the S1 section of `docs/CONFUSION_MEASUREMENTS.md`.
 *
 * Opt-in: `-PrunConfusionSweep=1`.
 */
class SkipMarginSweepTest {

    @Test
    fun sweep() {
        if (System.getProperty("runConfusionSweep").isNullOrEmpty()) {
            println("skipped; run with -PrunConfusionSweep=1")
            return
        }
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        val skip = File(System.getProperty("skipgram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        println("skip table: ${skip.groupCount} groups, ${skip.bigramCount} pairs")

        val sentences = load("hewiki_confusion_dev.txt.gz")
        println("dev slice: ${sentences.size} sentences")

        println("\n%-8s %-10s %-10s %-12s %s".format(
            "margin", "recall", "false", "flagged", "note"))
        for (margin in listOf(64, 66, 68, 70, 72, 74, 76, 78, 80, 84, 88)) {
            val detector = RealWordErrorDetector(
                lexicon, bigrams, skip,
                config = RealWordErrorDetector.Config(skipMargin = margin),
            )
            val r = evaluate(detector, sentences, lexicon, wide = true)
            println("%-8d %-10s %-10s %-12s %s".format(
                margin, "%.2f%%".format(r.recall), "%.3f%%".format(r.falseAlarm),
                "${r.caught}/${r.injected}",
                if (margin == 0) "no margin at all" else "",
            ))
        }
        val adjacentOnly = RealWordErrorDetector(lexicon, bigrams)
        val base = evaluate(adjacentOnly, sentences, lexicon, wide = false)
        println("\nadjacent only (shipped): recall %.2f%%  false %.3f%%  %d/%d".format(
            base.recall, base.falseAlarm, base.caught, base.injected))
    }

    class Result(val caught: Int, val injected: Int, val falseFlags: Int, val clean: Int) {
        val recall get() = 100.0 * caught / injected
        val falseAlarm get() = 100.0 * falseFlags / clean
    }

    companion object {

        fun load(name: String): List<List<String>> {
            val raw = GZIPInputStream(
                File(File(System.getProperty("eval.dir")!!), name).inputStream()
            ).use { it.readBytes() }
            return raw.toString(Charsets.UTF_8).split('\n')
                .filter { it.isNotBlank() }.map { it.split(' ') }
        }

        /**
         * Inject one confusion per eligible position and see whether it is caught, then run the
         * same detector over the UNMODIFIED text and count anything it flags as a false alarm.
         *
         * Deterministic: the first variant, every eligible position. No sampling, so there is
         * no seed to choose and no run-to-run variance to average away.
         */
        fun evaluate(
            detector: RealWordErrorDetector,
            sentences: List<List<String>>,
            lexicon: HebrewLexicon,
            wide: Boolean,
        ): Result {
            var caught = 0
            var injected = 0
            var falseFlags = 0
            var clean = 0

            fun run(s: List<String>, i: Int): Any? =
                if (wide) detector.checkWide(
                    s.getOrNull(i - 2), s.getOrNull(i - 1), s[i],
                    s.getOrNull(i + 1), s.getOrNull(i + 2),
                ) else detector.check(s.getOrNull(i - 1), s[i], s.getOrNull(i + 1))

            for (s in sentences) {
                for (i in s.indices) {
                    val w = s[i]
                    if (lexicon.indexOf(w) < 0) continue
                    val variants =
                        HebrewConfusions.variantsOf(w, lexicon, HebrewConfusions.HOMOPHONE_PAIRS)
                    if (variants.isEmpty()) continue

                    clean++
                    if (run(s, i) != null) falseFlags++

                    injected++
                    val corrupted = s.toMutableList()
                    corrupted[i] = lexicon.wordAt(variants.first())
                    val finding = if (wide) detector.checkWide(
                        corrupted.getOrNull(i - 2), corrupted.getOrNull(i - 1), corrupted[i],
                        corrupted.getOrNull(i + 1), corrupted.getOrNull(i + 2),
                    ) else detector.check(
                        corrupted.getOrNull(i - 1), corrupted[i], corrupted.getOrNull(i + 1),
                    )
                    if (finding != null && finding.suggested == w) caught++
                }
            }
            return Result(caught, injected, falseFlags, clean)
        }
    }
}

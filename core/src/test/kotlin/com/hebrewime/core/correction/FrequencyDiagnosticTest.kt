package com.hebrewime.core.correction

import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The weight sweep showed `frequencyWeight` making no difference at any value from 0 to 150,
 * which is implausible for a real ranking signal. This checks whether the frequency table is
 * actually carrying information, so that "frequency does not help" is a finding rather than a
 * bug reported as one.
 */
class FrequencyDiagnosticTest {

    @Test
    fun frequencyTableCarriesRealInformation() {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }

        assertEquals(lexicon.size, frequency.size, "table must align with the lexicon")

        var zero = 0
        var min = 255
        var max = 0
        var sum = 0L
        val histogram = IntArray(256)
        for (i in 0 until frequency.size) {
            val f = frequency.logFrequencyOf(i)
            histogram[f]++
            if (f == 0) zero++ else {
                if (f < min) min = f
                if (f > max) max = f
            }
            sum += f
        }
        val distinct = histogram.count { it > 0 }

        println(
            """
            |frequency table diagnostic
            |  entries        : ${frequency.size}
            |  zero (unattested): $zero
            |  attested       : ${frequency.size - zero}
            |  min non-zero   : $min
            |  max            : $max
            |  mean           : ${"%.1f".format(sum.toDouble() / frequency.size)}
            |  distinct values: $distinct
            """.trimMargin()
        )

        // Spot-check a few words whose relative frequency is not in doubt.
        for (w in listOf("שלום", "בית", "מקלדת", "אונייה")) {
            val idx = lexicon.indexOf(w)
            println("  $w -> index $idx, logFreq ${frequency.logFrequencyOf(idx)}")
        }

        assertEquals(57_425, zero, "unattested count should match the A-only word count")
        assertTrue(distinct > 20, "only $distinct distinct values; the table is not informative")
        assertTrue(max > 150, "max log frequency was $max; scaling looks wrong")
    }

    /**
     * Does frequency ever actually change the ranking? Counts, over real corpus queries, how
     * many times the top candidate differs between two frequency weights.
     */
    @Test
    fun measureWhetherFrequencyWeightEverChangesTheTopCandidate() {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val words = ArrayList<String>(lexicon.size)
        for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))
        val trie = LexiconTrie.build(words)
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }

        val goldenDir = File(System.getProperty("golden.dir")!!)
        val a = java.util.zip.GZIPInputStream(
            File(goldenDir, "a_uniform.tsv.gz").inputStream()
        ).use { it.readBytes() }.toString(Charsets.UTF_8)
            .split('\n').filter { it.isNotEmpty() }.take(500)

        val low = CorrectionEngine(
            lexicon, trie, frequency, NeutralCostModel,
            CorrectionEngine.Config(frequencyWeight = 0.0),
        )
        val high = CorrectionEngine(
            lexicon, trie, frequency, NeutralCostModel,
            CorrectionEngine.Config(frequencyWeight = 150.0),
        )

        var differing = 0
        var bothEmpty = 0
        var multiCandidate = 0
        for (line in a) {
            val typo = line.split('\t')[0]
            val l = low.suggest(typo)
            val h = high.suggest(typo)
            if (l.isEmpty() && h.isEmpty()) {
                bothEmpty++
                continue
            }
            if (l.size > 1) multiCandidate++
            if (l.firstOrNull()?.word != h.firstOrNull()?.word) differing++
        }
        println(
            "frequencyWeight 0 vs 150 over ${a.size} queries: " +
                "$differing top-1 differences, $multiCandidate had >1 candidate, " +
                "$bothEmpty produced nothing"
        )
    }
}

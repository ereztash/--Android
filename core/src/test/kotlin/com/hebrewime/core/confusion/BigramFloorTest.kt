package com.hebrewime.core.confusion

import com.hebrewime.core.prediction.BigramModel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bigram table is pruned at a count of 5, and counts are stored as
 * `round(log2(count + 1) * 8)`. That makes 21 the smallest value any stored bigram can carry,
 * which in turn makes every margin from 1 to 21 the same rule: "the corpus has seen this pair
 * at all". Measured here rather than derived on paper, because a threshold that turns out to
 * be a no-op is exactly the kind of thing that looks like tuning and is not.
 */
class BigramFloorTest {

    @Test
    fun theSmallestStoredLogCountIsTheArithmeticFloorOfThePruningThreshold() {
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        val observed = bigrams.minimumLogCount()
        val expected = Math.round(Math.log(6.0) / Math.log(2.0) * 8).toInt()
        println("min stored logCount = $observed; round(log2(min_count + 1) * 8) = $expected")
        assertEquals(expected, observed, "the pruning floor and the encoding disagree")
        assertEquals(21, observed, "21 is what makes margins 1..21 the same rule")
    }
}

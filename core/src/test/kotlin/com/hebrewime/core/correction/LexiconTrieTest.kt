package com.hebrewime.core.correction

import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The trie computes the same quantity as [DamerauLevenshtein], incrementally across shared
 * prefixes. That is the whole reason it is fast, and also the reason it could be subtly wrong
 * in a way nobody notices: a bad distance produces plausible-looking wrong suggestions, not an
 * exception.
 *
 * So it is checked against the obvious implementation exhaustively over a sample, rather than
 * spot-checked.
 */
class LexiconTrieTest {

    private val lexiconFile = File(System.getProperty("lexicon.file")!!)

    private fun smallTrie(words: List<String>) = LexiconTrie.build(words.sorted())

    @Test
    fun findsExactMatchesAtZeroCost() {
        val words = listOf("ספר", "ספרים", "מספר", "בית", "בתים")
        val trie = smallTrie(words)
        val sorted = words.sorted()
        for (w in words) {
            val hits = trie.search(w, 0, NeutralCostModel)
            assertEquals(1, hits.size, "exact search for $w")
            assertEquals(w, sorted[hits[0].wordIndex])
            assertEquals(0, hits[0].cost)
        }
    }

    @Test
    fun agreesWithTheReferenceImplementationExhaustively() {
        // Every word in a small lexicon against every query within budget, both ways.
        val words = listOf(
            "ספר", "ספרים", "ספרה", "מספר", "סופר", "בית", "בתים", "בת",
            "שלום", "שלומות", "מלון", "מלח", "חלום", "חלומות",
        ).sorted()
        val trie = LexiconTrie.build(words)
        val budget = 2 * EditCostModel.UNIT

        val queries = words + listOf("ספרי", "סםר", "פסר", "שלוב", "חלוב", "מלוח", "זזזז", "")
        var comparisons = 0
        for (q in queries) {
            val expected = words
                .mapIndexedNotNull { i, w ->
                    val d = DamerauLevenshtein.distance(q, w, NeutralCostModel)
                    if (d <= budget) i to d else null
                }
                .toMap()
            val actual = trie.search(q, budget, NeutralCostModel, limit = Int.MAX_VALUE)
                .associate { it.wordIndex to it.cost }

            if (q.isEmpty()) {
                assertTrue(actual.isEmpty(), "an empty query must return nothing")
                continue
            }
            assertEquals(
                expected.keys.sorted(), actual.keys.sorted(),
                "different candidate SET for query '$q'",
            )
            for ((idx, cost) in expected) {
                assertEquals(cost, actual[idx], "cost mismatch for '$q' -> '${words[idx]}'")
                comparisons++
            }
        }
        assertTrue(comparisons > 50, "only $comparisons comparisons; the sweep was too thin")
    }

    @Test
    fun transpositionCostsOneEditNotTwo() {
        // Both pairs are genuine adjacent transpositions, verified code point by code point.
        // An earlier version of this test used mem (U+05DE) where final mem (U+05DD) belonged,
        // which is two substitutions rather than a transposition -- the test was wrong, not
        // the trie.
        val cases = listOf(
            "שלום" to "שלםו",     // shin lamed vav finalmem -> shin lamed finalmem vav
            "מקלדת" to "מלקדת",   // mem qof lamed -> mem lamed qof
        )
        for ((word, typo) in cases) {
            assertEquals(
                word.toList().sorted(), typo.toList().sorted(),
                "$typo must be a permutation of $word",
            )
            val trie = LexiconTrie.build(listOf(word))
            val hits = trie.search(typo, 1 * EditCostModel.UNIT, NeutralCostModel)
            assertEquals(1, hits.size, "$typo -> $word must be reachable within one edit")
            assertEquals(EditCostModel.UNIT, hits[0].cost, "$typo -> $word cost")
            // And the reference implementation must agree.
            assertEquals(
                EditCostModel.UNIT,
                DamerauLevenshtein.distance(typo, word, NeutralCostModel),
            )
        }
    }

    @Test
    fun rejectsUnsortedInput() {
        try {
            LexiconTrie.build(listOf("בית", "אבא"))
            fail("unsorted input must be refused, not silently mis-built")
        } catch (expected: IllegalArgumentException) {
            assertTrue("sorted" in expected.message!!)
        }
    }

    @Test
    fun limitIsRespected() {
        val words = (1..500).map { "א".repeat(3) + "ב".repeat(it % 5 + 1) }.distinct().sorted()
        val trie = LexiconTrie.build(words)
        val hits = trie.search("אאא", 5 * EditCostModel.UNIT, NeutralCostModel, limit = 3)
        assertTrue(hits.size <= 3, "limit was ignored: ${hits.size} results")
    }

    /**
     * Measurement, not a threshold. Numbers are recorded so that a change in the data structure
     * shows up as a number moving rather than as a vague feeling that things got slower.
     */
    @Test
    fun measureRealLexiconStructure() {
        val lexicon = lexiconFile.inputStream().use { HebrewLexicon.load(it) }
        val words = ArrayList<String>(lexicon.size)
        for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))

        val buildStart = System.nanoTime()
        val trie = LexiconTrie.build(words)
        val buildMs = (System.nanoTime() - buildStart) / 1_000_000.0

        val naiveNodes = words.sumOf { it.length } + 1
        val sharing = 100.0 * (1 - trie.nodeCount.toDouble() / naiveNodes)

        println(
            """
            |LexiconTrie over the real lexicon
            |  words          : ${words.size}
            |  nodes          : ${trie.nodeCount}
            |  edges          : ${trie.edgeCount}
            |  nodes if no prefix sharing : $naiveNodes
            |  prefix sharing : ${"%.1f".format(sharing)}%
            |  array bytes    : ${trie.heapBytes} (${"%.2f".format(trie.heapBytes / 1048576.0)} MiB)
            |  build time     : ${"%.0f".format(buildMs)} ms  (JVM on this build host, NOT a device)
            """.trimMargin()
        )

        assertEquals(355_587, words.size)
        assertTrue(trie.nodeCount < naiveNodes, "the trie must share prefixes at all")
        // Every word must still be findable at zero cost after construction.
        for (w in listOf("שלום", "מקלדת", "ספר", "אונייה", "בית")) {
            val hits = trie.search(w, 0, NeutralCostModel)
            assertEquals(1, hits.size, "$w not found in the real trie")
            assertEquals(w, words[hits[0].wordIndex])
        }
    }
}

/**
 * Completion cost, measured before choosing a strategy rather than after.
 *
 * The first version capped the walk and returned trie order, which is alphabetical. The
 * fan-out measurement below is why that was wrong: a one-letter prefix has a median of 7,716
 * completions, so the cap would have discarded every common word in favour of whatever sorts
 * first. Walking the whole subtree and keeping top-K by frequency costs the numbers printed
 * here.
 */
class CompletionCostTest {

    private val lexiconFile = File(System.getProperty("lexicon.file")!!)
    private val frequencyFile = File(System.getProperty("frequency.file")!!)

    @Test
    fun measureCompletionCostAndCorrectness() {
        val lexicon = lexiconFile.inputStream().use { HebrewLexicon.load(it) }
        val words = ArrayList<String>(lexicon.size)
        for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))
        val trie = LexiconTrie.build(words)
        val frequency = frequencyFile.inputStream().use { HebrewFrequency.load(it) }
        val score = { i: Int -> frequency.logFrequencyOf(i) }

        val letters = ('א'..'ת').toList()
        val one = letters.map { it.toString() }
        val two = letters.flatMap { a -> letters.take(9).map { b -> "$a$b" } }

        fun report(label: String, prefixes: List<String>) {
            val counts = prefixes.map { trie.completionCount(it) }.sorted()
            val start = System.nanoTime()
            var produced = 0
            repeat(3) { prefixes.forEach { produced += trie.completionsTopK(it, 8, score).size } }
            val perCall = (System.nanoTime() - start) / 1_000.0 / (prefixes.size * 3)
            println(
                "  $label: n=${prefixes.size} fan-out median=${counts[counts.size / 2]} " +
                    "max=${counts.last()} | topK(8) ${"%.0f".format(perCall)} us/call"
            )
            assertTrue(produced > 0)
        }

        println("completion cost over the real lexicon (JVM on the build host, NOT a device)")
        report("1 letter ", one)
        report("2 letters", two)

        // Correctness: results really are the highest-frequency completions, and really are
        // completions.
        for (p in listOf("מק", "של", "בי", "אנ")) {
            val top = trie.completionsTopK(p, 5, score)
            assertTrue(top.isNotEmpty(), "no completions for '$p'")
            for (h in top) assertTrue(words[h].startsWith(p), "'${words[h]}' !startsWith '$p'")
            val scores = top.map { score(it) }
            assertEquals(scores.sortedDescending(), scores, "top-K must be frequency-ordered")
            // Nothing outside the returned set may beat the worst of it.
            val worst = scores.last()
            val better = trie.completions(p, Int.MAX_VALUE).count { score(it) > worst }
            assertTrue(better <= top.size, "top-K missed a more frequent completion for '$p'")
        }
        assertTrue(trie.completionsTopK("זזזזזז", 5, score).isEmpty())
    }
}

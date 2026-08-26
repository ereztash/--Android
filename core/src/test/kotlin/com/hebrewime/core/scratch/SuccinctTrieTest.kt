package com.hebrewime.core.scratch

import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `E3` — measures a succinct trie against `LexiconTrie` on the shipped lexicon.
 *
 * Gated behind `-PrunSuccinctTrie=1`: it builds two full tries over 355,587 words and runs
 * thousands of edit-distance searches through both.
 *
 * **The three positive controls run FIRST.** If a planted defect does not turn a check red,
 * that check is `NOT-A-CHECK` and the size numbers underneath it mean nothing, so the run
 * stops there rather than reporting a favourable number from an instrument that cannot fail.
 */
class SuccinctTrieTest {

    @Test
    fun succinctTrieMatchesLexiconTrieAndIsMeasured() {
        if (System.getProperty("runSuccinctTrie").isNullOrEmpty()) {
            println("skipped; -PrunSuccinctTrie=1")
            return
        }
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val words = lexicon.asWordList()
        val plain = LexiconTrie.build(words)

        // ---- the controls, before anything they control -------------------------------
        // A subset keeps three extra builds affordable. It exercises the same code paths;
        // what a control has to show is that the check CAN go red, not that it does so at
        // full scale.
        val subset = (0 until 20_000).map { words[it * (words.size / 20_000)] }.distinct().sorted()
        val subsetPlain = LexiconTrie.build(subset)
        val subsetHonest = SuccinctTrie.Companion.build(subset)
        val probes = subset.filterIndexed { i, _ -> i % 37 == 0 }

        assertTrue(equivalent(subsetPlain, subsetHonest, probes), "honest build must agree first")
        assertTrue(accepts(subsetHonest, subset), "honest build must accept the lexicon first")

        val pc1 = SuccinctTrie.Companion.build(subset, SuccinctTrie.Companion.Defect.OFF_BY_ONE_CHILD)
        val pc1Red = !equivalent(subsetPlain, pc1, probes)
        println("PC-1 off-by-one child walk -> equivalence ${if (pc1Red) "RED" else "GREEN (NOT-A-CHECK)"}")
        assertTrue(pc1Red, "PC-1: an off-by-one child walk must break equivalence")

        val pc2 = SuccinctTrie.Companion.build(subset, SuccinctTrie.Companion.Defect.DROP_LAST_TERMINAL)
        val pc2Red = !accepts(pc2, subset)
        println("PC-2 dropped last terminal -> acceptance ${if (pc2Red) "RED" else "GREEN (NOT-A-CHECK)"}")
        assertTrue(pc2Red, "PC-2: a dropped terminal must break whole-lexicon acceptance")

        // PC-3 is the one that matters: it separates the two checks. An extra accepted word is
        // invisible to acceptance -- every real word still resolves -- and only equivalence
        // sees it. Without this, the two checks could be one check under two names.
        val pc3 = SuccinctTrie.Companion.build(subset, SuccinctTrie.Companion.Defect.ACCEPT_EXTRA)
        val pc3AcceptGreen = accepts(pc3, subset)
        val pc3EquivRed = !equivalent(subsetPlain, pc3, probes)
        println(
            "PC-3 extra accepted word -> acceptance ${if (pc3AcceptGreen) "GREEN" else "RED"}, " +
                "equivalence ${if (pc3EquivRed) "RED" else "GREEN (NOT-A-CHECK)"}"
        )
        assertTrue(pc3AcceptGreen, "PC-3: acceptance must be blind to an extra word")
        assertTrue(pc3EquivRed, "PC-3: equivalence must not be")

        // ---- the measurement ------------------------------------------------------------
        val succinct = SuccinctTrie.Companion.build(words)
        assertEquals(plain.nodeCount, succinct.nodeCount, "both constructions must agree on nodes")

        var accepted = 0
        for (i in 0 until words.size) if (succinct.contains(words[i])) accepted++
        assertEquals(words.size, accepted, "every lexicon word must be accepted")

        val queries = ArrayList<String>()
        var i = 0
        while (queries.size < 2_000 && i < words.size) { queries.add(words[i]); i += 173 }
        // Half the probes are perturbed, so the search does real work rather than finding an
        // exact match at cost 0 and stopping.
        val perturbed = queries.mapIndexed { n, w ->
            if (n % 2 == 0 || w.length < 3) w else w.substring(0, 1) + w.substring(2)
        }
        var compared = 0
        for (q in perturbed) {
            val a = plain.search(q, 2 * 100, NeutralCostModel)
            val b = succinct.search(q, 2 * 100, NeutralCostModel)
            assertEquals(a.size, b.size, "result count for '$q'")
            assertEquals(a.toSet(), b.toSet(), "results for '$q'")
            compared++
        }

        // ---- latency, and the fact that it was NOT pre-registered ----------------------
        // No bar was committed for this, so no bar is applied to it. It is here because a 10x
        // memory win that costs 20x on the input path is not a win, and leaving the obvious
        // objection unmeasured would be the same mistake E2 made. A build-host ratio is not a
        // device latency: M7-LAT has still never run.
        val timed = perturbed.take(300)
        repeat(2) { for (q in timed) { plain.search(q, 200, NeutralCostModel); succinct.search(q, 200, NeutralCostModel) } }
        var plainNanos = 0L
        var succinctNanos = 0L
        repeat(3) {
            var t = System.nanoTime()
            for (q in timed) plain.search(q, 200, NeutralCostModel)
            plainNanos += System.nanoTime() - t
            t = System.nanoTime()
            for (q in timed) succinct.search(q, 200, NeutralCostModel)
            succinctNanos += System.nanoTime() - t
        }
        val plainMs = plainNanos / 3.0 / timed.size / 1e6
        val succinctMs = succinctNanos / 3.0 / timed.size / 1e6

        val ratio = plain.heapBytes.toDouble() / succinct.heapBytes
        println("=== E3 succinct trie ===")
        println("nodes            ${plain.nodeCount}")
        println("words            ${words.size}")
        println("alphabet         ${succinct.alphabetSize} distinct characters")
        println("LexiconTrie      ${plain.heapBytes} B")
        println("SuccinctTrie     ${succinct.heapBytes} B")
        println("reduction        ${"%.2f".format(ratio)}x")
        println("equivalence      $compared queries, all identical")
        println("acceptance       $accepted / ${words.size} words")
        println("--- unregistered: latency, build host, ${timed.size} queries x3 ---")
        println("LexiconTrie      ${"%.3f".format(plainMs)} ms/search")
        println("SuccinctTrie     ${"%.3f".format(succinctMs)} ms/search")
        println("cost             ${"%.2f".format(succinctMs / plainMs)}x")
    }

    private fun accepts(t: SuccinctTrie, words: List<String>): Boolean =
        words.all { t.contains(it) }

    private fun equivalent(plain: LexiconTrie, t: SuccinctTrie, probes: List<String>): Boolean =
        probes.all { q ->
            plain.search(q, 2 * 100, NeutralCostModel).toSet() ==
                t.search(q, 2 * 100, NeutralCostModel).toSet()
        }
}

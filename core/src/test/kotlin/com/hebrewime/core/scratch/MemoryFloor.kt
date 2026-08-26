package com.hebrewime.core.scratch

import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import java.io.File

/**
 * E2 — the smallest heap each shipped structure survives in.
 *
 * ### Why a heap floor and not a retained size
 * `K1` tried to measure resident bytes by differencing `totalMemory - freeMemory` around an
 * allocation and got **−408,760 bytes**, which is impossible, so it recorded `NOT-MEASURED`.
 * A GC-delta is taken at the JVM's discretion and is not an instrument.
 *
 * **The smallest `-Xmx` at which a load completes is deterministic and externally checkable.**
 * It over-states the true retained size by whatever headroom the collector needs, and that is
 * stated rather than corrected for: it is an upper bound on the structure and a *lower* bound
 * on what a device must supply, which is the direction that matters for a budget.
 *
 * Driven by `scripts/measure_memory_floor.sh`, which launches one JVM per (stage, heap) pair.
 * Stages are cumulative, so each line's cost is its own floor minus the previous one's.
 */
object MemoryFloor {
    @JvmStatic
    fun main(args: Array<String>) {
        val stage = args[0]
        val dir = args[1]
        try {
            // The zero arm. Without it "lexicon = 17 MB" conflates the lexicon with whatever
            // heap a JVM needs to reach `main` at all, and there is no way to tell which.
            if (stage == "empty") return ok(stage, "loaded=nothing")

            val lexicon = File(dir, "he_lexicon.txt.gz").inputStream().use { HebrewLexicon.load(it) }
            if (stage == "lexicon") return ok(stage, "words=${lexicon.size} heapBytes=${lexicon.heapBytes}")

            // The FIRST version of this probe copied the lexicon into an ArrayList<String>
            // before building. `CorrectionController` does not: it passes
            // `lexicon.asWordList()`, a non-copying view. The copy is 355,587 live Strings and
            // it dominated the floor, so the first `trie` figure measured a path the app does
            // not take. Both are kept so the difference is visible rather than asserted.
            val words = if (stage == "trie-copy") {
                ArrayList<String>(lexicon.size).also { l ->
                    for (i in 0 until lexicon.size) l.add(lexicon.wordAt(i))
                }
            } else {
                lexicon.asWordList()
            }
            if (stage == "succinct" || stage == "all-succinct") {
                val st = SuccinctTrie.build(words)
                if (stage == "succinct") {
                    return ok(stage, "nodes=${st.nodeCount} heapBytes=${st.heapBytes}")
                }
                // The whole shipped warm-up chain with the succinct trie substituted for
                // LexiconTrie. This is the number a shipping decision would rest on; the
                // `succinct` stage alone only prices the structure.
                File(dir, "he_freq.bin.gz").inputStream().use { HebrewFrequency.load(it) }
                val bg = File(dir, "he_bigrams.bin.gz").inputStream().use { BigramModel.load(it) }
                return ok(stage, "held=${st.heapBytes} bigramFloor=${bg.minimumLogCount()}")
            }

            val trie = LexiconTrie.build(words)
            if (stage == "trie" || stage == "trie-copy") {
                return ok(stage, "nodes=${trie.nodeCount} heapBytes=${trie.heapBytes}")
            }

            val freq = File(dir, "he_freq.bin.gz").inputStream().use { HebrewFrequency.load(it) }
            if (stage == "frequency") return ok(stage, "freq=${freq.hashCode() != 0}")

            val bigrams = File(dir, "he_bigrams.bin.gz").inputStream().use { BigramModel.load(it) }
            ok(stage, "bigramFloor=${bigrams.minimumLogCount()}")
        } catch (e: OutOfMemoryError) {
            println("OOM")
        }
    }

    private fun ok(stage: String, note: String) {
        // Touch the structures once so a clever JIT cannot elide the loads being measured.
        println("OK $stage $note max=${Runtime.getRuntime().maxMemory() / 1048576}m")
    }
}

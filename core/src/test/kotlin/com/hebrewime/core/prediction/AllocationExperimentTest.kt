package com.hebrewime.core.prediction

import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * B1 — does reallocating the bigram table's bytes move prediction?
 *
 * The table is 59% of shipped assets, and its bytes are allocated in near-inverse proportion
 * to where prediction succeeds: 426 words take 36% of it to serve the 31% of positions that
 * score worst, while `predictNextWord` reads only `continuationsOf(limit = 8)`. So a per-group
 * cap should cost that path nothing and free budget for the 14% of positions whose previous
 * word has no group at all and which score 0.00%.
 *
 * The prediction and the stopping rule were committed before the first variant was built; see
 * `docs/PREDICTION_MEASUREMENTS.md` B1. The answer is that the lever is real and tiny.
 *
 * The variants are NOT committed — 5 MB of derived binaries — so this skips over any that are
 * absent and prints which. Rebuild them with the commands in the doc.
 */
class AllocationExperimentTest {
    @Test fun compareAllocations() {
        if (System.getProperty("runConfusionSweep").isNullOrEmpty()) return
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val words = ArrayList<String>(lexicon.size)
        for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))
        val trie = LexiconTrie.build(words)
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        val raw = GZIPInputStream(
            File(File(System.getProperty("eval.dir")!!), "hewiki_eval_sample.txt.gz")
                .inputStream()).use { it.readBytes() }
        val sample = raw.toString(Charsets.UTF_8).split('\n')
            .filter { it.isNotBlank() }.map { it.split(' ') }

        val root = File(System.getProperty("lexicon.file")!!).parentFile.parentFile
        val variants = listOf(
            "shipped  (mc5, no cap)" to File(root, "assets/he_bigrams.bin.gz"),
            "cap32 / mc3" to File(root, "experimental/he_bigrams_cap32_mc3.bin.gz"),
            "cap8  / mc2" to File(root, "experimental/he_bigrams_cap8_mc2.bin.gz"),
            "cap64 / mc5 (no new grps)" to File(root, "experimental/he_bigrams_cap64_mc5.bin.gz"),
            "cap64 / mc4" to File(root, "experimental/he_bigrams_cap64_mc4.bin.gz"),
        )
        println("=".repeat(94))
        println("B1 ALLOCATION EXPERIMENT -- same slice, same harness, bigramWeight 2.0")
        println("%-24s %8s %8s %8s %8s %8s %8s %8s".format(
            "table", "groups", "next-1", "next-3", "offer", "p1-top3", "p2-top3", "p3-top3"))
        for ((name, file) in variants) {
            if (!file.isFile) { println("%-24s  not on disk; rebuild per docs B1".format(name)); continue }
            val bigrams = file.inputStream().use { BigramModel.load(it) }
            val engine = PredictiveEngine(lexicon, trie, frequency, bigrams,
                CorrectionEngine(lexicon, trie, frequency, NeutralCostModel,
                    CorrectionEngine.Config()))
            var n1 = 0; var n3 = 0; var na = 0; var offered = 0
            val c3 = IntArray(4); val ca = IntArray(4)
            for (s in sample) {
                for (i in 1 until s.size) {
                    val prev = s[i - 1]; val target = s[i]
                    if (target.length < 3) continue
                    if (na < 20000) {
                        na++
                        val p = engine.predict("", prev)
                        if (p.isNotEmpty()) offered++
                        if (p.firstOrNull()?.word == target) n1++
                        if (p.any { it.word == target }) n3++
                    }
                    for (k in 1..3) {
                        if (target.length <= k) continue
                        if (ca[k] >= 20000) continue
                        ca[k]++
                        if (engine.predict(target.substring(0, k), prev)
                                .any { it.word == target }) c3[k]++
                    }
                }
            }
            println("%-24s %8d %7.2f%% %7.2f%% %7.2f%% %7.2f%% %7.2f%% %7.2f%%".format(
                name, bigrams.groupCount, 100.0*n1/na, 100.0*n3/na, 100.0*offered/na,
                100.0*c3[1]/ca[1], 100.0*c3[2]/ca[2], 100.0*c3[3]/ca[3]))
        }
        println("=".repeat(94))
    }
}

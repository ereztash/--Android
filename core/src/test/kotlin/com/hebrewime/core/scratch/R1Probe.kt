package com.hebrewime.core.scratch

import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.lexicon.HebrewText
import java.io.File

/**
 * `R1` — the shipped correction path, run against fifteen messages a person actually wrote.
 *
 * Runs the **shipped configuration**: `NeutralCostModel` with default `Config`, which is what
 * `CorrectionController` constructs. Not the adjacency model — `CORRECTION_MEASUREMENTS.md`
 * finding 1 measured that as 8 points of top-1 worse and it is deliberately off.
 *
 * The two positive controls run **before** the fifteen. If either fails, nothing below it counts.
 */
object R1Probe {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args[0])
        val assets = File(args[1])
        val lexicon = File(assets, "he_lexicon.txt.gz").inputStream().use { HebrewLexicon.load(it) }
        val frequency = File(assets, "he_freq.bin.gz").inputStream().use { HebrewFrequency.load(it) }
        val engine = CorrectionEngine(
            lexicon = lexicon,
            trie = LexiconTrie.build(lexicon.asWordList()),
            frequency = frequency,
            costs = NeutralCostModel,
            config = CorrectionEngine.Config(),
        )

        // ---- PC-1: the harness can see an error -----------------------------------------
        // `מקלדת` is spelled correctly in message 15. Corrupt one letter and the engine must
        // report it. A silent harness measures nothing.
        val pc1Typed = "מקלדת".replace('ק', 'ר')
        val pc1 = engine.suggest(pc1Typed)
        val pc1Red = pc1.isNotEmpty()
        println("PC-1 injected error '$pc1Typed' -> ${pc1.size} suggestions : " +
            if (pc1Red) "RED (harness sees errors)" else "GREEN — NOT-A-CHECK, stop here")

        // ---- PC-2: the harness does not invent errors -------------------------------------
        val pc2 = engine.suggest("מקלדת")
        val pc2Silent = pc2.isEmpty()
        println("PC-2 correct word 'מקלדת'  -> ${pc2.size} suggestions : " +
            if (pc2Silent) "SILENT (does not invent)" else "FIRED — probe measures noise, stop here")
        if (!pc1Red || !pc2Silent) { println("\nCONTROLS FAILED. No result below this line counts."); return }

        // ---- the twenty-five --------------------------------------------------------------
        println("\n%-12s %-12s %-9s %s".format("typed", "intended", "verdict", "what the engine offered"))
        println("-".repeat(78))
        var top1 = 0; var top3 = 0; var silent = 0; var missed = 0
        val reasons = HashMap<String, Int>()
        for (line in File(dir, "items.tsv").readLines()) {
            val (_, typed, intent, _) = line.split("\t").let { listOf(it[0], it[1], it[2], it[3]) }
            val s = engine.suggest(typed)
            val words = s.map { it.word }
            // suggest() has THREE early returns and they are not the same finding.
            val norm = HebrewText.stripPoints(typed)
            val why = when {
                !HebrewText.isHebrewWord(norm) -> "not-hebrew"
                norm.length < 3 -> "too-short"
                engine.isValid(norm) -> "already-valid"
                else -> ""
            }
            val verdict = when {
                s.isEmpty() -> { silent++; "SILENT" }
                words.firstOrNull() == intent -> { top1++; top3++; "top-1" }
                words.take(3).contains(intent) -> { top3++; "top-3" }
                else -> { missed++; "missed" }
            }
            println("%-12s %-12s %-9s %s".format(typed, intent, verdict,
                if (s.isEmpty()) "(silent: $why)" else words.take(3).joinToString(", ")))
            if (s.isEmpty()) reasons[why] = (reasons[why] ?: 0) + 1
        }
        println("\ntop-1 %d/25   top-3 %d/25   SILENT %d/25   missed %d/25"
            .format(top1, top3, silent, missed))
        println("why silent: " + reasons.entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.key}=${it.value}" })

        // ---- prediction 1: characters the lexicon cannot represent -------------------------
        var withForeign = 0
        for (m in File(dir, "messages.txt").readLines()) {
            if (m.any { it !in 'א'..'ת' && !it.isWhitespace() && it !in ".,?!" }) withForeign++
        }
        println("\nP1: messages containing a character the lexicon cannot represent: $withForeign/15")

        // ---- prediction 2: OOV among correctly-spelled tokens ------------------------------
        val errorForms = File(dir, "items.tsv").readLines().map { it.split("\t")[1] }.toSet()
        var correct = 0; var oov = 0
        for (m in File(dir, "messages.txt").readLines()) {
            for (raw in m.split(" ", "\n")) {
                val t = raw.trim('.', ',', '?', '!', ':')
                if (t.isEmpty() || t in errorForms) continue
                if (!HebrewText.isHebrewWord(HebrewText.stripPoints(t))) continue
                correct++
                if (!engine.isValid(HebrewText.stripPoints(t))) oov++
            }
        }
        println("P2: out-of-lexicon among correctly-spelled Hebrew tokens: $oov/$correct = " +
            "%.2f%%  (W7 measured 5.52%% on Ynet comments)".format(100.0 * oov / correct))
    }
}

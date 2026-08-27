package com.hebrewime.core.scratch

import com.hebrewime.core.confusion.RealWordErrorDetector
import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.lexicon.HebrewText
import com.hebrewime.core.prediction.BigramModel
import java.io.File

/** `R2` — the withdrawn real-word layer, against one dyslexic writer's real text. */
object R2Probe {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args[0]); val assets = File(args[1])
        val lexicon = File(assets, "he_lexicon.txt.gz").inputStream().use { HebrewLexicon.load(it) }
        val bigrams = File(assets, "he_bigrams.bin.gz").inputStream().use { BigramModel.load(it) }
        val frequency = File(assets, "he_freq.bin.gz").inputStream().use { HebrewFrequency.load(it) }
        val detector = RealWordErrorDetector(lexicon, bigrams)
        val engine = CorrectionEngine(lexicon, LexiconTrie.build(lexicon.asWordList()),
            frequency, NeutralCostModel, CorrectionEngine.Config())

        // PC-1: the detector must be able to fire at all. If it cannot, a recall of zero below
        // says nothing about this writer and everything about the harness.
        val pc = detector.check("הולך", "אם", "הכלב")
        println("PC-1 'אני הולך אם הכלב' -> " +
            if (pc != null) "FIRES, suggests '${pc.suggested}' (advantage ${pc.advantage})"
            else "SILENT — NOT-A-CHECK, stop here")
        if (pc == null) { println("\nCONTROL FAILED."); return }

        val intent = HashMap<String, String>()
        for (l in File(dir, "items.tsv").readLines()) {
            val p = l.split("\t"); intent[p[1]] = p[2]
        }

        var realWordErrors = 0; var caught = 0; var caughtRight = 0
        var correct = 0; var falseAlarms = 0
        val hits = StringBuilder(); val fa = StringBuilder()

        for (line in File(dir, "messages.txt").readLines()) {
            val toks = line.split(" ").map { it.trim('.', ',', '?', '!', ':') }.filter { it.isNotEmpty() }
            for (i in toks.indices) {
                val w = HebrewText.stripPoints(toks[i])
                if (!HebrewText.isHebrewWord(w)) continue
                val prev = toks.getOrNull(i - 1)?.let { HebrewText.stripPoints(it) }
                val next = toks.getOrNull(i + 1)?.let { HebrewText.stripPoints(it) }
                val f = detector.check(prev, w, next)
                val want = intent[toks[i]]
                if (want != null) {
                    // A labelled error. Only counts here if the engine is silent on it, i.e.
                    // the typed form is a valid word -- that is what P7 existed to catch.
                    if (!engine.isValid(w)) continue
                    realWordErrors++
                    if (f != null) {
                        caught++
                        if (f.suggested == want) caughtRight++
                        hits.append("   $w -> ${f.suggested}  (wanted $want, advantage ${f.advantage})\n")
                    }
                } else {
                    correct++
                    if (f != null) {
                        falseAlarms++
                        fa.append("   $w -> ${f.suggested}  (advantage ${f.advantage})\n")
                    }
                }
            }
        }
        println("\nrecall on real-word errors : $caught / $realWordErrors flagged, " +
            "$caughtRight with the right suggestion")
        if (hits.isNotEmpty()) println(hits)
        println("false alarms on correct text: $falseAlarms / $correct")
        if (fa.isNotEmpty()) println(fa)
    }
}

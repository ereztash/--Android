package com.hebrewime.core.correction

import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * W8 — spelling correction on words a person typed.
 *
 * The predictions, the control and the kill condition were committed before this file existed.
 * See `docs/CORRECTION_TYPED.md`.
 *
 * **Only the source words differ.** Corpus A draws them from held-out Wikipedia, corpus D from
 * `he_typed_raw.txt.gz`; the corruption generator, the discard rule, the seed stream, the
 * minimum length and the engine are the same code. Both corpora require their source word to be
 * **in the lexicon**, so neither measures proper nouns or slang — what differs is the frequency
 * and length profile of ordinary Hebrew words.
 */
class CorrectionRegisterTest {

    private val goldenDir = File(System.getProperty("golden.dir")!!)
    private val typedDir = File(goldenDir.parentFile, "golden_typed")

    private companion object {
        const val PUBLISHED_A_TOP1 = 52.60
        const val PUBLISHED_A_TOP3 = 66.23
        const val PC1_TOLERANCE = 0.1
    }

    private fun read(dir: File, name: String): Pair<List<String>, String> {
        val raw = GZIPInputStream(File(dir, name).inputStream()).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it) }
        return raw.toString(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() } to hash
    }

    private fun engine(): CorrectionEngine {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val words = ArrayList<String>(lexicon.size)
        for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        // The SHIPPED configuration, exactly as CorrectionAccuracyTest builds it.
        return CorrectionEngine(
            lexicon, LexiconTrie.build(words), frequency,
            NeutralCostModel, CorrectionEngine.Config(),
        )
    }

    private class Result(val n: Int, val top1: Int, val top3: Int, val none: Int,
                         val lenSum: Int) {
        val p1 get() = 100.0 * top1 / n
        val p3 get() = 100.0 * top3 / n
        val pNone get() = 100.0 * none / n
        val avgLen get() = lenSum.toDouble() / n
    }

    private fun measure(e: CorrectionEngine, lines: List<String>): Result {
        var t1 = 0; var t3 = 0; var none = 0; var len = 0
        for (line in lines) {
            val parts = line.split('\t')
            val typo = parts[0]
            val want = parts[1]
            len += want.length
            val s = e.suggest(typo)
            if (s.isEmpty()) none++
            if (s.isNotEmpty() && s[0].word == want) t1++
            if (s.take(3).any { it.word == want }) t3++
        }
        return Result(lines.size, t1, t3, none, len)
    }

    /**
     * **False auto-replace**, the quantity `docs/CORRECTION_MEASUREMENTS.md` publishes as 0.68%
     * on `C2` — the engine actually swapping a word the user typed correctly.
     *
     * The first version of this measured `!isValid(w) && suggest(w).isNotEmpty()` and called it
     * the same thing. It is not: that counts the engine *offering* something, which happens far
     * more often and is a strictly weaker event. Reported as 4.63% on `C2`, it would have looked
     * like a 7x regression against a published 0.68% that measures something else entirely.
     */
    private fun falseAutoReplace(e: CorrectionEngine, words: List<String>): Double {
        var wrong = 0
        for (w in words) {
            if (e.isValid(w)) continue
            val s = e.suggest(w)
            if (s.isNotEmpty() && e.shouldAutoReplace(s)) wrong++
        }
        return 100.0 * wrong / words.size
    }

    /** The weaker event: the engine says anything at all about a word. Reported beside it. */
    private fun offersAnything(e: CorrectionEngine, words: List<String>): Double {
        var n = 0
        for (w in words) if (!e.isValid(w) && e.suggest(w).isNotEmpty()) n++
        return 100.0 * n / words.size
    }

    @Test
    fun correctionByRegister() {
        if (System.getProperty("runCorrectionRegister").isNullOrEmpty()) {
            println("skipped; -PrunCorrectionRegister=1"); return
        }
        if (!typedDir.isDirectory) {
            println("NOT-MEASURED: ${typedDir.path} absent; run " +
                "scripts/build_golden_corpus.py --source typed --out-dir lexicon/golden_typed")
            return
        }
        val e = engine()

        println("W8 - spelling correction by source register. See docs/CORRECTION_TYPED.md.")
        println()
        val (aLines, aHash) = read(goldenDir, "a_uniform.tsv.gz")
        val (dLines, dHash) = read(typedDir, "a_uniform.tsv.gz")
        println("corpus A (wiki source)  n=${aLines.size}  sha256 ${aHash.take(16)}...")
        println("corpus D (typed source) n=${dLines.size}  sha256 ${dHash.take(16)}...")
        println()

        val a = measure(e, aLines)
        // ---- PC-1 first: this must be the measurement those numbers came from.
        val pc1 = kotlin.math.abs(a.p1 - PUBLISHED_A_TOP1) <= PC1_TOLERANCE &&
            kotlin.math.abs(a.p3 - PUBLISHED_A_TOP3) <= PC1_TOLERANCE
        println("PC-1  corpus A reproduces the published cells: top-1 %.2f%% (published %.2f%%), "
            .format(a.p1, PUBLISHED_A_TOP1) +
            "top-3 %.2f%% (published %.2f%%)  %s".format(a.p3, PUBLISHED_A_TOP3,
                if (pc1) "PASS" else "FAIL - not the same measurement; nothing below is comparable"))
        println()
        if (!pc1) return

        val d = measure(e, dLines)
        val (c2, _) = read(goldenDir, "c2_control_real.txt.gz")
        val (d2, _) = read(typedDir, "c2_control_real.txt.gz")
        val fa = falseAutoReplace(e, c2)
        val fd = falseAutoReplace(e, d2)
        val oa = offersAnything(e, c2)
        val od = offersAnything(e, d2)

        println("=".repeat(96))
        println("%-26s %10s %10s %12s %12s %14s".format(
            "source register", "top-1", "top-3", "no suggestion", "avg word len", "false AUTO-repl"))
        println("=".repeat(96))
        println("%-26s %9.2f%% %9.2f%% %11.2f%% %12.2f %13.2f%%".format(
            "A - encyclopedic (wiki)", a.p1, a.p3, a.pNone, a.avgLen, fa))
        println("%-26s %9.2f%% %9.2f%% %11.2f%% %12.2f %13.2f%%".format(
            "D - typed (Ynet comments)", d.p1, d.p3, d.pNone, d.avgLen, fd))
        println("%-26s %+9.2f %+9.2f %+11.2f %+12.2f %+13.2f".format(
            "delta", d.p1 - a.p1, d.p3 - a.p3, d.pNone - a.pNone, d.avgLen - a.avgLen, fd - fa))
        println("=".repeat(96))
        println("  beside it, the WEAKER event -- the engine offers anything on a correct word: "
            + "wiki %.2f%%, typed %.2f%%".format(oa, od))
        println()

        println("AGAINST THE PRE-REGISTERED PREDICTIONS")
        val p1 = d.p1 <= a.p1 - 3.0
        println("  W8-P1  typed top-1 at least 3 points lower      %+.2f  %s"
            .format(d.p1 - a.p1, if (p1) "HELD" else "FALSIFIED"))
        println("  W8-P2  discard rate >= 1.5x  -- reported by the BUILDER, not this harness: " +
            "wiki A discarded 1,676, typed A discarded 1,716 (x1.02)  FALSIFIED")
        val p3 = fd > fa
        println("  W8-P3  false auto-replace higher on typed        %.2f%% -> %.2f%%  %s"
            .format(fa, fd, if (p3) "HELD" else "FALSIFIED"))
        println()
        val killed = kotlin.math.abs(d.p1 - a.p1) < 1.0 && kotlin.math.abs(d.p3 - a.p3) < 1.0
        println("  KILL CONDITION: " + if (killed)
            "corpus D is within a point of A on both headline cells. Register does not matter " +
                "for correction and the published headline stands unqualified."
        else "corpus D differs from A. The published headline is register-dependent and must " +
            "say so.")
        println("=".repeat(96))
    }
}

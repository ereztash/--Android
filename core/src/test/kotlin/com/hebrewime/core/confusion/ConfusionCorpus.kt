package com.hebrewime.core.confusion

import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Builds the two corpora M11 is measured on, from a held-out slice, deterministically.
 *
 * ### Corpus D — injected real-word errors
 * Each eligible position has one letter swapped for its homophone, producing **another real
 * lexicon word**. That is the whole point: the result passes every spell check, so only
 * context can find it.
 *
 * ### Corpus E — the same text, untouched
 * The false-alarm control, and the number that actually governs the thresholds. Telling
 * someone their correct Hebrew is wrong is a worse failure than missing an error, and a recall
 * figure with no false-alarm figure beside it says nothing at all.
 *
 * ### The circularity, stated rather than hidden
 * D's errors are drawn from the same confusion inventory the detector searches, so recall here
 * answers only: *given that the error is one this detector can express, does context find it?*
 * It is **not** the fraction of real-world Hebrew mistakes that get caught — that would need a
 * corpus of genuine human errors, which this project does not have. Corpus B in M5 was the
 * lesson: a model measured against a corpus built from its own assumption reports the
 * assumption back.
 *
 * E has no such problem. It is unmodified Wikipedia text and the detector's false-alarm rate on
 * it is a real number about real sentences.
 *
 * ### A caveat that cuts the other way
 * Some injections produce a perfectly sensible sentence — swapping a letter can land on a word
 * that also fits. Those count as misses even though nothing was really wrong, so recall here
 * is a floor, not a point estimate.
 */
object ConfusionCorpus {

    /** One injected error and everything needed to score a detector on it. */
    data class Injection(
        val sentence: List<String>,
        val position: Int,
        val original: String,
        val injected: String,
        /** The letter pair that produced it, for per-pair reporting. */
        val pair: Pair<Char, Char>,
    )

    fun sentences(file: File): Pair<List<List<String>>, String> {
        val raw = GZIPInputStream(file.inputStream()).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it) }
        return raw.toString(Charsets.UTF_8).split('\n')
            .filter { it.isNotBlank() }
            .map { it.split(' ') } to hash
    }

    /**
     * Corpus D. One injection per eligible position, so the per-pair denominators reflect how
     * often each confusion is actually available in Hebrew text rather than a quota chosen
     * here.
     *
     * @param bothSides when true, only positions with a word on each side are eligible. When
     *   false, a position needs only a preceding word — the harder, left-context-only case the
     *   app faces while the user is still typing.
     */
    fun inject(
        sentences: List<List<String>>,
        lexicon: HebrewLexicon,
        pairs: List<Pair<Char, Char>> = HebrewConfusions.HOMOPHONE_PAIRS,
        bothSides: Boolean = true,
    ): List<Injection> {
        val out = ArrayList<Injection>()
        for (s in sentences) {
            val last = if (bothSides) s.size - 1 else s.size
            for (i in 1 until last) {
                val word = s[i]
                if (word.length < 2) continue
                if (lexicon.indexOf(word) < 0) continue
                for ((a, b) in pairs) {
                    for (j in word.indices) {
                        val swapped = when (word[j]) {
                            a -> b
                            b -> a
                            else -> continue
                        }
                        val variant = word.substring(0, j) + swapped + word.substring(j + 1)
                        if (variant == word || lexicon.indexOf(variant) < 0) continue
                        val corrupted = s.toMutableList().also { it[i] = variant }
                        out.add(Injection(corrupted, i, word, variant, a to b))
                    }
                }
            }
        }
        return out
    }

    /** Every position in unmodified text a detector is allowed to speak about. Corpus E. */
    data class Site(val sentence: List<String>, val position: Int)

    fun sites(
        sentences: List<List<String>>,
        lexicon: HebrewLexicon,
        bothSides: Boolean = true,
    ): List<Site> {
        val out = ArrayList<Site>()
        for (s in sentences) {
            val last = if (bothSides) s.size - 1 else s.size
            for (i in 1 until last) {
                if (s[i].length < 2) continue
                if (lexicon.indexOf(s[i]) < 0) continue
                out.add(Site(s, i))
            }
        }
        return out
    }
}

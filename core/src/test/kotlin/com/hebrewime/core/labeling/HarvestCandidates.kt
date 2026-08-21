package com.hebrewime.core.labeling

import com.hebrewime.core.confusion.HebrewConfusions
import com.hebrewime.core.confusion.RealWordErrorDetector
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * A1 tool 1 of 4 — harvests every position the **shipped** detector speaks at, plus the two
 * control pools, and writes them as JSONL for `scripts/build_label_batch.py` to sample.
 *
 * ### Why this is Kotlin and not Python
 * The labels have to describe the detector that ships. A Python re-implementation would be a
 * second detector, and every label would then be a statement about a program no user runs —
 * the exact failure this repository keeps catching in other forms. So the harvester loads the
 * same artifacts through the same readers and calls the same entry point, in the same shape
 * production calls it: `checkWide(..., next2 = null)`, because the word two positions to the
 * right has not been typed when the keyboard runs this check.
 *
 * ### What it does NOT do
 * No sampling, no shuffling, no answer key. It emits facts; `build_label_batch.py` owns every
 * random decision and the seed that reproduces them. Splitting it this way means the seed
 * lives in exactly one place and the batch is reproducible from it.
 *
 * Run: `./gradlew :core:harvestLabelCandidates`
 */
object HarvestCandidates {

    /** How many of each control pool to emit. The batch needs 10 of each; this leaves room. */
    private const val CONTROL_POOL = 3_000

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.getOrNull(0) ?: "labeling/candidates.jsonl")
        out.parentFile?.mkdirs()

        val lexicon = File(prop("lexicon.file")).inputStream().use { HebrewLexicon.load(it) }
        val frequency = File(prop("frequency.file")).inputStream().use { HebrewFrequency.load(it) }
        val bigrams = File(prop("bigram.file")).inputStream().use { BigramModel.load(it) }
        val skip = File(prop("skipgram.file")).inputStream().use { BigramModel.load(it) }

        // The SHIPPED configuration, with no overrides. If a default moves, this moves with it.
        val shipped = RealWordErrorDetector(lexicon, bigrams, skip, frequency)
        // The two ablations, used only to label which evidence path spoke. Not part of the
        // batch; recorded so a low precision can be attributed rather than guessed at.
        val adjacentOnly = RealWordErrorDetector(lexicon, bigrams)
        val skipNoPrior = RealWordErrorDetector(lexicon, bigrams, skip, null)

        val corpus = File(prop("subtitle.heldout.file"))
        val bytes = GZIPInputStream(corpus.inputStream()).use { it.readBytes() }
        // The DECOMPRESSED blob, which is what SUBTITLE_MANIFEST.json hashes. Hashing the .gz
        // instead would disagree with the manifest and would not even be stable across
        // re-compressions, since gzip stamps an mtime into its header.
        val corpusSha = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        val sentences = bytes.toString(Charsets.UTF_8).split('\n')
            .filter { it.isNotBlank() }.map { it.split(' ') }

        var words = 0
        var eligible = 0
        val firings = ArrayList<String>()
        val clean = ArrayList<String>()
        val injected = ArrayList<String>()

        for ((sentenceIndex, s) in sentences.withIndex()) {
            words += s.size
            for (i in 1 until s.size - 1) {
                val w = s[i]
                if (w.length < 2) continue
                if (lexicon.indexOf(w) < 0) continue
                val variants = HebrewConfusions.variantsOf(w, lexicon)
                if (variants.isEmpty()) continue
                eligible++

                val finding = shipped.checkWide(
                    s.getOrNull(i - 2), s[i - 1], w, s[i + 1], null,
                )
                if (finding != null) {
                    val path = when {
                        adjacentOnly.check(s[i - 1], w, s[i + 1]) != null -> "adjacent"
                        skipNoPrior.checkWide(
                            s.getOrNull(i - 2), s[i - 1], w, s[i + 1], null) != null -> "distance-2"
                        else -> "prior"
                    }
                    firings.add(
                        record("firing", sentenceIndex, i, s, w, finding.suggested, path,
                            finding.advantage, finding.contextWords)
                    )
                    continue
                }

                // CLEAN CONTROL: the detector stayed silent here. The word in the text is the
                // answer, and the distractor is a real lexicon word one homophone away — so the
                // item is indistinguishable on screen from a real firing.
                if (clean.size < CONTROL_POOL && (sentenceIndex + i) % 97 == 0) {
                    clean.add(
                        record("clean", sentenceIndex, i, s, w,
                            lexicon.wordAt(variants.first()), "none")
                    )
                    continue
                }

                // INJECTED CONTROL: we corrupt the word and keep the item only when the shipped
                // detector recovers the original. The answer is then known AND the position is
                // known to be decidable, which is what a control has to be.
                if (injected.size < CONTROL_POOL && (sentenceIndex + i) % 89 == 0) {
                    val wrong = lexicon.wordAt(variants.first())
                    val corrupted = s.toMutableList().also { it[i] = wrong }
                    val recovered = shipped.checkWide(
                        corrupted.getOrNull(i - 2), corrupted[i - 1], wrong,
                        corrupted[i + 1], null,
                    )
                    if (recovered != null && recovered.suggested == w) {
                        injected.add(
                            record("injected", sentenceIndex, i, corrupted, wrong, w, "known")
                        )
                    }
                }
            }
        }

        out.bufferedWriter().use { fh ->
            fh.write(
                """{"kind":"manifest","corpus":"${corpus.name}","corpus_sha256":"$corpusSha",""" +
                    """"sentences":${sentences.size},"words":$words,"eligible_sites":$eligible,""" +
                    """"firings":${firings.size},"clean_pool":${clean.size},""" +
                    """"injected_pool":${injected.size},""" +
                    """"detector":"shipped defaults, checkWide with next2=null"}"""
            )
            fh.newLine()
            for (line in firings) { fh.write(line); fh.newLine() }
            for (line in clean) { fh.write(line); fh.newLine() }
            for (line in injected) { fh.write(line); fh.newLine() }
        }

        println("corpus       : ${corpus.name} sha256 $corpusSha")
        println("sentences    : ${sentences.size}, words $words, eligible sites $eligible")
        println("firings      : ${firings.size}  (%.2f per 1,000 words)"
            .format(1000.0 * firings.size / words))
        println("clean pool   : ${clean.size}")
        println("injected pool: ${injected.size}")
        println("wrote        : ${out.path}")
    }

    /**
     * One candidate.
     *
     * [typed] is always the word standing in the sentence as shown, and [other] is always the
     * word offered against it. [advantage] is the finding's evidence margin, recorded so a
     * labelled batch can be re-swept over `Config.margin` offline without labelling anything
     * again — the labels are a fixed asset and every threshold question they can answer for
     * free should be asked of them before another hour of anyone's time is spent. Neither field says which is correct — that differs by stratum
     * and is resolved in `scripts/score_labels.py` from the stratum, so no single field ever
     * means two things.
     */
    private fun record(
        kind: String,
        sentenceIndex: Int,
        position: Int,
        sentence: List<String>,
        typed: String,
        other: String,
        path: String,
        advantage: Int = -1,
        contextWords: Int = -1,
    ): String = buildString {
        append("""{"kind":"$kind","id":"$kind-$sentenceIndex-$position",""")
        append(""""sentence":""")
        append(sentence.joinToString(",", "[", "]") { json(it) })
        append(""","position":$position,"typed":""").append(json(typed))
        append(""","other":""").append(json(other))
        append(""","path":"$path","advantage":$advantage,""")
        append(""""context_words":$contextWords}""")
    }

    /** Minimal JSON string escaping. :core has no dependencies and is not getting one for this. */
    private fun json(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    private fun prop(name: String): String = System.getProperty(name)
        ?: error("missing system property $name; run through the Gradle task")
}

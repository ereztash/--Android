package com.hebrewime.core.learning

import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * The simulated-user protocol, and an honest account of what it is not.
 *
 * ### What it does
 * A slice is 120 contiguous blocks of 80 sentences. One block is one pseudo-user. The first
 * *H* sentences are replayed as "what this person has typed before" — recorded into a fresh
 * [UserNgramModel], one session per sentence — and next-word accuracy is measured on the
 * remaining sentences, against the static model on the **identical** split.
 *
 * ### A Wikipedia article is not a person
 * This is the protocol's central limitation and it is not argued away. A block of contiguous
 * encyclopedia sentences shares a topic, which is the thing an adaptive layer can exploit, and
 * that is why blocks were used instead of the strided slices. But a person is not a topic:
 * real typing repeats greetings, names, verbs of address and idiom at rates no encyclopedia
 * approaches, and it repeats them *across* topics rather than within one.
 *
 * The direction of the resulting bias is **UNVERIFIED**. It is tempting to assume this
 * understates the benefit — real users repeat themselves more — but an encyclopedia's topical
 * vocabulary is also unusually predictable within a block, which pushes the other way. Nothing
 * here measures which effect is larger, so nothing here claims one.
 *
 * ### One sentence, one session
 * [UserNgramModel.minimumSessions] counts separate sessions, and a session in the app is one
 * focused field. The proxy here is one sentence. That is generous to the model: a real field
 * often holds several sentences, so a pair repeated within one message would count once in the
 * app and twice here. Stated rather than smoothed over, and it means the eligibility protection
 * is *weaker* in this simulation than in the product, never stronger.
 */
object SimulatedUser {

    class Block(val sentences: List<List<String>>)

    fun load(evalDir: File, name: String, sentencesPerUser: Int): Pair<List<Block>, String> {
        val raw = GZIPInputStream(File(evalDir, name).inputStream()).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it) }
        val all = raw.toString(Charsets.UTF_8).split('\n')
            .filter { it.isNotBlank() }
            .map { it.split(' ') }
        return all.chunked(sentencesPerUser).filter { it.size == sentencesPerUser }
            .map { Block(it) } to hash
    }

    /** Counts for one measurement cell. Offer rate is reported beside hit rate, never alone. */
    class Score {
        var attempts = 0
        var offered = 0
        var top1 = 0
        var top3 = 0
        fun pct(x: Int) = if (attempts == 0) 0.0 else 100.0 * x / attempts
        override fun toString() =
            "top1 %.2f%%  top3 %.2f%%  offered %.2f%%  n=%d".format(
                pct(top1), pct(top3), pct(offered), attempts,
            )
    }

    /**
     * Replay [historySentences] of each block into a fresh model, then score the rest.
     *
     * @param predict given (previousWord, userModel) returns the ranked next-word suggestions.
     */
    fun run(
        blocks: List<Block>,
        lexicon: HebrewLexicon,
        historySentences: Int,
        minimumSessions: Int,
        predict: (previous: String, model: UserNgramModel) -> List<String>,
    ): Score {
        val score = Score()
        for (block in blocks) {
            val model = UserNgramModel(minimumSessions = minimumSessions)
            for (sentence in block.sentences.take(historySentences)) {
                for (i in 1 until sentence.size) {
                    model.record(idOf(lexicon, sentence[i - 1]), idOf(lexicon, sentence[i]))
                }
                // One sentence, one session. See the class docs for why that is generous.
                model.endSession()
            }
            for (sentence in block.sentences.drop(historySentences)) {
                for (i in 1 until sentence.size) {
                    val target = sentence[i]
                    score.attempts++
                    val out = predict(sentence[i - 1], model)
                    if (out.isNotEmpty()) score.offered++
                    if (out.firstOrNull() == target) score.top1++
                    if (target in out) score.top3++
                }
            }
        }
        return score
    }

    /**
     * The id a token is learned under: its lexicon index, or the sentinel.
     *
     * This is the single place the OOV decision is applied in the simulation, and it mirrors
     * what the app does — so the measured numbers include the cost of collapsing unknown words,
     * rather than measuring a model the product does not have.
     */
    fun idOf(lexicon: HebrewLexicon, word: String): Int {
        val index = lexicon.indexOf(word)
        return if (index >= 0) index else UserNgramModel.OOV
    }
}

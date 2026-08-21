package com.hebrewime.core.prediction

import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.learning.UserNgramModel
import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `Prediction.fromUserModel` must mean **caused**, not **touched**.
 *
 * The settings screen shows this count to answer "what did the learning do for me". Almost
 * every completion picks up some personal evidence once a person has typed for a while, so a
 * flag meaning "the user model contributed to the score" would be true of nearly everything and
 * the screen would be reporting activity rather than benefit. That is the vanity metric the
 * pair count already is, and the reason this exists.
 *
 * So the flag is a counterfactual: set only when the suggestion would not have been on screen
 * at all with the user model removed. These tests pin both directions, because a flag that is
 * never true and a flag that is always true are equally useless and look identical in a count.
 */
class UserModelAttributionTest {

    private fun fixture(
        model: UserNgramModel,
        limit: Int = PredictiveEngine.Config().limit,
    ): Pair<PredictiveEngine, HebrewLexicon> {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val words = ArrayList<String>(lexicon.size)
        for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))
        val trie = LexiconTrie.build(words)
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        return PredictiveEngine(
            lexicon, trie, frequency, bigrams,
            CorrectionEngine(lexicon, trie, frequency, NeutralCostModel,
                CorrectionEngine.Config()),
            userModel = model,
            config = PredictiveEngine.Config(limit = limit),
        ) to lexicon
    }

    /** With nothing learned — the default install — the flag is never set. */
    @Test
    fun anEmptyModelNeverClaimsCredit() {
        val (engine, _) = fixture(UserNgramModel.empty())
        var seen = 0
        for (prefix in listOf("ש", "מ", "הת", "בי", "אנ", "לה")) {
            for (p in engine.predict(prefix, null)) {
                seen++
                assertFalse(
                    p.fromUserModel,
                    "an empty user model was credited for '${p.word}' from prefix '$prefix'",
                )
            }
        }
        assertTrue(seen > 5, "too few predictions to have tested anything: $seen")
    }

    /**
     * POSITIVE CONTROL. Teach the model a word that the shipped ranking does not offer, and the
     * flag must appear — otherwise `anEmptyModelNeverClaimsCredit` passes for the trivial
     * reason that nothing is ever flagged.
     */
    @Test
    fun aWordTheModelPushedOntoTheStripIsCredited() {
        val prefix = "מת"
        // The word ranked just BELOW the cut. The user model's contribution is bounded --
        // userWeight x userEvidenceCap, 2.0 x 32 = 64 points -- so a word far down the list
        // cannot be lifted however often it is typed, by design. Picking the first word that
        // simply is not offered would be testing that bound, not the attribution.
        val (wide, lexicon) = fixture(UserNgramModel.empty(), limit = 8)
        val ranked = wide.predict(prefix, null)
        val shipped = PredictiveEngine.Config().limit
        assertTrue(ranked.size > shipped, "not enough candidates from '$prefix' to have a 4th")
        // STRICTLY below the cut, not merely after it. A word that ties with third place is
        // ordered by a tie-break rather than by score, and "would it have been shown" has no
        // determinate answer for it -- which is a property of ties, not of the attribution.
        val cut = ranked[shipped - 1].score
        val target = ranked.drop(shipped).firstOrNull { it.score < cut }?.word
        assertTrue(
            target != null,
            "every candidate below the cut ties with it; no unambiguous target. Scores: " +
                ranked.joinToString { "${it.word}=${"%.1f".format(it.score)}" },
        )

        // Eligibility is SESSIONS, not repetitions: a burst in one field is one session however
        // many times it repeats. So this has to come back across separate sessions, exactly as
        // a real user would.
        val taught = UserNgramModel.empty()
        val id = lexicon.indexOf(target)
        repeat(taught.minimumSessions + 1) {
            repeat(20) { taught.recordWord(id) }
            taught.endSession()
        }
        val (engine, _) = fixture(taught)
        val after = engine.predict(prefix, null)

        val promoted = after.firstOrNull { it.word == target }
        assertTrue(
            promoted != null,
            "teaching '$target' across ${taught.minimumSessions + 1} sessions did not lift it " +
                "onto the strip. Ranked scores were " +
                ranked.joinToString { "${it.word}=${"%.1f".format(it.score)}" } +
                " and the model can add at most " +
                "${PredictiveEngine.Config().userWeight * PredictiveEngine.Config().userEvidenceCap}",
        )
        assertTrue(
            promoted.fromUserModel,
            "'$target' was pushed onto the strip by the user model and was not credited",
        )
    }

    /**
     * The other direction, and the one that separates *caused* from *touched*: a word the
     * shipped ranking already offered stays uncredited even after the model has seen it.
     */
    @Test
    fun aWordThatWasAlreadyOfferedIsNotCredited() {
        val (plain, lexicon) = fixture(UserNgramModel.empty())
        val prefix = "מת"
        val alreadyTop = plain.predict(prefix, null).first().word

        val taught = UserNgramModel.empty()
        val id = lexicon.indexOf(alreadyTop)
        repeat(taught.minimumSessions + 1) {
            repeat(20) { taught.recordWord(id) }
            taught.endSession()
        }
        val (engine, _) = fixture(taught)

        val again = engine.predict(prefix, null).first { it.word == alreadyTop }
        assertFalse(
            again.fromUserModel,
            "'$alreadyTop' was already the top suggestion without the model; crediting the " +
                "model for it would report activity as benefit",
        )
    }
}

package com.hebrewime.core.correction

import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.lexicon.HebrewText
import com.hebrewime.core.lexicon.PrefixStripper
import com.hebrewime.core.lexicon.WordSet

/** One ranked candidate. */
data class Suggestion(
    val word: String,
    val wordIndex: Int,
    /** Edit cost in hundredths; see [EditCostModel.UNIT]. */
    val cost: Int,
    /** Log-scaled unigram frequency, 0..255. Zero means valid but unattested. */
    val logFrequency: Int,
    /** Ranking score. Lower is better. */
    val score: Double,
)

/**
 * Turns a typed word into ranked corrections.
 *
 * ### The first question is always "is this already a word?"
 * A correction engine that does not begin by asking whether the input needs correcting will
 * happily replace correct text, which is the failure users actually punish. Validity is
 * decided by [PrefixStripper], so a legitimately prefixed form such as *and-the-book* counts
 * as valid without needing its own lexicon entry.
 *
 * ### On weights
 * [Config] holds every tunable. The defaults are the **neutral baseline**: no frequency
 * weighting at all, frequency used only to break ties between equal-cost candidates, and
 * auto-replace restricted to the unambiguous case of a single candidate one edit away. Those
 * defaults were the configuration the first measurement was taken with, before any weight was
 * chosen. See `docs/CORRECTION_MEASUREMENTS.md` for the baseline and the tuned numbers side
 * by side.
 */
class CorrectionEngine(
    private val lexicon: HebrewLexicon,
    private val trie: LexiconTrie,
    private val frequency: HebrewFrequency,
    private val costs: EditCostModel = NeutralCostModel,
    private val config: Config = Config(),
    /**
     * Extra words that count as correct: the user's personal dictionary.
     *
     * Kept as a separate [WordSet] rather than merged into [lexicon] because the two are not
     * the same kind of thing. The lexicon is a fixed, hash-locked artifact that every
     * measurement in `docs/CORRECTION_MEASUREMENTS.md` was taken against; the personal
     * dictionary is small, mutable, per-user, and never appears in any accuracy figure. Merging
     * them would make every measured number a claim about a set that varies per device.
     *
     * Empty by default, so a `CorrectionEngine` built without one behaves exactly as it did
     * before this parameter existed — which is what keeps the M5 numbers meaning what they say.
     */
    private val personal: WordSet = WordSet { false },
) {

    data class Config(
        /** Search budget, in whole edits. */
        val maxEdits: Int = 2,
        /** Cap on candidates pulled out of the trie before ranking. */
        val candidateLimit: Int = LexiconTrie.DEFAULT_LIMIT,
        /** How many suggestions to return. */
        val suggestionCount: Int = 3,
        /** Minimum stem for [PrefixStripper]; see its docs for why this is 4. */
        val minStem: Int = PrefixStripper.DEFAULT_MIN_STEM,
        /**
         * How much a fully-attested word may outrank a cheaper edit, in hundredths of an edit.
         *
         * Zero is the neutral baseline: frequency then only breaks ties between candidates of
         * identical cost. A positive value lets a common word beat a rarer one that is closer
         * in edit distance.
         */
        val frequencyWeight: Double = 0.0,
        /**
         * Below this cost a single unambiguous candidate may replace the typed word outright.
         * The neutral baseline is one edit.
         */
        val autoReplaceMaxCost: Int = EditCostModel.UNIT,
        /**
         * How far ahead of the runner-up the best candidate must be before replacing without
         * asking. Zero in the baseline, meaning only a *sole* candidate qualifies.
         */
        val autoReplaceMargin: Int = 0,
        /** Never replace a word with one that has never been seen in the corpus. */
        val autoReplaceRequiresAttested: Boolean = true,
        /**
         * Optional second cost model used ONLY to re-rank the retrieved candidates.
         *
         * Retrieval and ranking are separated deliberately, and the separation was forced by a
         * measurement. Feeding the keyboard-adjacency model straight into the trie search
         * *widens* the candidate set: discounting an adjacent substitution to 0.6 of an edit
         * lets three such substitutions fit inside a two-edit budget, and Hebrew rows put many
         * common letters side by side. Measured on the unbiased corpus that dropped top-1 from
         * 52.60% to 44.63% and multiplied wrong auto-replacements by eight.
         *
         * Applying the same model here instead leaves the candidate SET exactly as the neutral
         * search found it, and only reorders within it. See docs/CORRECTION_MEASUREMENTS.md.
         */
        val rankingCosts: EditCostModel? = null,
        /** Words this short are left alone; too many are one edit from too much. */
        val minimumLengthToCorrect: Int = 3,
    )

    /**
     * True when the word needs no correction at all.
     *
     * Consults the personal dictionary as well as the lexicon. A word the user deliberately
     * added and is then told is misspelled is worse than no personal dictionary at all: it
     * offers a setting that does nothing and contradicts itself on screen.
     */
    fun isValid(word: String): Boolean =
        PrefixStripper.accepts(word, validWords, config.minStem)

    /**
     * The lexicon and the personal dictionary as one membership test, so prefix stripping
     * applies to user-added words too — `ולדני` is accepted once `דני` is.
     */
    private val validWords = WordSet { lexicon.contains(it) || personal.contains(it) }

    /**
     * Ranked corrections, best first. Empty when the word is already valid, too short, or not
     * Hebrew.
     */
    fun suggest(typed: String): List<Suggestion> {
        val normalized = HebrewText.stripPoints(typed)
        if (!HebrewText.isHebrewWord(normalized)) return emptyList()
        if (normalized.length < config.minimumLengthToCorrect) return emptyList()
        if (isValid(normalized)) return emptyList()

        val budget = config.maxEdits * EditCostModel.UNIT
        val matches = trie.search(normalized, budget, costs, config.candidateLimit)
        if (matches.isEmpty()) return emptyList()

        val ranking = config.rankingCosts
        return matches
            .map { match ->
                val freq = frequency.logFrequencyOf(match.wordIndex)
                val word = lexicon.wordAt(match.wordIndex)
                // Re-scoring uses the retrieved candidate only, so the set is unchanged.
                val rankCost =
                    if (ranking == null) match.cost
                    else DamerauLevenshtein.distance(normalized, word, ranking)
                Suggestion(
                    word = word,
                    wordIndex = match.wordIndex,
                    cost = rankCost,
                    logFrequency = freq,
                    score = score(rankCost, freq),
                )
            }
            .sortedWith(compareBy({ it.score }, { -it.logFrequency }, { it.wordIndex }))
            .take(config.suggestionCount)
    }

    /**
     * Score, lower better.
     *
     * With [Config.frequencyWeight] at zero this is exactly the edit cost, and the sort's
     * secondary key makes frequency a pure tie-break. A positive weight subtracts up to
     * `frequencyWeight` hundredths for a maximally common word, letting frequency overturn a
     * cost difference smaller than that.
     */
    private fun score(cost: Int, logFrequency: Int): Double =
        cost - config.frequencyWeight * (logFrequency / MAX_LOG_FREQUENCY)

    /**
     * Whether the best suggestion may replace the typed word **without the user choosing it**.
     *
     * Deliberately conservative. A wrong auto-replacement destroys text the user meant, and
     * they may not notice until later; a missing auto-replacement costs one tap on the
     * candidate strip. The two errors are not symmetric and the thresholds do not treat them
     * as though they were.
     */
    fun shouldAutoReplace(suggestions: List<Suggestion>): Boolean {
        val best = suggestions.firstOrNull() ?: return false
        if (best.cost > config.autoReplaceMaxCost) return false
        if (config.autoReplaceRequiresAttested && best.logFrequency == 0) return false
        val runnerUp = suggestions.getOrNull(1) ?: return true
        return runnerUp.cost - best.cost > config.autoReplaceMargin
    }

    private companion object {
        const val MAX_LOG_FREQUENCY = 255.0
    }
}

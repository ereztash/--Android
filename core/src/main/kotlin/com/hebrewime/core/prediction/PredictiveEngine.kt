package com.hebrewime.core.prediction

import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.lexicon.HebrewText

/** Why a word is being offered. The kinds are never blended into one opaque ranking. */
enum class SuggestionKind {
    /** The user is mid-word and this finishes it. */
    COMPLETION,

    /** The word as typed is not in the lexicon and this is what they probably meant. */
    CORRECTION,

    /** The word is finished and this is what usually comes next. */
    NEXT_WORD,
}

data class Prediction(
    val word: String,
    val wordIndex: Int,
    val kind: SuggestionKind,
    /** Higher is better. Only comparable within a [kind]. */
    val score: Double,
)

/**
 * Completion, correction and next-word prediction over the same lexicon.
 *
 * ### Why the kinds stay separate
 * A completion and a correction answer different questions — "what am I still typing" versus
 * "what did I get wrong" — and their scores are not on a common scale. Blending them into one
 * number would mean inventing an exchange rate between edit distance and unigram frequency and
 * then presenting the result as though it meant something. Instead the mode is decided first,
 * from whether the typed word is a real word, and ranking happens inside that mode.
 *
 * ### The bigram model is evidence, not a gate
 * `BigramModel` returns 0 for a pair it never saw, and the model is pruned at a threshold
 * chosen for size. Absence is therefore weak evidence, not proof the pair is wrong, so a
 * bigram only ever *raises* a candidate's score. A candidate is never suppressed for lacking
 * one, or the keyboard would go silent on every phrase Wikipedia happens not to contain.
 *
 * ### Restricted fields
 * This class holds no policy. The caller checks `SessionStart.maySuggest` and simply does not
 * call it — the same structural approach as the initial-text discard, where the safest way not
 * to leak something is not to compute it.
 */
class PredictiveEngine(
    private val lexicon: HebrewLexicon,
    private val trie: LexiconTrie,
    private val frequency: HebrewFrequency,
    private val bigrams: BigramModel = BigramModel.EMPTY,
    private val corrections: CorrectionEngine,
    private val config: Config = Config(),
) {

    data class Config(
        /** How many suggestions to return in total. */
        val limit: Int = 3,
        /**
         * Shortest prefix that produces completions.
         *
         * One letter is allowed. Measured on the real lexicon a one-letter prefix has a median
         * fan-out of 7,716 and a maximum of 54,298, and a top-K walk over that costs 856 us on
         * the build host's JVM — well inside budget, and it happens off the input thread. Two
         * letters costs 19 us. The cost is stated rather than assumed because it is the reason
         * this is a knob at all.
         */
        val minPrefixForCompletion: Int = 1,
        /**
         * Weight on bigram evidence when ranking completions, in units of log-frequency.
         *
         * **Chosen after the sweep, not before it.** Measured on 20,000 completions per cell
         * from a held-out corpus proven disjoint from the bigram training data
         * (sha256 `a1c14bb9…`), top-3 accuracy by prefix length, at the shipped [Mix]:
         *
         * | weight | prefix 1 | prefix 2 | prefix 3 |
         * |---|---|---|---|
         * | 0.0 (baseline) | 2.15% | 15.80% | 38.27% |
         * | 0.5 | 4.67% | 22.52% | 45.67% |
         * | 1.0 | 5.45% | 25.09% | 48.15% |
         * | **2.0** | **5.73%** | **25.77%** | **49.28%** |
         * | 4.0 | 5.77% | 25.92% | 49.49% |
         *
         * 2.0 takes almost all of the available gain — going to 4.0 buys 0.04, 0.15 and 0.21
         * points — while leaving unigram frequency enough weight to still matter. Pushing it
         * higher would let a single Wikipedia bigram outrank a far commoner word on thin
         * evidence.
         *
         * The whole curve is flat for next-word prediction (9.80% top-3 at every weight),
         * because that path reads the bigram table directly and never mixes in a unigram
         * score for this weight to balance against.
         */
        val bigramWeight: Double = 2.0,
        /** How many trie completions to consider before ranking. */
        val completionCandidates: Int = 24,
        /**
         * What to do when the typed string is not a word in the lexicon.
         *
         * **Chosen after the sweep, not before it.** The baseline was
         * [Mix.CORRECTIONS_FIRST] — what this class did before the option existed — and it
         * turned out to be *dominated*: worse on the completion corpus **and** worse on the
         * typo corpus. `OrderingSweepTest` scores every option on both at once, because
         * optimising on either alone has an obvious degenerate answer.
         *
         * | mix | prefix 3 top-3 | typo top-1 | typo top-3 |
         * |---|---|---|---|
         * | CORRECTIONS_FIRST (baseline) | 43.52% | 52.95% | 66.68% |
         * | **COMPLETIONS_FIRST** | **49.28%** | **53.05%** | 67.28% |
         * | INTERLEAVED | 48.64% | 53.05% | 67.40% |
         *
         * Nothing was traded away: there was no trade-off to make. Against `INTERLEAVED` the
         * one column that prefers interleaving is typo top-3, by 0.12 points — 5 items out of
         * 4,000 — while completions-first wins prefix-3 by 0.64 points, 128 items out of
         * 20,000. Prefix 1 and 2 are identical under every policy because
         * `CorrectionEngine.Config.minimumLengthToCorrect` is 3, so there are no corrections
         * to order below that length.
         */
        val mix: Mix = Mix.COMPLETIONS_FIRST,
    )

    /**
     * How corrections and completions share the strip when the typed string is not a word.
     *
     * A three-letter string that is not in the lexicon is ambiguous in a way nothing in the
     * string itself resolves: it is either the start of a longer word or a misspelling of a
     * finished one. The strip has three slots and both readings want them.
     */
    enum class Mix {
        /** Corrections lead. The typed string is read as a finished, wrong word. */
        CORRECTIONS_FIRST,

        /** Completions lead. The typed string is read as an unfinished word. */
        COMPLETIONS_FIRST,

        /** Best completion, best correction, then alternating. Neither reading is starved. */
        INTERLEAVED,
    }

    // A fourth option was written and then deleted: "completions first unless the string has
    // no completions at all, in which case correct it". It scored identically to
    // COMPLETIONS_FIRST in every column, and on inspection it is the same function --
    // `if (finishes.isEmpty()) fixes else finishes + fixes` is `finishes + fixes`. Keeping it
    // would have meant an enum offering a choice that does not exist.

    /**
     * Suggestions for the current input position.
     *
     * @param currentWord what the user has typed of the word in progress. Empty means they
     *   just finished a word.
     * @param previousWord the completed word before it, if known. Null after a desync or at the
     *   start of a field, in which case next-word prediction is simply unavailable rather than
     *   guessed.
     */
    fun predict(currentWord: String, previousWord: String?): List<Prediction> {
        val word = HebrewText.stripPoints(currentWord)
        val previousIndex = previousWord
            ?.let { HebrewText.stripPoints(it) }
            ?.takeIf { HebrewText.isHebrewWord(it) }
            ?.let { lexicon.indexOf(it) }
            ?.takeIf { it >= 0 }

        if (word.isEmpty()) return nextWord(previousIndex)
        if (!HebrewText.isHebrewWord(word)) return emptyList()

        if (corrections.isValid(word)) return completions(word, previousIndex)

        // The word is not in the lexicon, so it is either misspelled or unfinished, and
        // nothing in the string says which. See [Mix].
        val fixes = corrections.suggest(word).map {
            Prediction(it.word, it.wordIndex, SuggestionKind.CORRECTION, -it.cost.toDouble())
        }
        val finishes = completions(word, previousIndex)
        val ordered = when (config.mix) {
            Mix.CORRECTIONS_FIRST -> fixes + finishes
            Mix.COMPLETIONS_FIRST -> finishes + fixes
            Mix.INTERLEAVED -> interleave(finishes, fixes)
        }
        return ordered.distinctBy { it.wordIndex }.take(config.limit)
    }

    private fun interleave(a: List<Prediction>, b: List<Prediction>): List<Prediction> {
        val out = ArrayList<Prediction>(a.size + b.size)
        var i = 0
        while (i < a.size || i < b.size) {
            if (i < a.size) out.add(a[i])
            if (i < b.size) out.add(b[i])
            i++
        }
        return out
    }

    private fun nextWord(previousIndex: Int?): List<Prediction> {
        if (previousIndex == null) return emptyList()
        return bigrams.continuationsOf(previousIndex, config.limit)
            .map { (index, logCount) ->
                Prediction(
                    lexicon.wordAt(index), index, SuggestionKind.NEXT_WORD, logCount.toDouble(),
                )
            }
    }

    private fun completions(prefix: String, previousIndex: Int?): List<Prediction> {
        if (prefix.length < config.minPrefixForCompletion) return emptyList()
        val candidates = trie.completionsTopK(
            prefix,
            config.completionCandidates,
        ) { index -> frequency.logFrequencyOf(index) }

        return candidates
            // Offering back exactly what was typed is not a suggestion.
            .filter { lexicon.wordAt(it) != prefix }
            .map { index ->
                val unigram = frequency.logFrequencyOf(index).toDouble()
                val bigram =
                    if (previousIndex == null || config.bigramWeight == 0.0) 0.0
                    else bigrams.logCountOf(previousIndex, index).toDouble()
                Prediction(
                    lexicon.wordAt(index),
                    index,
                    SuggestionKind.COMPLETION,
                    unigram + config.bigramWeight * bigram,
                )
            }
            .sortedByDescending { it.score }
            .take(config.limit)
    }
}

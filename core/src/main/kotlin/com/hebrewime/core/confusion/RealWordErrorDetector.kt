package com.hebrewime.core.confusion

import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.lexicon.HebrewText
import com.hebrewime.core.prediction.BigramModel

/**
 * Finds words that are spelled correctly and still wrong.
 *
 * `אם` where `עם` was meant is the case this exists for: two real words, one letter apart, both
 * accepted by every spell check in the project — correctly, because both are words. The
 * mistake lives in the sentence, not in the word, so the only thing that can see it is what
 * stands on either side.
 *
 * ### The asymmetry that governs every threshold here
 * A missed real-word error costs the user a typo they would probably have caught themselves.
 * A false alarm tells someone their correct Hebrew is wrong — and if it were ever allowed to
 * auto-replace, it would silently change what they said into something they did not say. Those
 * two errors are not comparable, and nothing here treats them as though they were:
 *
 * - This class **never** auto-replaces. It returns a suggestion; the user taps it or ignores it.
 * - The default configuration is the strictest one measured, not the most accurate one.
 * - The false-alarm rate on unmodified text is the number that decided the thresholds, and it
 *   is reported per-pair rather than as an aggregate that could hide one bad pair inside five
 *   good ones.
 *
 * ### Absence of evidence is used deliberately, and only in one direction
 * `BigramModel` returns 0 for a pair it never saw, and the table is pruned at a count of 5.
 * Zero therefore means "no evidence", not "wrong". [Config.requireNoSupportForTyped] is the
 * switch that says whether a word the corpus *has* seen in this context may still be flagged;
 * with it on, evidence for what the user actually typed always wins, and the detector can only
 * speak when the corpus has nothing to say for the typed word and something to say for an
 * alternative.
 */
class RealWordErrorDetector(
    private val lexicon: HebrewLexicon,
    private val bigrams: BigramModel,
    /**
     * Distance-2 co-occurrence, for the 30.2% of confusable positions where **neither**
     * candidate has any adjacent evidence and the window therefore has nothing to weigh.
     *
     * Same format as [bigrams] — a skip table *is* a table of `(first, second, count)` and only
     * the definition of "second" differs, so [BigramModel] loads it unchanged and there is no
     * second reader to keep correct.
     *
     * Empty by default. An empty table makes [checkWide] behave exactly like [check].
     */
    private val skip: BigramModel = BigramModel.EMPTY,
    /**
     * Unigram frequencies, for the blind-position fallback. Null disables it entirely.
     *
     * Measured in D5: **81.2% of blind positions are resolvable by the prior alone**, and this
     * detector abstains on all of them because bigram counts are its only evidence. See
     * [Config.priorMargin].
     */
    private val frequency: com.hebrewime.core.correction.HebrewFrequency? = null,
    private val config: Config = Config(),
) {

    data class Config(
        /**
         * How much more bigram evidence an alternative needs before it is offered, in the
         * log-count units of [BigramModel] (`round(log2(count + 1) * 8)`, so 8 units is a
         * doubling of the underlying count).
         *
         * **21 is the model's own floor, not a tuned number.** `scripts/build_bigrams.py`
         * prunes at a count of 5, and `round(log2(5 + 1) * 8)` is 21, so no stored pair can
         * carry less. Every margin from 1 to 21 is therefore the *same rule* — "the corpus has
         * seen this pair at all" — and the sweep confirms it: identical recall and identical
         * false-alarm rate across all of them, with the first change at 22.
         * `BigramFloorTest` pins the floor against the table itself.
         *
         * 21 is the value that states the rule rather than sitting arbitrarily underneath it.
         * Measured alternatives are in `docs/CONFUSION_MEASUREMENTS.md`; going to 24 trades
         * 3.3 points of recall for 0.07 points of false alarms, which is 31 missed errors per
         * false alarm avoided.
         */
        val margin: Int = 21,

        /**
         * When true, a word the corpus has seen in this context is never flagged, however much
         * evidence an alternative has.
         *
         * **A principle rather than a threshold.** The detector may speak when the corpus has
         * nothing to say about what the user typed; it may not contradict evidence the corpus
         * actually has. Measured on the dev slice it cuts the false-alarm rate from 0.62% to
         * 0.30% — a little over half — for 4.28 points of recall. The measurement supports the
         * principle; it is not what chose it.
         */
        val requireNoSupportForTyped: Boolean = true,

        /** Words shorter than this are left alone. Two, because `אם` and `עם` are two. */
        val minLength: Int = 2,

        /** Which confusion table. See [HebrewConfusions] for why `ו`/`י` is not the default. */
        val pairs: List<Pair<Char, Char>> = HebrewConfusions.HOMOPHONE_PAIRS,

        /**
         * Margin required when the decision rests on **distance-2** evidence alone.
         *
         * Separate from [margin] because the two are not the same kind of evidence and must not
         * share a threshold. Measured on the test slice, distance-2 evidence points at the
         * correct word 92.4% of the time — real signal, and the 7.6% that point the wrong way
         * would add 0.42 points of false alarms on top of a shipped rate of 0.25%, roughly
         * tripling it, if used at the adjacent margin.
         *
         * **0 disables the layer**, the same way 0 disables [priorMargin]. It has to, because
         * without a threshold the comparison `candidateSkip - typedSkip < 0` is false for a pair
         * of zeroes and every variant of every blind word becomes a finding — 13.58% false
         * alarms where the shipped rate is 0.283%. Passing an empty table does not disable it
         * either, for the same reason. Three separate sweeps published a wrong row before this
         * was made an explicit branch.
         *
         * Swept jointly with [priorMargin] on `confusion_dev`; see
         * `docs/CONFUSION_MEASUREMENTS.md`.
         */
        val skipMargin: Int = DEFAULT_SKIP_MARGIN,

        /**
         * Margin required when the decision rests on **unigram frequency alone**, at a position
         * where neither candidate has any adjacent evidence at all.
         *
         * 0 disables the fallback. See `docs/CONFUSION_MEASUREMENTS.md` P1 for the sweep and the
         * rule that was fixed before it ran.
         *
         * This is not "use frequency" — the detector still refuses to contradict corpus evidence
         * it has. It fires only where there is none.
         */
        val priorMargin: Int = DEFAULT_PRIOR_MARGIN,
    )

    /** One real-word error, with the evidence that produced it. */
    data class Finding(
        val typed: String,
        val typedIndex: Int,
        val suggested: String,
        val suggestedIndex: Int,
        /** Bigram evidence for what was typed, summed over the available neighbours. */
        val typedEvidence: Int,
        /** Bigram evidence for the suggestion, over the same neighbours. */
        val suggestedEvidence: Int,
        /** How many neighbours were actually available: 1 or 2. */
        val contextWords: Int,
    ) {
        val advantage: Int get() = suggestedEvidence - typedEvidence
    }

    /**
     * Check [word] against its neighbours.
     *
     * @param previous the word before, or null when unknown. The app passes null across a
     *   sentence boundary and after a desync — see `InputContextBuffer.previousWord`.
     * @param next the word after, or null when it has not been typed yet. Left-context-only
     *   checking is a real, weaker mode and is measured separately rather than assumed
     *   equivalent.
     * @return the best alternative, or null when there is no confusable partner, no context, or
     *   not enough evidence.
     */
    fun check(previous: String?, word: String, next: String?): Finding? {
        if (word.length < config.minLength) return null
        val typed = HebrewText.stripPoints(word)
        if (!HebrewText.isHebrewWord(typed)) return null
        val typedIndex = lexicon.indexOf(typed)
        // A word that is not in the lexicon is a job for the correction engine, not this.
        if (typedIndex < 0) return null

        val previousIndex = indexOf(previous)
        val nextIndex = indexOf(next)
        val contextWords = (if (previousIndex >= 0) 1 else 0) + (if (nextIndex >= 0) 1 else 0)
        if (contextWords == 0) return null

        val variants = HebrewConfusions.variantsOf(typed, lexicon, config.pairs)
        if (variants.isEmpty()) return null

        val typedEvidence = evidence(typedIndex, previousIndex, nextIndex)

        if (config.requireNoSupportForTyped && typedEvidence > 0) return null

        var best: Finding? = null
        for (candidate in variants) {
            val candidateEvidence = evidence(candidate, previousIndex, nextIndex)
            if (candidateEvidence - typedEvidence < config.margin) continue
            if (best != null && candidateEvidence <= best.suggestedEvidence) continue
            best = Finding(
                typed = typed,
                typedIndex = typedIndex,
                suggested = lexicon.wordAt(candidate),
                suggestedIndex = candidate,
                typedEvidence = typedEvidence,
                suggestedEvidence = candidateEvidence,
                contextWords = contextWords,
            )
        }
        return best
    }

    /**
     * Like [check], but also given the words **two** positions away on each side.
     *
     * ### The adjacent path is untouched
     * If the adjacent window can decide — either candidate has any evidence at all — this
     * delegates to [check] and returns exactly what it would have returned. Distance-2 evidence
     * is consulted **only where the adjacent window is blind**, so recall can only rise and any
     * new false alarm is attributable to the new path alone. That is a property of the
     * structure, not of the thresholds, which is what makes the stopping rule in
     * `docs/CONFUSION_MEASUREMENTS.md` decidable.
     */
    fun checkWide(
        previous2: String?,
        previous: String?,
        word: String,
        next: String?,
        next2: String?,
    ): Finding? {
        val adjacent = check(previous, word, next)
        if (adjacent != null) return adjacent

        if (word.length < config.minLength) return null
        val typed = HebrewText.stripPoints(word)
        if (!HebrewText.isHebrewWord(typed)) return null
        val typedIndex = lexicon.indexOf(typed)
        if (typedIndex < 0) return null

        // Only where the adjacent window had nothing. If it had anything at all, `check` has
        // already applied the adjacent rule and declined, and overriding that here would be
        // the skip table contradicting evidence the corpus actually has.
        val previousIndex = indexOf(previous)
        val nextIndex = indexOf(next)
        val variants = HebrewConfusions.variantsOf(typed, lexicon, config.pairs)
        if (variants.isEmpty()) return null
        if (evidence(typedIndex, previousIndex, nextIndex) > 0) return null
        if (variants.any { evidence(it, previousIndex, nextIndex) > 0 }) return null

        val p2 = indexOf(previous2)
        val n2 = indexOf(next2)

        // 0 means OFF, exactly as it does for `priorMargin`.
        //
        // It did not, and the asymmetry produced a wrong number three times: with no margin the
        // loop's `candidateSkip - typedSkip < 0` is false for a pair of zeroes, so EVERY variant
        // of every blind word became a finding — 13.58% false alarms read as "the prior alone".
        // Handing the layer an empty table does not disable it either, for the same reason. The
        // fix is here rather than in each caller, because a footgun that has fired three times
        // will fire a fourth.
        //
        // ### Order: adjacent, then distance-2, then the prior. Never the other way round.
        //
        // Distance-2 counts are still evidence ABOUT THIS SENTENCE. The unigram prior is not —
        // it is what the language does on average, with the sentence ignored. Letting a
        // context-free signal pre-empt a contextual one would invert the ordering the whole
        // class is built on, and an earlier version of this did exactly that by putting the
        // prior fallback in `check`, which runs first.
        if (config.skipMargin > 0 && (p2 >= 0 || n2 >= 0)) {
            val typedSkip = skipEvidence(typedIndex, p2, n2)
            if (!(config.requireNoSupportForTyped && typedSkip > 0)) {
                var best: Finding? = null
                for (candidate in variants) {
                    val candidateSkip = skipEvidence(candidate, p2, n2)
                    if (candidateSkip - typedSkip < config.skipMargin) continue
                    if (best != null && candidateSkip <= best.suggestedEvidence) continue
                    best = Finding(
                        typed = typed,
                        typedIndex = typedIndex,
                        suggested = lexicon.wordAt(candidate),
                        suggestedIndex = candidate,
                        typedEvidence = typedSkip,
                        suggestedEvidence = candidateSkip,
                        contextWords = (if (p2 >= 0) 1 else 0) + (if (n2 >= 0) 1 else 0),
                    )
                }
                if (best != null) return best
            }
        }

        // Last resort: no adjacent evidence, no distance-2 evidence. See Config.priorMargin.
        val f = frequency
        if (config.priorMargin <= 0 || f == null) return null
        val typedFrequency = f.logFrequencyOf(typedIndex)
        var byPrior: Finding? = null
        for (candidate in variants) {
            if (f.logFrequencyOf(candidate) - typedFrequency < config.priorMargin) continue
            if (byPrior != null && f.logFrequencyOf(candidate) <= byPrior.suggestedEvidence) continue
            byPrior = Finding(
                typed = typed,
                typedIndex = typedIndex,
                suggested = lexicon.wordAt(candidate),
                suggestedIndex = candidate,
                typedEvidence = typedFrequency,
                suggestedEvidence = f.logFrequencyOf(candidate),
                contextWords = 0,
            )
        }
        return byPrior
    }

    private fun skipEvidence(wordIndex: Int, previous2: Int, next2: Int): Int {
        var total = 0
        if (previous2 >= 0) total += skip.logCountOf(previous2, wordIndex)
        if (next2 >= 0) total += skip.logCountOf(wordIndex, next2)
        return total
    }

    private fun evidence(wordIndex: Int, previousIndex: Int, nextIndex: Int): Int {
        var total = 0
        if (previousIndex >= 0) total += bigrams.logCountOf(previousIndex, wordIndex)
        if (nextIndex >= 0) total += bigrams.logCountOf(wordIndex, nextIndex)
        return total
    }

    private fun indexOf(word: String?): Int {
        val stripped = word?.let { HebrewText.stripPoints(it) } ?: return -1
        if (!HebrewText.isHebrewWord(stripped)) return -1
        return lexicon.indexOf(stripped)
    }

    companion object {
        /**
         * **0 — the distance-2 layer is WITHDRAWN.**
         *
         * It shipped at 80 for one commit. Measured against human judgement on 320 authentic
         * firings it added 0.11 recall points over the prior fallback alone, cost 387,300 bytes
         * in the release APK, and spoke **twice in 1.8 million words** of clean conversational
         * text. `docs/LABELING_LOG.md` has the numbers.
         *
         * The code and the sweep stay so the result is reproducible; the table moved to
         * `lexicon/experimental/` and is not packaged. 80 remains the operating point if it is
         * ever re-enabled — it was chosen on `confusion_dev` and is not what failed.
         */
        const val DEFAULT_SKIP_MARGIN: Int = 0

        /**
         * The shipped blind-position prior margin, chosen on `confusion_dev` jointly with
         * [DEFAULT_SKIP_MARGIN].
         *
         * **Not the margin with the best recall.** 96 gains half a point more and costs four
         * false alarms in 69,909 clean sites; 104 is the lowest margin at which the joint
         * operating point's false-alarm rate is identical to the adjacent-only baseline. The
         * asymmetry in this class's header decides that trade, not the recall column.
         *
         * 0 disables the fallback, which is what shipped before S1+P1.
         */
        const val DEFAULT_PRIOR_MARGIN: Int = 104
    }
}

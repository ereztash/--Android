package com.hebrewime.core.prediction

import com.hebrewime.core.confusion.RealWordErrorDetector
import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.dictionary.PersonalDictionary
import com.hebrewime.core.learning.UserNgramModel
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

    /**
     * An earlier word is spelled correctly and is still wrong for its sentence.
     *
     * `אם` where `עם` was meant. Distinct from [CORRECTION] because nothing about the word
     * itself is wrong, because the evidence is the surrounding words rather than the letters,
     * and because it replaces a word that is **not** under the cursor — see
     * [Prediction.wordsBack].
     */
    REAL_WORD_ERROR,
}

data class Prediction(
    val word: String,
    val wordIndex: Int,
    val kind: SuggestionKind,
    /** Higher is better. Only comparable within a [kind]. */
    val score: Double,
    /**
     * Which word this replaces. 0 is the word under the cursor; *n* is the *n*th most recent
     * **completed** word.
     *
     * Non-zero only for [SuggestionKind.REAL_WORD_ERROR], and the caller must honour it: a
     * suggestion applied to the wrong word rewrites text the user did not ask to change.
     */
    val wordsBack: Int = 0,
    /**
     * The word this replaces, when it is not the word under the cursor.
     *
     * Set only for [SuggestionKind.REAL_WORD_ERROR]. A suggestion that silently rewrites a word
     * further up the sentence has to be able to say *which* word, or the strip is asking the
     * user to accept a change they cannot see.
     */
    val replaces: String? = null,
)

/**
 * What is known about the text around the cursor.
 *
 * Real-word error detection needs a word on each side of the one being checked, so the engine
 * needs more than the immediately preceding word — but it must never *assume* more than it
 * has. Missing entries are simply absent, and the engine does less rather than guessing.
 */
data class TypingContext(
    /** What the user has typed of the word in progress. Empty means they just finished one. */
    val current: String,
    /**
     * Completed words before it, **most recent first**. Empty when the preceding context is
     * unknown — after a desync, in a field whose initial text was withheld, or at the start of
     * a sentence.
     */
    val completed: List<String> = emptyList(),
) {
    val previous: String? get() = completed.firstOrNull()
}

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
    /**
     * Words the user added deliberately, offered as completions before lexicon words.
     *
     * ### Why they go first, and why that is not a measured claim
     * A user who typed a word into the settings screen has told the keyboard something the
     * corpus cannot: that this word belongs to *their* vocabulary. Ranking it above a word
     * Wikipedia happens to use more often follows from that, and there is no corpus of personal
     * dictionaries to check it against. **Recorded as a design decision with no measurement
     * behind it**, rather than dressed up with a weight that would look derived.
     *
     * The exposure is bounded by the user: the dictionary holds only what they put in it.
     */
    private val personal: PersonalDictionary = PersonalDictionary(),
    /**
     * What this installation has learned about how this user writes. Empty unless the user
     * turned learning on.
     *
     * ### It adjusts, it does not replace
     * The static table stays the ranking's backbone; [Config.userWeight] scales a bounded
     * contribution on top of it. A pair the user typed a handful of times must not outrank
     * strong corpus evidence outright — that is how a keyboard becomes confidently wrong about
     * someone after one unusual afternoon.
     *
     * Empty by default, and an empty model must produce **byte-identical** predictions to an
     * engine built without one. `LearningNeutralityTest` asserts that, because it is what keeps
     * every number in `docs/PREDICTION_MEASUREMENTS.md` and
     * `docs/CONFUSION_MEASUREMENTS.md` a claim about what still ships.
     */
    private val userModel: UserNgramModel = UserNgramModel.empty(),
    /**
     * Finds words that are spelled correctly and still wrong. Optional, so an engine can be
     * built without one — and so the M10 measurements can be reproduced exactly as they were
     * taken, without M11 changing them.
     */
    private val realWordErrors: RealWordErrorDetector? = null,
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
         * Weight on the learned layer, in the same log-count units as [bigramWeight].
         *
         * **Chosen on `learning_dev` and reported on `learning_test`**, which share no
         * sentence; see `docs/LEARNING_MEASUREMENTS.md` for the sweep, the static-only
         * baseline beside it, and the cold-start curve.
         *
         * 0.0 disables the layer arithmetically as well as by an empty model, so a build can
         * be compared against static-only without rebuilding anything.
         */
        val userWeight: Double = DEFAULT_USER_WEIGHT,
        /**
         * Cap on the learned layer's contribution, in log-count units before weighting.
         *
         * Without it, a pair the user happens to repeat often enough would eventually dominate
         * any corpus evidence at all. The cap is what makes "adjusts, does not replace" a
         * property of the arithmetic rather than a hope about how people type.
         */
        val userEvidenceCap: Int = DEFAULT_USER_EVIDENCE_CAP,
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
    fun predict(currentWord: String, previousWord: String?): List<Prediction> =
        predict(TypingContext(currentWord, listOfNotNull(previousWord)))

    /**
     * Suggestions for the current input position, given everything known about the context.
     *
     * A real-word error, when there is one, takes the first slot. It is the only kind here that
     * asserts something is *wrong* with text the user has already written, and burying that
     * behind two completions would mean the keyboard noticed and did not say so. It also
     * replaces a word that is not under the cursor, so the caller must honour
     * [Prediction.wordsBack].
     */
    fun predict(context: TypingContext): List<Prediction> {
        val ordinary = predictCurrentWord(context.current, context.previous)
        val flagged = realWordError(context) ?: return ordinary
        return (listOf(flagged) + ordinary).take(config.limit)
    }

    /**
     * Checks the **second** most recent completed word, whose neighbours are both known.
     *
     * Not the most recent one: at that position the right-hand neighbour has not been typed
     * yet, and left context alone is worth 44.78% recall against 64.42% with both sides. One
     * check per word, made once, at the moment the evidence is complete.
     */
    private fun realWordError(context: TypingContext): Prediction? {
        val detector = realWordErrors ?: return null
        val target = context.completed.getOrNull(1) ?: return null
        val right = context.completed.getOrNull(0)
        val left = context.completed.getOrNull(2)
        val finding = detector.check(left, target, right) ?: return null
        return Prediction(
            word = finding.suggested,
            wordIndex = finding.suggestedIndex,
            kind = SuggestionKind.REAL_WORD_ERROR,
            score = finding.advantage.toDouble(),
            wordsBack = 2,
            // [target], not `finding.typed`. The detector strips niqqud before looking a word
            // up, so `typed` is the reduced form; the caller has to delete the text that is
            // actually in the editor, which is what the user wrote. Handing back the stripped
            // form would make a pointed word fail the caller's "is this still the same text"
            // check and silently drop the suggestion.
            replaces = target,
        )
    }

    private fun predictCurrentWord(currentWord: String, previousWord: String?): List<Prediction> {
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
        // By word, not by index: personal-dictionary entries all carry [PERSONAL_WORD_INDEX]
        // and would collapse into one under distinctBy { it.wordIndex }.
        return ordered.distinctBy { it.word }.take(config.limit)
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
        // Take more static candidates than will be shown, so the learned layer can reorder
        // within a real set rather than only appending to a list already truncated to three.
        val scored = LinkedHashMap<Int, Double>()
        for ((index, logCount) in bigrams.continuationsOf(previousIndex, NEXT_WORD_CANDIDATES)) {
            scored[index] = logCount.toDouble()
        }
        if (config.userWeight != 0.0) {
            for ((index, logCount) in userModel.continuationsOf(
                previousIndex, NEXT_WORD_CANDIDATES,
            )) {
                // A learned continuation the corpus never saw is still offerable -- that is
                // most of the point -- but it enters at its own bounded weight rather than
                // being promoted to the top by default.
                scored[index] = (scored[index] ?: 0.0) + userContribution(logCount)
            }
        }
        return scored.entries
            // Drop the sentinel BEFORE truncating. Filtering afterwards would silently return
            // two suggestions instead of three whenever the strongest learned continuation
            // happened to be an unknown word -- a hole in the strip with no visible cause.
            .filter { it.key >= 0 }
            .sortedByDescending { it.value }
            .take(config.limit)
            .map { (index, score) ->
                Prediction(lexicon.wordAt(index), index, SuggestionKind.NEXT_WORD, score)
            }
    }

    /**
     * The learned layer's bounded addition to a score.
     *
     * Capped **before** weighting, so raising [Config.userWeight] scales a bounded quantity
     * rather than removing the bound.
     */
    private fun userContribution(logCount: Int): Double =
        config.userWeight * logCount.coerceAtMost(config.userEvidenceCap)

    private fun completions(prefix: String, previousIndex: Int?): List<Prediction> {
        if (prefix.length < config.minPrefixForCompletion) return emptyList()
        val mine = personalCompletions(prefix)
        val candidates = trie.completionsTopK(
            prefix,
            config.completionCandidates,
        ) { index -> frequency.logFrequencyOf(index) }

        val fromLexicon = candidates
            // Offering back exactly what was typed is not a suggestion.
            .filter { lexicon.wordAt(it) != prefix }
            .map { index ->
                val unigram = frequency.logFrequencyOf(index).toDouble()
                val bigram =
                    if (previousIndex == null || config.bigramWeight == 0.0) 0.0
                    else bigrams.logCountOf(previousIndex, index).toDouble()
                val learned =
                    if (previousIndex == null || config.userWeight == 0.0) 0.0
                    else userContribution(userModel.logCountOf(previousIndex, index))
                Prediction(
                    lexicon.wordAt(index),
                    index,
                    SuggestionKind.COMPLETION,
                    unigram + config.bigramWeight * bigram + learned,
                )
            }
            .sortedByDescending { it.score }

        return (mine + fromLexicon).distinctBy { it.word }.take(config.limit)
    }

    /**
     * Completions drawn from the personal dictionary.
     *
     * A linear scan. The dictionary is user-curated and small — every entry was typed by hand
     * into a settings screen — so a scan is cheaper than any index over it would be, and it
     * costs nothing when the dictionary is empty, which is the common case.
     */
    private fun personalCompletions(prefix: String): List<Prediction> {
        if (personal.size == 0) return emptyList()
        return personal.all()
            .filter { it.length > prefix.length && it.startsWith(prefix) }
            .take(config.limit)
            .map {
                Prediction(it, PERSONAL_WORD_INDEX, SuggestionKind.COMPLETION, PERSONAL_SCORE)
            }
    }

    companion object {
        /**
         * [Prediction.wordIndex] for a word that is not in the lexicon at all.
         *
         * Callers that index back into the lexicon must check for it. Nothing in this project
         * does — the strip only ever reads [Prediction.word] — but a `-1` handed to
         * `HebrewLexicon.wordAt` throws rather than returning a wrong word, which is the
         * failure mode to prefer.
         */
        const val PERSONAL_WORD_INDEX: Int = -1

        /**
         * Sorts above every lexicon completion, whose scores are log-frequencies capped at 255
         * plus a bounded bigram term. Not a tuned number and not comparable to one: it means
         * "the user asked for this word", which is not a point on the frequency scale.
         */
        const val PERSONAL_SCORE: Double = Double.MAX_VALUE

        /**
         * 2.0, chosen on `learning_dev` — **and deliberately not the value that scored best.**
         *
         * The sweep's peak is 8.0 (top-1 11.24% against 11.07% at 4.0 and 10.87% at 2.0).
         * It was rejected on an invariant the accuracy table cannot see. The learned layer's
         * largest possible contribution is `userWeight × userEvidenceCap`, and the static
         * score it is added to is a log-count capped at 255, so:
         *
         * | weight | max contribution | overturns corpus pairs seen up to |
         * |---|---|---|
         * | 1.0 | 32 | 15 times |
         * | **2.0** | **64** | **255 times** |
         * | 4.0 | 128 | 65,535 times |
         * | 8.0 | 256 | **anything at all** |
         *
         * At 8.0 a pair the user typed fifteen times outranks *every* corpus pair including the
         * strongest, which is not interpolation — it is replacement wearing interpolation's
         * clothes. At 4.0 it overturns pairs seen 65,535 times in a 25.6M-token corpus, which
         * is overwhelming evidence losing to fifteen observations.
         *
         * At 2.0 the ceiling is a corpus pair seen 255 times — about one occurrence in 100,000
         * tokens, genuinely weak evidence — and beating that with fifteen personal sightings is
         * exactly what "this keyboard has learned how *you* write" should mean.
         *
         * **The cost of that choice is 0.37 points of top-1 and 0.40 of top-3 against the
         * peak**, measured, stated, and paid deliberately. Full tables in
         * `docs/LEARNING_MEASUREMENTS.md`.
         */
        const val DEFAULT_USER_WEIGHT: Double = 2.0

        /**
         * 32 log-count units, which is a raw count of 15.
         *
         * Above that the learned layer stops distinguishing "this person says this" from "this
         * person says this constantly", and the extra head-room would only buy the ability to
         * bury the corpus. Not swept: it is a bound, and tuning a bound upward until it stops
         * binding is how a bound becomes decorative.
         */
        const val DEFAULT_USER_EVIDENCE_CAP: Int = 32

        /** Static candidates pulled before the learned layer reorders them. */
        private const val NEXT_WORD_CANDIDATES: Int = 16
    }
}

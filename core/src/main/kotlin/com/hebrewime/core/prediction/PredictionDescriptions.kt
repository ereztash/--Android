package com.hebrewime.core.prediction

/**
 * Spoken descriptions for suggestions.
 *
 * ### Why the kind has to be spoken, not merely coloured
 * The candidate strip marks a [SuggestionKind.CORRECTION] in a different colour, because
 * "this word is wrong" is a materially different claim from "this word is unfinished" and the
 * user has to be able to tell them apart before tapping. Colour alone carries that distinction
 * only for a sighted user with normal colour vision, so the same distinction is put in the
 * content description as words. Neither channel is the fallback for the other; both say it.
 *
 * Lives in `:core` for the same reason [com.hebrewime.core.keyboard.KeyDescriptions] does:
 * a mapping that a screen reader will read out loud is worth a unit test, and a unit test
 * should not have to inflate a `View`.
 */
object PredictionDescriptions {

    /**
     * What a screen reader should say for [prediction]. Never empty.
     *
     * The word comes first. A listener scanning the strip is listening for the word, and
     * prefixing every entry with its category would make three suggestions sound like three
     * category announcements.
     */
    fun describe(prediction: Prediction): String = when (prediction.kind) {
        SuggestionKind.COMPLETION -> "${prediction.word}, completion"
        SuggestionKind.CORRECTION -> "${prediction.word}, correction"
        SuggestionKind.NEXT_WORD -> "${prediction.word}, next word"
    }

    /** Every kind this object can name, so a test can state a denominator of 3. */
    val describedKinds: Set<SuggestionKind> get() = SuggestionKind.entries.toSet()
}

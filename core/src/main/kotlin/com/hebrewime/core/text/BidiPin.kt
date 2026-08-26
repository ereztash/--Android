package com.hebrewime.core.text

import com.hebrewime.core.lexicon.HebrewText

/**
 * Pins the writing direction of a field the user is typing Hebrew into.
 *
 * ### The problem, measured
 * A keyboard has no control over the **paragraph direction** of the field it types into. That
 * follows the *app's* layout direction, which follows the *app's* locale rather than the user's
 * language. So the same keypresses produce two different-looking texts depending on which app
 * they are typed into — `B1` named that **divergence** and `B2` measured it on 3,257 lines a
 * person actually typed: **29% of them diverge**, and among lines carrying a bracket, **77%**.
 *
 * That 77% is the verified market complaint — *"מקשי ה() נכתבים הפוך בעברית"*, quoted against
 * both Gboard and SwiftKey. `ARM-EDGE` removes **100%** of it.
 *
 * ### Why both marks, and why that is not negotiable
 * `B2` measured each half alone, because they cost very different things to implement:
 *
 * | arm | fixes | breaks |
 * |---|---|---|
 * | leading mark only | 3 of 952 | **115** |
 * | trailing mark only | 275 of 952 | **115** |
 * | **both** | **641 of 952** | **0** |
 *
 * **Each half alone is worse than doing nothing.** They are only safe as a pair, so the cheap
 * implementation — one mark, committed once — is not available and was not taken.
 *
 * ### How the pair is placed without a per-keystroke cost
 * The obvious reading of "a mark at each end" is a treadmill: delete the trailing mark and
 * re-commit it on every keypress, which is an extra IPC per press on a path this project keeps
 * deliberately free of round-trips.
 *
 * Instead both marks are placed **once**, on the first Hebrew character committed into a field
 * that is known to be empty, with the cursor left **between** them. Every later character lands
 * inside the pair. Steady-state cost is **zero**.
 *
 * ### Why an empty field, and only a known-empty one
 * [shouldPin] requires the preceding context to be **known** and **empty**. A field whose
 * content the IME cannot see is never pinned: inserting a direction mark into the middle of text
 * somebody else wrote would change how that text renders, and this class has no way to tell
 * where it is. `null` context means unknown, and unknown means no.
 *
 * It also never pins a field the user has not typed Hebrew into, so a field left untouched
 * stays genuinely empty rather than containing two invisible characters — which would make a
 * "required field" check pass on nothing.
 *
 * ### What is NOT measured, and it is not small
 * - **`M7-LAT`** — never run. Two extra `commitText` calls on one keystroke per field is small,
 *   but "small" here is an argument, not a measurement.
 * - **`M13-BIDI-RENDER`** — `B2`'s oracle is `java.text.Bidi`, the algorithm. Android's
 *   `TextView` is not measured, and a field that renders non-conformantly is outside all of it.
 * - **Apps that reject the marks.** A field that strips or rejects U+200F, or that counts
 *   characters, sees two it did not expect. No app was tested. There is no device here.
 *
 * ### And it did not clear its own bar
 * `ARM-EDGE` fixes **67%** of divergence against a rule, registered before the run, that
 * requires **90%**. `ARM-FSI-EDGE` reaches 100% and is what that rule adopted — and it changes
 * what **339 of 3,257** lines look like for users who had no problem, which `ARM-EDGE` does not
 * do for a single line. Shipping this one is an operator decision recorded with a date and a
 * reason in `docs/DEFINITION_OF_DONE.md`, not a verdict this measurement handed down.
 */
object BidiPin {

    /** U+200F RIGHT-TO-LEFT MARK. Invisible, `Cf`, and removed by any format-stripping pass. */
    const val MARK: Char = '\u200f'

    /**
     * True when [text] about to be committed should carry the pair.
     *
     * @param textBefore what the IME knows sits before the cursor, or **null when it does not
     *   know**. Null is treated as "do not pin", never as "probably empty".
     * @param alreadyPinned whether this field has been pinned already in this input session.
     */
    fun shouldPin(text: CharSequence, textBefore: String?, alreadyPinned: Boolean): Boolean {
        if (alreadyPinned) return false
        if (textBefore == null || textBefore.isNotEmpty()) return false
        return text.any { HebrewText.isHebrewLetter(it) }
    }

    /** What is committed before the cursor: the leading mark and the character itself. */
    fun leading(text: CharSequence): String = MARK + text.toString()

    /** What is committed after it, with the cursor left in front. */
    fun trailing(): String = MARK.toString()
}

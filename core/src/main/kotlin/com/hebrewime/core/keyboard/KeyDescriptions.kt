package com.hebrewime.core.keyboard

/**
 * Spoken names for keys.
 *
 * A screen reader handed the bare glyph *shin* announces it as whatever its Hebrew voice makes
 * of a lone letter, which for the five final forms is often indistinguishable from their
 * ordinary counterparts. "Final mem" and "mem" sound identical if you only read the character.
 * So each key carries an explicit name, and the final forms say that they are final.
 *
 * This lives in `:core` so the mapping is testable without inflating a view, and so the same
 * names are available to any future surface.
 */
object KeyDescriptions {

    /**
     * The 27 Hebrew letters by name. The five final forms are named as such, because that is
     * the distinction a listener cannot otherwise hear.
     */
    private val hebrewLetterNames: Map<Char, String> = mapOf(
        'א' to "alef", 'ב' to "bet", 'ג' to "gimel", 'ד' to "dalet", 'ה' to "he",
        'ו' to "vav", 'ז' to "zayin", 'ח' to "het", 'ט' to "tet", 'י' to "yod",
        'כ' to "kaf", 'ך' to "final kaf", 'ל' to "lamed", 'מ' to "mem", 'ם' to "final mem",
        'נ' to "nun", 'ן' to "final nun", 'ס' to "samekh", 'ע' to "ayin", 'פ' to "pe",
        'ף' to "final pe", 'צ' to "tsadi", 'ץ' to "final tsadi", 'ק' to "qof",
        'ר' to "resh", 'ש' to "shin", 'ת' to "tav",
    )

    private val actionNames: Map<KeyAction, String> = mapOf(
        KeyAction.BACKSPACE to "Delete",
        KeyAction.SPACE to "Space",
        KeyAction.ENTER to "Enter",
        KeyAction.SHIFT to "Shift",
        KeyAction.NEXT_INPUT_METHOD to "Switch keyboard app",
    )

    private val layoutNames: Map<String, String> = mapOf(
        Layouts.HEBREW to "Hebrew letters",
        Layouts.ENGLISH to "English letters",
        Layouts.NUMERIC to "Numbers and symbols",
        Layouts.SYMBOLS to "Symbols",
    )

    private val punctuationNames: Map<String, String> = mapOf(
        "." to "Period", "," to "Comma", "?" to "Question mark", "!" to "Exclamation mark",
        "'" to "Apostrophe", "\"" to "Quotation mark", "-" to "Hyphen", "/" to "Slash",
        ":" to "Colon", ";" to "Semicolon", "(" to "Left parenthesis",
        ")" to "Right parenthesis", "&" to "Ampersand", "@" to "At sign",
        "₪" to "Shekel sign",
    )

    /** A spoken description for [key]. Never empty. */
    fun describe(key: Key): String {
        actionNames[key.action]?.let { return it }
        if (key.action == KeyAction.SWITCH_LAYOUT) {
            val target = key.output.orEmpty()
            return "Switch to ${layoutNames[target] ?: target}"
        }
        val output = key.output.orEmpty()
        if (output.length == 1) {
            hebrewLetterNames[output[0]]?.let { return it }
            punctuationNames[output]?.let { return it }
            if (output[0].isDigit()) return output
            if (output[0].isLetter()) return output.uppercase()
        }
        punctuationNames[output]?.let { return it }
        return if (output.isNotBlank()) output else key.label
    }

    /** Every Hebrew letter this object can name. Used by the tests to state a denominator. */
    val namedHebrewLetters: Set<Char> get() = hebrewLetterNames.keys
}

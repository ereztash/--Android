package com.hebrewime.core.keyboard

/** What pressing a key means. Rendering and hit-testing live in `:app`; meaning lives here. */
enum class KeyAction {
    /** Commit [Key.output]. */
    CHARACTER,
    BACKSPACE,
    SPACE,
    ENTER,
    SHIFT,
    /** Switch to the layout named by [Key.output]. */
    SWITCH_LAYOUT,
    /** Hand over to the next IME (the globe key). */
    NEXT_INPUT_METHOD,
}

/**
 * One key.
 *
 * @param label what is drawn.
 * @param output committed text for [KeyAction.CHARACTER], or the target layout id for
 *   [KeyAction.SWITCH_LAYOUT]. Null for keys that carry neither.
 * @param widthWeight share of the row's width relative to a normal key.
 */
data class Key(
    val label: String,
    val output: String? = null,
    val action: KeyAction = KeyAction.CHARACTER,
    val widthWeight: Float = 1f,
    /**
     * What a long press on this key commits instead, if anything.
     *
     * Exists so that characters a Hebrew keyboard genuinely needs but has no room for — above
     * all the **gershayim** ״, without which `כ״כ` cannot be typed at all and the whole
     * abbreviation feature is reachable only through the ASCII `"` — do not require a trip to
     * the symbols layout.
     *
     * Null on most keys. A key with no alternate simply has no long press, rather than having
     * one that does nothing.
     */
    val longPressOutput: String? = null,
) {
    init {
        require(widthWeight > 0f) { "widthWeight must be positive, was $widthWeight for $label" }
        if (action == KeyAction.CHARACTER) {
            require(!output.isNullOrEmpty()) { "character key '$label' has no output" }
        }
        if (action == KeyAction.SWITCH_LAYOUT) {
            require(!output.isNullOrEmpty()) { "layout-switch key '$label' names no layout" }
        }
    }
}

data class KeyboardRow(val keys: List<Key>) {
    init { require(keys.isNotEmpty()) { "a row must have keys" } }
    val totalWeight: Float get() = keys.sumOf { it.widthWeight.toDouble() }.toFloat()
}

/** Writing direction of the script a layout produces. See [KeyboardLayout.scriptDirection]. */
enum class ScriptDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

/**
 * A complete layout.
 *
 * @param scriptDirection the direction the *text* reads.
 *
 * **This does NOT affect key positions, and the distinction is the whole point.** An earlier
 * version of this class had an `rtl` flag that [KeyGeometry] used to mirror each row, on the
 * reasoning that Hebrew reads right-to-left so a Hebrew keyboard must too. That is wrong, it
 * shipped, and a user opened the keyboard and said it looked like a mirror.
 *
 * Hebrew keyboard layouts (SI-1452) map letters onto the **physical QWERTY key positions**,
 * which run left-to-right:
 * ```
 *   Q  W  E  R  T  Y  U  I  O  P
 *   /  '  ק  ר  א  ט  ו  ן  ם  פ
 * ```
 * so on a phone, with `/` and `'` dropped, the top row is `ק ר א ט ו ן ם פ` reading
 * **left to right on screen**. The script is right-to-left; the keyboard is not. Every Hebrew
 * typist has the left-to-right arrangement in muscle memory from a physical keyboard.
 *
 * The same mistake also put backspace on the left, shift on the right, and the layout switch
 * in the wrong corner — one root cause, three visible symptoms.
 *
 * [KeyGeometry] deliberately does not read this field. It exists for the candidate strip and
 * for text presentation, where direction genuinely matters.
 */
data class KeyboardLayout(
    val id: String,
    val rows: List<KeyboardRow>,
    val scriptDirection: ScriptDirection,
) {
    init { require(rows.isNotEmpty()) { "a layout must have rows" } }

    val allKeys: List<Key> get() = rows.flatMap { it.keys }

    fun characterKeys(): List<Key> = allKeys.filter { it.action == KeyAction.CHARACTER }
}

/**
 * The shipped layouts.
 *
 * The Hebrew arrangement follows the standard Israeli layout (SI-1452), the one every Hebrew
 * typist already has in muscle memory from a physical keyboard. Rearranging it "more logically"
 * would be a usability regression dressed as an improvement.
 */
object Layouts {

    const val HEBREW = "he"
    const val ENGLISH = "en"
    const val NUMERIC = "123"
    const val SYMBOLS = "sym"

    /** All 27 Hebrew letters: 22 base plus the 5 final forms. */
    val hebrew: KeyboardLayout = KeyboardLayout(
        id = HEBREW,
        // The SCRIPT is right-to-left. The KEY POSITIONS are not -- see KeyboardLayout.
        scriptDirection = ScriptDirection.RIGHT_TO_LEFT,
        rows = listOf(
            row("קראטוןםפ"),
            row("שדגכעיחלךף"),
            bottomLetterRow("זסבהנמצתץ"),
            functionRow(switchTo = NUMERIC, altLetters = ENGLISH),
        ),
    )

    val english: KeyboardLayout = KeyboardLayout(
        id = ENGLISH,
        scriptDirection = ScriptDirection.LEFT_TO_RIGHT,
        rows = listOf(
            row("qwertyuiop"),
            row("asdfghjkl"),
            bottomLetterRow("zxcvbnm"),
            functionRow(switchTo = NUMERIC, altLetters = HEBREW),
        ),
    )

    val numeric: KeyboardLayout = KeyboardLayout(
        id = NUMERIC,
        scriptDirection = ScriptDirection.LEFT_TO_RIGHT,
        rows = listOf(
            row("1234567890"),
            row("-/:;()₪&@\""),
            listOf(Key(".", ".", KeyAction.CHARACTER), Key(",", ",", KeyAction.CHARACTER),
                   Key("?", "?", KeyAction.CHARACTER), Key("!", "!", KeyAction.CHARACTER),
                   // Long press reaches the Hebrew punctuation the abbreviation feature needs.
                   // Typing `כ״כ` with an ASCII quote works, because the lexicon folds both,
                   // but the correct character should not be unreachable on a Hebrew keyboard.
                   Key("'", "'", KeyAction.CHARACTER, longPressOutput = "\u05f3"),
                   Key("\"", "\"", KeyAction.CHARACTER, longPressOutput = "\u05f4"),
                   Key("⌫", action = KeyAction.BACKSPACE, widthWeight = 1.5f))
                .let(::KeyboardRow),
            functionRow(switchTo = HEBREW, altLetters = ENGLISH),
        ),
    )

    val all: List<KeyboardLayout> = listOf(hebrew, english, numeric)

    fun byId(id: String): KeyboardLayout =
        all.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("unknown layout id '$id'")

    private fun row(chars: String) = KeyboardRow(
        chars.map { Key(it.toString(), it.toString(), KeyAction.CHARACTER) }
    )

    /**
     * Letters plus shift and backspace. Backspace is 1.5x because it is the key people hit
     * fastest and least accurately.
     */
    private fun bottomLetterRow(chars: String) = KeyboardRow(
        buildList {
            add(Key("⇧", action = KeyAction.SHIFT, widthWeight = 1.5f))
            addAll(chars.map { Key(it.toString(), it.toString(), KeyAction.CHARACTER) })
            add(Key("⌫", action = KeyAction.BACKSPACE, widthWeight = 1.5f))
        }
    )

    private fun functionRow(switchTo: String, altLetters: String) = KeyboardRow(
        listOf(
            Key(switchTo, switchTo, KeyAction.SWITCH_LAYOUT, widthWeight = 1.5f),
            Key("🌐", action = KeyAction.NEXT_INPUT_METHOD),
            Key(altLetters, altLetters, KeyAction.SWITCH_LAYOUT),
            Key(" ", " ", KeyAction.SPACE, widthWeight = 4f),
            Key(".", ".", KeyAction.CHARACTER),
            Key("↵", action = KeyAction.ENTER, widthWeight = 1.5f),
        )
    )
}

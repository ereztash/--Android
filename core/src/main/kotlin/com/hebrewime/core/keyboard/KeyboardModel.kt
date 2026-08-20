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

/**
 * A complete layout.
 *
 * @param rtl true when the row should be laid out right-to-left. This is a property of the
 *   *script*, not of the device locale: the Hebrew layout is RTL even for a user whose phone
 *   is in English, and the English layout is LTR even on a Hebrew phone.
 */
data class KeyboardLayout(
    val id: String,
    val rows: List<KeyboardRow>,
    val rtl: Boolean,
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
        rtl = true,
        rows = listOf(
            row("קראטוןםפ"),
            row("שדגכעיחלךף"),
            bottomLetterRow("זסבהנמצתץ"),
            functionRow(switchTo = NUMERIC, altLetters = ENGLISH),
        ),
    )

    val english: KeyboardLayout = KeyboardLayout(
        id = ENGLISH,
        rtl = false,
        rows = listOf(
            row("qwertyuiop"),
            row("asdfghjkl"),
            bottomLetterRow("zxcvbnm"),
            functionRow(switchTo = NUMERIC, altLetters = HEBREW),
        ),
    )

    val numeric: KeyboardLayout = KeyboardLayout(
        id = NUMERIC,
        rtl = false,
        rows = listOf(
            row("1234567890"),
            row("-/:;()₪&@\""),
            listOf(Key(".", ".", KeyAction.CHARACTER), Key(",", ",", KeyAction.CHARACTER),
                   Key("?", "?", KeyAction.CHARACTER), Key("!", "!", KeyAction.CHARACTER),
                   Key("'", "'", KeyAction.CHARACTER),
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

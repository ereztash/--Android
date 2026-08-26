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
    /**
     * Why this key's [label] is not the glyph the user will see when they press it.
     *
     * **Null for almost every key, and it must stay that way.** Of everything the shipped
     * layouts can emit, exactly two characters render as something else in Hebrew: `(` and `)`.
     * Rule L4 of UAX #9 mirrors them inside a right-to-left run, so pressing the key labelled
     * `(` puts a `)`-shaped glyph on screen. `B1` measured what that costs: **8 of 8 bracket
     * items change meaning if the user follows the key label** and presses `)` instead.
     *
     * This field does not fix that. It makes it impossible to have that mismatch **silently**,
     * which is the same bargain `GATE-CORPUS-2` strikes: a machine can find the mismatch, only
     * a person can say why it is allowed to stand.
     *
     * **The requirement is enforced by `KeyLabelGlyphTest`, deliberately not by `init`.** A
     * constructor that refused an undocumented mismatch would make the check unable to fail,
     * and a check that cannot fail is not a check.
     */
    val labelDiffersBecause: String? = null,
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

    /**
     * Why `(` stays labelled `(` even though a `)` appears on screen.
     *
     * Rule L4 of UAX #9 mirrors a bracket inside a right-to-left run, so in Hebrew the key
     * labelled `(` puts a `)`-shaped glyph at the caret. That is **correct text** — `B2`
     * measured it as the rendering a Hebrew-locale app has always produced — and it is a
     * genuine trap: `B1` measured that **8 of 8 bracket items change meaning** when a user
     * "corrects" it by pressing `)` instead.
     *
     * **Relabelling was considered and rejected**, because this file already records the
     * project making exactly that mistake once. `KeyboardLayout.scriptDirection` documents an
     * earlier version that mirrored the key positions on the reasoning that Hebrew reads
     * right-to-left — *"That is wrong, it shipped, and a user opened the keyboard and said it
     * looked like a mirror."* Every Hebrew typist carries SI-1452 in muscle memory from a
     * physical keyboard, where this key has been labelled `(` for decades. Swapping the labels
     * to match the glyph would break that for a benefit nobody has measured.
     *
     * **`L2-LABEL` is NOT MEASURED.** Whether matching labels to glyphs helps a user needs a
     * user, and there is not one. Until there is, the mismatch stands and is documented rather
     * than fixed on a hunch — which is what this constant is.
     */
    const val BRACKET_LABEL_REASON: String =
        "L4 mirrors brackets in RTL, so the glyph is the other bracket. Relabelling repeats " +
            "the mirrored-layout mistake this file already records; SI-1452 muscle memory wins " +
            "until L2-LABEL is measured on a user."

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
            // Built explicitly rather than through `row()`, because two of these ten keys are
            // the only characters the shipped layouts emit whose glyph is not what the label
            // shows. `KeyLabelGlyphTest` requires the reason; see `docs/KEY_LABELS.md`.
            KeyboardRow(listOf("-", "/", ":", ";", "(", ")", "₪", "&", "@", "\"").map { c ->
                Key(c, c, KeyAction.CHARACTER, labelDiffersBecause = BRACKET_LABEL_REASON.takeIf { c == "(" || c == ")" })
            }),
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

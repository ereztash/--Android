package com.hebrewime.core.text

import com.hebrewime.core.input.InputContextBuffer
import com.hebrewime.core.keyboard.EditCommand
import com.hebrewime.core.keyboard.KeyPressPlanner
import com.hebrewime.core.keyboard.Layouts
import java.io.File
import java.text.Bidi
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * B1 - does the bracket complaint reproduce, and is it ours?
 *
 * The corpus categories, the four arms, the four predictions, the three positive controls and
 * the four-clause adoption rule were committed before this file existed. See `docs/BIDI.md`.
 *
 * ### What this measures
 * **The logical string the keyboard commits.** Not rendering, which this project does not own,
 * and not Gboard, which cannot be run here.
 *
 * A keyboard has no control over the paragraph direction of the field it types into: that
 * follows the app's layout direction, which follows the app's locale rather than the user's
 * language. So the measurable question is whether the same keypresses produce two
 * different-looking texts depending on which app they are typed into. Call that **divergence**.
 *
 * ### The oracle is the JDK's UAX #9
 * `java.text.Bidi`, ICU-derived, the same algorithm family Android renders with. Levels are
 * resolved, runs reordered with `Bidi.reorderVisually`, characters inside an odd-level run
 * reversed, and rule **L4** applied - mirror iff the resolved level is odd and `Bidi_Mirrored`
 * is Yes.
 *
 * ### The mirror table is explicit, and guarded
 * The JDK exposes `Character.isMirrored` but not the mirroring *map*. [MIRROR] supplies it for
 * the pairs a keyboard can emit, and [mirrored] throws if a corpus character claims
 * `isMirrored` without an entry - a missing pair is a harness defect and is reported as one
 * rather than silently rendering unmirrored.
 */
class BidiDivergenceProbe {

    // ---------------------------------------------------------------- the oracle

    private val MIRROR: Map<Char, Char> = mapOf(
        '(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{',
        '<' to '>', '>' to '<',
    )

    private fun mirrored(c: Char): Char {
        if (!Character.isMirrored(c)) return c
        return MIRROR[c] ?: error("HARNESS DEFECT: U+%04X is Bidi_Mirrored with no entry in MIRROR".format(c.code))
    }

    private data class Run(val text: String, val level: Int)

    /**
     * The visual glyph sequence a conformant renderer would draw for [s] in a paragraph whose
     * direction is [rtlParagraph].
     *
     * @param mirror when false, rule L4 is skipped. That is the **PC-2 mutant**: it must change
     *   the result on the bracket category, or this harness is not measuring mirroring.
     */
    private fun visual(s: String, rtlParagraph: Boolean, mirror: Boolean = true): String {
        if (s.isEmpty()) return s
        val flags = if (rtlParagraph) Bidi.DIRECTION_RIGHT_TO_LEFT else Bidi.DIRECTION_LEFT_TO_RIGHT
        val bidi = Bidi(s, flags)
        val n = bidi.runCount
        val levels = ByteArray(n)
        val objs = arrayOfNulls<Any>(n)
        for (i in 0 until n) {
            levels[i] = bidi.getRunLevel(i).toByte()
            objs[i] = Run(s.substring(bidi.getRunStart(i), bidi.getRunLimit(i)), bidi.getRunLevel(i))
        }
        Bidi.reorderVisually(levels, 0, objs, 0, n)
        val sb = StringBuilder(s.length)
        for (o in objs) {
            val r = o as Run
            if (r.level % 2 == 1) {
                for (i in r.text.indices.reversed()) sb.append(if (mirror) mirrored(r.text[i]) else r.text[i])
            } else {
                sb.append(r.text)
            }
        }
        return sb.toString()
    }

    private fun diverges(s: String, mirror: Boolean = true): Boolean =
        visual(s, rtlParagraph = false, mirror = mirror) != visual(s, rtlParagraph = true, mirror = mirror)

    // ---------------------------------------------------------------- the arms

    private val RLM = '\u200f'
    private val RLI = '\u2067'
    private val PDI = '\u2069'

    private fun isHebrew(c: Char): Boolean = c in 'א'..'ת'
    private fun isStrong(c: Char): Boolean =
        isHebrew(c) || c.isLetter() && Character.getDirectionality(c).let {
            it == Character.DIRECTIONALITY_LEFT_TO_RIGHT ||
                it == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                it == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        }

    /** What ships today: the key labelled `(` commits U+0028. */
    private fun armNone(s: String) = s

    /**
     * The "make it look right" fix people ask for: in the Hebrew layout, `(` commits U+0029.
     * It changes a character rather than adding a control, which is what clause 3 of the
     * adoption rule exists to catch.
     */
    private fun armSwap(s: String) = buildString {
        for (c in s) append(if (c == '(') ')' else if (c == ')') '(' else c)
    }

    /**
     * U+200F after every bidi-neutral character, when the string has Hebrew in it at all.
     * Implementable per keystroke: the IME knows which layout is active.
     */
    private fun armRlm(s: String): String {
        if (s.none { isHebrew(it) }) return s
        return buildString { for (c in s) { append(c); if (!isStrong(c)) append(RLM) } }
    }

    /**
     * The whole committed content wrapped in an isolate. This is the only arm that can pin the
     * direction of neutrals when the app has explicitly set an LTR paragraph, because that is
     * what an isolate is for.
     *
     * Its implementation cost is real and is not hidden: an IME commits one key at a time, so
     * maintaining a trailing PDI means deleting and re-committing it on every keystroke.
     * Measured here before that cost is paid, not after.
     */
    private fun armIsolate(s: String): String {
        if (s.none { isHebrew(it) }) return s
        return "$RLI$s$PDI"
    }

    private val arms: List<Pair<String, (String) -> String>> = listOf(
        "ARM-NONE" to ::armNone,
        "ARM-SWAP" to ::armSwap,
        "ARM-RLM" to ::armRlm,
        "ARM-ISOLATE" to ::armIsolate,
    )

    // ---------------------------------------------------------------- the corpus

    private data class Item(val category: String, val text: String)

    private val corpus: List<Item> = buildList {
        fun add(cat: String, vararg xs: String) = xs.forEach { add(Item(cat, it)) }

        // 1 - Hebrew + parentheses. The primary verified complaint.
        add("1 brackets",
            "(שלום)",
            "אמרתי (בקול) שלום",
            "הוא בא (אתמול)",
            "יש (הרבה) אנשים",
            "(א)",
            "טקסט (עם סוגריים) בתוך משפט",
            "שלום (עולם) שלום",
            "(שלום עולם)")

        // 2 - Hebrew + a Latin word.
        add("2 he+latin",
            "שלחתי לך email",
            "הורדתי את Android",
            "אני עובד ב Google",
            "הקובץ readme נמצא")

        // 3 - Hebrew + digits.
        add("3 he+digits",
            "יש לי 5 ילדים",
            "השעה 14:30",
            "עולה 250 שקל",
            "בשנת 2026 קרה משהו")

        // 4 - Hebrew + Latin + digits. The second verified complaint.
        add("4 he+latin+digits",
            "גרסה 5 של Android",
            "שלחתי 3 קבצים ב email",
            "iPhone 15 עולה 4000",
            "קניתי 2 כרטיסים ל Google IO")

        // 5 - Hebrew + geresh, gershayim, ASCII quotes.
        add("5 he+marks",
            "צה״ל",
            "וכו׳",
            "אמר \"שלום\"",
            "זה 'טוב'")

        // 6 - Hebrew + every remaining neutral the numeric layout emits.
        add("6 he+neutrals",
            "שלום, מה נשמע?",
            "כן! באמת.",
            "א-ב",
            "כן/לא",
            "מחיר: 50₪",
            "שלום; להתראות",
            "כתוב לי ב @name",
            "לחם & חלב")

        // 7 - controls.
        add("7 control he-only", "שלום עולם", "אני הולך הביתה", "מה נשמע")
        add("7 control latin-only", "hello world", "android studio")
    }

    // ---------------------------------------------------------------- PC-3, reachability

    /**
     * Every character the shipped keyboard can commit, derived by running **`KeyPressPlanner`**
     * over every key in every layout in both shift states - not by re-reading `Key.output`.
     *
     * The first version of this control did re-read `Key.output`, and reported `A G P I O` as
     * unreachable. They are reachable: `KeyPressPlanner.plan` uppercases when shift is latched,
     * and the English layout has a shift key. The control was measuring a copy of the rule
     * instead of the rule, which is the same defect it exists to catch.
     */
    private val emittable: Set<Char> = buildSet {
        val ctx = InputContextBuffer()
        for (layout in Layouts.all) for (k in layout.allKeys) {
            for (shifted in listOf(false, true)) {
                for (cmd in KeyPressPlanner.plan(k, ctx, shifted)) {
                    if (cmd is EditCommand.CommitText) cmd.text.forEach { add(it) }
                    if (cmd is EditCommand.PerformEditorAction) add('\n')
                }
            }
            k.longPressOutput?.forEach { add(it) }
        }
    }

    // ---------------------------------------------------------------- the run

    @Test
    fun bidiDivergence() {
        if (System.getProperty("runBidiProbe").isNullOrEmpty()) {
            println("skipped; -PrunBidiProbe=1")
            return
        }

        println("B1 - bidi divergence. Oracle: java.text.Bidi (UAX #9). See docs/BIDI.md.")
        println()

        // ---- PC-3: reachability. A string the keyboard cannot type is a harness defect.
        val unreachable = corpus.flatMap { it.text.toSet() }.toSet().filterNot { it in emittable }
        println("PC-3 reachability: ${corpus.size} items, " +
            if (unreachable.isEmpty()) "every character emittable by Layouts. OK"
            else "HARNESS DEFECT - not emittable: " +
                unreachable.joinToString(" ") { "U+%04X".format(it.code) })
        require(unreachable.isEmpty()) { "PC-3 failed: corpus uses characters the keyboard cannot emit" }
        println()

        // ---- PC-1: the instrument must be blind where the corpora are blind.
        val evalDir = File(System.getProperty("eval.dir") ?: error("eval.dir not set"))
        println("PC-1 - divergence on the shipped evaluation corpora (Hebrew-letters-only by construction)")
        var pc1Total = 0
        var pc1Diverged = 0
        for (name in listOf("he_conversational_test.txt.gz", "hewiki_eval_sample.txt.gz")) {
            val f = File(evalDir, name)
            if (!f.isFile) { println("  $name  ABSENT - NOT MEASURED"); continue }
            var n = 0
            var d = 0
            GZIPInputStream(f.inputStream()).bufferedReader().forEachLine { raw ->
                val line = raw.trim()
                if (line.isNotEmpty()) { n++; if (diverges(line)) d++ }
            }
            pc1Total += n
            pc1Diverged += d
            println("  %-32s %,8d lines   diverged %,6d   %.4f%%".format(name, n, d, 100.0 * d / n))
        }
        val pc1Ok = pc1Diverged == 0
        println("  PC-1: ${if (pc1Ok) "PASS - exactly 0 divergence on %,d lines".format(pc1Total)
                           else "FAIL - %,d lines diverged; every number below is void".format(pc1Diverged)}")
        println()

        // ---- PC-2: the planted defect. Skipping L4 must change the bracket result.
        val brackets = corpus.filter { it.category == "1 brackets" }
        val withL4 = brackets.map { visual(it.text, rtlParagraph = true, mirror = true) }
        val withoutL4 = brackets.map { visual(it.text, rtlParagraph = true, mirror = false) }
        val changed = withL4.indices.count { withL4[it] != withoutL4[it] }
        println("PC-2 - planted defect: skip rule L4 (no mirroring)")
        println("  ${changed} of ${brackets.size} bracket items change. " +
            if (changed > 0) "Control RED. The harness measures mirroring."
            else "Control GREEN - NOT-A-GATE: this harness is not measuring mirroring.")
        require(changed > 0) { "PC-2 failed: the mutant changed nothing" }
        println()

        // ---- the arms
        println("=".repeat(100))
        println("DIVERGENCE BY ARM  (V(LTR) != V(RTL); the same keypresses, two apps)")
        println("=".repeat(100))
        val byArm = LinkedHashMap<String, List<Boolean>>()
        for ((name, fn) in arms) byArm[name] = corpus.map { diverges(fn(it.text)) }

        val cats = corpus.map { it.category }.distinct()
        print("%-22s".format("category"))
        for ((name, _) in arms) print("%14s".format(name))
        println("     n")
        for (cat in cats) {
            val idx = corpus.indices.filter { corpus[it].category == cat }
            print("%-22s".format(cat))
            for ((name, _) in arms) {
                val d = idx.count { byArm[name]!![it] }
                print("%13s ".format("%d (%.0f%%)".format(d, 100.0 * d / idx.size)))
            }
            println("%6d".format(idx.size))
        }
        print("%-22s".format("ALL"))
        for ((name, _) in arms) {
            val d = byArm[name]!!.count { it }
            print("%13s ".format("%d (%.0f%%)".format(d, 100.0 * d / corpus.size)))
        }
        println("%6d".format(corpus.size))
        println()

        // ---- worked examples, so the numbers can be checked by eye
        println("Worked examples under ARM-NONE (what ships today):")
        for (it in corpus.filter { it.category == "1 brackets" }.take(3) +
                   corpus.filter { it.category == "4 he+latin+digits" }.take(2)) {
            val l = visual(it.text, rtlParagraph = false)
            val r = visual(it.text, rtlParagraph = true)
            println("  logical      %s".format(it.text))
            println("  LTR paragraph %s".format(l))
            println("  RTL paragraph %s   %s".format(r, if (l == r) "same" else "DIVERGES"))
            println()
        }

        // ---- the adoption rule, all four clauses
        println("=".repeat(100))
        println("AGAINST THE PRE-REGISTERED ADOPTION RULE (docs/BIDI.md)")
        println("=".repeat(100))
        val noneDiv = byArm["ARM-NONE"]!!
        val divergentIdx = corpus.indices.filter { noneDiv[it] }
        val convergentIdx = corpus.indices.filter { !noneDiv[it] }
        println("  ARM-NONE diverges on ${divergentIdx.size} of ${corpus.size} items; " +
            "${convergentIdx.size} converge.")
        println()
        println("%-14s %10s %10s %12s %8s  %s".format(
            "arm", "fixed", "broke", "round-trip", "PC-1", "ADOPTED?"))
        var anyAdopted = false
        for ((name, fn) in arms) {
            if (name == "ARM-NONE") continue
            val d = byArm[name]!!
            val fixed = divergentIdx.count { !d[it] }
            val broke = convergentIdx.count { d[it] }
            val roundTrip = corpus.all { item ->
                fn(item.text).filter { Character.getType(it) != Character.FORMAT.toInt() } == item.text
            }
            val fixedFrac = if (divergentIdx.isEmpty()) 0.0 else fixed.toDouble() / divergentIdx.size
            val adopted = fixedFrac >= 0.90 && broke == 0 && roundTrip && pc1Ok
            anyAdopted = anyAdopted || adopted
            println("%-14s %10s %10d %12s %8s  %s".format(
                name, "%d/%d (%.0f%%)".format(fixed, divergentIdx.size, 100 * fixedFrac),
                broke, if (roundTrip) "clean" else "CORRUPTS",
                if (pc1Ok) "ok" else "FAIL", if (adopted) "YES" else "no"))
        }
        println()
        // ---- POST HOC. Not pre-registered. Labelled so it cannot be read as a result.
        println()
        println("=".repeat(100))
        println("POST HOC - NOT PRE-REGISTERED. P1 was falsified; this asks why. Hypothesis, not result.")
        println("=".repeat(100))

        // Which Unicode bidi revision is under us. N0 / BD16 (bracket pairs) and the isolate
        // characters shipped in the SAME revision, Unicode 6.3, so recognising an isolate
        // directionality class dates the tables.
        //
        // The first discriminator written here compared a matched pair `(HE)` against an
        // unmatched `(HE` and is kept as a note rather than deleted: **it does not
        // discriminate.** Worked by hand, N0-present and N0-absent produce the SAME visual
        // string for that input - with N0 both brackets go to level 1 and are mirrored, without
        // N0 both stay at level 0 unmirrored, and the two orderings coincide. A control that
        // cannot come out two ways was never a control.
        val isolateKnown =
            Character.getDirectionality(RLI) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ISOLATE
        println("  Unicode tables: isolate class recognised = %s -> bidi revision is %s".format(
            isolateKnown, if (isolateKnown) "6.3 or later, so N0/BD16 is present" else "PRE-6.3"))
        println("  That dates the tables. It does not by itself explain the bracket result;")
        println("  the 0% divergence on brackets is a measurement and stands either way.")
        println()

        // What the user SEES the moment they press the key labelled '(' with Hebrew around it,
        // and what they get if they "correct" it by pressing ')' instead.
        var trap = 0
        for (item in brackets) {
            val intended = visual(item.text, rtlParagraph = true)
            val corrected = visual(armSwap(item.text), rtlParagraph = true)
            if (intended != corrected) trap++
        }
        println("  The key labelled '(' commits U+0028. Rule L4 mirrors it in an RTL run, so a")
        println("  ')'-shaped glyph appears. The text is CORRECT; the key label contradicts it.")
        println("  If the user 'fixes' that by pressing ')' instead, the logical string inverts:")
        println("    intended   %s  ->  %s".format(brackets[0].text, visual(brackets[0].text, true)))
        println("    'corrected' %s  ->  %s".format(armSwap(brackets[0].text), visual(armSwap(brackets[0].text), true)))
        println("  %d of %d bracket items change meaning if the user follows the key label.".format(trap, brackets.size))
        println("=".repeat(100))
        println()

        println("  VERDICT: " + when {
            divergentIdx.isEmpty() -> "ARM-NONE never diverges - NOT-A-GATE. See docs/BIDI.md."
            anyAdopted -> "an arm meets all four clauses"
            else -> "NO arm meets all four clauses - NOTHING IS ADOPTED"
        })
        println("=".repeat(100))
    }
}

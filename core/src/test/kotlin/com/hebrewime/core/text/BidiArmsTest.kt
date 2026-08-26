package com.hebrewime.core.text

import java.io.File
import java.text.Bidi
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * B2 - the bidi arms `B1` never tested, on lines a person typed.
 *
 * The seven arms, the corpus rule, the four predictions, three controls and the four-clause
 * adoption rule were committed before this file existed. See `docs/BIDI_ARMS.md`.
 *
 * The oracle is `B1`'s: `java.text.Bidi`, levels resolved, runs reordered, odd-level runs
 * reversed, rule L4 applied. Divergence is `V(s, LTR) != V(s, RTL)` - the same keypresses
 * producing two different-looking texts depending on the app's locale.
 */
class BidiArmsTest {

    private val MIRROR = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[',
                               '{' to '}', '}' to '{', '<' to '>', '>' to '<')

    private fun mirrored(c: Char): Char {
        if (!Character.isMirrored(c)) return c
        return MIRROR[c] ?: error("HARNESS DEFECT: U+%04X is Bidi_Mirrored with no MIRROR entry".format(c.code))
    }

    private data class Run(val text: String, val level: Int)

    private fun visual(s: String, rtl: Boolean, mirror: Boolean = true): String {
        if (s.isEmpty()) return s
        val b = Bidi(s, if (rtl) Bidi.DIRECTION_RIGHT_TO_LEFT else Bidi.DIRECTION_LEFT_TO_RIGHT)
        val n = b.runCount
        val levels = ByteArray(n)
        val objs = arrayOfNulls<Any>(n)
        for (i in 0 until n) {
            levels[i] = b.getRunLevel(i).toByte()
            objs[i] = Run(s.substring(b.getRunStart(i), b.getRunLimit(i)), b.getRunLevel(i))
        }
        Bidi.reorderVisually(levels, 0, objs, 0, n)
        val sb = StringBuilder(s.length)
        for (o in objs) {
            val r = o as Run
            if (r.level % 2 == 1) for (i in r.text.indices.reversed())
                sb.append(if (mirror) mirrored(r.text[i]) else r.text[i])
            else sb.append(r.text)
        }
        return sb.toString()
    }

    private fun diverges(s: String, mirror: Boolean = true) =
        visual(s, false, mirror) != visual(s, true, mirror)

    // ------------------------------------------------------------------ arms
    private val RLM = '‏'
    private val LRI = '⁦'
    private val FSI = '⁨'
    private val PDI = '⁩'

    private fun isHebrew(c: Char) = c in 'א'..'ת'
    private fun isForeign(c: Char) = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9')

    /** Apply [wrap] to every maximal run of foreign characters. */
    private fun perForeignRun(s: String, wrap: (String) -> String): String {
        if (s.none { isHebrew(it) }) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (!isForeign(s[i])) { sb.append(s[i]); i++; continue }
            var j = i
            while (j < s.length && isForeign(s[j])) j++
            sb.append(wrap(s.substring(i, j)))
            i = j
        }
        return sb.toString()
    }

    private fun edge(s: String) = if (s.none { isHebrew(it) }) s else "$RLM$s$RLM"

    private val arms: List<Pair<String, (String) -> String>> = listOf(
        "ARM-NONE" to { s: String -> s },
        // What AOSP LatinIME actually ships for Hebrew, and therefore what every keyboard
        // descended from it inherits -- FUTO included, whose v0.1.27 release note reads
        // "Fixed some behavior when it comes to typing parentheses in Hebrew".
        //
        // AOSP `values-iw/donottranslate-more-keys.xml` defines
        //     keyspec_left_parenthesis  = "(|)"
        //     keyspec_right_parenthesis = ")|("
        // and KeySpecParser's own javadoc documents the format as `keyLabel|keyOutputText`.
        // So the key LABELLED `(` COMMITS U+0029. That is not a rendering trick: it changes
        // the character in the user's text.
        //
        // `B1` measured this as ARM-SWAP and it was the only arm to fail the round-trip
        // clause. It is included here to measure the SHIPPED behaviour of the incumbent
        // against real typed Hebrew, which nothing in this repository had done.
        "ARM-SWAP (AOSP ships this)" to { s: String ->
            buildString { for (c in s) append(if (c == '(') ')' else if (c == ')') '(' else c) }
        },
        "ARM-FSI" to { s -> perForeignRun(s) { "$FSI$it$PDI" } },
        "ARM-LRI" to { s -> perForeignRun(s) { "$LRI$it$PDI" } },
        "ARM-RLM-AFTER" to { s -> perForeignRun(s) { "$it$RLM" } },
        "ARM-RLM-AROUND" to { s -> perForeignRun(s) { "$RLM$it$RLM" } },
        // The two halves, measured separately because they cost wildly different things to
        // implement. A LEADING mark is committed once, when the field is empty. A TRAILING mark
        // must be deleted and re-committed on EVERY keystroke, which is an extra IPC per press
        // and a desync risk on a path this project deliberately keeps free of round-trips.
        "ARM-EDGE-LEAD" to { s: String -> if (s.none { isHebrew(it) }) s else "$RLM$s" },
        "ARM-EDGE-TRAIL" to { s: String -> if (s.none { isHebrew(it) }) s else "$s$RLM" },
        "ARM-EDGE" to { s -> edge(s) },
        "ARM-FSI-EDGE" to { s -> edge(perForeignRun(s) { "$FSI$it$PDI" }) },
    )

    // ------------------------------------------------------------------ corpus
    private data class Item(val family: String, val text: String)

    private fun corpus(cap: Int): List<Item> {
        val f = File(File(System.getProperty("eval.dir")!!), "he_typed_raw.txt.gz")
        if (!f.isFile) return emptyList()
        val lines = GZIPInputStream(f.inputStream()).use { it.readBytes() }
            .toString(Charsets.UTF_8).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val mixed = ArrayList<Item>()
        val bracket = ArrayList<Item>()
        val control = ArrayList<Item>()
        for (l in lines) {
            if (!l.any { isHebrew(it) }) continue
            when {
                l.any { isForeign(it) } && mixed.size < cap -> mixed.add(Item("MIXED", l))
                l.any { it in "()[]{}" } && bracket.size < cap -> bracket.add(Item("BRACKET", l))
                l.all { isHebrew(it) || it.isWhitespace() } && control.size < cap ->
                    control.add(Item("CONTROL-HE", l))
            }
        }
        return mixed + bracket + control
    }

    /** Does the item's first foreign run touch the start or end of the line? */
    private fun edgePlaced(s: String): Boolean {
        val first = s.indexOfFirst { isForeign(it) }
        val last = s.indexOfLast { isForeign(it) }
        if (first < 0) return false
        val beforeIsEdge = s.take(first).none { isHebrew(it) }
        val afterIsEdge = s.drop(last + 1).none { isHebrew(it) }
        return beforeIsEdge || afterIsEdge
    }

    @Test
    fun bidiArms() {
        if (System.getProperty("runBidiArms").isNullOrEmpty()) {
            println("skipped; -PrunBidiArms=1"); return
        }
        val cap = 2_000
        val items = corpus(cap)
        if (items.isEmpty()) { println("NOT-MEASURED: he_typed_raw.txt.gz absent"); return }

        println("B2 - bidi arms on lines a person typed. See docs/BIDI_ARMS.md.")
        val families = items.map { it.family }.distinct()
        println("corpus: ${items.size} lines, cap $cap per family  " +
            families.joinToString("  ") { fam -> "$fam=${items.count { it.family == fam }}" })
        println()

        // ---- PC-1: Hebrew-only lines, drawn from the SAME typed corpus, must not diverge
        val ctrl = items.filter { it.family == "CONTROL-HE" }
        val ctrlDiv = ctrl.count { diverges(it.text) }
        val pc1 = ctrlDiv == 0
        println("PC-1  CONTROL-HE divergence under ARM-NONE: $ctrlDiv of ${ctrl.size}  " +
            if (pc1) "PASS" else "FAIL - the instrument is measuring something other than script mixing")
        // ---- PC-2: the planted defect
        val mixed = items.filter { it.family == "MIXED" }
        val l4 = mixed.count { visual(it.text, true, true) != visual(it.text, true, false) }
        println("PC-2  skipping rule L4 changes $l4 of ${mixed.size} MIXED lines  " +
            if (l4 > 0) "control RED" else "control GREEN - NOT-A-GATE")
        println()

        // ---- the table
        val div = LinkedHashMap<String, List<Boolean>>()
        for ((name, fn) in arms) div[name] = items.map { diverges(fn(it.text)) }

        println("=".repeat(112))
        println("DIVERGENCE BY ARM  (V(LTR) != V(RTL): the same keypresses, two apps)")
        println("=".repeat(112))
        print("%-18s".format("arm"))
        for (fam in families) print("%16s".format(fam))
        println("%14s".format("ALL"))
        for ((name, _) in arms) {
            print("%-18s".format(name))
            for (fam in families) {
                val idx = items.indices.filter { items[it].family == fam }
                val d = idx.count { div[name]!![it] }
                print("%15s ".format("%d (%.0f%%)".format(d, 100.0 * d / idx.size)))
            }
            val all = div[name]!!.count { it }
            println("%13s".format("%d (%.0f%%)".format(all, 100.0 * all / items.size)))
        }
        println()

        // ---- the rule
        val none = div["ARM-NONE"]!!
        val divergentIdx = items.indices.filter { none[it] }
        val convergentIdx = items.indices.filter { !none[it] }
        println("=".repeat(112))
        println("AGAINST THE PRE-REGISTERED RULE (docs/BIDI_ARMS.md)")
        println("=".repeat(112))
        println("  ARM-NONE diverges on ${divergentIdx.size} of ${items.size}; ${convergentIdx.size} converge.")
        println()
        println("%-18s %14s %8s %12s %10s  %s".format("arm", "fixed", "broke", "round-trip", "inert?", "ADOPTED?"))
        var any = false
        for ((name, fn) in arms) {
            if (name == "ARM-NONE") continue
            val d = div[name]!!
            val fixed = divergentIdx.count { !d[it] }
            val broke = convergentIdx.count { d[it] }
            val rt = items.all { fn(it.text).filter { c -> Character.getType(c) != Character.FORMAT.toInt() } == it.text }
            val inert = d == none
            val frac = if (divergentIdx.isEmpty()) 0.0 else fixed.toDouble() / divergentIdx.size
            val ok = frac >= 0.90 && broke == 0 && rt && pc1 && !inert
            any = any || ok
            println("%-18s %14s %8d %12s %10s  %s".format(
                name, "%d/%d (%.0f%%)".format(fixed, divergentIdx.size, 100 * frac), broke,
                if (rt) "clean" else "CORRUPTS",
                if (inert) "INERT" else "fires", if (ok) "YES" else "no"))
        }
        println()

        // ---- B2-P2: is the residual edge-placed?
        val fsi = div["ARM-FSI"]!!
        val unfixed = divergentIdx.filter { fsi[it] && items[it].family == "MIXED" }
        val edgeCount = unfixed.count { edgePlaced(items[it].text) }
        println("  B2-P2  of the %d MIXED lines ARM-FSI does NOT fix, %d are edge-placed (%.0f%%; bar 80%%)  %s"
            .format(unfixed.size, edgeCount,
                if (unfixed.isEmpty()) 0.0 else 100.0 * edgeCount / unfixed.size,
                if (unfixed.isNotEmpty() && edgeCount >= 0.8 * unfixed.size) "HELD" else "FALSIFIED"))
        println()
        // ---- POST HOC, not pre-registered. Divergence measures CONSISTENCY, not correctness:
        // two identical-but-wrong renderings converge. The reference for "right" is ARM-NONE
        // under an RTL paragraph -- what the user sees today in a Hebrew-locale app, where the
        // complaint does not arise. An arm that preserves that while removing the locale
        // dependence has fixed something; an arm that changes it has moved the damage.
        println("=".repeat(112))
        println("POST HOC - NOT PRE-REGISTERED. Consistency is not correctness.")
        println("=".repeat(112))
        println("  Reference: ARM-NONE rendered in an RTL paragraph -- what a Hebrew-locale app shows today.")
        for ((name, fn) in arms) {
            if (name == "ARM-NONE") continue
            var same = 0
            var changed = 0
            for (it0 in items) {
                val ref = visual(it0.text, rtl = true)
                val got = visual(fn(it0.text), rtl = true)
                    .filter { c -> Character.getType(c) != Character.FORMAT.toInt() }
                if (ref == got) same++ else changed++
            }
            println("    %-18s preserves the Hebrew-locale rendering on %d of %d (%.1f%%), changes %d"
                .format(name, same, items.size, 100.0 * same / items.size, changed))
        }
        println("=".repeat(112))
        println()

        println("  VERDICT: " + if (any) "an arm meets every clause"
                                else "NO arm meets every clause - NOTHING IS ADOPTED")
        println("=".repeat(112))
    }
}

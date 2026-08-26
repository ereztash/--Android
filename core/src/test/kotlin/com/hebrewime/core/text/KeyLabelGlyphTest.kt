package com.hebrewime.core.text

import com.hebrewime.core.keyboard.KeyAction
import com.hebrewime.core.keyboard.Layouts
import java.text.Bidi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * W4 — a key's label must be the glyph its output produces, or the key must say why not.
 *
 * ### The defect
 * `B1` recorded, post hoc, that pressing the key labelled `(` puts a `)`-shaped glyph on screen
 * in Hebrew, and that **8 of 8 bracket items change meaning** when a user "corrects" that by
 * pressing `)` instead. Nothing in the codebase noticed the mismatch, and nothing prevented the
 * next one.
 *
 * ### Why the rule is "match, or say why", not "match"
 * The same bargain `GATE-CORPUS-2` strikes. Forcing a match would mean relabelling `(` to `)`,
 * and `KeyboardLayout.scriptDirection` records this project shipping precisely that class of
 * mistake once already — mirroring the layout because the script is right-to-left, which a user
 * called a mirror. A machine can find a mismatch; only a person can decide it may stand, and
 * `L2-LABEL` — whether matching helps anyone — is **NOT MEASURED** and needs a user.
 *
 * ### Why a test and not a gate
 * The oracle is `java.text.Bidi`, which lives in the JVM. `run_gates.py` is Python and has no
 * bidi implementation, so a gate would have to re-implement UAX #9 to check a two-key rule.
 * `scripts/assert_tests_ran.py` covers the "did it run" half.
 */
class KeyLabelGlyphTest {

    private val MIRROR = "()[]{}<>"

    private fun mirror(c: Char): Char {
        val i = MIRROR.indexOf(c)
        if (i < 0) return c
        return MIRROR[if (i % 2 == 0) i + 1 else i - 1]
    }

    /** The glyph [c] takes when typed between Hebrew letters, per UAX #9 rules L4 and N1. */
    private fun glyphInHebrew(c: Char): Char {
        val s = "ש$c" + "ל"
        val b = Bidi(s, Bidi.DIRECTION_RIGHT_TO_LEFT)
        val n = b.runCount
        val levels = ByteArray(n)
        val objs = arrayOfNulls<Any>(n)
        for (i in 0 until n) {
            levels[i] = b.getRunLevel(i).toByte()
            objs[i] = s.substring(b.getRunStart(i), b.getRunLimit(i)) to b.getRunLevel(i)
        }
        Bidi.reorderVisually(levels, 0, objs, 0, n)
        val sb = StringBuilder()
        for (o in objs) {
            @Suppress("UNCHECKED_CAST")
            val run = o as Pair<String, Int>
            val t = run.first
            if (run.second % 2 == 1) for (i in t.indices.reversed()) sb.append(mirror(t[i]))
            else sb.append(t)
        }
        return sb[1]
    }

    @Test
    fun everyLabelMismatchCarriesAReason() {
        val offenders = ArrayList<String>()
        var mismatches = 0
        var keys = 0
        for (layout in Layouts.all) {
            for (k in layout.allKeys) {
                if (k.action != KeyAction.CHARACTER) continue
                val out = k.output ?: continue
                if (out.length != 1) continue
                keys++
                val glyph = glyphInHebrew(out[0])
                if (glyph == out[0]) {
                    // No mismatch, so a reason would be a lie about what the key does.
                    assertTrue(
                        k.labelDiffersBecause == null,
                        "${layout.id}: key '${k.label}' records a label-mismatch reason but its " +
                            "glyph and label agree. A reason for a mismatch that does not exist " +
                            "is worse than none.",
                    )
                    continue
                }
                mismatches++
                if (k.labelDiffersBecause.isNullOrBlank()) {
                    offenders.add("${layout.id}: label '${k.label}' renders as '$glyph' in Hebrew")
                }
            }
        }
        assertTrue(keys > 20, "too few character keys examined: $keys")
        // The count is pinned so that a NEW mirrored character silently entering a layout is a
        // failure rather than a quiet addition to the documented set.
        assertEquals(
            2, mismatches,
            "exactly two shipped characters mirror in Hebrew, '(' and ')'. This tree has " +
                "$mismatches. A new one must be documented deliberately, not absorbed.",
        )
        if (offenders.isNotEmpty()) {
            fail("keys whose label is not the glyph the user sees, with no reason recorded:\n  " +
                offenders.joinToString("\n  "))
        }
    }
}

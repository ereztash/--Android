package com.hebrewime.core.keyboard

import com.hebrewime.core.lexicon.HebrewText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Long-press alternates, and the character that made them necessary.
 *
 * The abbreviation feature reads `כ״כ` with a **gershayim** (U+05F4). Until this existed the
 * character was not on the keyboard at all: a user could only type the ASCII `"`, which the
 * lexicon folds so it works — but a Hebrew keyboard on which a Hebrew punctuation mark is
 * unreachable is a keyboard with a hole in it.
 *
 * Denominator: 6 tests over every layout.
 */
class LongPressTest {

    @Test
    fun theGershayimIsReachable() {
        val withAlternate = Layouts.all
            .flatMap { it.allKeys }
            .mapNotNull { it.longPressOutput }
        assertTrue(
            HebrewText.GERSHAYIM.toString() in withAlternate,
            "the gershayim U+05F4 is not reachable by any long press; `כ״כ` cannot be typed " +
                "correctly at all",
        )
        assertTrue(HebrewText.GERESH.toString() in withAlternate, "the geresh U+05F3 is not reachable")
    }

    @Test
    fun theAlternateIsNeverTheCharacterAlreadyOnTheKey() {
        // A long press REPLACES what the tap committed. An alternate equal to the output would
        // delete a character and type the same one back -- a visible flicker for nothing.
        for (layout in Layouts.all) {
            for (key in layout.allKeys) {
                val alt = key.longPressOutput ?: continue
                assertTrue(
                    alt != key.output,
                    "${layout.id}: long press on '${key.label}' commits what tapping it already " +
                        "commits",
                )
            }
        }
    }

    @Test
    fun onlyCharacterKeysCarryAlternates() {
        // The service implements the replacement by deleting key.output. A function key has no
        // output to delete, so an alternate on one would delete whatever happened to precede it.
        for (layout in Layouts.all) {
            for (key in layout.allKeys) {
                if (key.longPressOutput == null) continue
                assertEquals(
                    KeyAction.CHARACTER, key.action,
                    "${layout.id}: '${key.label}' is a ${key.action} key with a long-press " +
                        "alternate; the replacement path would delete text it did not write",
                )
                assertNotNull(key.output, "${layout.id}: '${key.label}' has an alternate but no output")
            }
        }
    }

    @Test
    fun mostKeysHaveNoAlternate() {
        // A long press that does nothing is worse than none: it teaches people to hold keys and
        // then does not reward it. Alternates are the exception, not the default.
        val total = Layouts.all.sumOf { it.allKeys.size }
        val withAlternate = Layouts.all.sumOf { l -> l.allKeys.count { it.longPressOutput != null } }
        assertTrue(
            withAlternate < total / 4,
            "$withAlternate of $total keys carry an alternate; that is a mode, not an accent",
        )
        assertTrue(withAlternate > 0, "no alternates at all")
    }

    @Test
    fun theHebrewLayoutIsTheOneThatCarriesThem() {
        val hebrew = Layouts.hebrew.allKeys.count { it.longPressOutput != null }
        assertTrue(hebrew == 0, "the Hebrew LETTER layout needs no alternates; its letters are all keys")
    }

    @Test
    fun alternatesAreSingleCharactersSoTheReplacementArithmeticHolds() {
        for (layout in Layouts.all) {
            for (key in layout.allKeys) {
                val alt = key.longPressOutput ?: continue
                assertTrue(
                    alt.codePointCount(0, alt.length) == 1,
                    "${layout.id}: '${key.label}' has a multi-code-point alternate '$alt'",
                )
            }
        }
    }
}

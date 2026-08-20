package com.hebrewime.core.keyboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Denominator: 3 layouts, 27 Hebrew letters, 26 Latin letters. */
class LayoutsTest {

    /** All 27 Hebrew letters, U+05D0..U+05EA, including the five final forms. */
    private val allHebrewLetters = ('א'..'ת').toSet()

    @Test
    fun hebrewLayoutCoversEveryHebrewLetterExactlyOnce() {
        val emitted = Layouts.hebrew.characterKeys().map { it.output!! }
            .filter { it.length == 1 && it[0] in 'א'..'ת' }
        assertEquals(27, allHebrewLetters.size, "the Hebrew block is 27 letters")
        assertEquals(
            emitted.size, emitted.toSet().size,
            "a letter appears on two keys: ${emitted.groupingBy { it }.eachCount()
                .filterValues { it > 1 }}",
        )
        val missing = allHebrewLetters - emitted.map { it[0] }.toSet()
        if (missing.isNotEmpty()) fail("layout is missing Hebrew letters: $missing")
        assertEquals(27, emitted.size)
    }

    @Test
    fun finalFormsArePresent() {
        val emitted = Layouts.hebrew.characterKeys().mapNotNull { it.output }.toSet()
        for (finalForm in listOf("ך", "ם", "ן", "ף", "ץ")) {
            assertTrue(finalForm in emitted, "final form $finalForm missing from the layout")
        }
    }

    @Test
    fun englishLayoutCoversTheLatinAlphabet() {
        val emitted = Layouts.english.characterKeys().map { it.output!! }
            .filter { it.length == 1 && it[0] in 'a'..'z' }
        assertEquals(26, emitted.toSet().size)
    }

    @Test
    fun hebrewIsRtlAndEnglishIsNot() {
        // A property of the script, not of the device locale.
        assertTrue(Layouts.hebrew.rtl)
        assertTrue(!Layouts.english.rtl)
        assertTrue(!Layouts.numeric.rtl)
    }

    @Test
    fun everyLayoutHasBackspaceSpaceAndEnter() {
        for (layout in Layouts.all) {
            val actions = layout.allKeys.map { it.action }.toSet()
            for (required in listOf(KeyAction.BACKSPACE, KeyAction.SPACE, KeyAction.ENTER)) {
                assertTrue(required in actions, "${layout.id} has no $required key")
            }
        }
    }

    @Test
    fun everyLayoutCanBeReachedFromEveryOther() {
        // A layout you can switch into but not out of is a trap.
        for (layout in Layouts.all) {
            val targets = layout.allKeys
                .filter { it.action == KeyAction.SWITCH_LAYOUT }
                .map { it.output!! }
            assertTrue(targets.isNotEmpty(), "${layout.id} offers no way out")
            for (t in targets) Layouts.byId(t) // throws if the id is unknown
        }
    }

    @Test
    fun rowWeightsAreSane() {
        for (layout in Layouts.all) {
            for ((i, row) in layout.rows.withIndex()) {
                assertTrue(row.totalWeight > 0f, "${layout.id} row $i has no width")
                assertTrue(
                    row.totalWeight <= 14f,
                    "${layout.id} row $i totals ${row.totalWeight}; keys would be unusably thin",
                )
            }
        }
    }

    @Test
    fun unknownLayoutIdIsRejected() {
        try {
            Layouts.byId("nope")
            fail("expected an exception for an unknown layout id")
        } catch (expected: IllegalArgumentException) {
            assertTrue("nope" in expected.message!!)
        }
    }
}

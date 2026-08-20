package com.hebrewime.core.keyboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Denominator: 5 tests covering every key of all three layouts (73 keys) and all 27 Hebrew
 * letters.
 */
class KeyDescriptionsTest {

    @Test
    fun everyKeyInEveryLayoutHasANonEmptyDescription() {
        var checked = 0
        for (layout in Layouts.all) {
            for (key in layout.allKeys) {
                val description = KeyDescriptions.describe(key)
                assertTrue(
                    description.isNotBlank(),
                    "${layout.id}: key '${key.label}' (${key.action}) has no description",
                )
                checked++
            }
        }
        assertTrue(checked >= 70, "only $checked keys checked; the sweep was too thin")
    }

    @Test
    fun allTwentySevenHebrewLettersAreNamed() {
        val letters = ('א'..'ת').toSet()
        assertEquals(27, letters.size)
        assertEquals(
            letters, KeyDescriptions.namedHebrewLetters,
            "every Hebrew letter must have a spoken name",
        )
    }

    @Test
    fun finalFormsAreDistinguishableFromTheirOrdinaryCounterparts() {
        // The whole reason for an explicit name table: a screen reader reading the bare glyph
        // gives a listener no way to tell final mem from mem.
        val pairs = listOf('כ' to 'ך', 'מ' to 'ם', 'נ' to 'ן', 'פ' to 'ף', 'צ' to 'ץ')
        for ((ordinary, final) in pairs) {
            val a = KeyDescriptions.describe(Key(ordinary.toString(), ordinary.toString()))
            val b = KeyDescriptions.describe(Key(final.toString(), final.toString()))
            assertNotEquals(a, b, "$ordinary and $final must not sound the same")
            assertTrue("final" in b, "$final should be announced as a final form, got '$b'")
        }
    }

    @Test
    fun noTwoKeysInARowShareADescription() {
        // Two adjacent keys reading identically make the row impossible to navigate by ear.
        for (layout in Layouts.all) {
            for ((rowIndex, row) in layout.rows.withIndex()) {
                val descriptions = row.keys.map { KeyDescriptions.describe(it) }
                assertEquals(
                    descriptions.size, descriptions.toSet().size,
                    "${layout.id} row $rowIndex has duplicate descriptions: " +
                        descriptions.groupingBy { it }.eachCount().filterValues { it > 1 },
                )
            }
        }
    }

    @Test
    fun functionalKeysAreNamedByPurposeNotByGlyph() {
        assertEquals("Delete", KeyDescriptions.describe(Key("⌫", action = KeyAction.BACKSPACE)))
        assertEquals("Space", KeyDescriptions.describe(Key(" ", " ", KeyAction.SPACE)))
        assertEquals("Enter", KeyDescriptions.describe(Key("↵", action = KeyAction.ENTER)))
        assertEquals(
            "Switch to Hebrew letters",
            KeyDescriptions.describe(Key("he", "he", KeyAction.SWITCH_LAYOUT)),
        )
    }
}

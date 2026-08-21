package com.hebrewime.core.keyboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The label size was set once from the row height, for a single Hebrew letter, and every
 * multi-character label on the keyboard was drawn at that size. No JVM test caught the
 * overflow because no JVM test draws text -- so this tests the arithmetic that decides the
 * size, which is the part that was wrong.
 *
 * ### Where these numbers come from
 * Measured off the operator's screenshot of the running keyboard, not invented. The image is
 * 891px wide; key edges land on a 12-unit grid at 74.25px, and the drawn fills measure 67.6px,
 * a difference of 6.65px which is `2 * GAP` at a 0.825 scale -- so the device is 1080px wide
 * and every figure below is that measurement divided by 0.825, in device pixels.
 *
 * The first version of this test used numbers I made up, and its own control caught them:
 * `en` did not overflow at the invented figures, so the test would have proved nothing. The
 * control stayed and the numbers were replaced with measurements.
 */
class LabelFitTest {

    private companion object {
        /**
         * The shared label size. Its value does not matter: [KeyGeometry.fitTextSize] scales
         * linearly, so every assertion here is on the RATIO and holds for any positive size.
         * A round number makes the arithmetic readable.
         */
        const val FULL = 100f

        const val GAP = 4f
        const val BREATHING_ROOM = 8f

        /** 1080px across a 12-unit grid. */
        const val UNIT = 90f
        fun drawn(units: Float) = UNIT * units - 2f * GAP
        fun available(units: Float) = drawn(units) - BREATHING_ROOM

        /** Advances measured from the screenshot, in device pixels. */
        const val EN = 87.3f            // 72px at 0.825, on a 1-unit key
        const val DIGITS_123 = 129.7f   // 107px at 0.825, on a 1.5-unit key
        const val WIDEST_LETTER = 47.3f // `ם` at 39px, the widest glyph in the top row
    }

    @Test
    fun `a single letter keeps the size the whole keyboard shares`() {
        assertEquals(FULL, KeyGeometry.fitTextSize(FULL, WIDEST_LETTER, available(1f)))
    }

    @Test
    fun `en overflowed its key, and does not after fitting`() {
        // POSITIVE CONTROL for the defect itself. If `en` ever fits at the shared size there
        // is no overflow here to fix and the assertion below would pass vacuously.
        assertTrue(EN > drawn(1f),
                   "control void: `en` (${EN}px) already fitted the drawn key (${drawn(1f)}px)")
        val fitted = KeyGeometry.fitTextSize(FULL, EN, available(1f))
        assertTrue(fitted < FULL, "must shrink; got $fitted")
        assertTrue(EN * (fitted / FULL) <= available(1f) + 0.01f)
    }

    @Test
    fun `123 overflowed its wider key, and does not after fitting`() {
        assertTrue(DIGITS_123 > drawn(1.5f),
                   "control void: `123` (${DIGITS_123}px) already fitted ${drawn(1.5f)}px")
        val fitted = KeyGeometry.fitTextSize(FULL, DIGITS_123, available(1.5f))
        assertTrue(DIGITS_123 * (fitted / FULL) <= available(1.5f) + 0.01f)
    }

    @Test
    fun `the overflow was cosmetic - neither label reached a neighbouring key`() {
        // The claim published about this defect is that it costs the border and nothing else.
        // If a label ever exceeded the key's full geometric width it could be read as
        // belonging to the key next door, and the claim would have to be widened.
        assertTrue(EN <= UNIT, "`en` (${EN}px) crossed its ${UNIT}px key")
        assertTrue(DIGITS_123 <= UNIT * 1.5f, "`123` crossed its ${UNIT * 1.5f}px key")
    }

    @Test
    fun `never grows a label, however much room the key has`() {
        assertEquals(FULL, KeyGeometry.fitTextSize(FULL, WIDEST_LETTER, 400f))
    }

    @Test
    fun `degenerate inputs return the shared size rather than zero or NaN`() {
        assertEquals(FULL, KeyGeometry.fitTextSize(FULL, 0f, available(1f)))
        assertEquals(FULL, KeyGeometry.fitTextSize(FULL, EN, 0f))
        assertEquals(FULL, KeyGeometry.fitTextSize(FULL, EN, -5f))
        assertEquals(0f, KeyGeometry.fitTextSize(0f, EN, available(1f)))
    }

    @Test
    fun `every multi-character label the shipped layouts carry is one that needed this`() {
        val multi = Layouts.all.flatMap { it.allKeys }.map { it.label }
            .filter { it.length > 1 }.distinct()
        assertTrue(multi.isNotEmpty(), "no multi-character labels ship; this fix is dead code")
        assertTrue(multi.containsAll(listOf("123", "en", "he")),
                   "expected the layout-switch labels among $multi")
    }
}

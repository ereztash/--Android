package com.hebrewime.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Turns `docs/VERIFICATION.md` into a regression test.
 *
 * Each case re-derives a composite `inputType` value from the primitives in
 * [AndroidInputTypes] and asserts it equals the literal the build spec names in its restricted
 * field set. The literals on the right-hand side are the spec's; the left-hand side is
 * computed. A typo in any primitive therefore fails here rather than silently changing which
 * fields the keyboard treats as sensitive.
 *
 * Denominator: 16 assertions over 14 distinct platform constants.
 */
class AndroidInputTypesTest {

    @Test
    fun masksMatchPlatform() {
        assertEquals(15, AndroidInputTypes.TYPE_MASK_CLASS, "TYPE_MASK_CLASS")
        assertEquals(4080, AndroidInputTypes.TYPE_MASK_VARIATION, "TYPE_MASK_VARIATION")
    }

    @Test
    fun restrictedFieldTypesComposeToTheSpecLiterals() {
        val text = AndroidInputTypes.TYPE_CLASS_TEXT
        val number = AndroidInputTypes.TYPE_CLASS_NUMBER

        assertEquals(
            0x81, text or AndroidInputTypes.TYPE_TEXT_VARIATION_PASSWORD,
            "password"
        )
        assertEquals(
            0x91, text or AndroidInputTypes.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            "visible password"
        )
        assertEquals(
            0xe1, text or AndroidInputTypes.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            "web password"
        )
        assertEquals(
            0x12, number or AndroidInputTypes.TYPE_NUMBER_VARIATION_PASSWORD,
            "numeric password"
        )
        assertEquals(
            0x21, text or AndroidInputTypes.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            "email"
        )
        assertEquals(
            0xd1, text or AndroidInputTypes.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            "web email"
        )
        assertEquals(
            0x11, text or AndroidInputTypes.TYPE_TEXT_VARIATION_URI,
            "URI"
        )
        assertEquals(0x3, AndroidInputTypes.TYPE_CLASS_PHONE, "phone")
        assertEquals(0x2, AndroidInputTypes.TYPE_CLASS_NUMBER, "number")
    }

    @Test
    fun flagsMatchSpec() {
        assertEquals(
            0x00080000, AndroidInputTypes.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            "TYPE_TEXT_FLAG_NO_SUGGESTIONS"
        )
        assertEquals(
            0x01000000, AndroidInputTypes.IME_FLAG_NO_PERSONALIZED_LEARNING,
            "IME_FLAG_NO_PERSONALIZED_LEARNING"
        )
        assertEquals(524288, AndroidInputTypes.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        assertEquals(16777216, AndroidInputTypes.IME_FLAG_NO_PERSONALIZED_LEARNING)
    }

    @Test
    fun variationMaskIsolatesTheVariationNibbles() {
        // The mask must not leak class bits, or a phone field could read as a text variation.
        val webPassword = AndroidInputTypes.TYPE_CLASS_TEXT or
            AndroidInputTypes.TYPE_TEXT_VARIATION_WEB_PASSWORD
        assertEquals(
            AndroidInputTypes.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            webPassword and AndroidInputTypes.TYPE_MASK_VARIATION
        )
        assertEquals(
            AndroidInputTypes.TYPE_CLASS_TEXT,
            webPassword and AndroidInputTypes.TYPE_MASK_CLASS
        )
    }
}

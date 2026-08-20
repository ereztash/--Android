package com.hebrewime.core.privacy

import com.hebrewime.core.text.AndroidInputTypes as T
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denominator: 15 tests. The exhaustive sweep alone covers **4,096** variation values against
 * `TYPE_CLASS_TEXT` and **16** input-type classes.
 */
class SensitiveFieldPolicyTest {

    private fun field(inputType: Int, imeOptions: Int = 0) =
        FieldDescriptor(inputType = inputType, imeOptions = imeOptions)

    // ---------------------------------------------------------------- the restricted set

    @Test
    fun everyValueInTheRestrictedSetIsRestricted() {
        val cases = mapOf(
            0x81 to RestrictionReason.PASSWORD,
            0x91 to RestrictionReason.VISIBLE_PASSWORD,
            0xe1 to RestrictionReason.WEB_PASSWORD,
            0x12 to RestrictionReason.NUMERIC_PASSWORD,
            0x21 to RestrictionReason.EMAIL,
            0xd1 to RestrictionReason.WEB_EMAIL,
            0x11 to RestrictionReason.URI,
            0x3 to RestrictionReason.PHONE,
            0x2 to RestrictionReason.NUMBER,
        )
        assertEquals(9, cases.size, "the build spec names 9 input-type values")
        for ((inputType, expected) in cases) {
            val reasons = SensitiveFieldPolicy.classify(field(inputType))
            assertTrue(
                expected in reasons,
                "inputType 0x${inputType.toString(16)} should give $expected, got $reasons",
            )
        }
    }

    @Test
    fun plainTextIsNotRestricted() {
        val start = SensitiveFieldPolicy.beginSession(field(T.TYPE_CLASS_TEXT), 0) { "hello" }
        assertEquals(InputMode.NORMAL, start.mode)
        assertTrue(start.maySuggest && start.mayLearn && start.mayReadContext)
        assertEquals("hello", start.initialTextBeforeCursor)
    }

    @Test
    fun noSuggestionsFlagRestrictsEvenPlainText() {
        val reasons = SensitiveFieldPolicy.classify(
            field(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        )
        assertTrue(RestrictionReason.NO_SUGGESTIONS_FLAG in reasons)
    }

    @Test
    fun noPersonalizedLearningFlagRestrictsEvenPlainText() {
        val reasons = SensitiveFieldPolicy.classify(
            field(T.TYPE_CLASS_TEXT, imeOptions = T.IME_FLAG_NO_PERSONALIZED_LEARNING)
        )
        assertTrue(RestrictionReason.NO_PERSONALIZED_LEARNING_FLAG in reasons)
    }

    // -------------------------------------------------- the invariant this project turns on

    @Test
    fun initialTextIsNeverEvenFETCHEDForARestrictedField() {
        // The field DID contain text. A test using an empty field would be blind: it would
        // pass whether or not the discard works.
        //
        // And the assertion is that the provider was NEVER CALLED, not merely that the result
        // is null. "Read it then throw it away" would satisfy a null check while the plaintext
        // had already been pulled into this process, copied, and left for the GC.
        val password = "correct horse battery staple"
        for (inputType in listOf(0x81, 0x91, 0xe1, 0x12, 0x21, 0xd1, 0x11, 0x3, 0x2)) {
            var invocations = 0
            val start = SensitiveFieldPolicy.beginSession(field(inputType), 12) {
                invocations++
                password
            }
            assertTrue(start.isRestricted, "0x${inputType.toString(16)} must be restricted")
            assertEquals(
                0, invocations,
                "provider was invoked for restricted inputType 0x${inputType.toString(16)}: " +
                    "the plaintext entered the process",
            )
            assertNull(start.initialTextBeforeCursor)
        }
    }

    @Test
    fun restrictedSessionsForbidEveryCapability() {
        val start = SensitiveFieldPolicy.beginSession(field(0x81), 0) { "secret" }
        assertFalse(start.mayReadContext)
        assertFalse(start.maySuggest)
        assertFalse(start.mayAutocorrect)
        assertFalse(start.mayUseComposingText)
        assertFalse(start.mayLearn)
        assertFalse(start.mayPersist)
    }

    @Test
    fun normalSessionReadsTheProviderExactlyOnce() {
        var invocations = 0
        SensitiveFieldPolicy.beginSession(field(T.TYPE_CLASS_TEXT), 0) {
            invocations++
            "context"
        }
        assertEquals(1, invocations, "a normal field should read its context once, not twice")
    }

    // ------------------------------------------------------------------- exhaustive sweeps

    @Test
    fun sweepEveryTextVariationValue() {
        // 4096 values. Anything not on the known-safe list must fail CLOSED.
        val knownSafe = setOf(0x00, 0x30, 0x40, 0x50, 0x60, 0x70, 0xa0, 0xb0, 0xc0)
        val knownRestricted = setOf(0x10, 0x20, 0x80, 0x90, 0xd0, 0xe0)
        var normals = 0
        var restricteds = 0
        for (variation in 0..0xfff) {
            val masked = variation and T.TYPE_MASK_VARIATION
            if (masked != variation) continue
            val inputType = T.TYPE_CLASS_TEXT or variation
            val reasons = SensitiveFieldPolicy.classify(field(inputType))
            when {
                variation in knownSafe -> {
                    assertTrue(
                        reasons.isEmpty(),
                        "safe variation 0x${variation.toString(16)} was restricted: $reasons",
                    )
                    normals++
                }
                variation in knownRestricted -> {
                    assertTrue(
                        reasons.isNotEmpty(),
                        "sensitive variation 0x${variation.toString(16)} was NOT restricted",
                    )
                    restricteds++
                }
                else -> {
                    assertTrue(
                        RestrictionReason.UNRECOGNISED_INPUT_TYPE in reasons,
                        "unknown variation 0x${variation.toString(16)} must fail closed, " +
                            "got $reasons",
                    )
                    restricteds++
                }
            }
        }
        assertEquals(9, normals, "exactly 9 text variations should be treated as normal")
        assertEquals(256 - 9, restricteds, "every other variation must be restricted")
    }

    @Test
    fun sweepEveryInputTypeClass() {
        // 16 possible class values. Only text, datetime and TYPE_NULL may be unrestricted.
        val unrestrictedClasses = setOf(0, T.TYPE_CLASS_TEXT, T.TYPE_CLASS_DATETIME)
        for (klass in 0..0xf) {
            val reasons = SensitiveFieldPolicy.classify(field(klass))
            if (klass in unrestrictedClasses) {
                assertTrue(reasons.isEmpty(), "class $klass should be normal, got $reasons")
            } else {
                assertTrue(reasons.isNotEmpty(), "class $klass must be restricted")
            }
        }
    }

    @Test
    fun unrecognisedClassFailsClosed() {
        // A future Android adding, say, a payment-card class must be sensitive by default.
        val reasons = SensitiveFieldPolicy.classify(field(0xf))
        assertTrue(RestrictionReason.UNRECOGNISED_INPUT_TYPE in reasons)
    }

    // ------------------------------------------------------------------------ learning

    @Test
    fun personNameAndPostalAddressSuggestButNeverLearn() {
        for ((variation, reason) in mapOf(
            0x60 to NoLearnReason.PERSON_NAME,
            0x70 to NoLearnReason.POSTAL_ADDRESS,
        )) {
            val start = SensitiveFieldPolicy.beginSession(
                field(T.TYPE_CLASS_TEXT or variation), 0,
            ) { "prior text" }
            assertEquals(InputMode.NORMAL, start.mode, "should not be fully restricted")
            assertTrue(start.maySuggest, "suggestions stay on")
            assertFalse(start.mayLearn, "but nothing is memorised")
            assertFalse(start.mayPersist)
            assertTrue(reason in start.noLearnReasons)
        }
    }

    @Test
    fun ordinaryTextBothSuggestsAndLearns() {
        val start = SensitiveFieldPolicy.beginSession(field(T.TYPE_CLASS_TEXT), 0) { "x" }
        assertTrue(start.maySuggest && start.mayLearn && start.mayPersist)
        assertTrue(start.noLearnReasons.isEmpty())
    }

    // ----------------------------------------------------------------------------- OTP

    @Test
    fun otpKeywordsInHintOrFieldNameRestrict() {
        val hints = listOf(
            "Enter OTP", "One-time code", "Verification code", "Security code",
            "6-digit PIN", "2FA code", "קוד אימות", "סיסמה חד פעמית",
        )
        for (h in hints) {
            val f = FieldDescriptor(inputType = T.TYPE_CLASS_TEXT, hintText = h)
            assertTrue(
                RestrictionReason.OTP_HEURISTIC in SensitiveFieldPolicy.classify(f),
                "hint '$h' should trip the OTP heuristic",
            )
        }
    }

    @Test
    fun shortNumericFieldsAreTreatedAsCodes() {
        for (len in 4..8) {
            val f = FieldDescriptor(inputType = T.TYPE_CLASS_NUMBER, maxLength = len)
            assertTrue(SensitiveFieldPolicy.looksLikeOneTimeCode(f), "maxLength=$len")
        }
        // A long numeric field is a quantity, not a code -- though TYPE_CLASS_NUMBER is
        // restricted anyway, so this only affects the recorded reason.
        val long = FieldDescriptor(inputType = T.TYPE_CLASS_NUMBER, maxLength = 20)
        assertFalse(SensitiveFieldPolicy.looksLikeOneTimeCode(long))
    }

    @Test
    fun ordinaryHintsDoNotTripTheHeuristic() {
        // A heuristic that fires on everything protects nothing and just removes suggestions.
        for (h in listOf("Search", "Message", "Name", "Write something", "חיפוש", "הודעה")) {
            val f = FieldDescriptor(inputType = T.TYPE_CLASS_TEXT, hintText = h)
            assertFalse(
                SensitiveFieldPolicy.looksLikeOneTimeCode(f),
                "hint '$h' should NOT trip the OTP heuristic",
            )
        }
    }
}

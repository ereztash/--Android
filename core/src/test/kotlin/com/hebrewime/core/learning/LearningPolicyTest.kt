package com.hebrewime.core.learning

import com.hebrewime.core.privacy.FieldDescriptor
import com.hebrewime.core.privacy.SensitiveFieldPolicy
import com.hebrewime.core.text.AndroidInputTypes as T
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which fields may be learned from — the policy `SensitiveFieldPolicy` has exposed since M4 and
 * that nothing read until adaptive learning existed.
 *
 * The test that matters here is not any of the "must not learn" ones. It is
 * [learningDoesHappenInAnOrdinaryTextField]: without it, every assertion below would pass on a
 * build where learning was broken and nothing was ever recorded anywhere. A privacy gate whose
 * subject never fires is not a gate, it is a description of an inert feature.
 *
 * Denominator: 8 tests over 6 field classes.
 */
class LearningPolicyTest {

    private companion object {
        /**
         * Variation values `SensitiveFieldPolicy` holds privately.
         *
         * Written as literals here rather than re-exported, because widening that object's
         * visibility to satisfy a test would be the test changing the production surface. The
         * values themselves are checked against the platform by `AndroidInputTypesTest` and
         * recorded under VERIF-SDK.
         */
        const val VARIATION_NORMAL = 0x00000000
        const val VARIATION_PERSON_NAME = 0x00000060
        const val VARIATION_POSTAL_ADDRESS = 0x00000070
    }

    private fun session(inputType: Int, hint: String? = null) =
        SensitiveFieldPolicy.beginSession(
            FieldDescriptor(inputType = inputType, hintText = hint), 0,
        ) { null }

    /**
     * POSITIVE CONTROL for every "must not learn" test in this file.
     *
     * An ordinary message field must actually learn. If this fails, the rest of the file is
     * measuring nothing.
     */
    @Test
    fun learningDoesHappenInAnOrdinaryTextField() {
        val s = session(T.TYPE_CLASS_TEXT or VARIATION_NORMAL)
        assertTrue(s.mayLearn, "POSITIVE CONTROL: a normal text field must be learnable from")

        // And end to end: a pair recorded twice in separate sessions becomes offerable.
        val model = UserNgramModel(minimumSessions = 2)
        model.record(1, 2)
        model.endSession()
        model.record(1, 2)
        assertTrue(model.logCountOf(1, 2) > 0, "POSITIVE CONTROL: learning must reach the model")
    }

    @Test
    fun passwordFieldsAreNeverLearnedFrom() {
        for (variation in listOf(
            T.TYPE_TEXT_VARIATION_PASSWORD,
            T.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            T.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) {
            assertFalse(session(T.TYPE_CLASS_TEXT or variation).mayLearn, "variation $variation")
        }
    }

    @Test
    fun everyRestrictedFieldIsAlsoUnlearnable() {
        // mayLearn is strictly stronger than maySuggest, and this pins the direction of that
        // implication rather than trusting the two flags to stay in step.
        for (variation in 0 until 4096) {
            val s = session(T.TYPE_CLASS_TEXT or variation)
            if (s.isRestricted) {
                assertFalse(
                    s.mayLearn,
                    "variation $variation is restricted but reports mayLearn",
                )
            }
        }
    }

    @Test
    fun personNameFieldsSuggestButAreNeverMemorised() {
        val s = session(T.TYPE_CLASS_TEXT or VARIATION_PERSON_NAME)
        assertTrue(s.maySuggest, "a name field still gets suggestions")
        assertFalse(s.mayLearn, "but writing names into a persistent model is a record of who "
            + "someone knows")
    }

    @Test
    fun postalAddressFieldsSuggestButAreNeverMemorised() {
        val s = session(T.TYPE_CLASS_TEXT or VARIATION_POSTAL_ADDRESS)
        assertTrue(s.maySuggest)
        assertFalse(s.mayLearn, "an address field is where someone lives")
    }

    @Test
    fun numericAndPhoneFieldsAreNeverLearnedFrom() {
        assertFalse(session(T.TYPE_CLASS_NUMBER).mayLearn)
        assertFalse(session(T.TYPE_CLASS_PHONE).mayLearn)
    }

    @Test
    fun anUnknownFieldFailsClosedForLearningToo() {
        // The same fail-closed posture M4 established for suggestions. A future Android release
        // adding a sensitive variation is protected by default rather than opened up by silence.
        assertFalse(session(0xf).mayLearn)
    }

    @Test
    fun mayPersistNeverExceedsMayLearn() {
        for (variation in 0 until 4096) {
            val s = session(T.TYPE_CLASS_TEXT or variation)
            if (s.mayPersist) {
                assertTrue(s.mayLearn, "variation $variation persists without permission to learn")
            }
        }
    }
}

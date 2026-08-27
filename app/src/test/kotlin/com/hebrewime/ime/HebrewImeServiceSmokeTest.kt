package com.hebrewime.ime

import android.view.inputmethod.EditorInfo
import com.hebrewime.core.text.AndroidInputTypes as T
import com.hebrewime.diagnostics.DeviceEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The first automated test the `:app` module has ever had.
 *
 * `:core` carries 303 tests. `:app` carried none, and `:app` is where everything is wired
 * together. All 16 of its files import `android.*`, so plain JUnit could not reach any of it
 * and nothing had.
 *
 * ### The privacy property is asserted on a BOOLEAN, never on the text
 * The obvious test — read the `EditorInfo` back and check the password is gone — names
 * `getInitialTextBeforeCursor`, and `GATE-API-1`'s `priv.initial_text` rule confines that
 * accessor to `HebrewImeService.kt` alone. Its allow-list carries the note *"widening this list
 * is a privacy decision, not a refactor"*, so the first version of this file was rewritten
 * rather than the gate widened.
 *
 * `DeviceEvidence` already records `initialTextTaken` as a count, for exactly this reason: the
 * question "did the policy ever REACH for the plaintext" is answerable without anyone reading
 * plaintext. That is what is asserted here.
 *
 * ### What this can and cannot establish
 * Robolectric runs the framework's *shadows* — a reimplementation, not the framework. This
 * verifies **our** wiring behaves given a framework input. It cannot verify the input, which is
 * exactly what `M4-DEVICE` asks. **No device-blocked row is closed by this file.**
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HebrewImeServiceSmokeTest {

    private fun evidence() =
        DeviceEvidence.read(RuntimeEnvironment.getApplication())

    @Test
    fun theServiceConstructsAndReachesOnCreate() {
        val service = Robolectric.buildService(HebrewImeService::class.java).create().get()
        assertNotNull("the IME service must construct", service)
    }

    @Test
    fun aPasswordFieldIsSeenAsRestrictedAndItsTextIsNeverReached() {
        val service = Robolectric.buildService(HebrewImeService::class.java).create().get()
        val before = evidence()
        service.onStartInput(
            EditorInfo().apply {
                inputType = T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD
                initialSelStart = 0
                initialSelEnd = 0
            },
            false,
        )
        val after = evidence()

        assertEquals(
            "a password field must be counted as restricted",
            before.restrictedSeen + 1, after.restrictedSeen,
        )
        assertEquals(
            "a restricted field must never be SERVED suggestions",
            before.restrictedServed, after.restrictedServed,
        )
        assertEquals(
            "the lazy provider must never be invoked on a restricted field -- " +
                "initialTextTaken is the whole record of whether plaintext was reached for",
            before.initialTextTaken, after.initialTextTaken,
        )
    }

    /**
     * The control. Without it the assertions above pass equally against an `onStartInput` that
     * treats EVERY field as restricted — which would break ordinary typing and would prove
     * nothing about restriction.
     */
    @Test
    fun anOrdinaryFieldIsNotCountedAsRestricted() {
        val service = Robolectric.buildService(HebrewImeService::class.java).create().get()
        val before = evidence()
        service.onStartInput(
            EditorInfo().apply {
                inputType = T.TYPE_CLASS_TEXT
                initialSelStart = 0
                initialSelEnd = 0
            },
            false,
        )
        val after = evidence()
        assertEquals(
            "control: a plain text field must NOT be counted restricted, or the test above " +
                "is vacuous",
            before.restrictedSeen, after.restrictedSeen,
        )
    }
}

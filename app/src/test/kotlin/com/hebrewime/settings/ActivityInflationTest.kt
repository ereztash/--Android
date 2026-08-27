package com.hebrewime.settings

import android.view.ViewGroup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Both user-facing screens, opened.
 *
 * Neither had ever been exercised by anything. `MI-*` and `M6-UI` are device-blocked on
 * questions about how they *look* and *feel*, which is fair — but "does it open at all" is not
 * one of those questions, and it had no coverage either. A settings screen that throws on
 * inflate is a crash the user sees on their first tap, and the only thing standing between
 * that and a release was whether someone happened to open it by hand.
 *
 * `OnboardingActivity` is `android:exported="true"` — it is the entry point another app or the
 * launcher can start, so it is the one that must not throw.
 *
 * ### This does not close `M6-UI`
 * That row asks whether the dictionary management screen has ever been **displayed**, on a
 * device, to a person. Robolectric inflates against shadows: it proves the layout resolves and
 * the code path runs, not that anything is legible or usable. The row stays device-blocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActivityInflationTest {

    @Test
    fun onboardingOpensWithoutThrowing() {
        val controller = Robolectric.buildActivity(OnboardingActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull("the exported entry-point activity must construct", activity)
        assertFalse(
            "OnboardingActivity must not finish itself immediately on open",
            activity.isFinishing,
        )
        // Non-vacuity: setContent must actually have put a view up. Without this the test
        // passes against an onCreate that does nothing at all.
        val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        assertTrue(
            "setContent must have attached a composition; content view is empty",
            root != null && root.childCount > 0,
        )
    }

    @Test
    fun settingsOpensWithoutThrowing() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull("the settings activity must construct", activity)
        assertFalse(
            "SettingsActivity must not finish itself immediately on open",
            activity.isFinishing,
        )
        val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        assertTrue(
            "setContent must have attached a composition; content view is empty",
            root != null && root.childCount > 0,
        )
    }
}

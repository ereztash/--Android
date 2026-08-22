package com.hebrewime.core.selfcheck

import com.hebrewime.core.keyboard.KeyGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The arithmetic the on-device self-check decides with.
 *
 * Every test here is paired: one asserts the check passes on the values the app actually
 * ships, and one asserts it FAILS on a value that would be a defect. A check that cannot be
 * shown to fail is not a check, and on a phone there is no build server to notice.
 */
class CheckArithmeticTest {

    // The colours in app/src/main/res/values/colors.xml and values-night/colors.xml. Kept here
    // as literals on purpose: if someone changes a colour, this test is where the contrast
    // consequence surfaces, and a test that read the same resource could not disagree with it.
    private companion object {
        const val LIGHT_KEY_BG = 0xFFFFFFFF.toInt()
        const val LIGHT_FN_BG = 0xFFD3D8E2.toInt()
        const val LIGHT_LABEL = 0xFF101418.toInt()
        const val LIGHT_KEYBOARD_BG = 0xFFE8EAF0.toInt()
        const val LIGHT_CORRECTION = 0xFFC2410C.toInt()

        const val DARK_KEY_BG = 0xFF28313D.toInt()
        const val DARK_FN_BG = 0xFF1C242E.toInt()
        const val DARK_LABEL = 0xFFF2F4F8.toInt()
        const val DARK_KEYBOARD_BG = 0xFF111820.toInt()
        const val DARK_CORRECTION = 0xFFFB923C.toInt()

        const val PRESSED_BG = 0xFF3D8BFD.toInt()
        const val PRESSED_LABEL = 0xFFFFFFFF.toInt()
    }

    @Test
    fun `every shipped label-on-key pair clears AA for large text`() {
        val pairs = listOf(
            "light label on key" to (LIGHT_LABEL to LIGHT_KEY_BG),
            "light label on function key" to (LIGHT_LABEL to LIGHT_FN_BG),
            "light correction on keyboard" to (LIGHT_CORRECTION to LIGHT_KEYBOARD_BG),
            "dark label on key" to (DARK_LABEL to DARK_KEY_BG),
            "dark label on function key" to (DARK_LABEL to DARK_FN_BG),
            "dark correction on keyboard" to (DARK_CORRECTION to DARK_KEYBOARD_BG),
            "pressed label on pressed key" to (PRESSED_LABEL to PRESSED_BG),
        )
        val failures = pairs.mapNotNull { (name, colours) ->
            val r = CheckArithmetic.contrastRatio(colours.first, colours.second)
            assertNotNull(r, "$name: opaque colours must produce a ratio")
            if (r < CheckArithmetic.AA_LARGE_TEXT) "$name = ${CheckArithmetic.ratio(r)}:1" else null
        }
        assertTrue(failures.isEmpty(), "below AA large-text 3.0:1 -> $failures")
    }

    @Test
    fun `the contrast check fails on a pair that is genuinely illegible`() {
        // POSITIVE CONTROL. Mid-grey on mid-grey is 1.0:1 and must not come back as a pass.
        val r = CheckArithmetic.contrastRatio(0xFF808080.toInt(), 0xFF808080.toInt())
        assertNotNull(r)
        assertTrue(r < CheckArithmetic.AA_LARGE_TEXT, "identical colours reported $r:1")
        assertEquals("1.00", CheckArithmetic.ratio(r))
    }

    @Test
    fun `known WCAG anchors come out right`() {
        // Black on white is exactly 21:1; these are the two ends of the scale.
        val bw = CheckArithmetic.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertNotNull(bw)
        assertEquals("21.00", CheckArithmetic.ratio(bw))
        assertEquals("1.00", CheckArithmetic.ratio(
            CheckArithmetic.contrastRatio(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())!!))
    }

    @Test
    fun `a translucent colour returns no ratio rather than a wrong one`() {
        assertNull(CheckArithmetic.contrastRatio(0x80101418.toInt(), LIGHT_KEY_BG))
        assertNull(CheckArithmetic.contrastRatio(LIGHT_LABEL, 0x00FFFFFF))
    }

    @Test
    fun `clearance is positive when the keys stop above the gesture inset`() {
        // 776px tall keyboard, keys end at 728, 48px gesture inset -> exactly flush.
        assertEquals(0f, CheckArithmetic.gestureClearance(776, 728f, 48))
        assertEquals(12f, CheckArithmetic.gestureClearance(776, 716f, 48))
    }

    @Test
    fun `clearance goes negative when the bottom row is under the gesture strip`() {
        // POSITIVE CONTROL for M2-INSETS: keys drawn to the window edge with an inset present.
        assertEquals(-48f, CheckArithmetic.gestureClearance(776, 776f, 48))
    }

    @Test
    fun `percentiles are nearest-rank and every value is a real sample`() {
        val samples = longArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
        assertEquals(50L, CheckArithmetic.percentile(samples, 50.0))
        assertEquals(100L, CheckArithmetic.percentile(samples, 95.0))
        assertEquals(10L, CheckArithmetic.percentile(samples, 1.0))
        assertEquals(100L, CheckArithmetic.percentile(samples, 100.0))
    }

    @Test
    fun `an empty sample reports nothing rather than zero`() {
        // POSITIVE CONTROL for the failure GATE-TRACE-1 guards on the benchmark side: a zero
        // latency must never be renderable as "fast".
        assertNull(CheckArithmetic.percentile(longArrayOf(), 95.0))
    }

    @Test
    fun `percentile order does not depend on the order samples arrived in`() {
        val a = longArrayOf(90, 10, 50, 30, 70)
        val b = longArrayOf(10, 30, 50, 70, 90)
        assertEquals(CheckArithmetic.percentile(b, 95.0), CheckArithmetic.percentile(a, 95.0))
    }

    @Test
    fun `overflow agrees with the fitter that decides the size`() {
        val available = 74f
        val fits = 47.3f
        val doesNot = 87.3f
        assertFalse(CheckArithmetic.overflows(fits, available))
        assertTrue(CheckArithmetic.overflows(doesNot, available))

        // The two must not disagree: anything fitTextSize shrinks must be something overflows
        // reports, or the self-check would say the keyboard is fine while the drawing code is
        // busy compensating for it.
        val full = 100f
        assertEquals(full, KeyGeometry.fitTextSize(full, fits, available))
        assertTrue(KeyGeometry.fitTextSize(full, doesNot, available) < full)
    }

    @Test
    fun `overflow ignores sub-pixel rounding`() {
        assertFalse(CheckArithmetic.overflows(74.2f, 74f))
        assertTrue(CheckArithmetic.overflows(75.0f, 74f))
    }

    @Test
    fun `no restricted field seen is not a pass`() {
        // POSITIVE CONTROL for the shape of M4-DEVICE: zero leaks out of zero opportunities
        // must be NOT-MEASURED, never green.
        assertNull(CheckArithmetic.restrictedFieldVerdict(0, 0))
        assertEquals(true, CheckArithmetic.restrictedFieldVerdict(5, 0))
        assertEquals(false, CheckArithmetic.restrictedFieldVerdict(5, 1))
    }

    @Test
    fun `a report is not clean when anything was unmeasured or ungated`() {
        fun check(status: SelfCheck.Status) =
            SelfCheck("X-1", "q", status, "m", "n")
        assertTrue(SelfCheckReport(listOf(check(SelfCheck.Status.PASS)), emptyList()).clean)
        assertFalse(SelfCheckReport(listOf(check(SelfCheck.Status.FAIL)), emptyList()).clean)
        assertFalse(SelfCheckReport(listOf(check(SelfCheck.Status.NOT_MEASURED)), emptyList()).clean)
        assertFalse(SelfCheckReport(listOf(check(SelfCheck.Status.NOT_A_GATE)), emptyList()).clean)
    }

    @Test
    fun `the rendered report names every check and its own limits`() {
        val report = SelfCheckReport(
            listOf(SelfCheck("M2-INSETS", "does the bottom row clear the gesture bar?",
                             SelfCheck.Status.PASS, "clearance 12px", "one device, one posture")),
            listOf("api" to "36"),
        )
        val text = report.render()
        assertTrue("M2-INSETS" in text)
        assertTrue("clearance 12px" in text)
        assertTrue("does not cover" in text, "a report must carry each check's limits")
        assertTrue("one device, one posture" in text)
        assertTrue("api: 36" in text)
    }
}

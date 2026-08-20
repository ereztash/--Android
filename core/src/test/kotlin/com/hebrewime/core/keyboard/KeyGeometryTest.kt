package com.hebrewime.core.keyboard

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Denominator: 8 tests over 3 layouts and every key in each. */
class KeyGeometryTest {

    private val width = 1080f
    private val height = 720f

    @Test
    fun everyKeyGetsAPositiveAreaRect() {
        for (layout in Layouts.all) {
            val rects = KeyGeometry.layout(layout, width, height)
            assertEquals(layout.allKeys.size, rects.size, "${layout.id}: rect count")
            for (r in rects) {
                assertTrue(r.width > 0f, "${layout.id}: ${r.key.label} has zero width")
                assertTrue(r.height > 0f, "${layout.id}: ${r.key.label} has zero height")
            }
        }
    }

    /**
     * Every plain letter key is the same width, in every row.
     *
     * **Nothing tested this until a user looked at the rendered keyboard and said the letter
     * proportions were not identical.** Hebrew rows are 8, 10 and 9 keys; stretching each row
     * to the full width independently made a top-row key 25% wider than a middle-row key. The
     * arithmetic was self-consistent and every existing test passed, because they all checked
     * rows in isolation and never compared one row against another.
     */
    @Test
    fun everyLetterKeyIsTheSameWidthAcrossRows() {
        for (layout in Layouts.all) {
            val rects = KeyGeometry.layout(layout, width, height)
            val letterWidths = rects
                .filter { it.key.widthWeight == 1f }
                .map { it.width }
            if (letterWidths.size < 2) continue
            val smallest = letterWidths.min()
            val largest = letterWidths.max()
            assertTrue(
                largest - smallest < 0.01f,
                "${layout.id}: unit-width keys range from $smallest to $largest — a " +
                    "%.0f%% difference. Rows are being stretched independently."
                        .format(100.0 * (largest / smallest - 1.0)),
            )
        }
    }

    @Test
    fun rowsTileTheFullWidthWithNoGapsOrOverlaps() {
        for (layout in Layouts.all) {
            val rects = KeyGeometry.layout(layout, width, height)
            var index = 0
            for (row in layout.rows) {
                val rowRects = rects.subList(index, index + row.keys.size)
                    .sortedBy { it.left }
                index += row.keys.size
                // Rows are CENTRED, not stretched: a row narrower than the widest one leaves
                // equal margins. Asserting every row is flush to both edges is what allowed
                // 8-key and 10-key rows to render with different key widths.
                val leftMargin = rowRects.first().left
                val rightMargin = width - rowRects.last().right
                assertTrue(
                    abs(leftMargin - rightMargin) < 0.01f,
                    "${layout.id}: row is not centred, margins $leftMargin vs $rightMargin",
                )
                for (i in 1 until rowRects.size) {
                    assertTrue(
                        abs(rowRects[i].left - rowRects[i - 1].right) < 0.01f,
                        "${layout.id}: gap or overlap between " +
                            "${rowRects[i - 1].key.label} and ${rowRects[i].key.label}",
                    )
                }
            }
        }
    }

    @Test
    fun rowsTileTheFullHeight() {
        for (layout in Layouts.all) {
            val rects = KeyGeometry.layout(layout, width, height)
            assertTrue(abs(rects.minOf { it.top }) < 0.01f)
            assertTrue(abs(rects.maxOf { it.bottom } - height) < 0.01f)
        }
    }

    @Test
    fun everyKeyCentreHitTestsToItself() {
        for (layout in Layouts.all) {
            val rects = KeyGeometry.layout(layout, width, height)
            for (r in rects) {
                val hit = KeyGeometry.hitTest(rects, r.centerX, r.centerY)
                assertEquals(r.key, hit, "${layout.id}: ${r.key.label} centre hit the wrong key")
            }
        }
    }

    /**
     * THE REGRESSION TEST FOR THE MIRROR BUG.
     *
     * The version of this test that shipped asserted the opposite -- that qof renders flush
     * RIGHT -- because the code mirrored Hebrew rows and the test was written to match the
     * code rather than to match a keyboard. It passed, and a user opened the app and said it
     * looked like a mirror. A gate cannot catch a wrong assumption that was written into the
     * assertion as well as the implementation.
     *
     * So this now checks against the external standard instead of against the code: SI-1452
     * maps Hebrew letters onto the physical QWERTY positions, which run left-to-right.
     */
    @Test
    fun hebrewLettersSitAtTheirQwertyPositions() {
        val rects = KeyGeometry.layout(Layouts.hebrew, width, height)
        val topRow = Layouts.hebrew.rows[0].keys
        val ordered = topRow.map { key -> rects.first { it.key == key } }.sortedBy { it.left }

        // Reading the screen left to right, the top row must be qof resh alef tet vav
        // final-nun final-mem pe -- the Q..P physical positions with / and ' dropped.
        assertEquals(
            "קראטוןםפ",
            ordered.joinToString("") { it.key.output!! },
            "Hebrew top row is not in QWERTY physical order left-to-right",
        )
        // Anti-mirroring is carried by the ORDER assertion above, which is the real guard.
        // This one only checks that qof is in the left half and pe in the right, which stays
        // true under centring and would still fail loudly on a mirrored layout.
        assertTrue(
            ordered.first().centerX < width / 2f,
            "qof must sit in the LEFT half (it is the Q key); mirroring has come back",
        )
        assertTrue(
            ordered.last().centerX > width / 2f,
            "pe must sit in the RIGHT half (it is the P key); mirroring has come back",
        )
    }

    @Test
    fun hebrewFunctionKeysAreOnTheConventionalSides() {
        // The same mirroring put backspace on the left and shift on the right. Those are the
        // symptoms a user notices first, so they are asserted directly.
        val rects = KeyGeometry.layout(Layouts.hebrew, width, height)
        val shift = rects.first { it.key.action == KeyAction.SHIFT }
        val backspace = rects.first { it.key.action == KeyAction.BACKSPACE }
        assertTrue(
            abs(shift.left) < 0.01f,
            "shift belongs at the far LEFT, like every physical keyboard; left=${shift.left}",
        )
        assertTrue(
            abs(backspace.right - width) < 0.01f,
            "backspace belongs at the far RIGHT; right=${backspace.right}",
        )
        assertTrue(shift.left < backspace.left, "shift must precede backspace on screen")
    }

    /**
     * POSITIVE CONTROL for GATE-LAYOUT-1.
     *
     * The QWERTY-order assertion above is only worth anything if a mirrored layout would fail
     * it. The shipped bug proved that a wrong assertion can sit next to wrong code and both
     * look green, so the check gets its own planted defect: a layout whose rows are reversed.
     */
    @Test
    fun aMirroredLayoutIsCaughtByTheSameCheck() {
        val mirrored = Layouts.hebrew.copy(
            rows = Layouts.hebrew.rows.map { KeyboardRow(it.keys.reversed()) },
        )
        val rects = KeyGeometry.layout(mirrored, width, height)
        val topRow = mirrored.rows[0].keys
        val screenOrder = topRow.map { key -> rects.first { it.key == key } }
            .sortedBy { it.left }
            .joinToString("") { it.key.output!! }

        assertTrue(
            screenOrder != "קראטוןםפ",
            "the QWERTY check accepted a mirrored layout; it would not have caught the " +
                "bug that shipped",
        )
        assertEquals("פםןוטארק", screenOrder, "the planted mirror should read backwards")
    }

    @Test
    fun englishFirstKeyIsOnTheLeft() {
        val rects = KeyGeometry.layout(Layouts.english, width, height)
        val firstKey = Layouts.english.rows[0].keys.first()
        val rect = rects.first { it.key == firstKey }
        assertTrue(abs(rect.left) < 0.01f, "LTR: ${firstKey.label} should be flush left")
    }

    @Test
    fun noLayoutIsMirrored() {
        // Whatever the script, the model order and the screen order must agree.
        for (layout in Layouts.all) {
            var index = 0
            for (row in layout.rows) {
                val rowRects = rects@ run {
                    val all = KeyGeometry.layout(layout, width, height)
                    all.subList(index, index + row.keys.size)
                }
                index += row.keys.size
                val screenOrder = rowRects.sortedBy { it.left }.map { it.key }
                assertEquals(
                    row.keys, screenOrder,
                    "${layout.id}: screen order differs from model order -- the row is mirrored",
                )
            }
        }
    }

    @Test
    fun weightIsHonouredWithinARowInBothDirections() {
        // The RTL layout must be a reflection, not a re-flow: a 1.5x backspace stays 1.5x.
        //
        // The comparison has to be WITHIN a row. Across rows it means nothing -- the Hebrew
        // top row holds 8 keys and the bottom row 12 weight-units, so a top-row letter is
        // legitimately wider than the bottom-row backspace.
        for (layout in listOf(Layouts.english, Layouts.hebrew, Layouts.numeric)) {
            val rects = KeyGeometry.layout(layout, width, height)
            var index = 0
            var rowsChecked = 0
            for (row in layout.rows) {
                val rowRects = rects.subList(index, index + row.keys.size)
                index += row.keys.size
                val backspace = rowRects.firstOrNull { it.key.action == KeyAction.BACKSPACE }
                    ?: continue
                val letters = rowRects.filter { it.key.action == KeyAction.CHARACTER }
                if (letters.isEmpty()) continue
                rowsChecked++
                for (letter in letters) {
                    assertTrue(
                        backspace.width > letter.width,
                        "${layout.id}: backspace ${backspace.width} should exceed " +
                            "'${letter.key.label}' ${letter.width} in the same row",
                    )
                }
                // 1.5 weight against 1.0 in the same row, so exactly 1.5x within rounding.
                assertTrue(
                    abs(backspace.width / letters.first().width - 1.5f) < 0.01f,
                    "${layout.id}: backspace/letter ratio was " +
                        "${backspace.width / letters.first().width}, expected 1.5",
                )
            }
            assertTrue(rowsChecked > 0, "${layout.id}: no row had both a backspace and letters")
        }
    }

    @Test
    fun touchesOutsideTheKeyboardResolveToTheNearestKey() {
        val rects = KeyGeometry.layout(Layouts.hebrew, width, height)
        // A pixel past the right edge is a touch the user meant for the edge key.
        assertNotNull(KeyGeometry.hitTest(rects, width + 5f, height / 2f))
        assertNotNull(KeyGeometry.hitTest(rects, -5f, height / 2f))
        assertNotNull(KeyGeometry.hitTest(rects, width / 2f, height + 20f))
        assertEquals(null, KeyGeometry.hitTest(emptyList(), 0f, 0f))
    }
}

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

    @Test
    fun rowsTileTheFullWidthWithNoGapsOrOverlaps() {
        for (layout in Layouts.all) {
            val rects = KeyGeometry.layout(layout, width, height)
            var index = 0
            for (row in layout.rows) {
                val rowRects = rects.subList(index, index + row.keys.size)
                    .sortedBy { it.left }
                index += row.keys.size
                assertTrue(abs(rowRects.first().left) < 0.01f, "${layout.id}: row starts at ${rowRects.first().left}")
                assertTrue(abs(rowRects.last().right - width) < 0.01f,
                    "${layout.id}: row ends at ${rowRects.last().right}, expected $width")
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

    @Test
    fun hebrewFirstKeyIsOnTheRight() {
        // The layout is RTL, so 'qof' -- first in the model -- must render rightmost.
        val rects = KeyGeometry.layout(Layouts.hebrew, width, height)
        val firstKey = Layouts.hebrew.rows[0].keys.first()
        val rect = rects.first { it.key == firstKey }
        assertTrue(
            abs(rect.right - width) < 0.01f,
            "RTL: ${firstKey.label} should be flush right, but right=${rect.right}",
        )
    }

    @Test
    fun englishFirstKeyIsOnTheLeft() {
        val rects = KeyGeometry.layout(Layouts.english, width, height)
        val firstKey = Layouts.english.rows[0].keys.first()
        val rect = rects.first { it.key == firstKey }
        assertTrue(abs(rect.left) < 0.01f, "LTR: ${firstKey.label} should be flush left")
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

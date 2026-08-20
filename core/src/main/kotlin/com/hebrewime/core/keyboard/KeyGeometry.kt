package com.hebrewime.core.keyboard

/** A key's position, in pixels, within the keyboard view. */
data class KeyRect(
    val key: Key,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    /** Squared distance from (x, y) to this rect; 0 inside. Used for nearest-key fallback. */
    fun distanceSquaredTo(x: Float, y: Float): Float {
        val dx = when {
            x < left -> left - x
            x > right -> x - right
            else -> 0f
        }
        val dy = when {
            y < top -> top - y
            y > bottom -> y - bottom
            else -> 0f
        }
        return dx * dx + dy * dy
    }
}

/**
 * Turns a [KeyboardLayout] plus a pixel size into key rectangles, and resolves touches back to
 * keys.
 *
 * This is in `:core`, not in the View, because it is arithmetic with an easy off-by-one and a
 * mirroring step that is exactly the kind of thing that looks fine and is wrong for RTL. Here
 * it can be tested without inflating anything.
 */
object KeyGeometry {

    /**
     * @param rtl mirrors each row horizontally. Applied as a reflection of the finished
     *   left-to-right layout rather than by laying rows out backwards: reflection is a single
     *   operation that cannot drift, and it keeps row tiling exact.
     */
    fun layout(
        layout: KeyboardLayout,
        width: Float,
        height: Float,
    ): List<KeyRect> {
        require(width > 0f && height > 0f) { "keyboard size must be positive" }
        val rowHeight = height / layout.rows.size
        val out = ArrayList<KeyRect>(layout.allKeys.size)

        layout.rows.forEachIndexed { rowIndex, row ->
            val top = rowIndex * rowHeight
            val bottom = if (rowIndex == layout.rows.size - 1) height else top + rowHeight
            val total = row.totalWeight
            var consumedWeight = 0f
            row.keys.forEachIndexed { keyIndex, key ->
                // Derive each edge from the cumulative weight rather than advancing a cursor,
                // so rounding cannot accumulate and leave a dead strip at the row's end.
                val left = width * (consumedWeight / total)
                consumedWeight += key.widthWeight
                val right = if (keyIndex == row.keys.size - 1) {
                    width
                } else {
                    width * (consumedWeight / total)
                }
                out += if (layout.rtl) {
                    KeyRect(key, width - right, top, width - left, bottom)
                } else {
                    KeyRect(key, left, top, right, bottom)
                }
            }
        }
        return out
    }

    /**
     * The key at (x, y), or the nearest one if the touch landed in a gap or just off the edge.
     *
     * Nearest-key fallback rather than null: a touch a pixel outside the keyboard is a touch
     * the user meant for the nearest key, and returning nothing would make edge keys feel
     * broken.
     */
    fun hitTest(rects: List<KeyRect>, x: Float, y: Float): Key? {
        if (rects.isEmpty()) return null
        rects.firstOrNull { it.contains(x, y) }?.let { return it.key }
        return rects.minByOrNull { it.distanceSquaredTo(x, y) }?.key
    }
}

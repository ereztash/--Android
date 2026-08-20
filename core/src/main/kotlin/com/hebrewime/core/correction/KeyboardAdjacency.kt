package com.hebrewime.core.correction

import com.hebrewime.core.keyboard.KeyAction
import com.hebrewime.core.keyboard.KeyGeometry
import com.hebrewime.core.keyboard.KeyboardLayout
import kotlin.math.abs

/**
 * Which keys are physically next to which, derived from the layout's own geometry.
 *
 * ### Why derived rather than listed
 * A hand-written neighbour table is a second copy of the layout that silently stops matching
 * the first. Rearranging a row would leave the table describing a keyboard that no longer
 * exists, and the only symptom would be slightly worse corrections — nothing that fails.
 * Here the neighbours come from [KeyGeometry], so the layout is the single source of truth.
 *
 * ### The adjacency rule
 * Two character keys are neighbours when they are in the same row and horizontally touching,
 * or in adjacent rows with overlapping horizontal extent. Grid adjacency rather than
 * centre-to-centre distance, because distance depends on the aspect ratio the keyboard happens
 * to be drawn at, and "is qof next to resh" must not change with screen size.
 */
class KeyboardAdjacency private constructor(
    private val neighbours: Map<Char, Set<Char>>,
) {

    fun neighboursOf(c: Char): Set<Char> = neighbours[c] ?: emptySet()

    fun areAdjacent(a: Char, b: Char): Boolean = b in neighboursOf(a)

    /** Characters with at least one neighbour. Used by the tests to state a denominator. */
    val coveredCharacters: Set<Char> get() = neighbours.keys

    companion object {
        /**
         * Nominal geometry. Adjacency is a property of the grid, not of the pixel size, so any
         * consistent size gives the same answer; these numbers only have to be positive.
         */
        private const val NOMINAL_WIDTH = 1080f
        private const val NOMINAL_HEIGHT = 720f

        /** How much horizontal overlap two keys need to count as vertical neighbours. */
        private const val OVERLAP_TOLERANCE = 0.15f

        fun from(layout: KeyboardLayout): KeyboardAdjacency {
            val rects = KeyGeometry.layout(layout, NOMINAL_WIDTH, NOMINAL_HEIGHT)
                .filter { it.key.action == KeyAction.CHARACTER && it.key.output?.length == 1 }

            // Group by row using the top edge, which is identical within a row by construction.
            val rowTops = rects.map { it.top }.distinct().sorted()
            val rowOf = rects.associateWith { rowTops.indexOf(it.top) }

            val map = HashMap<Char, MutableSet<Char>>()
            for (a in rects) {
                for (b in rects) {
                    if (a === b) continue
                    val rowGap = abs(rowOf.getValue(a) - rowOf.getValue(b))
                    if (rowGap > 1) continue

                    val adjacent = if (rowGap == 0) {
                        // Same row: touching edges.
                        abs(a.right - b.left) < 1f || abs(b.right - a.left) < 1f
                    } else {
                        // Neighbouring row: horizontal extents must genuinely overlap, not
                        // merely touch at a corner.
                        val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
                        overlap > OVERLAP_TOLERANCE * minOf(a.width, b.width)
                    }
                    if (adjacent) {
                        map.getOrPut(a.key.output!![0]) { mutableSetOf() }
                            .add(b.key.output!![0])
                    }
                }
            }
            return KeyboardAdjacency(map.mapValues { it.value.toSet() })
        }
    }
}

package com.hebrewime.core.scratch

import com.hebrewime.core.correction.KeyboardAdjacency
import com.hebrewime.core.keyboard.Layouts

/**
 * `R1` — are the writer's actual substitutions the ones the shipped cost model discounts?
 *
 * `AdjacencyCostModel` has exactly one discount, and it is geometric: neighbouring keys cost
 * less to substitute. Its own KDoc states the assumption plainly — *"typing qof where resh was
 * meant is a slip of the thumb; typing qof where tav was meant is a different word."*
 *
 * The fifteen messages contain the pairs below. This asks the shipped `KeyboardAdjacency`,
 * derived from the real layout geometry, whether any of them is discounted.
 *
 * Reads the layout only. It does not touch the messages, so it cannot disturb the frozen labels.
 */
object HomophoneAdjacency {
    @JvmStatic
    fun main(args: Array<String>) {
        val adj = KeyboardAdjacency.from(Layouts.hebrew)
        val observed = listOf(
            'ח' to 'כ', 'ק' to 'כ', 'ת' to 'ט', 'א' to 'ע', 'ו' to 'ה', 'ה' to 'ח', 'ה' to 'א',
        )
        println("pair   adjacent?   (a discount applies only when true)")
        var discounted = 0
        for ((a, b) in observed) {
            val yes = adj.areAdjacent(a, b) || adj.areAdjacent(b, a)
            if (yes) discounted++
            println("$a / $b     $yes")
        }
        println("\ndiscounted: $discounted of ${observed.size}")

        // The control: pairs the model was BUILT for. If these come back false the probe is
        // asking the wrong question and nothing above it means anything.
        val slips = listOf('ק' to 'ר', 'ש' to 'ד', 'ב' to 'ה')
        val slipYes = slips.count { adj.areAdjacent(it.first, it.second) }
        println("thumb-slip control: $slipYes of ${slips.size} adjacent  " +
            if (slipYes == slips.size) "(probe works)" else "(PROBE BROKEN)")
    }
}

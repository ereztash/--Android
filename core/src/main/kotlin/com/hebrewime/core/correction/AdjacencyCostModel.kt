package com.hebrewime.core.correction

/**
 * Damerau-Levenshtein with a discount for substitutions between physically adjacent keys.
 *
 * Typing *qof* where *resh* was meant is a slip of the thumb; typing *qof* where *tav* was
 * meant is a different word. Charging both a full edit throws away the strongest signal a
 * touch keyboard has about which mistake was made.
 *
 * ### Every weight here was chosen after measurement
 * The values are **not** defaults picked by taste. `docs/CORRECTION_MEASUREMENTS.md` records
 * the neutral baseline first — all edits costing one unit — and then each weight with the
 * before-and-after numbers it produced. If a weight would have to move to make a suite pass,
 * that is a conflict to report, not an edit to make.
 *
 * @param adjacentSubstitution cost of substituting a neighbouring key, in hundredths.
 * @param transposition cost of swapping two adjacent characters, in hundredths. Transposition
 *   is a timing slip rather than a knowledge error, so it can be cheaper than a full edit.
 */
class AdjacencyCostModel(
    private val adjacency: KeyboardAdjacency,
    private val adjacentSubstitution: Int = EditCostModel.UNIT,
    private val transposition: Int = EditCostModel.UNIT,
    private val insertion: Int = EditCostModel.UNIT,
    private val deletion: Int = EditCostModel.UNIT,
) : EditCostModel {

    override fun substitute(typed: Char, candidate: Char): Int =
        if (adjacency.areAdjacent(typed, candidate)) adjacentSubstitution else EditCostModel.UNIT

    override fun insert(candidate: Char): Int = insertion

    override fun delete(typed: Char): Int = deletion

    override fun transpose(a: Char, b: Char): Int = transposition

    /**
     * The cheapest any single edit can be. The trie's pruning uses this, so it must be a true
     * lower bound: claiming a higher minimum than the model can actually charge would prune
     * subtrees that contain reachable candidates.
     */
    override val minimumEditCost: Int =
        minOf(adjacentSubstitution, transposition, insertion, deletion, EditCostModel.UNIT)
}

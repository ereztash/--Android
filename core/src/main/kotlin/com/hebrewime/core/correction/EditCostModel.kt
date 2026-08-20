package com.hebrewime.core.correction

/**
 * Costs for the edit operations, in hundredths so the search can stay in integer arithmetic.
 *
 * **Every weight in a tuned model was chosen AFTER a baseline was measured against a
 * hash-locked corpus, never before.** See `docs/CORRECTION_MEASUREMENTS.md`, which reports the
 * neutral baseline and the tuned result side by side. A weight is not allowed to move in order
 * to make a test pass.
 */
interface EditCostModel {
    fun substitute(typed: Char, candidate: Char): Int
    fun insert(candidate: Char): Int
    fun delete(typed: Char): Int
    fun transpose(a: Char, b: Char): Int

    /** Smallest cost any single edit can have. The search uses it to prune. */
    val minimumEditCost: Int

    companion object {
        /** One whole edit. All costs are expressed relative to this. */
        const val UNIT = 100
    }
}

/**
 * Every edit costs exactly one unit. This is plain Damerau-Levenshtein.
 *
 * This is the **baseline** model: the engine is measured with it before any weight is chosen,
 * so that the effect of every later weight is a delta against a real number rather than
 * against an assumption.
 */
object NeutralCostModel : EditCostModel {
    override fun substitute(typed: Char, candidate: Char): Int = EditCostModel.UNIT
    override fun insert(candidate: Char): Int = EditCostModel.UNIT
    override fun delete(typed: Char): Int = EditCostModel.UNIT
    override fun transpose(a: Char, b: Char): Int = EditCostModel.UNIT
    override val minimumEditCost: Int = EditCostModel.UNIT
}

package com.hebrewime.core.prediction

import com.hebrewime.core.confusion.RealWordErrorDetector
import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * S1+P1 through the engine, on the shipped artifacts — the claim `WideContextVerdictTest` does
 * not make.
 *
 * That test measures the detector. This one proves the **engine** reaches the two new evidence
 * paths, because a layer wired into `CorrectionController` and then starved of context by
 * `TypingContext` would measure exactly as well and do exactly nothing. It was starved: the app
 * handed the engine three completed words, and the distance-2 left neighbour is the fourth.
 *
 * Both sentences are taken from `hewiki_confusion_test.txt.gz` rather than invented, and both
 * are positions where the adjacent window is **blind** — `check()` returns null on each.
 *
 * Denominator: 4 tests, 2 corpus positions.
 */
class PredictiveEngineWideContextTest {

    /**
     * `completed` is most recent first, so the window the detector sees is
     * `completed[3] completed[2] [completed[1]] completed[0]`.
     */
    private fun context(vararg completedNewestFirst: String) =
        TypingContext("", completedNewestFirst.toList())

    private fun engine(skip: Boolean, prior: Boolean): PredictiveEngine {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val words = ArrayList<String>(lexicon.size)
        for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))
        val trie = LexiconTrie.build(words)
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        val skipgrams = if (skip) File(System.getProperty("skipgram.file")!!)
            .inputStream().use { BigramModel.load(it) } else BigramModel.EMPTY
        return PredictiveEngine(
            lexicon, trie, frequency, bigrams,
            CorrectionEngine(lexicon, trie, frequency, NeutralCostModel, CorrectionEngine.Config()),
            // skipMargin is passed EXPLICITLY. The shipped default is now 0, which is off:
            // S1 is withdrawn. Handing the constructor a table is no longer enough to turn the
            // layer on, and that is the withdrawal working rather than a test to fix.
            realWordErrors = RealWordErrorDetector(
                lexicon, bigrams, skipgrams, if (prior) frequency else null,
                RealWordErrorDetector.Config(
                    skipMargin = if (skip) 80 else 0,
                ),
            ),
        )
    }

    private fun flagged(engine: PredictiveEngine, context: TypingContext): Prediction? =
        engine.predict(context).firstOrNull { it.kind == SuggestionKind.REAL_WORD_ERROR }

    /** `בין הכניסה [לוין] הקתוליקון` — decided by distance-2 evidence for `בין … לבין`. */
    private fun skipCase() = context("הקתוליקון", "לוין", "הכניסה", "בין")

    /** `למערכת החוקים [נעמר] שהתאוריה` — no counts anywhere; decided by the prior. */
    private fun priorCase() = context("שהתאוריה", "נעמר", "החוקים", "למערכת")

    /**
     * Kept, and no longer a statement about production: S1 is withdrawn and
     * `CorrectionController` passes no skip table. This asserts the CODE PATH still works when
     * a table is handed to it, so the sweep and verdict stay reproducible and re-enabling is a
     * constant rather than a rebuild.
     */
    @Test
    fun theDistance2LayerReachesTheStripWhenItIsGivenATable() {
        val f = assertNotNull(
            flagged(engine(skip = true, prior = false), skipCase()),
            "the distance-2 layer produced nothing at a position the sweep says it catches",
        )
        assertEquals("לבין", f.word)
        assertEquals("לוין", f.replaces)
        assertEquals(2, f.wordsBack)
    }

    /**
     * The fourth completed word is load-bearing, not decoration.
     *
     * `CONTEXT_WORDS` went 3 → 4 for this and back to 3 when the layer was withdrawn, so in
     * production the fourth word is now absent by design. The test still pins the dependency,
     * because the pair "table in the APK, context not supplied" is exactly the silent-nothing
     * failure this project keeps finding, and re-enabling S1 means restoring both.
     */
    @Test
    fun withoutTheFourthCompletedWordTheDistance2LayerCannotFire() {
        val threeWords = context("הקתוליקון", "לוין", "הכניסה")
        assertNull(
            flagged(engine(skip = true, prior = false), threeWords),
            "the distance-2 layer fired without the word it reads; the test is not testing it",
        )
    }

    @Test
    fun theBlindPositionPriorReachesTheStrip() {
        val f = assertNotNull(
            flagged(engine(skip = false, prior = true), priorCase()),
            "the prior fallback produced nothing at a position the sweep says it catches",
        )
        assertEquals("נאמר", f.word)
        assertEquals("נעמר", f.replaces)
    }

    /**
     * CONTROL: both positions are blind, so neither is a case the adjacent detector already had.
     *
     * Without it, the two tests above would pass just as well if `checkWide` were quietly
     * delegating everything to `check` and the new paths were dead code.
     */
    @Test
    fun theAdjacentDetectorAloneFindsNeitherOfThem() {
        val adjacentOnly = engine(skip = false, prior = false)
        assertNull(flagged(adjacentOnly, skipCase()), "not a blind position after all")
        assertNull(flagged(adjacentOnly, priorCase()), "not a blind position after all")
    }
}

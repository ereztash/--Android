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
import kotlin.test.assertTrue

/**
 * The whole path the IME actually calls: a [TypingContext] in, a ranked strip out.
 *
 * The unit tests below it prove the detector finds errors and the buffer produces context.
 * This proves the two are wired together — which is a separate claim, and the one that was
 * false for the entire time the keyboard was mirrored.
 *
 * Denominator: 6 tests.
 */
class PredictiveEngineContextTest {

    private fun engine(withDetector: Boolean = true): Pair<PredictiveEngine, HebrewLexicon> {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val words = ArrayList<String>(lexicon.size)
        for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))
        val trie = LexiconTrie.build(words)
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        val corrections = CorrectionEngine(
            lexicon, trie, frequency, NeutralCostModel, CorrectionEngine.Config(),
        )
        return PredictiveEngine(
            lexicon, trie, frequency, bigrams, corrections,
            realWordErrors = if (withDetector) RealWordErrorDetector(lexicon, bigrams) else null,
        ) to lexicon
    }

    /** `completed` is most recent first, so this is `... דיברתי אם המורה` with `של` in progress. */
    private fun context(current: String, vararg completedNewestFirst: String) =
        TypingContext(current, completedNewestFirst.toList())

    @Test
    fun aRealWordErrorTakesTheFirstSlotAndNamesTheWordItReplaces() {
        val (e, _) = engine()
        val out = e.predict(context("של", "המורה", "אם", "דיברתי"))
        assertTrue(out.isNotEmpty(), "expected a suggestion for דיברתי [אם] המורה")
        val first = out.first()
        assertEquals(SuggestionKind.REAL_WORD_ERROR, first.kind)
        assertEquals("עם", first.word)
        assertEquals("אם", first.replaces)
        assertEquals(2, first.wordsBack, "it replaces the SECOND most recent completed word")
        println("strip: " + out.joinToString(", ") { "${it.word}[${it.kind}]" })
    }

    @Test
    fun completionsForTheCurrentWordStillFollowIt() {
        val (e, _) = engine()
        val out = e.predict(context("של", "המורה", "אם", "דיברתי"))
        assertTrue(
            out.drop(1).all { it.wordsBack == 0 },
            "everything after the flagged word must apply to the word under the cursor",
        )
        assertTrue(out.size > 1, "a real-word error must not crowd out the ordinary suggestions")
    }

    @Test
    fun correctHebrewIsLeftAlone() {
        val (e, _) = engine()
        val out = e.predict(context("", "חברים", "עם", "אני"))
        assertTrue(
            out.none { it.kind == SuggestionKind.REAL_WORD_ERROR },
            "אני עם חברים is correct and must not be flagged: ${out.map { it.word }}",
        )
    }

    @Test
    fun withoutADetectorTheEngineBehavesExactlyAsItDidInM10() {
        val (with, _) = engine(withDetector = true)
        val (without, _) = engine(withDetector = false)
        // A context with no real-word error must give identical answers either way, so the
        // M10 numbers are not quietly a different measurement now.
        val ctx = context("שלו", "חברים", "עם", "אני")
        assertEquals(
            without.predict(ctx).map { it.word to it.kind },
            with.predict(ctx).map { it.word to it.kind },
        )
    }

    @Test
    fun oneCompletedWordIsNotEnoughToCheckAnything() {
        val (e, _) = engine()
        // Only one completed word: nothing has a right-hand neighbour, so nothing is checked.
        val out = e.predict(context("", "אם"))
        assertTrue(out.none { it.kind == SuggestionKind.REAL_WORD_ERROR })
    }

    @Test
    fun theTwoArgumentOverloadStillWorksAndNeverFlags() {
        val (e, _) = engine()
        val out = e.predict("של", "המורה")
        assertNotNull(out)
        assertTrue(
            out.none { it.kind == SuggestionKind.REAL_WORD_ERROR },
            "the M10 signature carries one word of context and cannot support a both-sides check",
        )
    }
}

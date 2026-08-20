package com.hebrewime.core.prediction

import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.dictionary.PersonalDictionary
import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The personal dictionary has to actually affect typing.
 *
 * M6 built an encrypted store and a settings screen for it, and nothing read it: a user could
 * add a word and watch the keyboard go on calling it a misspelling. A setting that does nothing
 * and then contradicts itself on screen is worse than no setting.
 *
 * Denominator: 8 tests.
 */
class PersonalDictionaryIntegrationTest {

    /** A name that is definitely not in a 355,587-word lexicon. Asserted, not assumed. */
    private val invented = "זרגלוב"

    private fun parts(): Triple<HebrewLexicon, LexiconTrie, HebrewFrequency> {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val words = ArrayList<String>(lexicon.size)
        for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        return Triple(lexicon, LexiconTrie.build(words), frequency)
    }

    private fun corrections(personal: PersonalDictionary): CorrectionEngine {
        val (lexicon, trie, frequency) = parts()
        return CorrectionEngine(
            lexicon, trie, frequency, NeutralCostModel, CorrectionEngine.Config(),
            personal = personal,
        )
    }

    private fun engine(personal: PersonalDictionary): PredictiveEngine {
        val (lexicon, trie, frequency) = parts()
        return PredictiveEngine(
            lexicon, trie, frequency, BigramModel.EMPTY,
            CorrectionEngine(
                lexicon, trie, frequency, NeutralCostModel, CorrectionEngine.Config(),
                personal = personal,
            ),
            personal = personal,
        )
    }

    @Test
    fun theInventedWordIsGenuinelyAbsentFromTheLexicon() {
        val (lexicon, _, _) = parts()
        // indexOf returns the binary-search insertion point negated, not -1, so absence is
        // "negative" and not "equal to minus one". Asserted the right way round because the
        // whole test rests on this word being absent.
        assertTrue(
            lexicon.indexOf(invented) < 0,
            "the whole test rests on $invented being absent from the lexicon",
        )
        assertFalse(lexicon.contains(invented))
    }

    @Test
    fun withoutTheDictionaryTheWordIsTreatedAsMisspelled() {
        assertFalse(corrections(PersonalDictionary()).isValid(invented))
    }

    @Test
    fun addingItMakesItValid() {
        val personal = PersonalDictionary().apply { add(invented) }
        assertTrue(
            corrections(personal).isValid(invented),
            "a word the user deliberately added must stop being an error",
        )
    }

    @Test
    fun prefixStrippingAppliesToUserWordsToo() {
        // ו + ל + invented. The stripper accepts prefixed forms of lexicon words; it has to do
        // the same for personal ones, or the feature works for one word and not its inflections.
        val personal = PersonalDictionary().apply { add(invented) }
        assertTrue(corrections(personal).isValid("ול$invented"))
        assertFalse(corrections(PersonalDictionary()).isValid("ול$invented"))
    }

    @Test
    fun theEngineStopsOfferingCorrectionsForIt() {
        val withDictionary = engine(PersonalDictionary().apply { add(invented) })
        val out = withDictionary.predict(invented, null)
        assertTrue(
            out.none { it.kind == SuggestionKind.CORRECTION },
            "still offering to 'fix' a word the user added: ${out.map { it.word }}",
        )
        val without = engine(PersonalDictionary())
        assertTrue(
            without.predict(invented, null).any { it.kind == SuggestionKind.CORRECTION },
            "the control: without the dictionary it IS corrected, so the test above measures " +
                "the dictionary and not the engine's silence",
        )
    }

    @Test
    fun personalWordsAreOfferedAsCompletions() {
        val personal = PersonalDictionary().apply { add(invented) }
        val out = engine(personal).predict(invented.substring(0, 3), null)
        assertEquals(
            invented, out.firstOrNull()?.word,
            "a user's own word should complete first: ${out.map { it.word }}",
        )
        assertEquals(PredictiveEngine.PERSONAL_WORD_INDEX, out.first().wordIndex)
    }

    @Test
    fun severalPersonalWordsDoNotCollapseIntoOne() {
        // They all carry PERSONAL_WORD_INDEX, so de-duplicating by index would keep only one.
        val personal = PersonalDictionary().apply {
            add(invented)
            add(invented + "ים")
        }
        val out = engine(personal).predict(invented.substring(0, 3), null)
        assertEquals(2, out.count { it.wordIndex == PredictiveEngine.PERSONAL_WORD_INDEX })
    }

    @Test
    fun anEmptyDictionaryChangesNothing() {
        // The measured M5/M10 numbers were taken with no personal dictionary; this is what
        // keeps them meaning what they say.
        val a = engine(PersonalDictionary()).predict("של", "אני")
        val b = PredictiveEngine(
            parts().first,
            parts().second,
            parts().third,
            BigramModel.EMPTY,
            corrections(PersonalDictionary()),
        ).predict("של", "אני")
        assertEquals(b.map { it.word }, a.map { it.word })
    }
}

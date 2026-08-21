package com.hebrewime.core.prediction

import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.lexicon.HebrewAbbreviations
import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The control the abbreviation feature shipped without.
 *
 * `HebrewAbbreviationsTest` checks that the table maps what it should. It never asked the
 * opposite question: **how often does the table fire on a word the user actually meant?**
 *
 * Measured here for the first time: **506 of 861 bare forms — 58.8% — are themselves valid
 * lexicon words.** `מס` is a word and abbreviates `מס׳`. `צהל` is a word and abbreviates `צה״ל`.
 * The first shipped version gave every abbreviation `Double.MAX_VALUE`, so typing an ordinary
 * word put its abbreviation ahead of the word's own completions.
 *
 * Denominator: 5 tests over the whole shipped table.
 */
class AbbreviationPrecisionTest {

    private class Fixture(
        val lexicon: HebrewLexicon,
        val abbreviations: HebrewAbbreviations,
        val engine: PredictiveEngine,
    )

    private fun fixture(): Fixture {
        val lexicon = File(System.getProperty("lexicon.file")!!)
            .inputStream().use { HebrewLexicon.load(it) }
        val words = ArrayList<String>(lexicon.size)
        for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))
        val trie = LexiconTrie.build(words)
        val frequency = File(System.getProperty("frequency.file")!!)
            .inputStream().use { HebrewFrequency.load(it) }
        val bigrams = File(System.getProperty("bigram.file")!!)
            .inputStream().use { BigramModel.load(it) }
        val abbreviations = File(System.getProperty("abbreviation.file")!!)
            .inputStream().use { HebrewAbbreviations.load(it) }
        val corrections = CorrectionEngine(
            lexicon, trie, frequency, NeutralCostModel, CorrectionEngine.Config(),
        )
        return Fixture(
            lexicon, abbreviations,
            PredictiveEngine(
                lexicon, trie, frequency, bigrams, corrections,
                abbreviations = abbreviations,
            ),
        )
    }

    /** Every bare form in the table, split by whether it is also a lexicon word. */
    private fun bareForms(f: Fixture): Pair<List<String>, List<String>> {
        val raw = java.util.zip.GZIPInputStream(
            File(System.getProperty("abbreviation.file")!!).inputStream()
        ).use { it.readBytes() }.toString(Charsets.UTF_8)
        val bare = raw.lineSequence().filter { it.isNotBlank() }
            .map { it.split('\t')[0] }.toList()
        return bare.partition { f.lexicon.indexOf(it) >= 0 }
    }

    @Test
    fun reportTheCollisionSurface() {
        val f = fixture()
        val (collide, clean) = bareForms(f)
        val total = collide.size + clean.size
        println("abbreviation bare forms: $total, of which ${collide.size} " +
            "(%.1f%%) are themselves lexicon words".format(100.0 * collide.size / total))
        assertEquals(861, total, "the shipped table is not the measured one")
        assertTrue(
            collide.size > total / 2,
            "the collision surface has changed shape; the ranking rule below was chosen for a " +
                "table where most bare forms ARE words",
        )
    }

    @Test
    fun anAbbreviationNeverOutranksTheRealWordItCollidesWith() {
        // The defect. `מס` is a word; typing it must not lead with `מס׳`.
        val f = fixture()
        val (collide, _) = bareForms(f)
        var checked = 0
        var led = 0
        for (bare in collide.take(400)) {
            val out = f.engine.predict(TypingContext(current = bare, completed = emptyList()))
            // Only counts as a hijack when there was something to outrank. An abbreviation
            // alone in the strip, because the word has no completions, is the right answer and
            // not a defect -- the first version of this test called that a failure and would
            // have driven a change that suggested nothing at all.
            if (out.size < 2) continue
            checked++
            if (out.first().kind == SuggestionKind.ABBREVIATION) {
                led++
                if (led <= 3) println("  LEADS WRONGLY: '$bare' -> ${out.map { it.word }}")
            }
        }
        println("checked $checked colliding forms, abbreviation led in $led")
        assertTrue(checked > 100, "denominator too small: $checked")
        assertEquals(
            0, led,
            "an abbreviation led the strip for a word that exists in the lexicon; typing an " +
                "ordinary word must not be hijacked by its abbreviation",
        )
    }

    /**
     * POSITIVE CONTROL for the test above. If abbreviations never led, that test would pass by
     * the feature being switched off.
     */
    @Test
    fun anAbbreviationDoesLeadWhenTheLettersAreNotAWord() {
        val f = fixture()
        val (_, clean) = bareForms(f)
        var checked = 0
        var led = 0
        for (bare in clean.take(400)) {
            val out = f.engine.predict(TypingContext(current = bare, completed = emptyList()))
            if (out.isEmpty()) continue
            checked++
            if (out.first().kind == SuggestionKind.ABBREVIATION) led++
        }
        println("POSITIVE CONTROL: $led of $checked non-word forms led with the abbreviation")
        assertTrue(checked > 50, "denominator too small: $checked")
        assertTrue(
            led > checked / 2,
            "abbreviations led in only $led of $checked cases where the letters are NOT a word. " +
                "The feature is off, and the precision test above proves nothing.",
        )
    }

    @Test
    fun theOperatorsExamplesAreStillOffered() {
        val f = fixture()
        // `אחכ` is not a lexicon word, so its abbreviation leads.
        val ahk = f.engine.predict(TypingContext(current = "אחכ", completed = emptyList()))
        assertEquals("אח״כ", ahk.firstOrNull()?.word, "'אחכ' no longer leads with 'אח״כ'")

        // `ככ` IS a lexicon entry, so `כ״כ` is offered but does not lead. That is the
        // conservative choice documented in PredictiveEngine, not an accident: no rule
        // available here gets both `ככ` and `מס` right, so nothing the user literally typed a
        // valid form of is outranked.
        val kk = f.engine.predict(TypingContext(current = "ככ", completed = emptyList()))
        println("'ככ' -> ${kk.map { "${it.word}(${it.kind})" }}")
        assertTrue(
            kk.any { it.word == "כ״כ" },
            "'ככ' no longer offers 'כ״כ' at all: ${kk.map { it.word }}",
        )
        assertTrue(
            kk.first().kind != SuggestionKind.ABBREVIATION,
            "'ככ' is a lexicon entry; its abbreviation must not outrank it",
        )
    }

    @Test
    fun aCollidingFormStillOffersItsAbbreviationSomewhere() {
        // Demoted, not discarded: a user typing `מס` may well have meant `מס׳`.
        val f = fixture()
        val out = f.engine.predict(TypingContext(current = "מס", completed = emptyList()))
        println("'מס' -> ${out.map { "${it.word}(${it.kind})" }}")
        assertTrue(
            out.any { it.kind == SuggestionKind.ABBREVIATION },
            "the abbreviation was dropped entirely rather than demoted",
        )
    }
}

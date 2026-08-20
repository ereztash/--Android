package com.hebrewime.core.lexicon

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the real shipped lexicon, not a fixture.
 *
 * The most important case here is [kotlinAgreesWithThePythonMeasurement]. The coverage numbers
 * in `docs/LEXICON_MEASUREMENTS.md` were produced by `scripts/measure_coverage.py`; the app
 * ships the Kotlin path. Two implementations of the same rule is exactly where a measurement
 * quietly stops describing the thing that ships, so the two are pinned to each other on the
 * same hash-locked corpus, token for token.
 */
class HebrewLexiconTest {

    private val lexiconFile = File(System.getProperty("lexicon.file")!!)
    private val heldoutFile = File(System.getProperty("heldout.file")!!)

    private fun load(): HebrewLexicon =
        lexiconFile.inputStream().use { HebrewLexicon.load(it) }

    private fun corpusTokens(): List<String> =
        GZIPInputStream(heldoutFile.inputStream()).use { gz ->
            gz.readBytes().toString(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
        }

    @Test
    fun loadsTheExpectedNumberOfForms() {
        val lex = load()
        assertEquals(355_587, lex.size, "lexicon form count")
    }

    @Test
    fun blobIsActuallySortedForByteWiseSearch() {
        // Binary search is only correct if the build script's code-point sort really does
        // coincide with UTF-8 byte order. That is a documented property of UTF-8, but it is
        // the kind of assumption that fails silently, so it is checked rather than trusted.
        val lex = load()
        var previous = ByteArray(0)
        var checked = 0
        for (i in 0 until lex.size) {
            val current = lex.wordAt(i).toByteArray(Charsets.UTF_8)
            assertTrue(
                compareUnsigned(previous, current) < 0,
                "blob not sorted at index $i: ${lex.wordAt(i)}",
            )
            previous = current
            checked++
        }
        assertEquals(355_587, checked, "every entry must have been compared")
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (k in 0 until n) {
            val d = (a[k].toInt() and 0xff) - (b[k].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    @Test
    fun everyIndexRoundTrips() {
        val lex = load()
        var checked = 0
        for (i in 0 until lex.size) {
            val w = lex.wordAt(i)
            assertEquals(i, lex.indexOf(w), "round trip failed for $w at $i")
            checked++
        }
        assertEquals(355_587, checked)
    }

    @Test
    fun containsKnownWordsAndRejectsNonWords() {
        val lex = load()
        // The seven words the spec names as the prefix-stripper objection.
        for (w in listOf("שמן", "שולחן", "מים", "הר", "לחם", "שמש", "ברזל")) {
            assertTrue(lex.contains(w), "$w must be present")
        }
        for (w in listOf("זזזזזזזז", "קקקקקקק", "", "hello", "שלוםX")) {
            assertFalse(lex.contains(w), "$w must be absent")
        }
    }

    @Test
    fun bothKtivSpellingsArePresent() {
        // The lexicon is corpus-derived, not Hspell-derived, so it carries pre- and
        // post-reform spellings side by side. Denominator: 10 pairs, and these are exactly
        // the pairs the build spec names -- not a random sample.
        val lex = load()
        val pairs = listOf(
            "אניה" to "אונייה", "חכמה" to "חוכמה", "צהריים" to "צוהריים",
            "קרבן" to "קורבן", "תכנית" to "תוכנית", "אמתי" to "אמיתי",
            "לעתים" to "לעיתים", "שער" to "שיער", "ססמה" to "סיסמה", "מיד" to "מייד",
        )
        for ((old, new) in pairs) {
            assertTrue(lex.contains(old), "pre-reform spelling $old missing")
            assertTrue(lex.contains(new), "post-reform spelling $new missing")
        }
    }

    @Test
    fun kotlinAgreesWithThePythonMeasurement() {
        val lex = load()
        val tokens = corpusTokens()
        assertEquals(75_000, tokens.size, "held-out corpus token count")

        // Values produced by scripts/measure_coverage.py against corpus sha256
        // 02fe828ce7ef083ab5db1d602714989d06676c2dadf490376c88b40e30520515.
        // These are recorded measurements, not thresholds: if the Kotlin path disagrees with
        // the Python path by even one token, one of them is wrong and the build must stop.
        val expected = mapOf(
            -1 to 70_960,  // -1 encodes "no stripper at all"
            2 to 72_633,
            3 to 72_622,
            4 to 72_544,
            5 to 72_270,
        )
        for ((minStem, expectedHits) in expected) {
            var hits = 0
            for (t in tokens) {
                val ok = if (minStem < 0) lex.contains(t)
                else PrefixStripper.accepts(t, lex, minStem)
                if (ok) hits++
            }
            assertEquals(
                expectedHits, hits,
                "Kotlin/Python disagreement at minStem=$minStem over ${tokens.size} tokens",
            )
        }
    }
}

/**
 * Compression must be detected from the stream, because the repository stores the artifact
 * gzipped and AGP ships it plain. Both forms must load identically.
 */
class HebrewLexiconCompressionTest {

    private val lexiconFile = java.io.File(System.getProperty("lexicon.file")!!)

    @Test
    fun loadsBothGzippedAndPlainFormsIdentically() {
        val gzipped = lexiconFile.inputStream().use { HebrewLexicon.load(it) }

        // Exactly what AGP puts in the APK: the same bytes, gunzipped.
        val plainBytes = GZIPInputStream(lexiconFile.inputStream()).use { it.readBytes() }
        val plain = plainBytes.inputStream().use { HebrewLexicon.load(it) }

        assertEquals(gzipped.size, plain.size, "form count must not depend on framing")
        assertEquals(355_587, plain.size)
        for (w in listOf("שלום", "מקלדת", "בית", "אונייה")) {
            assertTrue(plain.contains(w), "$w missing from the plain-text load")
            assertTrue(gzipped.contains(w), "$w missing from the gzipped load")
        }
        assertEquals(gzipped.wordAt(0), plain.wordAt(0))
        assertEquals(gzipped.wordAt(plain.size - 1), plain.wordAt(plain.size - 1))
    }

    @Test
    fun rejectsAnArtifactWithNoTrailingNewline() {
        val truncated = "אב\nגד".toByteArray(Charsets.UTF_8)
        val error = kotlin.runCatching {
            truncated.inputStream().use { HebrewLexicon.load(it) }
        }.exceptionOrNull()
        assertTrue(
            error is IllegalArgumentException,
            "a blob without a trailing newline has an ambiguous last word and must be refused",
        )
    }
}

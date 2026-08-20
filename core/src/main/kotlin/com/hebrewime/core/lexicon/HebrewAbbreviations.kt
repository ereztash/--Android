package com.hebrewime.core.lexicon

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Hebrew abbreviations — ראשי תיבות — keyed by the letters alone.
 *
 * ### Why this is a separate artifact
 * Neither lexicon source contains a single abbreviation: the inflected-verb list and the
 * frequency list are letters-only, checked rather than assumed. Worse, the cached bigram corpus
 * could not have supplied them either — it was tokenised with `[א-ת]+`, which splits on the
 * gershayim, so `כ״כ` had already been destroyed into two `כ` tokens before anything was
 * written. `scripts/build_abbreviations.py` therefore fetches article text again.
 *
 * ### What this fixes
 * Before it existed the keyboard treated `כ״כ` as two one-letter words, offered nothing useful
 * for either, and had no way to turn the `ככ` somebody types into the `כ״כ` they meant.
 *
 * ### Keyed on the bare letters, mapping to one canonical form
 * A bare form with more than one expansion — `דר` is both `ד״ר` and `דר׳` — is **dropped at
 * build time rather than guessed**. Offering the wrong expansion of an abbreviation is worse
 * than offering none, and this keyboard never replaces anything without a tap anyway.
 *
 * ### Format
 * ```
 * bare<TAB>canonical<TAB>count
 * ```
 * sorted by count descending. 861 entries, 6,757 bytes gzipped — small enough that no pruning
 * decision beyond the build threshold is needed.
 */
class HebrewAbbreviations private constructor(
    private val byBare: Map<String, String>,
    private val canonicalForms: Set<String>,
) {

    val size: Int get() = byBare.size

    /** The punctuated form for [bareLetters], or null. Input must already be marks-free. */
    fun canonicalFor(bareLetters: String): String? = byBare[bareLetters]

    /**
     * True when [word] is a known abbreviation as written, marks and all.
     *
     * Used to stop the correction engine calling `צה״ל` a misspelling — it is not in the
     * lexicon and never will be, because the lexicon holds letters.
     */
    fun isKnownAbbreviation(word: String): Boolean = word in canonicalForms

    companion object {

        val EMPTY: HebrewAbbreviations = HebrewAbbreviations(emptyMap(), emptySet())

        /** Detects gzip from the stream: AGP gunzips `.gz` assets while packaging. */
        fun load(stream: InputStream): HebrewAbbreviations {
            val buffered = BufferedInputStream(stream)
            buffered.mark(2)
            val first = buffered.read()
            val second = buffered.read()
            buffered.reset()
            if (first < 0) return EMPTY
            val gzipped = first == 0x1f && second == 0x8b
            val text = (if (gzipped) GZIPInputStream(buffered) else buffered)
                .use { it.readBytes() }
                .toString(Charsets.UTF_8)

            val byBare = HashMap<String, String>()
            val canonical = HashSet<String>()
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                val parts = line.split('\t')
                if (parts.size < 2) continue
                byBare[parts[0]] = parts[1]
                canonical.add(parts[1])
            }
            return HebrewAbbreviations(byBare, canonical)
        }
    }
}

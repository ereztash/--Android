package com.hebrewime.core.dictionary

import com.hebrewime.core.lexicon.HebrewText
import com.hebrewime.core.lexicon.WordSet

/**
 * Words the user added on purpose. Nothing else.
 *
 * ### The data model IS the privacy guarantee
 * The rule is "never store sentences or keystroke logs". That is enforced by the type rather
 * than by discipline: [add] takes a single word and rejects anything with whitespace, digits,
 * punctuation or a second token. **There is no API here that accepts a sentence**, so there is
 * no code path that could persist one, and no reviewer has to remember not to add one.
 *
 * ### Entries are user-initiated only
 * Nothing arrives by typing. The caller must have checked `SessionStart.mayLearn`, which is
 * false for password, email, URI, phone, numeric and OTP-shaped fields, and also false for
 * person-name and postal-address fields — those suggest normally but are never memorised.
 *
 * Serialisation is newline-delimited UTF-8, sorted, so the plaintext handed to the cipher is
 * deterministic and carries no insertion-order information about when the user added what.
 */
class PersonalDictionary private constructor(
    private val words: MutableSet<String>,
) : WordSet {

    constructor() : this(sortedSetOf())

    val size: Int get() = words.size

    /** Sorted, for display and for deterministic serialisation. */
    fun all(): List<String> = words.toList()

    override fun contains(word: String): Boolean = word in words

    /**
     * Add one word.
     *
     * @return true if it was added, false if it was already present.
     * @throws IllegalArgumentException if [word] is not a single unpointed Hebrew word. The
     *   exception is the point: a caller trying to store a phrase gets a failure, not a
     *   truncation.
     */
    fun add(word: String): Boolean {
        require(isStorable(word)) {
            "personal dictionary entries must be a single Hebrew word; refused: " +
                describeRejection(word)
        }
        return words.add(HebrewText.stripPoints(word))
    }

    fun remove(word: String): Boolean = words.remove(word)

    /** Forget everything. The caller is responsible for deleting the ciphertext too. */
    fun clear() {
        words.clear()
    }

    /** Deterministic plaintext: sorted, newline-delimited UTF-8. */
    fun serialize(): ByteArray =
        if (words.isEmpty()) ByteArray(0)
        else (words.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)

    companion object {

        /** Longest entry accepted. Nothing legitimate is longer, and this bounds the format. */
        const val MAX_WORD_LENGTH = 32

        /** Whether [word] may be stored at all. */
        fun isStorable(word: String): Boolean {
            if (word.isEmpty() || word.length > MAX_WORD_LENGTH) return false
            val stripped = HebrewText.stripPoints(word)
            return stripped.isNotEmpty() &&
                stripped.length <= MAX_WORD_LENGTH &&
                HebrewText.isHebrewWord(stripped)
        }

        private fun describeRejection(word: String): String = when {
            word.isEmpty() -> "empty"
            word.length > MAX_WORD_LENGTH -> "longer than $MAX_WORD_LENGTH characters"
            word.any { it.isWhitespace() } -> "contains whitespace, so it is more than one word"
            word.any { it.isDigit() } -> "contains digits"
            else -> "not a Hebrew word"
        }

        fun deserialize(bytes: ByteArray): PersonalDictionary {
            val set = sortedSetOf<String>()
            if (bytes.isNotEmpty()) {
                for (line in bytes.toString(Charsets.UTF_8).split('\n')) {
                    // Silently dropping an unstorable line rather than throwing: a corrupted
                    // or older file must not make the keyboard unusable, and the entry is only
                    // ever a convenience.
                    if (isStorable(line)) set.add(line)
                }
            }
            return PersonalDictionary(set)
        }
    }
}

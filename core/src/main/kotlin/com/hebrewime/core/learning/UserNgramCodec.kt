package com.hebrewime.core.learning

/**
 * The on-disk form of [UserNgramModel]: four integers per pair, and nothing else.
 *
 * ### This file IS the privacy claim
 * "Never persist raw text" is not enforced by remembering it at each call site; it is enforced
 * by there being no encoder here that accepts a `String`, a `CharSequence`, or a byte array of
 * text. [encode] takes a model whose entire contents are `Int`s. To store a word one would have
 * to add a new field and a new writer, which is a visible change to this file and a red
 * `GATE-LEARN-1`, whose audit reads the produced bytes and fails on any run that decodes as
 * Hebrew or Latin text.
 *
 * ### Layout
 * ```
 * u8  version
 * i32 pairCount
 * repeated pairCount times, sorted by (first, second):
 *   i32 firstWordId     lexicon index, or UserNgramModel.OOV
 *   i32 secondWordId    lexicon index, or UserNgramModel.OOV
 *   i32 count
 *   i32 sessions
 * ```
 * Ids are **signed** because [UserNgramModel.OOV] is negative by construction, so it can never
 * be mistaken for a lexicon index by arithmetic accident.
 *
 * A version byte, for the same reason [com.hebrewime.core.dictionary.EncryptedStore] has one: a
 * future format change should be a clean decode failure rather than a plausible mis-parse of
 * someone's data.
 */
object UserNgramCodec {

    /**
     * 2 — pairs **and** personal word counts.
     *
     * Version 1 stored pairs only. When personal frequency was added it worked inside a session
     * and reset on every restart, because nothing wrote it down. A v1 blob is rejected outright
     * rather than read as a v2 with a truncated tail.
     */
    const val VERSION: Byte = 2
    const val BYTES_PER_PAIR = 16

    /** Why a blob was refused. Enumerated, not free text — see [CorruptedException]. */
    enum class Reason { VERSION, TRUNCATED_HEADER, NEGATIVE_COUNT, LENGTH_MISMATCH }

    const val BYTES_PER_WORD = 12

    /**
     * A refusal, carrying a [Reason] and two integers rather than a formatted message.
     *
     * A `String` parameter anywhere in this file is refused by `GATE-LEARN-1`, and when the
     * gate first ran it was this constructor that made it red. The rule could have been
     * narrowed to allow exception messages — they are not persisted, and logging is separately
     * banned — but a narrow rule with an exception in it is a rule the next person adds a
     * second exception to. The code changed instead.
     *
     * It is also simply better here: a decode failure is now one of four enumerated states with
     * numbers attached, so nothing in the failure path formats a string out of bytes that came
     * from a file this app did not write.
     */
    class CorruptedException(
        val reason: Reason,
        val found: Int = 0,
        val expected: Int = 0,
    ) : Exception("$reason found=$found expected=$expected")

    fun encode(model: UserNgramModel): ByteArray {
        val entries = model.entries()
        val words = model.unigramEntries()
        val out = ByteArray(
            1 + 4 + entries.size * BYTES_PER_PAIR + 4 + words.size * BYTES_PER_WORD
        )
        var p = 0
        out[p++] = VERSION
        p = writeInt(out, p, entries.size)
        for (e in entries) {
            p = writeInt(out, p, e[0])
            p = writeInt(out, p, e[1])
            p = writeInt(out, p, e[2])
            p = writeInt(out, p, e[3])
        }
        p = writeInt(out, p, words.size)
        for (e in words) {
            p = writeInt(out, p, e[0])
            p = writeInt(out, p, e[1])
            p = writeInt(out, p, e[2])
        }
        return out
    }

    fun decode(
        bytes: ByteArray,
        minimumSessions: Int = UserNgramModel.DEFAULT_MINIMUM_SESSIONS,
        capacity: Int = UserNgramModel.DEFAULT_CAPACITY,
    ): UserNgramModel {
        val model = UserNgramModel(minimumSessions, capacity)
        if (bytes.isEmpty()) return model
        if (bytes[0] != VERSION) {
            throw CorruptedException(Reason.VERSION, bytes[0].toInt(), VERSION.toInt())
        }
        if (bytes.size < 5) throw CorruptedException(Reason.TRUNCATED_HEADER, bytes.size, 5)
        val count = readInt(bytes, 1)
        if (count < 0) throw CorruptedException(Reason.NEGATIVE_COUNT, count, 0)
        val pairsEnd = 1L + 4 + count.toLong() * BYTES_PER_PAIR
        if (bytes.size.toLong() < pairsEnd + 4) {
            throw CorruptedException(
                Reason.LENGTH_MISMATCH, bytes.size,
                (pairsEnd + 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }
        var p = 5
        repeat(count) {
            val first = readInt(bytes, p)
            val second = readInt(bytes, p + 4)
            val occurrences = readInt(bytes, p + 8)
            val sessions = readInt(bytes, p + 12)
            p += BYTES_PER_PAIR
            model.restore(first, second, occurrences, sessions)
        }

        val wordCount = readInt(bytes, p)
        p += 4
        if (wordCount < 0) throw CorruptedException(Reason.NEGATIVE_COUNT, wordCount, 0)
        val expected = pairsEnd + 4 + wordCount.toLong() * BYTES_PER_WORD
        if (bytes.size.toLong() != expected) {
            throw CorruptedException(
                Reason.LENGTH_MISMATCH, bytes.size,
                expected.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }
        repeat(wordCount) {
            model.restoreWord(readInt(bytes, p), readInt(bytes, p + 4), readInt(bytes, p + 8))
            p += BYTES_PER_WORD
        }
        return model
    }

    private fun writeInt(out: ByteArray, at: Int, value: Int): Int {
        out[at] = (value and 0xff).toByte()
        out[at + 1] = ((value ushr 8) and 0xff).toByte()
        out[at + 2] = ((value ushr 16) and 0xff).toByte()
        out[at + 3] = ((value ushr 24) and 0xff).toByte()
        return at + 4
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xff) or
            ((bytes[at + 1].toInt() and 0xff) shl 8) or
            ((bytes[at + 2].toInt() and 0xff) shl 16) or
            ((bytes[at + 3].toInt() and 0xff) shl 24)
}

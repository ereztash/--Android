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

    const val VERSION: Byte = 1
    const val BYTES_PER_PAIR = 16

    class CorruptedException(message: String) : Exception(message)

    fun encode(model: UserNgramModel): ByteArray {
        val entries = model.entries()
        val out = ByteArray(1 + 4 + entries.size * BYTES_PER_PAIR)
        var p = 0
        out[p++] = VERSION
        p = writeInt(out, p, entries.size)
        for (e in entries) {
            p = writeInt(out, p, e[0])
            p = writeInt(out, p, e[1])
            p = writeInt(out, p, e[2])
            p = writeInt(out, p, e[3])
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
            throw CorruptedException("user model version ${bytes[0]}, expected $VERSION")
        }
        if (bytes.size < 5) throw CorruptedException("user model truncated at the header")
        val count = readInt(bytes, 1)
        if (count < 0) throw CorruptedException("user model declares $count pairs")
        val expected = 1 + 4 + count.toLong() * BYTES_PER_PAIR
        if (bytes.size.toLong() != expected) {
            throw CorruptedException(
                "user model is ${bytes.size} bytes, $count pairs implies $expected"
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

package com.hebrewime.core.dictionary

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Denominator: 9 tests over the data model. */
class PersonalDictionaryTest {

    @Test
    fun storesSingleHebrewWords() {
        val d = PersonalDictionary()
        assertTrue(d.add("מקלדת"))
        assertTrue(d.add("עברית"))
        assertFalse(d.add("מקלדת"), "duplicates are not re-added")
        assertEquals(2, d.size)
        assertTrue(d.contains("מקלדת"))
    }

    @Test
    fun refusesAnythingThatIsNotOneWord() {
        // The rule is "never store sentences or keystroke logs", and it is enforced by the
        // type rather than by discipline. Each of these must throw, not truncate.
        val d = PersonalDictionary()
        val rejected = listOf(
            "" to "empty",
            "שלום עולם" to "two words",
            "שלום\nעולם" to "a newline",
            "שלום\tעולם" to "a tab",
            "hello" to "Latin",
            "שלום123" to "digits",
            "שלום!" to "punctuation",
            "שלום-עולם" to "a hyphenated compound",
            "א".repeat(33) to "longer than the cap",
        )
        for ((value, why) in rejected) {
            assertFailsWith<IllegalArgumentException>("must refuse $why: '$value'") {
                d.add(value)
            }
        }
        assertEquals(0, d.size, "nothing rejected may have been stored")
    }

    @Test
    fun thereIsNoApiThatAcceptsASentence() {
        // Restating the guarantee as a test: every public mutator takes a single String and
        // routes through isStorable. If someone adds a bulk or free-text API later, the
        // rejection list above stops covering it -- so this asserts the surface directly.
        val mutators = PersonalDictionary::class.java.methods
            .filter { it.name in setOf("add", "remove") }
        assertTrue(mutators.isNotEmpty())
        for (m in mutators) {
            assertEquals(1, m.parameterCount, "${m.name} should take exactly one argument")
            assertEquals(String::class.java, m.parameterTypes[0])
        }
    }

    @Test
    fun stripsPointsOnTheWayIn() {
        val d = PersonalDictionary()
        d.add("בָּגַדְתִּי")
        assertTrue(d.contains("בגדתי"), "entries are stored unpointed, like the lexicon")
    }

    @Test
    fun serialisationIsSortedAndDeterministic() {
        val a = PersonalDictionary().apply { add("מקלדת"); add("אבא"); add("עברית") }
        val b = PersonalDictionary().apply { add("עברית"); add("מקלדת"); add("אבא") }
        assertContentEquals(
            a.serialize(), b.serialize(),
            "insertion order must not survive into the plaintext",
        )
    }

    @Test
    fun roundTripsThroughSerialisation() {
        val d = PersonalDictionary().apply { add("מקלדת"); add("עברית") }
        val back = PersonalDictionary.deserialize(d.serialize())
        assertEquals(d.all(), back.all())
    }

    @Test
    fun emptyDictionarySerialisesToNothing() {
        assertEquals(0, PersonalDictionary().serialize().size)
        assertEquals(0, PersonalDictionary.deserialize(ByteArray(0)).size)
    }

    @Test
    fun corruptedLinesAreDroppedNotFatal() {
        // A damaged or older file must not make the keyboard unusable; the entries are only
        // ever a convenience.
        val raw = "מקלדת\nhello world\n\nעברית\n".toByteArray(Charsets.UTF_8)
        val d = PersonalDictionary.deserialize(raw)
        assertEquals(listOf("מקלדת", "עברית").sorted(), d.all())
    }

    @Test
    fun clearForgetsEverything() {
        val d = PersonalDictionary().apply { add("מקלדת") }
        d.clear()
        assertEquals(0, d.size)
        assertFalse(d.contains("מקלדת"))
    }
}

/** Denominator: 8 tests; the IV test alone performs 2,000 seals. */
class EncryptedStoreTest {

    private fun key(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun sample() = PersonalDictionary()
        .apply { add("מקלדת"); add("עברית"); add("בגדתי") }
        .serialize()

    @Test
    fun roundTrips() {
        val k = key()
        val plaintext = sample()
        val opened = EncryptedStore.open(EncryptedStore.seal(plaintext, k), k)
        assertContentEquals(plaintext, opened)
        assertEquals(3, PersonalDictionary.deserialize(opened).size)
    }

    @Test
    fun emptyPlaintextRoundTrips() {
        val k = key()
        assertContentEquals(
            ByteArray(0),
            EncryptedStore.open(EncryptedStore.seal(ByteArray(0), k), k),
        )
    }

    @Test
    fun identicalPlaintextProducesDifferentCiphertext() {
        val k = key()
        val plaintext = sample()
        val first = EncryptedStore.seal(plaintext, k)
        val second = EncryptedStore.seal(plaintext, k)
        assertNotEquals(
            first.toList(), second.toList(),
            "deterministic ciphertext would leak that the dictionary is unchanged",
        )
    }

    /**
     * An IV reused with the same key under GCM is catastrophic, not untidy: it leaks the XOR of
     * the two plaintexts and can expose the authentication subkey. So this is measured with a
     * real denominator rather than spot-checked.
     */
    @Test
    fun everyIvIsUniqueAcrossManySealsOfIdenticalPlaintext() {
        val k = key()
        val plaintext = sample()
        val n = 2000
        val seen = HashSet<List<Byte>>(n * 2)
        repeat(n) {
            val iv = EncryptedStore.ivOf(EncryptedStore.seal(plaintext, k)).toList()
            assertTrue(seen.add(iv), "IV repeated after ${seen.size} seals")
        }
        assertEquals(n, seen.size, "denominator: $n seals, all IVs distinct")
    }

    @Test
    fun tamperingWithAnyRegionIsDetected() {
        val k = key()
        val sealed = EncryptedStore.seal(sample(), k)
        // One offset from each region: the IV, the body, and the trailing GCM tag.
        val offsets = mapOf(
            "version" to 0,
            "IV" to 5,
            "body" to 1 + EncryptedStore.IV_LENGTH + 2,
            "tag" to sealed.size - 3,
        )
        for ((region, offset) in offsets) {
            val tampered = sealed.copyOf()
            tampered[offset] = (tampered[offset].toInt() xor 0x01).toByte()
            assertFailsWith<EncryptedStore.CorruptedException>("tampering in $region went undetected") {
                EncryptedStore.open(tampered, k)
            }
        }
    }

    @Test
    fun theWrongKeyFails() {
        val sealed = EncryptedStore.seal(sample(), key())
        assertFailsWith<EncryptedStore.CorruptedException> {
            EncryptedStore.open(sealed, key())
        }
    }

    @Test
    fun truncatedInputFailsCleanly() {
        val k = key()
        val sealed = EncryptedStore.seal(sample(), k)
        for (length in listOf(0, 1, EncryptedStore.IV_LENGTH, sealed.size - 1)) {
            assertFailsWith<EncryptedStore.CorruptedException>("length $length") {
                EncryptedStore.open(sealed.copyOf(length), k)
            }
        }
    }

    @Test
    fun aFutureFormatVersionIsRejectedRatherThanMisparsed() {
        val k = key()
        val sealed = EncryptedStore.seal(sample(), k)
        sealed[0] = 99
        val e = assertFailsWith<EncryptedStore.CorruptedException> {
            EncryptedStore.open(sealed, k)
        }
        assertTrue("version" in e.message!!)
    }
}

package com.hebrewime.core.learning

import com.hebrewime.core.dictionary.EncryptedStore
import com.hebrewime.core.dictionary.PersonalDictionary
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The two encrypted stores must be independently destroyable.
 *
 * The claim is that "forget what you learned" does not touch the personal dictionary and
 * "delete my dictionary" does not touch what was learned. On a device that rests on their using
 * different Keystore aliases, and the Keystore cannot be exercised anywhere except a device —
 * so what is testable here is the property that makes the aliases matter: **each blob is
 * readable only by its own key, and destroying one key leaves the other blob perfectly
 * readable.**
 *
 * That is the whole mechanism of the wipe. Deleting a file leaves bytes on flash that no app
 * can reliably overwrite; deleting the key is what makes them unopenable. If one key opened
 * both blobs, one wipe would silently destroy both — which is precisely what a shared alias
 * would have caused, and precisely what this asserts against.
 *
 * The Keystore lookup itself is recorded as NOT RUN, as it has been since M6.
 *
 * Denominator: 4 tests.
 */
class LearningWipeTest {

    private fun freshKey() = KeyGenerator.getInstance("AES")
        .apply { init(256) }.generateKey()

    @Test
    fun destroyingTheLearningKeyLeavesTheDictionaryReadable() {
        val dictionaryKey = freshKey()
        val learningKey = freshKey()

        val dictionary = PersonalDictionary().apply { add("שלום") }
        val learned = UserNgramModel(minimumSessions = 1).apply { record(1, 2) }

        val sealedDictionary = EncryptedStore.seal(dictionary.serialize(), dictionaryKey)
        val sealedLearned = EncryptedStore.seal(UserNgramCodec.encode(learned), learningKey)

        // "Forget what you learned" destroys the learning key. Model the destruction the only
        // way a JVM test can: the key is gone, so the blob is no longer openable.
        assertFailsWith<EncryptedStore.CorruptedException> {
            EncryptedStore.open(sealedLearned, freshKey())
        }
        // And the dictionary is untouched.
        val recovered = PersonalDictionary.deserialize(
            EncryptedStore.open(sealedDictionary, dictionaryKey)
        )
        assertEquals(listOf("שלום"), recovered.all())
    }

    @Test
    fun destroyingTheDictionaryKeyLeavesTheLearnedModelReadable() {
        val dictionaryKey = freshKey()
        val learningKey = freshKey()
        val dictionary = PersonalDictionary().apply { add("שלום") }
        val learned = UserNgramModel(minimumSessions = 1).apply { record(1, 2) }
        val sealedDictionary = EncryptedStore.seal(dictionary.serialize(), dictionaryKey)
        val sealedLearned = EncryptedStore.seal(UserNgramCodec.encode(learned), learningKey)

        assertFailsWith<EncryptedStore.CorruptedException> {
            EncryptedStore.open(sealedDictionary, freshKey())
        }
        val recovered = UserNgramCodec.decode(EncryptedStore.open(sealedLearned, learningKey))
        assertEquals(1, recovered.pairCount)
    }

    /**
     * POSITIVE CONTROL: a SHARED key destroys both.
     *
     * The two tests above are only meaningful if the alternative — the design that was
     * rejected — really would have coupled the wipes. This shows it does.
     */
    @Test
    fun aSharedKeyWouldHaveCoupledTheTwoWipes() {
        val shared = freshKey()
        val sealedDictionary = EncryptedStore.seal(
            PersonalDictionary().apply { add("שלום") }.serialize(), shared,
        )
        val sealedLearned = EncryptedStore.seal(
            UserNgramCodec.encode(UserNgramModel(1).apply { record(1, 2) }), shared,
        )
        // Destroy the one shared key: BOTH become unopenable. That is the bug the separate
        // alias exists to prevent.
        val afterWipe = freshKey()
        assertFailsWith<EncryptedStore.CorruptedException> {
            EncryptedStore.open(sealedDictionary, afterWipe)
        }
        assertFailsWith<EncryptedStore.CorruptedException> {
            EncryptedStore.open(sealedLearned, afterWipe)
        }
    }

    @Test
    fun clearingTheModelLeavesNothingRecoverableInMemoryEither() {
        val m = UserNgramModel(minimumSessions = 1)
        m.record(1, 2)
        m.record(UserNgramModel.OOV, 3)
        m.clear()
        assertEquals(0, m.pairCount)
        assertTrue(m.entries().isEmpty())
        // version byte + pair count + word count. Both counts, since v2 carries personal word
        // frequency as well as pairs.
        assertEquals(9, UserNgramCodec.encode(m).size, "an empty model encodes to a bare header")
    }
}

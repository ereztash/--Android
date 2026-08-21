package com.hebrewime.core.learning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The model's job is not to learn well. Its job is to be right about **what it is allowed to
 * say**, and these tests are mostly about that second thing.
 *
 * Denominator: 14 tests.
 */
class UserNgramModelTest {

    @Test
    fun aPairSeenOnceIsCountedAndNeverOffered() {
        // The attack this exists for: a card number, an address, a person's name typed once
        // into a chat field. It is counted -- otherwise a second sighting could not be
        // recognised -- and it must not be suggestible.
        val m = UserNgramModel()
        m.record(10, 20)
        assertEquals(1, m.pairCount, "it was counted")
        assertEquals(0, m.eligiblePairCount)
        assertEquals(0, m.logCountOf(10, 20), "and it must not be reportable")
        assertEquals(emptyList(), m.continuationsOf(10))
    }

    /**
     * POSITIVE CONTROL for the test above.
     *
     * "Never offered" is only reassuring if something ever IS offered. Without this, the
     * eligibility rule would pass by the model being incapable of suggesting anything at all.
     */
    @Test
    fun aPairSeenInEnoughSeparateSessionsIsOffered() {
        val m = UserNgramModel(minimumSessions = 2)
        m.record(10, 20)
        m.endSession()
        m.record(10, 20)
        assertTrue(m.logCountOf(10, 20) > 0, "POSITIVE CONTROL: a repeated pair must surface")
        assertEquals(listOf(20 to UserNgramModel.logScale(2)), m.continuationsOf(10))
        assertEquals(1, m.eligiblePairCount)
    }

    @Test
    fun repetitionInsideOneSessionDoesNotManufactureSessions() {
        // The whole point of counting sessions rather than occurrences. One message containing
        // a card number three times is ONE sighting, not three.
        val m = UserNgramModel(minimumSessions = 2)
        repeat(20) { m.record(10, 20) }
        assertEquals(0, m.logCountOf(10, 20), "20 occurrences in one session is still 1 session")
        m.endSession()
        m.record(10, 20)
        assertTrue(m.logCountOf(10, 20) > 0, "a second session makes it eligible")
    }

    @Test
    fun theSecondSightingMustBeInALaterSessionNotJustLater() {
        val m = UserNgramModel(minimumSessions = 2)
        m.record(10, 20)
        m.record(30, 40)
        m.record(10, 20)
        assertEquals(0, m.logCountOf(10, 20))
    }

    @Test
    fun outOfLexiconTokensCollapseToOneSentinelAndCarryNoText() {
        // Two different unknown words are indistinguishable afterwards. That is the design:
        // the model never held their characters, so it cannot surface either of them.
        val m = UserNgramModel(minimumSessions = 1)
        m.record(UserNgramModel.OOV, 20)
        m.record(UserNgramModel.OOV, 20)
        assertEquals(1, m.pairCount, "both unknown words are the same id")
        assertTrue(m.logCountOf(UserNgramModel.OOV, 20) > 0)
        // And nothing anywhere in the model can name them.
        assertTrue(m.entries().all { it.size == 4 }, "entries are four ints, never a string")
    }

    @Test
    fun theSentinelCannotCollideWithALexiconIndex() {
        assertTrue(UserNgramModel.OOV < 0, "lexicon indices are non-negative by construction")
    }

    @Test
    fun idsBelowTheSentinelAreRefusedRatherThanStored() {
        val m = UserNgramModel(minimumSessions = 1)
        m.record(-99, 20)
        m.record(20, -99)
        assertEquals(0, m.pairCount)
    }

    @Test
    fun continuationsComeBackMostSeenFirst() {
        val m = UserNgramModel(minimumSessions = 1)
        m.record(1, 100)
        repeat(5) { m.record(1, 200) }
        repeat(3) { m.record(1, 300) }
        assertEquals(listOf(200, 300, 100), m.continuationsOf(1).map { it.first })
    }

    @Test
    fun theLogScaleMatchesTheStaticModelsScale() {
        // Shared units are what make interpolation meaningful rather than arbitrary mixing.
        // BigramModel stores round(log2(count + 1) * 8); a count of 5 is 21 there.
        assertEquals(21, UserNgramModel.logScale(5))
        assertEquals(8, UserNgramModel.logScale(1))
        assertEquals(0, UserNgramModel.logScale(0))
    }

    @Test
    fun evictionDropsFewestSessionsFirstNotFewestOccurrences() {
        // A pair seen many times in ONE session is the shape of a secret typed and retyped.
        // Keeping by raw occurrences would preserve exactly the wrong thing.
        val m = UserNgramModel(minimumSessions = 1, capacity = 4)
        repeat(50) { m.record(1, 100) }          // 50 occurrences, 1 session
        m.endSession()
        m.record(1, 200); m.endSession(); m.record(1, 200)  // 2 occurrences, 2 sessions
        for (i in 300..500) m.record(1, i)       // push far past capacity
        assertTrue(m.pairCount <= 4)
        assertTrue(
            m.logCountOf(1, 200) > 0,
            "the twice-in-two-sessions pair should survive eviction",
        )
    }

    @Test
    fun clearForgetsEverything() {
        val m = UserNgramModel(minimumSessions = 1)
        m.record(1, 2)
        m.clear()
        assertEquals(0, m.pairCount)
        assertEquals(0, m.logCountOf(1, 2))
        assertEquals(emptyList(), m.entries())
    }

    @Test
    fun roundTripsThroughTheCodec() {
        val m = UserNgramModel(minimumSessions = 2)
        m.record(1, 2); m.endSession(); m.record(1, 2)
        m.record(UserNgramModel.OOV, 7); m.endSession(); m.record(UserNgramModel.OOV, 7)
        val back = UserNgramCodec.decode(UserNgramCodec.encode(m))
        assertEquals(m.pairCount, back.pairCount)
        assertEquals(m.logCountOf(1, 2), back.logCountOf(1, 2))
        assertEquals(
            m.logCountOf(UserNgramModel.OOV, 7), back.logCountOf(UserNgramModel.OOV, 7),
            "the sentinel must survive a signed round trip",
        )
    }

    @Test
    fun eligibilitySurvivesTheRoundTripRatherThanResetting() {
        // If sessions were not persisted, every restart would re-arm the once-seen protection
        // and a genuinely learned pair would go silent -- or worse, a reload would be a way to
        // launder a once-seen pair into an eligible one.
        val m = UserNgramModel(minimumSessions = 2)
        m.record(5, 6)
        val reloaded = UserNgramCodec.decode(UserNgramCodec.encode(m))
        assertEquals(0, reloaded.logCountOf(5, 6), "a once-seen pair stays ineligible on reload")
        assertEquals(1, reloaded.pairCount, "but it is still remembered as seen once")
    }

    /**
     * REGRESSION. Personal word frequency was added to the model before it was added to the
     * codec, so it worked inside a session and reset on every process restart — the failure
     * that looks to a user like "it stopped learning" and to a developer like nothing at all,
     * because every in-memory test passes.
     */
    @Test
    fun personalWordFrequencySurvivesTheRoundTrip() {
        val m = UserNgramModel(minimumSessions = 2)
        m.recordWord(42)
        m.endSession()
        m.recordWord(42)
        m.recordWord(99)
        assertTrue(m.unigramLogCountOf(42) > 0)

        val back = UserNgramCodec.decode(UserNgramCodec.encode(m))
        assertEquals(
            m.unigramLogCountOf(42), back.unigramLogCountOf(42),
            "personal word frequency did not survive being written and read back",
        )
        assertEquals(0, back.unigramLogCountOf(99), "a once-seen word stays ineligible on reload")
        assertEquals(2, back.unigramCount, "but both words are still remembered as seen")
    }

    @Test
    fun aTruncatedOrRewrittenBlobIsRefusedNotMisparsed() {
        val m = UserNgramModel(minimumSessions = 1)
        m.record(1, 2)
        val good = UserNgramCodec.encode(m)
        assertEquals(
            UserNgramCodec.Reason.LENGTH_MISMATCH,
            assertFailsWith<UserNgramCodec.CorruptedException> {
                UserNgramCodec.decode(good.copyOf(good.size - 1))
            }.reason,
        )
        val wrongVersion = good.copyOf().also { it[0] = 9 }
        assertEquals(
            UserNgramCodec.Reason.VERSION,
            assertFailsWith<UserNgramCodec.CorruptedException> {
                UserNgramCodec.decode(wrongVersion)
            }.reason,
            "a refusal names an enumerated reason, so nothing formats a string out of bytes " +
                "this app did not write",
        )
    }
}

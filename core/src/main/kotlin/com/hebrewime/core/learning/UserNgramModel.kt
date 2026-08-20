package com.hebrewime.core.learning

/**
 * What this installation has learned about how *this* user puts words together.
 *
 * A second layer beside [com.hebrewime.core.prediction.BigramModel], never a replacement for
 * it. The shipped table is immutable, hash-locked and verified inside the APK by
 * `GATE-BIGRAM-1`; this one changes as someone types. Keeping them apart is what lets the
 * static artifact stay byte-reproducible while the adaptive part stays mutable.
 *
 * ### Counts over ids. Never text.
 * The entire stored form is `(firstWordId, secondWordId) -> (count, sessions)`. There is no
 * field here that can hold a character, and therefore no code path that could persist a
 * sentence, a keystroke log, or a word. `GATE-LEARN-1` audits the serialized bytes for that
 * property rather than trusting this paragraph.
 *
 * ### Out-of-lexicon words are [OOV], and that is a deliberate loss
 * A word the user types that is not in the 355,587-form lexicon has no id. The tempting fix is
 * a user-local string table — and it would be a passive, automatic capture of exactly the
 * tokens most likely to be a person's name, a handle, an address or a code. That is the attack
 * [minimumSessions] exists to blunt, and a string table would walk straight into it.
 *
 * So every out-of-lexicon token collapses to one sentinel id. The model still learns that
 * *something unknown* precedes or follows a known word, which sharpens ranking among words it
 * CAN name, and it is structurally incapable of surfacing a typed name because it never held
 * the characters.
 *
 * **The cost is real and is not hidden: this layer will never learn your friends' names.** The
 * personal dictionary is the user-initiated path for that, it already feeds completions, and it
 * asks first.
 *
 * Measured on the test slice, **8.3% of learned pairs touch the sentinel** — smaller than it
 * feels like it should be, because Hebrew Wikipedia is mostly in-lexicon. Real phone typing has
 * more names and handles in it, so the real share is higher by an amount that is NOT MEASURED.
 *
 * ### Eligibility is not the same as knowledge
 * [record] counts a pair immediately. [logCountOf] refuses to *report* it until it has been
 * seen in [minimumSessions] separate sessions. The gap between those two is the whole defence:
 * a card number typed once into a chat box is counted and can never be suggested, and it ages
 * out under the eviction policy without ever having been offerable.
 *
 * Not thread-safe. Confined to the same background scope that owns prediction.
 */
class UserNgramModel(
    /**
     * How many **separate sessions** a pair must appear in before it may be suggested.
     *
     * Sessions, not occurrences, and that distinction is the point. A single burst of typing —
     * one message containing a card number twice, one address pasted and retyped — is one
     * session however many times a pair repeats inside it. Requiring the pair to come back in a
     * *later* field, at a later time, is what separates "this is how this person writes" from
     * "this person once typed a secret".
     *
     * **2 is the smallest value that has this property at all**, and the property is the reason
     * for the parameter, so 2 is what it is: 1 would make eligibility meaningless, and each
     * step above 2 costs adaptation speed for a defence that is already qualitative rather than
     * incremental. The cost of 2 versus 3 versus 5 is measured on the dev slice and reported in
     * `docs/LEARNING_MEASUREMENTS.md`; the *floor* of 2 is not a tuned number and does not move
     * on the strength of an accuracy table.
     */
    val minimumSessions: Int = DEFAULT_MINIMUM_SESSIONS,
    /**
     * Hard cap on distinct pairs retained.
     *
     * Unbounded growth in an IME process is a crash on somebody's phone, and the eviction it
     * forces is also a privacy property: a pair seen once and never again does not live
     * forever. See [evictIfNeeded] for what is dropped first.
     */
    val capacity: Int = DEFAULT_CAPACITY,
) {

    /** Counts and session-counts for one first word, keyed by second word. */
    private class Successors {
        val counts = HashMap<Int, Int>()
        val sessions = HashMap<Int, Int>()
    }

    private val table = HashMap<Int, Successors>()

    /**
     * Pairs already credited with a session in the current session, so a pair repeated inside
     * one field does not manufacture the separate sightings [minimumSessions] asks for.
     *
     * Cleared by [endSession]. Holds ids, like everything else here.
     */
    private val creditedThisSession = HashSet<Long>()

    var pairCount: Int = 0
        private set

    /** Pairs that have met [minimumSessions] and may therefore be suggested. */
    val eligiblePairCount: Int
        get() = table.values.sumOf { s -> s.sessions.count { it.value >= minimumSessions } }

    /**
     * Record that [second] followed [first].
     *
     * **The caller is responsible for having checked `SessionStart.mayLearn`.** This class holds
     * no policy, for the same reason [com.hebrewime.core.prediction.PredictiveEngine] holds
     * none: the safest way not to learn from a password field is for the call never to happen.
     * `GATE-LEARN-2` checks statically that the one call site is guarded.
     */
    fun record(first: Int, second: Int) {
        if (first < OOV || second < OOV) return
        val successors = table.getOrPut(first) { Successors() }
        val had = successors.counts.containsKey(second)
        successors.counts[second] = (successors.counts[second] ?: 0) + 1
        if (!had) pairCount++

        val key = pairKey(first, second)
        if (creditedThisSession.add(key)) {
            successors.sessions[second] = (successors.sessions[second] ?: 0) + 1
        }
        evictIfNeeded()
    }

    /**
     * End the current session, so the next sighting of a pair counts as a separate one.
     *
     * Called from `onFinishInput`. A session here is one focused field, which is stricter than
     * "one app launch" and deliberately so.
     */
    fun endSession() {
        creditedThisSession.clear()
    }

    /**
     * Evidence that [second] follows [first], in the same log units [BigramModel] uses, or 0.
     *
     * Returns 0 for a pair that exists but is not yet eligible. The caller cannot distinguish
     * "never seen" from "seen but withheld", and that is intentional — an API that reported the
     * difference would be a way to ask whether a given pair had ever been typed.
     */
    fun logCountOf(first: Int, second: Int): Int {
        val successors = table[first] ?: return 0
        if ((successors.sessions[second] ?: 0) < minimumSessions) return 0
        val count = successors.counts[second] ?: return 0
        return logScale(count)
    }

    /** Eligible continuations of [first], most-seen first, as `(secondWordId, logCount)`. */
    fun continuationsOf(first: Int, limit: Int = 8): List<Pair<Int, Int>> {
        val successors = table[first] ?: return emptyList()
        return successors.counts.asSequence()
            .filter { (second, _) -> (successors.sessions[second] ?: 0) >= minimumSessions }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to logScale(it.value) }
            .toList()
    }

    /**
     * Drop the least valuable pairs when over [capacity].
     *
     * Least valuable is fewest sessions, then fewest occurrences: a pair seen many times in one
     * burst is exactly the shape of a secret typed once and repeated, so occurrences alone would
     * be the wrong key to keep by.
     */
    private fun evictIfNeeded() {
        if (pairCount <= capacity) return
        val target = (capacity * RETAIN_ON_EVICT).toInt()
        val all = ArrayList<Triple<Int, Int, Long>>(pairCount)
        for ((first, successors) in table) {
            for ((second, count) in successors.counts) {
                val sessions = successors.sessions[second] ?: 0
                all.add(Triple(first, second, (sessions.toLong() shl 32) or count.toLong()))
            }
        }
        all.sortBy { it.third }
        var removed = 0
        for ((first, second, _) in all) {
            if (pairCount - removed <= target) break
            val successors = table[first] ?: continue
            successors.counts.remove(second)
            successors.sessions.remove(second)
            if (successors.counts.isEmpty()) table.remove(first)
            removed++
        }
        pairCount -= removed
    }

    /**
     * Every stored pair, for serialization and for the schema audit. `(first, second, count,
     * sessions)`, ids only.
     */
    fun entries(): List<IntArray> {
        val out = ArrayList<IntArray>(pairCount)
        for ((first, successors) in table) {
            for ((second, count) in successors.counts) {
                out.add(intArrayOf(first, second, count, successors.sessions[second] ?: 0))
            }
        }
        out.sortWith(compareBy({ it[0] }, { it[1] }))
        return out
    }

    /** Restore one pair without touching session-credit bookkeeping. Used by deserialize. */
    fun restore(first: Int, second: Int, count: Int, sessions: Int) {
        if (count <= 0 || first < OOV || second < OOV) return
        val successors = table.getOrPut(first) { Successors() }
        if (!successors.counts.containsKey(second)) pairCount++
        successors.counts[second] = count
        successors.sessions[second] = sessions.coerceAtLeast(0)
    }

    /** Forget everything. The caller is responsible for deleting the ciphertext and the key. */
    fun clear() {
        table.clear()
        creditedThisSession.clear()
        pairCount = 0
    }

    companion object {
        /**
         * The id every out-of-lexicon token collapses to. Negative, so it can never collide
         * with a lexicon index. See the class docs for why there is no string table.
         */
        const val OOV: Int = -1

        const val DEFAULT_MINIMUM_SESSIONS: Int = 2

        /**
         * 40,000 pairs.
         *
         * At 16 bytes per entry in the serialized form that is 640 KB before compression, and
         * roughly 3 MB of `HashMap` overhead resident — the same order as the shipped bigram
         * table's 2.95 MiB, which the app already carries.
         *
         * Measured: a pseudo-user producing 80 sentences learns a mean of 888 distinct pairs
         * and at most 1,341, which is **3.4% of this cap**. So it bounds pathological use
         * rather than normal use, and `LearningShapeTest` fails if a simulated user ever
         * reaches it — because at that point the reported accuracy would silently include
         * eviction effects.
         */
        const val DEFAULT_CAPACITY: Int = 40_000

        /** Evict down to this fraction of capacity, so eviction is not run on every keystroke. */
        const val RETAIN_ON_EVICT: Double = 0.9

        /**
         * `round(log2(count + 1) * 8)`, capped at 255 — **the same scale
         * [com.hebrewime.core.prediction.BigramModel] stores**.
         *
         * Sharing the scale is what makes interpolation meaningful rather than an arbitrary
         * mixing of two differently-shaped numbers. It also means a user pair seen 3 times
         * scores 16 against a corpus pair seen 5 times scoring 21, which is the right starting
         * relationship: personal evidence is worth something immediately and does not
         * immediately dominate.
         */
        fun logScale(count: Int): Int =
            Math.round(Math.log((count + 1).toDouble()) / Math.log(2.0) * 8).toInt()
                .coerceIn(0, 255)

        private fun pairKey(first: Int, second: Int): Long =
            (first.toLong() shl 32) or (second.toLong() and 0xffffffffL)

        /** An empty model, so prediction with learning off is exactly prediction without it. */
        fun empty(): UserNgramModel = UserNgramModel()
    }
}

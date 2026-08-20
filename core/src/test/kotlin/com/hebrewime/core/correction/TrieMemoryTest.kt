package com.hebrewime.core.correction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The trie's array sizing, which was 3.7x too generous and is now bounded by growth.
 *
 * ### Why this is a test and not just a comment
 * Sizing by total characters is the obvious, correct-looking choice — it is a genuine upper
 * bound. It is also, for a real dictionary, roughly four times too many, because a trie exists
 * precisely so that words share prefixes. The consequence is invisible: the build succeeds, the
 * trie is correct, and 27 MB is allocated and discarded inside a process that may not have it.
 *
 * Denominator: 4 tests.
 */
class TrieMemoryTest {

    @Test
    fun sharedPrefixesMeanFarFewerNodesThanCharacters() {
        val words = listOf("אבג", "אבגד", "אבגדה", "אבגדהו")
        val trie = LexiconTrie.build(words)
        val characters = words.sumOf { it.length }
        // 3 + 4 + 5 + 6 = 18 characters, but only 6 nodes plus the root are needed.
        assertEquals(18, characters)
        assertEquals(7, trie.nodeCount)
        assertTrue(
            trie.nodeCount < characters / 2,
            "sizing arrays by character count would over-allocate by ${characters / trie.nodeCount}x",
        )
    }

    @Test
    fun growthProducesTheSameTrieAsAGenerousReservation() {
        // The growth path must not change the structure, only what it costs to reach it. A
        // dictionary with many shared prefixes forces several growth steps.
        val words = (0 until 500).map { "אב" + it.toString().padStart(4, '0') }.sorted()
        val trie = LexiconTrie.build(words)
        for ((i, w) in words.withIndex()) {
            val found = trie.search(w, 0, NeutralCostModel, limit = 8)
            assertTrue(found.any { it.wordIndex == i }, "'$w' is missing after growth")
        }
    }

    @Test
    fun growthHandlesAWordLongerThanTheInitialCapacity() {
        // One word, so the initial capacity is the 16-entry floor; the word is longer.
        val long = "א".repeat(64)
        val trie = LexiconTrie.build(listOf(long))
        assertEquals(65, trie.nodeCount)
        assertEquals(listOf(0), trie.completions(long))
    }

    @Test
    fun anEmptyDictionaryStillBuilds() {
        val trie = LexiconTrie.build(emptyList())
        assertEquals(1, trie.nodeCount, "just the root")
        assertEquals(emptyList(), trie.completions("א"))
    }
}

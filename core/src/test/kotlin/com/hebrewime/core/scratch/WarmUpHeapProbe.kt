package com.hebrewime.core.scratch

import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.lexicon.HebrewLexicon
import java.io.File
import kotlin.test.Test

class WarmUpHeapProbe {
    @Test
    fun warmUpUnderConstrainedHeap() {
        val mode = System.getProperty("warmUpMode").orEmpty()
        if (mode.isEmpty()) { println("skipped; -PwarmUpMode=copy|view"); return }
        val dir = File(System.getProperty("lexicon.file")!!).parentFile
        try {
            val lx = File(System.getProperty("lexicon.file")!!)
                .inputStream().use { HebrewLexicon.load(it) }
            val words: List<String> = if (mode == "copy") {
                ArrayList<String>(lx.size).also { l ->
                    for (i in 0 until lx.size) l.add(lx.wordAt(i))
                }
            } else {
                lx.asWordList()
            }
            val trie = LexiconTrie.build(words)
            println("RESULT $mode OK nodes=${trie.nodeCount} max=${Runtime.getRuntime().maxMemory() / 1048576}m")
        } catch (e: OutOfMemoryError) {
            println("RESULT $mode OOM max=${Runtime.getRuntime().maxMemory() / 1048576}m")
        }
    }
}

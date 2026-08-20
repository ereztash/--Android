package com.hebrewime.ime.correction

import android.content.Context
import androidx.tracing.Trace
import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.correction.Suggestion
import com.hebrewime.core.lexicon.HebrewLexicon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs correction off the main thread and delivers results back to it.
 *
 * ### Why nothing here happens on the input path's thread
 * Loading the lexicon means decompressing 950 KB and indexing 355,587 words; building the trie
 * adds 567,767 nodes. Neither belongs anywhere near a keystroke. Suggestion itself is fast on
 * a JVM — p95 2.88 ms on the build host — but that is not a device measurement and is not
 * treated as one.
 *
 * ### Cancellation
 * Each request cancels the previous one. A keyboard produces requests faster than it can
 * answer them, and a stale suggestion for a word the user has already finished typing is worse
 * than none: it can arrive after the word boundary and offer to "correct" the wrong word.
 * The session change in `onStartInput` cancels everything outstanding, so results from a
 * previous field can never surface in a new one — which matters most when the previous field
 * was a password.
 *
 * The `androidx.tracing` sections are what the M7 macrobenchmark harness measures with
 * `TraceSectionMetric`. They are the only route to a real latency number, since Macrobenchmark's
 * built-in metrics are bound to `targetPackageName` and never see the IME process.
 */
class CorrectionController(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private var engine: CorrectionEngine? = null
    private var loadJob: Job? = null
    private var inFlight: Job? = null

    /** True once the lexicon is loaded and suggestions are possible. */
    val isReady: Boolean get() = engine != null

    /** Begin loading. Safe to call more than once; later calls are ignored. */
    fun warmUp() {
        if (engine != null || loadJob?.isActive == true) return
        loadJob = scope.launch {
            Trace.beginSection(TRACE_LOAD)
            try {
                val lexicon = context.assets.open(LEXICON_ASSET).use { HebrewLexicon.load(it) }
                val words = ArrayList<String>(lexicon.size)
                for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))
                val trie = LexiconTrie.build(words)
                val frequency = context.assets.open(FREQUENCY_ASSET)
                    .use { HebrewFrequency.load(it) }
                engine = CorrectionEngine(
                    lexicon = lexicon,
                    trie = trie,
                    frequency = frequency,
                    // The shipped configuration. The keyboard-adjacency discount is measured
                    // and deliberately NOT enabled -- it costs 8 points of top-1 accuracy on
                    // an unbiased corpus. See docs/CORRECTION_MEASUREMENTS.md finding 1.
                    costs = NeutralCostModel,
                    config = CorrectionEngine.Config(),
                )
            } finally {
                Trace.endSection()
            }
        }
    }

    /**
     * Request suggestions for [word], cancelling any request still outstanding.
     *
     * @param onResult delivered on the main thread. Never called if the request is cancelled.
     */
    fun requestSuggestions(
        word: String,
        allowed: Boolean,
        onResult: (List<Suggestion>) -> Unit,
    ) {
        inFlight?.cancel()
        if (!allowed) {
            // A restricted field. Not "compute and discard" -- nothing is computed at all, so
            // the word never reaches the engine, the trie, or any allocation that outlives
            // this call.
            onResult(emptyList())
            return
        }
        val ready = engine
        if (ready == null) {
            warmUp()
            onResult(emptyList())
            return
        }
        inFlight = scope.launch {
            Trace.beginSection(TRACE_SUGGEST)
            val result = try {
                ready.suggest(word)
            } finally {
                Trace.endSection()
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /** Cancel outstanding work. Called when the input session changes or the IME is destroyed. */
    fun cancelOutstanding() {
        inFlight?.cancel()
        inFlight = null
    }

    fun shutdown() {
        scope.cancel()
        engine = null
    }

    companion object {
        /**
         * AGP transparently gunzips `.gz` assets while packaging, so the repository's
         * `he_lexicon.txt.gz` ships as `he_lexicon.txt`. Both loaders detect compression from
         * the stream, but the NAME still has to match what is actually in the APK.
         */
        const val LEXICON_ASSET = "he_lexicon.txt"
        const val FREQUENCY_ASSET = "he_freq.bin"

        /** Trace section names, matched by the M7 macrobenchmark. */
        const val TRACE_LOAD = "HebrewIme.loadLexicon"
        const val TRACE_SUGGEST = "HebrewIme.suggest"
    }
}

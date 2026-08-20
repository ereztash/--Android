package com.hebrewime.ime.correction

import android.content.Context
import androidx.tracing.Trace
import com.hebrewime.core.confusion.RealWordErrorDetector
import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import com.hebrewime.core.prediction.Prediction
import com.hebrewime.core.prediction.PredictiveEngine
import com.hebrewime.core.prediction.TypingContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs completion, correction and next-word prediction off the main thread and delivers the
 * results back to it.
 *
 * ### Why nothing here happens on the input path's thread
 * Loading the lexicon means decompressing 950 KB and indexing 355,587 words; building the trie
 * adds 567,767 nodes; the bigram table adds 532,168 entries in 2.95 MiB of arrays. None of it
 * belongs anywhere near a keystroke. Prediction itself is fast on a JVM — 242 us per call on
 * the build host — but that is not a device measurement and is not treated as one.
 *
 * ### Cancellation
 * Each request cancels the previous one. A keyboard produces requests faster than it can
 * answer them, and a stale suggestion for a word the user has already finished typing is worse
 * than none: it can arrive after the word boundary and offer to "correct" the wrong word.
 * The session change in `onStartInput` cancels everything outstanding, so results from a
 * previous field can never surface in a new one — which matters most when the previous field
 * was a password.
 *
 * ### A missing asset degrades the keyboard; it must never kill it
 * `AssetManager.open` throws when the asset is absent, and an uncaught throw inside
 * `scope.launch` reaches the thread's default handler and takes the IME process with it. A
 * keyboard that will not start is a phone the user cannot type on at all — a far worse outcome
 * than a keyboard with no suggestions. So each stage degrades instead:
 *
 * - bigrams missing → [BigramModel.EMPTY], prediction falls back to unigram ranking
 * - lexicon or frequency missing → no engine, no suggestions, and typing still works
 *
 * That is emphatically **not** where the guarantee lives. Degrading silently would mean
 * shipping a keyboard scoring 2.15% instead of 5.73% top-3 with nothing anywhere saying so, so
 * the real check is on the artifact: the `apk_lexicon` and `apk_bigrams` detectors in
 * `scripts/check_apk.py` fail the build when an asset the code opens by name is not in the
 * APK, or does not hash to what the manifests describe. The stale release APK that first
 * shipped without `he_bigrams.bin` was caught there, before a device ever ran it.
 *
 * [loaded] and [degradedAssets] record what actually happened, so the state is inspectable
 * rather than inferred from a keyboard that feels worse than it should.
 *
 * The `androidx.tracing` sections are what the M7 macrobenchmark harness measures with
 * `TraceSectionMetric`. They are the only route to a real latency number, since Macrobenchmark's
 * built-in metrics are bound to `targetPackageName` and never see the IME process.
 */
class CorrectionController(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private var engine: PredictiveEngine? = null
    private var loadJob: Job? = null
    private var inFlight: Job? = null

    /**
     * What the last load actually managed to read. Null until a load completes.
     *
     * Kept so the state is inspectable rather than inferred from behaviour; a keyboard that
     * quietly ranks worse than it should is the failure mode this guards against.
     */
    @Volatile
    var loaded: Loaded? = null
        private set

    /** Sizes of what was loaded, as counted from the artifacts themselves. */
    data class Loaded(
        val lexiconWords: Int,
        val trieNodes: Int,
        val bigramGroups: Int,
        val bigramPairs: Int,
    )

    /**
     * Assets that could not be read, by asset name. Empty on a healthy build.
     *
     * Never logged: `GATE-API-1`'s `priv.no_logging` rule bans logging from production source,
     * and an asset failure is not worth making an exception for. It is exposed as state
     * instead, so a test or the host app can assert on it.
     */
    @Volatile
    var degradedAssets: Set<String> = emptySet()
        private set

    /** True once the lexicon is loaded and suggestions are possible. */
    val isReady: Boolean get() = engine != null

    /** Begin loading. Safe to call more than once; later calls are ignored. */
    fun warmUp() {
        if (engine != null || loadJob?.isActive == true) return
        loadJob = scope.launch {
            Trace.beginSection(TRACE_LOAD)
            val degraded = LinkedHashSet<String>()
            try {
                val lexicon = context.assets.open(LEXICON_ASSET).use { HebrewLexicon.load(it) }
                val words = ArrayList<String>(lexicon.size)
                for (i in 0 until lexicon.size) words.add(lexicon.wordAt(i))
                val trie = LexiconTrie.build(words)
                val frequency = context.assets.open(FREQUENCY_ASSET)
                    .use { HebrewFrequency.load(it) }
                // Prediction is worth having without bigrams; the keyboard is not worth losing
                // over them. GATE-BIGRAM-1 is what makes this branch a diagnostic rather than
                // a way for a packaging mistake to reach a user unnoticed.
                val bigrams = try {
                    context.assets.open(BIGRAM_ASSET).use { BigramModel.load(it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (unreadable: Throwable) {
                    degraded.add(BIGRAM_ASSET)
                    BigramModel.EMPTY
                }
                engine = PredictiveEngine(
                    lexicon = lexicon,
                    trie = trie,
                    frequency = frequency,
                    bigrams = bigrams,
                    corrections = CorrectionEngine(
                        lexicon = lexicon,
                        trie = trie,
                        frequency = frequency,
                        // The shipped configuration. The keyboard-adjacency discount is
                        // measured and deliberately NOT enabled -- it costs 8 points of top-1
                        // accuracy on an unbiased corpus. See docs/CORRECTION_MEASUREMENTS.md
                        // finding 1.
                        costs = NeutralCostModel,
                        config = CorrectionEngine.Config(),
                    ),
                    // Defaults, whose bigramWeight was chosen from the sweep in
                    // docs/PREDICTION_MEASUREMENTS.md and not before it.
                    config = PredictiveEngine.Config(),
                    // Real-word errors: `אם` where `עם` was meant. Costs no asset bytes --
                    // confusion sets are generated from the lexicon on demand -- and reuses
                    // the same bigram table. Its thresholds come from a dev slice that shares
                    // no sentence with the slice they were then measured on.
                    realWordErrors = RealWordErrorDetector(lexicon, bigrams),
                )
                loaded = Loaded(
                    lexiconWords = lexicon.size,
                    trieNodes = trie.nodeCount,
                    bigramGroups = bigrams.groupCount,
                    bigramPairs = bigrams.bigramCount,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unreadable: Throwable) {
                // No lexicon means no suggestions. It must not mean no keyboard: an uncaught
                // throw here reaches the thread's default handler and kills the IME process,
                // leaving the user with no way to type at all.
                engine = null
                loaded = null
                degraded.add(LEXICON_ASSET)
            } finally {
                degradedAssets = degraded
                Trace.endSection()
            }
        }
    }

    /**
     * Request suggestions for the current input position, cancelling any request still
     * outstanding.
     *
     * @param context what is known about the text around the cursor. Missing entries are
     *   absent rather than guessed: `InputContextBuffer` reports nothing after a desync, in a
     *   field whose initial text was withheld, or across a sentence boundary, and the engine
     *   then simply does less.
     * @param onResult delivered on the main thread. Never called if the request is cancelled.
     */
    fun requestPredictions(
        context: TypingContext,
        allowed: Boolean,
        onResult: (List<Prediction>) -> Unit,
    ) {
        inFlight?.cancel()
        if (!allowed) {
            // A restricted field. Not "compute and discard" -- nothing is computed at all, so
            // the context never reaches the engine, the trie, or any allocation that outlives
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
                ready.predict(context)
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
        loaded = null
        degradedAssets = emptySet()
    }

    companion object {
        /**
         * AGP transparently gunzips `.gz` assets while packaging, so the repository's
         * `he_lexicon.txt.gz` ships as `he_lexicon.txt`. Every loader detects compression from
         * the stream, but the NAME still has to match what is actually in the APK.
         */
        const val LEXICON_ASSET = "he_lexicon.txt"
        const val FREQUENCY_ASSET = "he_freq.bin"
        const val BIGRAM_ASSET = "he_bigrams.bin"

        /**
         * Trace section names, matched by the M7 macrobenchmark.
         *
         * `TRACE_SUGGEST` kept its name through M10 because it still marks the same thing —
         * the candidate computation on the input path — but the work inside it changed: it now
         * includes a trie top-K walk and bigram lookups that the M7 numbers were never taken
         * against. The M7 latency baseline therefore does not describe this code and is
         * re-measured in M12 rather than carried forward.
         */
        const val TRACE_LOAD = "HebrewIme.loadLexicon"
        const val TRACE_SUGGEST = "HebrewIme.suggest"
    }
}

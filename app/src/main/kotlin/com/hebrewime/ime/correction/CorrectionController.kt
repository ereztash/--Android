package com.hebrewime.ime.correction

import android.content.Context
import androidx.tracing.Trace
import com.hebrewime.core.correction.CorrectionEngine
import com.hebrewime.core.correction.HebrewFrequency
import com.hebrewime.core.correction.LexiconTrie
import com.hebrewime.core.correction.NeutralCostModel
import com.hebrewime.core.dictionary.PersonalDictionary
import com.hebrewime.core.learning.UserNgramModel
import com.hebrewime.core.lexicon.HebrewAbbreviations
import com.hebrewime.core.lexicon.HebrewLexicon
import com.hebrewime.core.prediction.BigramModel
import com.hebrewime.core.prediction.Prediction
import com.hebrewime.core.prediction.PredictiveEngine
import com.hebrewime.core.prediction.TypingContext
import com.hebrewime.diagnostics.ImeDiagnostics
import com.hebrewime.dictionary.PersonalDictionaryRepository
import com.hebrewime.learning.LearningPreferences
import com.hebrewime.learning.UserModelRepository
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

    /** The artifacts, kept so the engine can be rebuilt when the personal dictionary changes. */
    private var artifacts: Artifacts? = null
    private var personal: PersonalDictionary = PersonalDictionary()
    private val personalStore = PersonalDictionaryRepository(context)

    /**
     * What this installation has learned. Empty and never written to unless the user opted in.
     *
     * Held here rather than on the service because it has to outlive an input session and be
     * rebuilt into the engine, exactly like the personal dictionary.
     */
    private var userModel: UserNgramModel = UserNgramModel.empty()
    private val userStore = UserModelRepository(context, scope)

    /** True when the user turned adaptive learning on. Re-read on every session start. */
    @Volatile
    var learningEnabled: Boolean = false
        private set

    private class Artifacts(
        val lexicon: HebrewLexicon,
        val trie: LexiconTrie,
        val frequency: HebrewFrequency,
        val bigrams: BigramModel,
        val abbreviations: HebrewAbbreviations,
    )

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
        val personalWords: Int,
        val learnedPairs: Int,
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
            val degraded = LinkedHashSet<String>()
            ImeDiagnostics.recordLoading(context)
            try {
                val assets = loadAssets(degraded)
                // The two reads below SUSPEND, and they are deliberately outside the traced
                // region above. See loadAssets.
                personal = readPersonalDictionary()
                learningEnabled = LearningPreferences.isEnabled(context)
                userModel = if (learningEnabled) readUserModel() else UserNgramModel.empty()
                artifacts = assets
                engine = build(assets, personal, userModel)
                loaded = Loaded(
                    lexiconWords = assets.lexicon.size,
                    trieNodes = assets.trie.nodeCount,
                    bigramGroups = assets.bigrams.groupCount,
                    bigramPairs = assets.bigrams.bigramCount,
                    personalWords = personal.size,
                    learnedPairs = userModel.pairCount,
                )
                ImeDiagnostics.recordReady(
                    context, assets.lexicon.size, assets.trie.nodeCount,
                    assets.bigrams.bigramCount, degraded,
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
                // The class name only. NEVER the message: an exception from a parser can
                // quote the bytes it choked on, and those bytes could be anything.
                ImeDiagnostics.recordFailed(
                    context, unreadable.javaClass.simpleName, degraded,
                )
            } finally {
                degradedAssets = degraded
            }
        }
    }

    /**
     * The packaged assets, loaded and turned into the structures prediction needs.
     *
     * ### Why this is a separate, NON-suspend function
     * `Trace.beginSection`/`endSection` are **per-thread**. This work used to run inline in the
     * `launch` block with the trace opened around all of it, and two suspending calls --
     * `readPersonalDictionary()` and `readUserModel()` -- sat inside the traced region. On
     * `Dispatchers.Default` a coroutine that suspends can resume on a **different** worker, so
     * `endSection()` could run on a thread that never called `beginSection()`: the original
     * thread's section stays open forever and the resuming thread closes a section it does not
     * own. That corrupts every measurement after it in the same trace, not just this one, and
     * `GATE-TRACE-1` cannot see it -- it checks that the benchmark asks for section names the
     * app emits, not that the sections are balanced.
     *
     * Making this a plain function is what enforces the rule: a non-suspend function cannot
     * contain a suspension point, so the compiler now guarantees what a comment used to ask
     * for. `GATE-TRACE-2` checks the same property across the file, because the next traced
     * region will not have this comment attached to it.
     *
     * Found by Android lint's `UnclosedTrace` while clearing warnings for release.
     */
    private fun loadAssets(degraded: MutableSet<String>): Artifacts {
        Trace.beginSection(TRACE_LOAD)
        try {
            val lexicon = context.assets.open(LEXICON_ASSET).use { HebrewLexicon.load(it) }
            // A VIEW, not a copy. Copying 355,587 words into an ArrayList costs ~34 MB of
            // heap and is alive exactly while the trie's arrays are being allocated; see
            // HebrewLexicon.asWordList.
            val trie = LexiconTrie.build(lexicon.asWordList())
            val frequency = context.assets.open(FREQUENCY_ASSET)
                .use { HebrewFrequency.load(it) }
            // Prediction is worth having without bigrams; the keyboard is not worth losing
            // over them. GATE-BIGRAM-1 is what makes this branch a diagnostic rather than
            // a way for a packaging mistake to reach a user unnoticed.
            val bigrams = try {
                context.assets.open(BIGRAM_ASSET).use { BigramModel.load(it) }
            } catch (unreadable: Throwable) {
                degraded.add(BIGRAM_ASSET)
                BigramModel.EMPTY
            }
            // Same treatment as the bigram table: worth having, not worth losing the
            // keyboard over. GATE-ASSET-1 is what keeps this a diagnostic rather than a
            // way for a packaging mistake to reach a user unnoticed.
            val abbreviations = try {
                context.assets.open(ABBREVIATION_ASSET)
                    .use { HebrewAbbreviations.load(it) }
            } catch (unreadable: Throwable) {
                degraded.add(ABBREVIATION_ASSET)
                HebrewAbbreviations.EMPTY
            }
            return Artifacts(lexicon, trie, frequency, bigrams, abbreviations)
        } finally {
            Trace.endSection()
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
    /**
     * The Android [android.content.Context], named apart from the `TypingContext` parameter
     * below. Two different things called `context` in one function is how a diagnostic ends up
     * silently recording the wrong object -- or, here, simply failing to compile, which is the
     * better outcome of the two.
     */
    private val androidContext get() = context

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
            ImeDiagnostics.recordRequest(androidContext, allowed = false, produced = 0)
            onResult(emptyList())
            return
        }
        val ready = engine
        if (ready == null) {
            ImeDiagnostics.recordRequest(androidContext, allowed = true, produced = 0)
            warmUp()
            onResult(emptyList())
            return
        }
        inFlight = scope.launch {
            Trace.beginSection(TRACE_SUGGEST)
            @Suppress("UnclosedTrace")  // paired in the finally on the next lines
            val result = try {
                ready.predict(context)
            } finally {
                Trace.endSection()
            }
            ImeDiagnostics.recordRequest(androidContext, allowed = true, produced = result.size)
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /**
     * Re-read the personal dictionary and rebuild the engine if it changed.
     *
     * Called from `onStartInput` on fields that may show suggestions. The settings screen and
     * the keyboard are separate components, and a word added while the keyboard was already
     * running would otherwise keep being underlined until the process restarted — a setting
     * that appears not to work.
     *
     * Never called for a restricted field. Nothing here would leak — the dictionary holds only
     * words the user typed into a settings screen, and predictions are not computed at all when
     * `maySuggest` is false — but reading a Keystore-sealed file on entry to a password field
     * is work with no possible purpose.
     */
    fun refreshPersonalDictionary() {
        val ready = artifacts ?: return
        scope.launch {
            val fresh = readPersonalDictionary()
            if (fresh.size == personal.size && fresh.all() == personal.all()) return@launch
            personal = fresh
            engine = build(ready, fresh, userModel)
            loaded = loaded?.copy(personalWords = fresh.size)
        }
    }

    private suspend fun readPersonalDictionary(): PersonalDictionary =
        try {
            personalStore.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Throwable) {
            // The repository already returns an empty dictionary for a corrupted or
            // unopenable file; this catches the rest -- a missing Keystore key after a
            // restore, for instance. The keyboard works without it.
            PersonalDictionary()
        }

    /**
     * Record that [second] followed [first], if this installation is allowed to.
     *
     * **The caller must already have checked `SessionStart.mayLearn`.** Two independent
     * conditions have to hold before a single pair is counted — the user opted in, and the
     * field is one that may be learned from — and they are checked in different places on
     * purpose: an opt-in that silently covered password fields would be worthless, and a field
     * policy that applied to people who never opted in would be a broken promise.
     *
     * `GATE-LEARN-2` checks statically that the one call site is guarded.
     */
    fun learn(first: String, second: String) {
        if (!learningEnabled) return
        val ready = artifacts ?: return
        val model = userModel
        scope.launch {
            model.record(idFor(ready, first), idFor(ready, second))
            // Personal word frequency, which sharpens COMPLETIONS. Recorded for the completed
            // word regardless of whether its predecessor was known, because a word is worth
            // counting even when the pair is not -- at the start of a field, or after a desync.
            model.recordWord(idFor(ready, second))
            userStore.scheduleSave(model)
        }
    }

    /**
     * End the learning session, so the next sighting of a pair counts separately.
     *
     * Called from `onFinishInput`. One focused field is one session, which is what makes
     * `UserNgramModel.minimumSessions` mean "came back later" rather than "was repeated".
     */
    fun endLearningSession() {
        if (!learningEnabled) return
        val model = userModel
        scope.launch { model.endSession() }
    }

    /**
     * Re-read the opt-in and rebuild if it changed.
     *
     * Called from `onStartInput`. The switch lives in a different Activity, so without this a
     * user turning learning **off** would keep being learned from until the IME process
     * happened to be killed — the failure that matters, and the reason this is not merely a
     * convenience.
     *
     * Turning it off drops the in-memory model and stops writing. It does **not** delete what
     * is stored; that is what "forget what you learned" is for, and conflating pause with
     * delete would mean someone pausing the feature silently lost everything.
     */
    fun refreshLearningState() {
        val enabled = LearningPreferences.isEnabled(context)
        val ready = artifacts ?: return
        val toggled = enabled != learningEnabled
        learningEnabled = enabled
        scope.launch {
            // "Forget what you learned" happens in the settings Activity, which deletes the
            // file and the key. The IME may already be running with the model in memory, and
            // without this check it would go on suggesting from data the user just asked to be
            // destroyed -- the wipe would look like it worked and would not have.
            val wiped = enabled && userModel.pairCount > 0 && !userStore.hasStoredData()
            if (!toggled && !wiped) return@launch
            if (wiped) userModel.clear()
            userModel = if (enabled) readUserModel() else UserNgramModel.empty()
            engine = build(ready, personal, userModel)
            loaded = loaded?.copy(learnedPairs = userModel.pairCount)
        }
    }

    /**
     * The id a token is learned under: its lexicon index, or `UserNgramModel.OOV`.
     *
     * The single place the out-of-lexicon decision is applied in the app, and it mirrors what
     * the measurement harness does — so the reported numbers describe this code rather than a
     * model the product does not have.
     */
    private fun idFor(a: Artifacts, word: String): Int {
        val index = a.lexicon.indexOf(word)
        return if (index >= 0) index else UserNgramModel.OOV
    }

    private suspend fun readUserModel(): UserNgramModel =
        try {
            userStore.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Throwable) {
            UserNgramModel()
        }

    private fun build(
        a: Artifacts,
        personal: PersonalDictionary,
        learned: UserNgramModel,
    ) = PredictiveEngine(
        lexicon = a.lexicon,
        trie = a.trie,
        frequency = a.frequency,
        bigrams = a.bigrams,
        corrections = CorrectionEngine(
            lexicon = a.lexicon,
            trie = a.trie,
            frequency = a.frequency,
            // The shipped configuration. The keyboard-adjacency discount is measured and
            // deliberately NOT enabled -- it costs 8 points of top-1 accuracy on an unbiased
            // corpus. See docs/CORRECTION_MEASUREMENTS.md finding 1.
            costs = NeutralCostModel,
            config = CorrectionEngine.Config(),
            personal = personal,
        ),
        // Defaults, whose bigramWeight was chosen from the sweep in
        // docs/PREDICTION_MEASUREMENTS.md and not before it.
        config = PredictiveEngine.Config(),
        // Real-word errors: `אם` where `עם` was meant.
        //
        // ### WITHDRAWN 2026-08-25. This slot is deliberately empty.
        //
        // The rule was registered BEFORE the labels existed: a detector whose precision is
        // under 40% is withdrawable. Measured on 320 real firings judged blind by a Hebrew
        // speaker, precision is bounded at [12.5%, 39.7%] -- **the ceiling does not reach the
        // floor of the rule**, and no configuration rescues it. Margin, letter-pair and
        // frequency restrictions were each swept and each failed; the evidence advantage is
        // flat across bands (16.0 / 12.5 / 16.7), so the detector's own confidence signal does
        // not separate its right answers from its wrong ones.
        //
        // 4.83 false alarms per true positive among decided items means the modal response to
        // a flag is dismissal. That is not neutral: a strip wrong five times in six teaches
        // the user to stop reading the strip, including when it is right about something else.
        //
        // W1 then measured it getting WORSE in the register people actually type in -- its
        // false-alarm rate on CORRECT text is 0.19% on transcribed dialogue and 0.33% on typed
        // Hebrew. See docs/TYPED_REGISTER.md.
        //
        // The distance-2 layer was withdrawn on exactly this kind of evidence and the adjacent
        // layer was not. That inconsistency is what is being closed here.
        //
        // The class, its tests and every measurement stay: `:core` still constructs it, the
        // sweeps still reproduce, and nothing about the finding becomes unverifiable. What
        // changes is that it no longer runs for a user. `GATE-WITHDRAWN-1` keeps it that way.
        //
        // realWordErrors is left at its default of null. PredictiveEngine returns early when it
        // is null, so this is off rather than configured-quiet.
        personal = personal,
        // Empty unless the user opted in, and an empty model is arithmetically identical to no
        // model at all -- LearningNeutralityTest asserts that over 135,960 contexts.
        userModel = learned,
        abbreviations = a.abbreviations,
    )

    /** Cancel outstanding work. Called when the input session changes or the IME is destroyed. */
    fun cancelOutstanding() {
        inFlight?.cancel()
        inFlight = null
    }

    fun shutdown() {
        scope.cancel()
        userModel = UserNgramModel.empty()
        engine = null
        artifacts = null
        personal = PersonalDictionary()
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
        const val ABBREVIATION_ASSET = "he_abbreviations.txt"

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

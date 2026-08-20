package com.hebrewime.diagnostics

import android.content.Context

/**
 * Why the keyboard is not suggesting anything — readable by the person holding the phone.
 *
 * ### Why this exists
 * A user reported that the keyboard typed correctly and never offered a suggestion. Every
 * candidate explanation was **silent**: the dictionary failing to load leaves `engine = null`
 * behind a `catch (Throwable)`, a restricted field returns an empty list by design, and an
 * `OutOfMemoryError` during warm-up looks exactly like both. Nothing was written down, so
 * neither the user nor the developer could tell which had happened, and the only way to find
 * out would have been to guess and re-ship.
 *
 * That is the defect this fixes. The suggestion engine failing is a normal, survivable
 * condition — the keyboard must keep typing — but a *silent* failure is not survivable for
 * anyone trying to work out what went wrong.
 *
 * ### What is recorded, and what is deliberately not
 * Counters and load status. **No text, no field identity, no package name, no hint.**
 *
 * Recording "the last field was a password field" would be genuinely useful here and is not
 * recorded, because it would put a note on disk saying the user recently typed a password
 * somewhere. The counters answer the same question — if requests are being made and none are
 * allowed, the fields are being classified as restricted — without naming a single one.
 *
 * Persisted rather than held in memory because the IME service and the settings screen are
 * separate components with independent lifetimes: by the time someone opens settings to read
 * this, the process that produced it may be long gone.
 */
object ImeDiagnostics {

    private const val FILE = "com.hebrewime.diagnostics"

    private const val KEY_STATE = "engine_state"
    private const val KEY_DETAIL = "engine_detail"
    private const val KEY_WORDS = "lexicon_words"
    private const val KEY_NODES = "trie_nodes"
    private const val KEY_BIGRAMS = "bigram_pairs"
    private const val KEY_DEGRADED = "degraded_assets"
    private const val KEY_REQUESTS = "suggest_requests"
    private const val KEY_ALLOWED = "suggest_allowed"
    private const val KEY_NONEMPTY = "suggest_nonempty"

    enum class EngineState { NEVER_STARTED, LOADING, READY, FAILED }

    data class Snapshot(
        val state: EngineState,
        /** Exception class name when [state] is [EngineState.FAILED]. Never a message. */
        val detail: String,
        val lexiconWords: Int,
        val trieNodes: Int,
        val bigramPairs: Int,
        val degradedAssets: String,
        val requests: Int,
        val allowed: Int,
        val nonEmpty: Int,
    ) {
        /** A one-line answer to "why am I not getting suggestions?". */
        val verdict: String
            get() = when {
                state == EngineState.NEVER_STARTED -> "The dictionary has never been loaded."
                state == EngineState.LOADING -> "The dictionary is still loading."
                state == EngineState.FAILED ->
                    "The dictionary FAILED to load ($detail). Suggestions are off."
                requests == 0 -> "Loaded, but the keyboard has never asked for a suggestion."
                allowed == 0 ->
                    "Loaded, but every field so far was treated as sensitive, so nothing was " +
                        "suggested."
                nonEmpty == 0 ->
                    "Loaded and asked $requests times, but no suggestion was ever produced."
                else -> "Working: $nonEmpty of $requests requests produced suggestions."
            }
    }

    fun recordLoading(context: Context) = edit(context) {
        putString(KEY_STATE, EngineState.LOADING.name)
    }

    fun recordReady(
        context: Context,
        lexiconWords: Int,
        trieNodes: Int,
        bigramPairs: Int,
        degraded: Set<String>,
    ) = edit(context) {
        putString(KEY_STATE, EngineState.READY.name)
        putString(KEY_DETAIL, "")
        putInt(KEY_WORDS, lexiconWords)
        putInt(KEY_NODES, trieNodes)
        putInt(KEY_BIGRAMS, bigramPairs)
        putString(KEY_DEGRADED, degraded.joinToString(", "))
    }

    /** @param failure the throwable's class name only — never its message, which could quote input. */
    fun recordFailed(context: Context, failure: String, degraded: Set<String>) = edit(context) {
        putString(KEY_STATE, EngineState.FAILED.name)
        putString(KEY_DETAIL, failure)
        putString(KEY_DEGRADED, degraded.joinToString(", "))
    }

    /**
     * One suggestion request. [allowed] is false on a field the policy restricts; [produced] is
     * how many candidates came back.
     */
    fun recordRequest(context: Context, allowed: Boolean, produced: Int) = edit(context) {
        val p = prefs(context)
        putInt(KEY_REQUESTS, p.getInt(KEY_REQUESTS, 0) + 1)
        if (allowed) putInt(KEY_ALLOWED, p.getInt(KEY_ALLOWED, 0) + 1)
        if (produced > 0) putInt(KEY_NONEMPTY, p.getInt(KEY_NONEMPTY, 0) + 1)
    }

    fun read(context: Context): Snapshot {
        val p = prefs(context)
        return Snapshot(
            state = runCatching {
                EngineState.valueOf(p.getString(KEY_STATE, null) ?: "")
            }.getOrDefault(EngineState.NEVER_STARTED),
            detail = p.getString(KEY_DETAIL, "").orEmpty(),
            lexiconWords = p.getInt(KEY_WORDS, 0),
            trieNodes = p.getInt(KEY_NODES, 0),
            bigramPairs = p.getInt(KEY_BIGRAMS, 0),
            degradedAssets = p.getString(KEY_DEGRADED, "").orEmpty(),
            requests = p.getInt(KEY_REQUESTS, 0),
            allowed = p.getInt(KEY_ALLOWED, 0),
            nonEmpty = p.getInt(KEY_NONEMPTY, 0),
        )
    }

    fun reset(context: Context) = edit(context) { clear() }

    private inline fun edit(
        context: Context,
        block: android.content.SharedPreferences.Editor.() -> Unit,
    ) {
        prefs(context).edit().apply(block).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

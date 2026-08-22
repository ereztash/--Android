package com.hebrewime.diagnostics

import android.content.Context
import com.hebrewime.core.selfcheck.CheckArithmetic

/**
 * Numbers the keyboard records about itself while it runs, so a self-check can read them later.
 *
 * ### Why not just look at the phone
 * `docs/QA_MATRIX.md` has a table of checks that "require a physical Android device". Most of
 * them required a device *and a person looking at it at the right moment*, which is why they
 * stayed NOT RUN. Whether the bottom key row clears the gesture inset is a subtraction the
 * device can do; whether a label overflowed its key is a comparison the drawing code already
 * makes. This is where those measurements are kept between the moment they happen and the
 * moment someone opens Settings to read them.
 *
 * ### What is stored, and what is refused
 * Integers and floats. **No text, no field identity, no package name, no timestamps of
 * individual events.** The same rule as [ImeDiagnostics], for the same reason: a diagnostic
 * that records what someone typed is a keystroke log with a friendly name.
 *
 * Latency is the one measurement that could leak by shape rather than by content -- a sequence
 * of per-keystroke timings is a rhythm, and rhythm identifies people. So raw samples never
 * leave memory: the ring buffer is process-local, and only **percentiles and a count** are
 * written down.
 */
object DeviceEvidence {

    private const val FILE = "com.hebrewime.device_evidence"

    private const val KEY_VIEW_HEIGHT = "view_height"
    private const val KEY_LOWEST_KEY_BOTTOM = "lowest_key_bottom"
    private const val KEY_BOTTOM_INSET = "bottom_inset"
    private const val KEY_INSET_SEEN = "inset_seen"

    private const val KEY_LABELS_TOTAL = "labels_total"
    private const val KEY_LABELS_OVER = "labels_overflowing"
    private const val KEY_LABEL_WORST = "label_worst_ratio"

    private const val KEY_RESTRICTED_SEEN = "restricted_seen"
    private const val KEY_RESTRICTED_SERVED = "restricted_served"
    private const val KEY_INITIAL_TEXT_TAKEN = "initial_text_taken"

    private const val KEY_LAT_N = "latency_n"
    private const val KEY_LAT_P50 = "latency_p50_us"
    private const val KEY_LAT_P95 = "latency_p95_us"
    private const val KEY_LAT_MAX = "latency_max_us"

    private const val KEY_RELOADS = "dictionary_reloads"
    private const val KEY_TYPEFACE = "typeface_loaded"

    private const val KEY_PREVIEWS = "key_previews_shown"
    private const val KEY_REPEATS = "backspace_repeats"
    private const val KEY_LONG_PRESSES = "long_presses"

    /**
     * The last [RING] keystroke durations, in microseconds, process-local and never persisted.
     *
     * 256 is about twenty seconds of fast typing: long enough that a p95 means something,
     * short enough that it is always the recent past rather than a session history.
     */
    private const val RING = 256
    private val ring = LongArray(RING)
    private var ringCount = 0
    private var ringNext = 0

    /** Percentiles are rewritten every this many keystrokes, not on every one. */
    private const val FLUSH_EVERY = 16

    // ------------------------------------------------------------------ record

    /**
     * The keyboard's laid-out geometry against the system inset it has to clear.
     *
     * Recorded on every layout, overwriting: the current posture is the one worth checking, and
     * keeping a history would be a record of when the phone was rotated.
     */
    fun recordLayout(
        context: Context,
        viewHeight: Int,
        lowestKeyBottom: Float,
        bottomInset: Int,
    ) = edit(context) {
        putInt(KEY_VIEW_HEIGHT, viewHeight)
        putFloat(KEY_LOWEST_KEY_BOTTOM, lowestKeyBottom)
        putInt(KEY_BOTTOM_INSET, bottomInset)
        putBoolean(KEY_INSET_SEEN, true)
    }

    /**
     * How many key labels did not fit their key at the size the keyboard drew them.
     *
     * [worstRatio] is the largest advance/available ratio seen, so a report can say how badly
     * rather than only whether.
     */
    fun recordLabelFit(context: Context, total: Int, overflowing: Int, worstRatio: Float) =
        edit(context) {
            putInt(KEY_LABELS_TOTAL, total)
            putInt(KEY_LABELS_OVER, overflowing)
            putFloat(KEY_LABEL_WORST, worstRatio)
        }

    /**
     * One field the policy restricted.
     *
     * [served] is true if a suggestion was nevertheless produced for it, which would be the
     * M4-DEVICE failure. [initialTextTaken] is true if the lazy initial-text provider was
     * invoked on a restricted field -- the plaintext the framework hands over in `EditorInfo`,
     * which on a restricted field must never be read.
     */
    fun recordRestrictedField(context: Context, served: Boolean, initialTextTaken: Boolean) =
        edit(context) {
            val p = prefs(context)
            putInt(KEY_RESTRICTED_SEEN, p.getInt(KEY_RESTRICTED_SEEN, 0) + 1)
            if (served) putInt(KEY_RESTRICTED_SERVED, p.getInt(KEY_RESTRICTED_SERVED, 0) + 1)
            if (initialTextTaken) {
                putInt(KEY_INITIAL_TEXT_TAKEN, p.getInt(KEY_INITIAL_TEXT_TAKEN, 0) + 1)
            }
        }

    /**
     * One keystroke, start to finish, in microseconds.
     *
     * Called from the IME's key handler, so it spans planning, the batch edit over the Binder,
     * the learning hook and the suggestion refresh -- the whole thing between the finger and
     * the screen, which is the only latency a person experiences.
     */
    fun recordKeystroke(context: Context, micros: Long) {
        synchronized(ring) {
            ring[ringNext] = micros
            ringNext = (ringNext + 1) % RING
            if (ringCount < RING) ringCount++
            if (ringCount % FLUSH_EVERY != 0) return
        }
        flushLatency(context)
    }

    /** Writes percentiles of the in-memory ring. Raw samples are never persisted. */
    fun flushLatency(context: Context) {
        val snapshot = synchronized(ring) {
            if (ringCount == 0) return
            ring.copyOfRange(0, ringCount)
        }
        val p50 = CheckArithmetic.percentile(snapshot, 50.0) ?: return
        val p95 = CheckArithmetic.percentile(snapshot, 95.0) ?: return
        edit(context) {
            putInt(KEY_LAT_N, snapshot.size)
            putLong(KEY_LAT_P50, p50)
            putLong(KEY_LAT_P95, p95)
            putLong(KEY_LAT_MAX, snapshot.max())
        }
    }

    fun recordDictionaryReload(context: Context) = edit(context) {
        putInt(KEY_RELOADS, prefs(context).getInt(KEY_RELOADS, 0) + 1)
    }

    fun recordTypeface(context: Context, loaded: Boolean) = edit(context) {
        putBoolean(KEY_TYPEFACE, loaded)
    }

    fun recordPreviewShown(context: Context) = bump(context, KEY_PREVIEWS)
    fun recordBackspaceRepeat(context: Context) = bump(context, KEY_REPEATS)
    fun recordLongPress(context: Context) = bump(context, KEY_LONG_PRESSES)

    // -------------------------------------------------------------------- read

    data class Snapshot(
        val insetSeen: Boolean,
        val viewHeight: Int,
        val lowestKeyBottom: Float,
        val bottomInset: Int,
        val labelsTotal: Int,
        val labelsOverflowing: Int,
        val labelWorstRatio: Float,
        val restrictedSeen: Int,
        val restrictedServed: Int,
        val initialTextTaken: Int,
        val latencyN: Int,
        val latencyP50Micros: Long,
        val latencyP95Micros: Long,
        val latencyMaxMicros: Long,
        val dictionaryReloads: Int,
        val typefaceLoaded: Boolean,
        val previewsShown: Int,
        val backspaceRepeats: Int,
        val longPresses: Int,
    )

    fun read(context: Context): Snapshot {
        val p = prefs(context)
        return Snapshot(
            insetSeen = p.getBoolean(KEY_INSET_SEEN, false),
            viewHeight = p.getInt(KEY_VIEW_HEIGHT, 0),
            lowestKeyBottom = p.getFloat(KEY_LOWEST_KEY_BOTTOM, 0f),
            bottomInset = p.getInt(KEY_BOTTOM_INSET, 0),
            labelsTotal = p.getInt(KEY_LABELS_TOTAL, 0),
            labelsOverflowing = p.getInt(KEY_LABELS_OVER, 0),
            labelWorstRatio = p.getFloat(KEY_LABEL_WORST, 0f),
            restrictedSeen = p.getInt(KEY_RESTRICTED_SEEN, 0),
            restrictedServed = p.getInt(KEY_RESTRICTED_SERVED, 0),
            initialTextTaken = p.getInt(KEY_INITIAL_TEXT_TAKEN, 0),
            latencyN = p.getInt(KEY_LAT_N, 0),
            latencyP50Micros = p.getLong(KEY_LAT_P50, 0L),
            latencyP95Micros = p.getLong(KEY_LAT_P95, 0L),
            latencyMaxMicros = p.getLong(KEY_LAT_MAX, 0L),
            dictionaryReloads = p.getInt(KEY_RELOADS, 0),
            typefaceLoaded = p.getBoolean(KEY_TYPEFACE, false),
            previewsShown = p.getInt(KEY_PREVIEWS, 0),
            backspaceRepeats = p.getInt(KEY_REPEATS, 0),
            longPresses = p.getInt(KEY_LONG_PRESSES, 0),
        )
    }

    /**
     * Throws away everything measured so far, including the in-memory latency ring.
     *
     * Offered in Settings beside the report: a self-check whose numbers came from a build two
     * installs ago is worse than no numbers, and the person holding the phone is the only one
     * who knows when they last changed something.
     */
    fun reset(context: Context) {
        synchronized(ring) {
            ringCount = 0
            ringNext = 0
            ring.fill(0L)
        }
        edit(context) { clear() }
    }

    private fun bump(context: Context, key: String) = edit(context) {
        putInt(key, prefs(context).getInt(key, 0) + 1)
    }

    private inline fun edit(
        context: Context,
        block: android.content.SharedPreferences.Editor.() -> Unit,
    ) {
        prefs(context).edit().apply(block).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

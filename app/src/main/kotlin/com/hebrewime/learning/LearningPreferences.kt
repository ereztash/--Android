package com.hebrewime.learning

import android.content.Context

/**
 * Whether this installation is allowed to learn from typing. **Default off.**
 *
 * Default-off is not a technical hedge, it is what lets the store listing keep saying "this
 * keyboard does not learn from what you type" without an asterisk for anyone who never opened
 * settings. Turning it on is an act; the promise for everyone else is unchanged.
 *
 * A plain boolean in `SharedPreferences`. Deliberately not in the encrypted store: whether the
 * feature is enabled is not user content, and putting it behind the Keystore key would mean the
 * setting became unreadable in exactly the situation where it most needs to be read — a restore
 * onto a new device, where the key is gone and learning should come back **off**.
 */
object LearningPreferences {

    private const val FILE = "com.hebrewime.learning"
    private const val KEY_ENABLED = "adaptive_learning_enabled"
    private const val KEY_ACCEPTED = "completions_accepted"
    private const val KEY_ACCEPTED_FROM_MODEL = "completions_accepted_from_user_model"

    /** False unless the user turned it on. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * How many completions the user has accepted, and how many of those were on screen **only
     * because** of what this installation learned.
     *
     * Two integers. Not which words, not when, not in which app — a count is the most that can
     * be stored here and still be a count. `GATE-LEARN-1` refuses a `String` anywhere in the
     * learning path and these are the reason the rule is easy to keep.
     *
     * The second number is the one worth showing. "Word pairs learned" goes up whether or not
     * anything got better; this goes up only when the learning changed what the user saw.
     */
    data class Benefit(val accepted: Int, val fromUserModel: Int)

    fun benefit(context: Context): Benefit = prefs(context).let {
        Benefit(it.getInt(KEY_ACCEPTED, 0), it.getInt(KEY_ACCEPTED_FROM_MODEL, 0))
    }

    /** Called once per accepted completion, on the main thread, off the input path. */
    fun recordAcceptedCompletion(context: Context, fromUserModel: Boolean) {
        val p = prefs(context)
        val edit = p.edit().putInt(KEY_ACCEPTED, p.getInt(KEY_ACCEPTED, 0) + 1)
        if (fromUserModel) {
            edit.putInt(KEY_ACCEPTED_FROM_MODEL, p.getInt(KEY_ACCEPTED_FROM_MODEL, 0) + 1)
        }
        edit.apply()
    }

    /** Cleared with the model itself: a count of what was forgotten is still a record of it. */
    fun clearBenefit(context: Context) {
        prefs(context).edit().remove(KEY_ACCEPTED).remove(KEY_ACCEPTED_FROM_MODEL).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

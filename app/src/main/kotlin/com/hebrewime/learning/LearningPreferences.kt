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

    /** False unless the user turned it on. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

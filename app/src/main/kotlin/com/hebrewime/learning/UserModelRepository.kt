package com.hebrewime.learning

import android.content.Context
import com.hebrewime.core.dictionary.EncryptedStore
import com.hebrewime.core.learning.UserNgramCodec
import com.hebrewime.core.learning.UserNgramModel
import com.hebrewime.dictionary.KeystoreKeyProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads and writes the learned n-gram model, encrypted, off the main thread, debounced.
 *
 * Same crypto as the personal dictionary — [EncryptedStore], AES-GCM, a Keystore-backed key —
 * because writing a second cipher would be inventing risk for no benefit. A **different key
 * alias**, because the two wipes are different promises; see
 * [KeystoreKeyProvider.LEARNING_KEY_ALIAS].
 *
 * ### Why writes are debounced rather than immediate
 * The model changes on every completed word. Sealing and rewriting a file that often would put
 * AES and an fsync on the path between someone pressing space and seeing a suggestion. The
 * write is therefore coalesced: [scheduleSave] restarts a short timer, and only the last edit
 * in a burst reaches the disk.
 *
 * The cost is that a crash loses at most the last [DEBOUNCE_MS] of learning, which for this
 * data is not worth a single dropped frame. [saveNow] exists for `onDestroy`, where the process
 * is going away and the timer will not fire.
 *
 * ### Writes are atomic
 * Temp file, then rename — the pattern the personal dictionary already uses. A half-written
 * GCM blob is not a partially-corrupted model, it is an **unopenable** one, so a crash
 * mid-write would otherwise cost everything rather than the last few pairs.
 */
class UserModelRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val file: File get() = File(context.filesDir, FILE_NAME)
    private var pendingSave: Job? = null

    /**
     * Load, returning an empty model if nothing is stored or the file cannot be opened.
     *
     * Every failure path lands on "empty", including a Keystore key that no longer exists —
     * which is what a restore onto a new device looks like, since Keystore keys do not migrate.
     * Presenting whatever partial bytes happen to decode is exactly what GCM exists to prevent.
     */
    suspend fun load(): UserNgramModel = withContext(Dispatchers.IO) {
        val f = file
        if (!f.exists()) return@withContext UserNgramModel()
        try {
            val plaintext = EncryptedStore.open(
                f.readBytes(), KeystoreKeyProvider.getOrCreate(ALIAS),
            )
            UserNgramCodec.decode(plaintext)
        } catch (_: EncryptedStore.CorruptedException) {
            UserNgramModel()
        } catch (_: UserNgramCodec.CorruptedException) {
            UserNgramModel()
        } catch (_: java.security.GeneralSecurityException) {
            UserNgramModel()
        } catch (_: java.io.IOException) {
            UserNgramModel()
        }
    }

    /** Coalesce a save. Only the last edit in a burst reaches the disk. */
    fun scheduleSave(model: UserNgramModel) {
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(DEBOUNCE_MS)
            saveNow(model)
        }
    }

    suspend fun saveNow(model: UserNgramModel): Unit = withContext(Dispatchers.IO) {
        val sealed = EncryptedStore.seal(
            UserNgramCodec.encode(model), KeystoreKeyProvider.getOrCreate(ALIAS),
        )
        val temporary = File(context.filesDir, "$FILE_NAME.tmp")
        temporary.writeBytes(sealed)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw java.io.IOException("could not replace the learned model atomically")
        }
    }

    /**
     * Forget everything: the ciphertext **and** the key.
     *
     * Deleting the key is the part that counts. An app cannot reliably overwrite the flash a
     * file occupied, so bytes may survive deletion — without the key they are unopenable by
     * anyone, this app included. The personal dictionary's key is a different alias and is
     * untouched here.
     */
    suspend fun wipe(): Unit = withContext(Dispatchers.IO) {
        pendingSave?.cancel()
        pendingSave = null
        file.delete()
        File(context.filesDir, "$FILE_NAME.tmp").delete()
        KeystoreKeyProvider.delete(ALIAS)
    }

    suspend fun hasStoredData(): Boolean = withContext(Dispatchers.IO) {
        file.exists() || KeystoreKeyProvider.exists(ALIAS)
    }

    companion object {
        const val FILE_NAME = "user_ngrams.bin"
        const val ALIAS = KeystoreKeyProvider.LEARNING_KEY_ALIAS

        /**
         * 3 seconds.
         *
         * Long enough that a burst of typing produces one write rather than dozens; short
         * enough that switching apps mid-sentence does not routinely lose a session's worth of
         * learning. Not measured on a device — nothing here has been — and recorded as a
         * judgement rather than a result.
         */
        const val DEBOUNCE_MS = 3_000L
    }
}

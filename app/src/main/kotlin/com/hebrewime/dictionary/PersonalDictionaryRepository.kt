package com.hebrewime.dictionary

import android.content.Context
import com.hebrewime.core.dictionary.EncryptedStore
import com.hebrewime.core.dictionary.PersonalDictionary
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads and writes the encrypted personal dictionary.
 *
 * All file and crypto work happens on [Dispatchers.IO]; none of it belongs on the thread that
 * services keystrokes.
 *
 * Writes are atomic: the new blob goes to a temporary file which is then renamed over the old
 * one. A half-written ciphertext is not a corrupted dictionary, it is an *unopenable* one —
 * GCM authentication would reject it wholesale — so a crash mid-save would otherwise lose every
 * word the user had added rather than the one being added.
 */
class PersonalDictionaryRepository(private val context: Context) {

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    /** Load, returning an empty dictionary if nothing is stored or the file cannot be opened. */
    suspend fun load(): PersonalDictionary = withContext(Dispatchers.IO) {
        val f = file
        if (!f.exists()) return@withContext PersonalDictionary()
        try {
            val plaintext = EncryptedStore.open(f.readBytes(), KeystoreKeyProvider.getOrCreate())
            PersonalDictionary.deserialize(plaintext)
        } catch (_: EncryptedStore.CorruptedException) {
            // Tampered, truncated, or sealed under a key that no longer exists -- which is
            // what a restore-to-a-new-device looks like, since Keystore keys do not migrate.
            // An empty dictionary is the only safe interpretation; the alternative is
            // presenting whatever partial bytes happen to decode, which GCM exists to prevent.
            PersonalDictionary()
        } catch (_: java.security.GeneralSecurityException) {
            PersonalDictionary()
        } catch (_: java.io.IOException) {
            PersonalDictionary()
        }
    }

    suspend fun save(dictionary: PersonalDictionary): Unit = withContext(Dispatchers.IO) {
        val sealed = EncryptedStore.seal(dictionary.serialize(), KeystoreKeyProvider.getOrCreate())
        val temporary = File(context.filesDir, "$FILE_NAME.tmp")
        temporary.writeBytes(sealed)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw java.io.IOException("could not replace the dictionary file atomically")
        }
    }

    /**
     * Remove everything: the ciphertext **and** the key.
     *
     * Deleting the key is the part that counts. An app cannot overwrite the flash a file
     * occupied, so bytes may survive deletion -- but without the key they are unopenable by
     * anyone, including this app.
     */
    suspend fun wipe(): Unit = withContext(Dispatchers.IO) {
        file.delete()
        File(context.filesDir, "$FILE_NAME.tmp").delete()
        KeystoreKeyProvider.delete()
    }

    /** Whether anything is stored at all. */
    suspend fun hasStoredData(): Boolean = withContext(Dispatchers.IO) {
        file.exists() || KeystoreKeyProvider.exists()
    }

    private companion object {
        const val FILE_NAME = "personal_dictionary.bin"
    }
}

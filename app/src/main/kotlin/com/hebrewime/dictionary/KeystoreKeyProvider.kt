package com.hebrewime.dictionary

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The AES key for the personal dictionary, held in the Android Keystore.
 *
 * The key material never enters this process. `SecretKey` here is a handle; the actual bytes
 * live in the Keystore, backed by the TEE or StrongBox where the device provides one. An
 * attacker with the app's data directory gets ciphertext and nothing that opens it.
 *
 * ### `setRandomizedEncryptionRequired` is left at its default of true
 * That default is what makes the Keystore refuse a caller-supplied IV, and it is the reason
 * [com.hebrewime.core.dictionary.EncryptedStore] lets the provider generate the IV instead of
 * generating one itself. Turning it off to accommodate caller-supplied IVs would trade a real
 * protection against IV reuse for nothing.
 *
 * ### What is deliberately NOT set
 * `setUserAuthenticationRequired(true)` would tie the dictionary to a device unlock. It is not
 * set, because the keyboard must be able to read its own dictionary while the user is typing
 * into a lock-screen field, and a keyboard that loses its words at the wrong moment is a
 * keyboard people turn off. Recorded as a decision, not an omission.
 */
object KeystoreKeyProvider {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "com.hebrewime.personal_dictionary.v1"

    /** Fetch the key, creating it on first use. */
    fun getOrCreate(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun exists(): Boolean =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.containsAlias(KEY_ALIAS)

    /**
     * Destroy the key.
     *
     * This is the part of "wipe everything" that actually matters. Deleting the ciphertext file
     * leaves bytes on flash that no app can reliably overwrite; deleting the key makes those
     * bytes permanently unopenable, including by this app.
     */
    fun delete() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }
}

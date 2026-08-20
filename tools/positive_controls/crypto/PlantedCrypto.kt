// POSITIVE CONTROL for the crypto rules. Every line is a deliberate defect. Compiled by nothing.
package tools.positive_controls.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.security.SecureRandom

object PlantedCrypto {

    // A bare "AES" transformation silently means AES/ECB.
    fun ecbByDefault() = Cipher.getInstance("AES")

    // ECB spelled out.
    fun ecbExplicit() = Cipher.getInstance("AES/ECB/PKCS5Padding")

    // A fixed IV. Under GCM this is catastrophic.
    fun fixedIv() = IvParameterSpec(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))

    // A key baked into the binary.
    fun hardcodedKey() = SecretKeySpec("hunter2hunter2hu".toByteArray(), "AES")

    // A broken hash.
    fun weakHash() = MessageDigest.getInstance("MD5")

    // Reproducible randomness.
    fun seededRandom() = SecureRandom(byteArrayOf(1, 2, 3, 4))
}

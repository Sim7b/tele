package io.github.sim7b.tele.data.transfer

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption of a configuration sent to another TV on the local network: AES-256-GCM with a key derived
 * from the code shown on the sending TV (PBKDF2, random salt). Without the code the payload is useless,
 * and a wrong code fails to decrypt (authenticated encryption).
 */
object TransferCrypto {

    const val CODE_LENGTH = 8
    private const val ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val VERSION = 1

    fun newCode(random: SecureRandom = SecureRandom()): String =
        (0 until CODE_LENGTH).joinToString("") { random.nextInt(10).toString() }

    /** Code as typed: digits only ("1234 5678" -> "12345678"). */
    fun normalizeCode(typed: String): String = typed.filter { it.isDigit() }

    fun seal(plain: String, code: String, random: SecureRandom = SecureRandom()): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(code, salt), GCMParameterSpec(TAG_BITS, iv))
        val encoder = Base64.getEncoder()
        return JSONObject()
            .put("v", VERSION)
            .put("salt", encoder.encodeToString(salt))
            .put("iv", encoder.encodeToString(iv))
            .put("data", encoder.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8))))
            .toString()
    }

    /** Decrypted configuration; throws on a wrong code or a modified payload. */
    fun open(sealed: String, code: String): String {
        val json = JSONObject(sealed)
        require(json.optInt("v") == VERSION) { "Unsupported transfer version" }
        val decoder = Base64.getDecoder()
        val salt = decoder.decode(json.getString("salt"))
        val iv = decoder.decode(json.getString("iv"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(normalizeCode(code), salt), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(decoder.decode(json.getString("data"))), Charsets.UTF_8)
    }

    private fun key(code: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(normalizeCode(code).toCharArray(), salt, ITERATIONS, 256)
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(derived, "AES")
    }
}

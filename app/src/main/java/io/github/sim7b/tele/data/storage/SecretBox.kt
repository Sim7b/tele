package io.github.sim7b.tele.data.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec

/**
 * Encryption at rest of what holds credentials: the sources (API keys, Xtream passwords, tokenized
 * links), the chosen streams and the channel cache (Xtream stream URLs embed the password).
 *
 * Short values use AES-GCM; the channel cache, which can weigh tens of MB, is a CBC stream (GCM
 * decryption streams buffer the whole plaintext on Android). The key is created in the Android
 * Keystore and never leaves it; [keyProvider] is replaceable for the JVM tests.
 */
class SecretBox(
    keyProvider: () -> SecretKey,
    // Android Keystore name of PKCS#5/7 padding; the desktop JVM of the tests only knows "PKCS5Padding"
    private val cbcTransformation: String = "AES/CBC/PKCS7Padding"
) {

    private val key by lazy(keyProvider)

    fun isEncrypted(value: String) = value.startsWith(VALUE_PREFIX)

    fun encrypt(text: String): String {
        val cipher = Cipher.getInstance(GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        return VALUE_PREFIX + Base64.getEncoder().encodeToString(iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(value: String): String {
        val bytes = Base64.getDecoder().decode(value.removePrefix(VALUE_PREFIX))
        if (bytes.size <= GCM_IV_BYTES) throw IOException("Truncated encrypted value")
        val cipher = Cipher.getInstance(GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_BYTES))
        return String(cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES), Charsets.UTF_8)
    }

    /** Stream written as [STREAM_MAGIC], IV length, IV, then the encrypted bytes. */
    fun encryptingStream(output: OutputStream): OutputStream {
        val cipher = Cipher.getInstance(cbcTransformation)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        output.write(STREAM_MAGIC)
        output.write(iv.size)
        output.write(iv)
        return CipherOutputStream(output, cipher)
    }

    /** Reads a stream written by [encryptingStream], after its [STREAM_MAGIC] (already consumed). */
    fun decryptingStream(input: InputStream): InputStream {
        val ivSize = input.read()
        if (ivSize !in 1..32) throw IOException("Invalid encrypted stream")
        val iv = input.readNBytesCompat(ivSize)
        val cipher = Cipher.getInstance(cbcTransformation)
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        return CipherInputStream(input, cipher)
    }

    private fun InputStream.readNBytesCompat(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(bytes, read, count - read)
            if (n == -1) throw IOException("Truncated encrypted stream")
            read += n
        }
        return bytes
    }

    companion object {
        private const val GCM = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val VALUE_PREFIX = "enc1:"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "tele_settings"

        /** First bytes of an encrypted stream: a plain JSON cache starts with '{'. */
        val STREAM_MAGIC = "TELE1".toByteArray(Charsets.US_ASCII)

        /** Box whose AES key lives in the Android Keystore, created on first use. */
        fun androidKeystore(): SecretBox = SecretBox(keyProvider = {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM, KeyProperties.BLOCK_MODE_CBC)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_PKCS7)
                            .setKeySize(256)
                            .build()
                    )
                }
                .generateKey()
        })
    }
}

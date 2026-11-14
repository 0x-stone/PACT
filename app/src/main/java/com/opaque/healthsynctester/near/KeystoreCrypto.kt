package com.opaque.healthsynctester.near

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the app-generated Ed25519 seed at rest with an AndroidKeyStore
 * AES-256-GCM key.
 *
 * Why not an AndroidKeyStore Ed25519 key directly? Keystore Ed25519 support
 * is unreliable across devices (Google issue 39985623), so the pact uses
 * BouncyCastle software Ed25519 keys (see [NearIdentity]) and instead makes
 * the hardware back the *secrecy* of the seed: the AES-GCM key never leaves
 * the Keystore, and the seed is only ever plaintext in memory while a
 * challenge is being signed.
 *
 * The wrapped blob format is `base64(iv || ciphertext)` with a fresh
 * 12-byte IV per wrap. The IV is not secret (it is authenticated by the
 * GCM tag together with the ciphertext).
 */
object KeystoreCrypto {

    private const val KEY_ALIAS = "opaque-near-identity-seed"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /** The wrap key, generated on first use and never exportable. */
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }
        val generator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Wrap secret bytes: `base64(iv || ciphertext)` with a fresh IV. */
    fun wrap(secret: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(secret)
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    /** Unwrap a [wrap] blob; throws if the key or blob is unavailable/corrupt. */
    fun unwrap(blob: String): ByteArray {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        require(raw.size > GCM_IV_BYTES) { "corrupt wrapped-seed blob" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GCM_TAG_BITS, raw, 0, GCM_IV_BYTES)
        )
        return cipher.doFinal(raw, GCM_IV_BYTES, raw.size - GCM_IV_BYTES)
    }
}

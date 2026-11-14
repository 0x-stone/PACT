package com.opaque.healthsynctester.near

import java.security.MessageDigest

/**
 * NEP-413 message signing, mirroring `backend/near_auth.py` byte for byte
 * (that module is the verifying authority; this must produce the exact same
 * SHA-256 digest it verifies against).
 *
 * NEP-413 payload — exact spec (github.com/near/NEPs, nep-0413.md):
 * ```
 * struct Payload {
 *     message:     string;    // Borsh: u32 LE length + UTF-8 bytes
 *     nonce:       [u8; 32];  // raw 32 bytes
 *     recipient:   string;
 *     callbackUrl: string?;   // Borsh Option: 0x00 for None
 * }
 *
 * signed_hash = sha256( u32_le(2^31 + 413) || borsh(Payload) )
 * ```
 *
 * Note the Ed25519 signature is over the SHA-256 *hash* (NEAR's convention
 * for account-key signatures, see nearcore#2835), not over the raw payload
 * bytes.
 */
object Nep413 {

    /** 2^31 + 413: makes the payload an invalid Borsh transaction (NEP-413). */
    private const val NEP413_TAG = 2147484061L

    /** Borsh `String`: u32 little-endian length + UTF-8 bytes. */
    fun borshString(value: String): ByteArray {
        val encoded = value.toByteArray(Charsets.UTF_8)
        return u32Le(encoded.size.toLong()) + encoded
    }

    /** Borsh-serialized NEP-413 `Payload` struct, prefixed with the tag. */
    fun payload(
        message: String,
        nonce: ByteArray,
        recipient: String,
        callbackUrl: String? = null
    ): ByteArray {
        require(nonce.size == 32) { "NEP-413 nonce must be exactly 32 bytes" }
        val callback: ByteArray =
            if (callbackUrl == null) byteArrayOf(0)
            else byteArrayOf(1) + borshString(callbackUrl)
        return u32Le(NEP413_TAG) + borshString(message) + nonce +
            borshString(recipient) + callback
    }

    /** The 32-byte SHA-256 digest that gets Ed25519-signed (NEP-413). */
    fun hash(
        message: String,
        nonce: ByteArray,
        recipient: String,
        callbackUrl: String? = null
    ): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(payload(message, nonce, recipient, callbackUrl))

    /** Python `struct.pack("<I", value)`: 4 bytes little-endian, unsigned. */
    private fun u32Le(value: Long): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte(),
    )
}

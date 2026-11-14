package com.opaque.healthsynctester.near

import java.math.BigInteger

/**
 * Bitcoin-alphabet base58, mirroring `pact/encoding.py` (the pact's single
 * Python implementation) byte for byte.
 *
 * NEAR represents Ed25519 public keys as `"ed25519:<base58>"` (NEP-413
 * `SignedMessage.publicKey`), so the mobile client needs both directions:
 * encode when claiming the generated key, decode never (kept symmetric with
 * the Python side, and useful for auditing keys by hand).
 */
object Base58 {

    private const val ALPHABET =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private val INDEX: Map<Char, Int> =
        ALPHABET.mapIndexed { i, c -> c to i }.toMap()

    /** Encode bytes with the Bitcoin base58 alphabet (NEAR public keys). */
    fun encode(data: ByteArray): String {
        // Leading zero bytes become leading '1's, like in pact/encoding.py.
        var padding = 0
        for (byte in data) {
            if (byte.toInt() == 0) padding += 1 else break
        }
        var number = BigInteger(1, data)
        val digits = StringBuilder()
        while (number.signum() > 0) {
            val base = BigInteger.valueOf(58L)
            val remainder = number.mod(base)
            digits.append(ALPHABET[remainder.toInt()])
            number = number.subtract(remainder).divide(base)
        }
        return ALPHABET[0].toString().repeat(padding) + digits.reverse().toString()
    }

    /** Decode a base58 string; throws IllegalArgumentException on any
     * non-alphabet character (mirrors pact/encoding.py's ValueError). */
    fun decode(text: String): ByteArray {
        var padding = 0
        for (char in text) {
            if (char == ALPHABET[0]) padding += 1 else break
        }
        var number = BigInteger.ZERO
        for (char in text) {
            val digit = INDEX[char]
                ?: throw IllegalArgumentException("invalid base58 character '$char'")
            number = number
                .multiply(BigInteger.valueOf(58L))
                .add(BigInteger.valueOf(digit.toLong()))
        }
        // Minimal unsigned big-endian bytes of the number. BigInteger may
        // prepend a zero sign byte when the high bit is set; strip it.
        val body = if (number.signum() == 0) {
            ByteArray(0)
        } else {
            number.toByteArray().let {
                if (it.isNotEmpty() && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it
            }
        }
        return ByteArray(padding) + body
    }
}

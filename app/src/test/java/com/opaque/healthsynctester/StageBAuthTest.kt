package com.opaque.healthsynctester

import com.opaque.healthsynctester.near.Base58
import com.opaque.healthsynctester.near.NearIdentity
import org.junit.Assert.*
import org.junit.Test

class StageBAuthTest {

    @Test
    fun testUsernameNormalization() {
        assertEquals("alice.testnet", NearIdentity.normalizeAccountId("alice"))
        assertEquals("alice.testnet", NearIdentity.normalizeAccountId("ALICE"))
        assertEquals("alice.testnet", NearIdentity.normalizeAccountId(" alice "))
        assertEquals("alice.testnet", NearIdentity.normalizeAccountId("alice.testnet"))
        assertEquals("sub.alice.testnet", NearIdentity.normalizeAccountId("sub.alice.testnet"))
    }

    @Test
    fun testValidUsernameRules() {
        assertNull(NearIdentity.validateAccountId("alice.testnet"))
        assertNull(NearIdentity.validateAccountId("maxwell-123.testnet"))
        assertNull(NearIdentity.validateAccountId("strider_99.testnet"))
        assertNull(NearIdentity.validateAccountId("ab.testnet"))
    }

    @Test
    fun testInvalidUsernameRules() {
        // Too short (< 2 chars before suffix or full)
        assertNotNull(NearIdentity.validateAccountId("a"))

        // Consecutive separators
        assertNotNull(NearIdentity.validateAccountId("alice..testnet"))
        assertNotNull(NearIdentity.validateAccountId("alice--testnet"))
        assertNotNull(NearIdentity.validateAccountId("alice._testnet"))

        // Leading / Trailing separators
        assertNotNull(NearIdentity.validateAccountId(".alice.testnet"))
        assertNotNull(NearIdentity.validateAccountId("alice.testnet."))
        assertNotNull(NearIdentity.validateAccountId("-alice.testnet"))

        // Disallowed symbols
        assertNotNull(NearIdentity.validateAccountId("alice@testnet"))
        assertNotNull(NearIdentity.validateAccountId("alice$123.testnet"))
        assertNotNull(NearIdentity.validateAccountId("alice 123.testnet"))

        // Too long (> 64 chars)
        val veryLong = "a".repeat(65) + ".testnet"
        assertNotNull(NearIdentity.validateAccountId(veryLong))
    }

    @Test
    fun testBase58EncodingDecoding() {
        val originalBytes = byteArrayOf(1, 2, 3, 4, 5, 42, 100, 127)
        val encoded = Base58.encode(originalBytes)
        val decoded = Base58.decode(encoded)
        assertArrayEquals(originalBytes, decoded)
    }

    @Test
    fun testSuggestedAccountIdFormat() {
        val suggested = NearIdentity.suggestedAccountId()
        assertTrue("Suggested account must end in .testnet", suggested.endsWith(".testnet"))
        assertTrue("Suggested account must start with opaque-", suggested.startsWith("opaque-"))
        assertNull("Suggested account must pass format validation", NearIdentity.validateAccountId(suggested))
    }
}

package com.opaque.healthsynctester

import com.google.gson.JsonObject
import com.opaque.healthsynctester.near.Base58
import com.opaque.healthsynctester.near.NearContractClient
import com.opaque.healthsynctester.near.NearIdentity
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

class NearContractClientTest {

    // -------------------------------------------------------------------
    // Helper Hex conversion
    // -------------------------------------------------------------------
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray {
        val len = length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    // -------------------------------------------------------------------
    // 1. Borsh Primitive Serialization Known-Answer Tests
    // -------------------------------------------------------------------

    @Test
    fun testBorshPrimitives() {
        val writer = NearContractClient.BorshWriter()
        writer.writeU8(2)
        writer.writeU32(1L)
        writer.writeU64(30_000_000_000_000L) // 0x1B48EB57E000
        // u128: 1250000000000000000000 yocto = 0x43c33c193756480000
        writer.writeU128(BigInteger("1250000000000000000000"))
        writer.writeString("test")

        val bytes = writer.toByteArray()
        // u8: 02
        // u32: 01000000
        // u64: 00e057eb481b0000
        // u128: 0000485637193cc34300000000000000
        // string: 04000000 (length) + 74657374 ("test")
        val expectedHex = "02" +
                "01000000" +
                "00e057eb481b0000" +
                "0000485637193cc34300000000000000" +
                "0400000074657374"

        assertEquals("Borsh primitive encoding must match little-endian wire format", expectedHex, bytes.toHex())
    }

    // -------------------------------------------------------------------
    // 2. FunctionCall Action Serialization Known-Answer Test
    // -------------------------------------------------------------------

    @Test
    fun testFunctionCallActionSerialization() {
        val methodName = "storage_deposit"
        val args = "{}".toByteArray(Charsets.UTF_8)
        val gas = 30_000_000_000_000L
        val deposit = BigInteger("1250000000000000000000")

        val actionBytes = NearContractClient.serializeFunctionCallAction(
            methodName = methodName,
            argsBytes = args,
            gas = gas,
            depositYocto = deposit
        )

        // Action variant 2 = FunctionCall
        // Method name: 0f000000 (15) + 73746f726167655f6465706f736974 ("storage_deposit")
        // Args: 02000000 (2) + 7b7d ("{}")
        // Gas: 00e057eb481b0000
        // Deposit: 0000485637193cc34300000000000000
        val expectedActionHex = "02" +
                "0f00000073746f726167655f6465706f736974" +
                "020000007b7d" +
                "00e057eb481b0000" +
                "0000485637193cc34300000000000000"

        assertEquals(expectedActionHex, actionBytes.toHex())
    }

    // -------------------------------------------------------------------
    // 3. Transaction Serialization Known-Answer Test
    // -------------------------------------------------------------------

    @Test
    fun testTransactionSerialization() {
        val signerId = "alice.testnet"
        val publicKeyBytes = ByteArray(32) { 0x01.toByte() }
        val nonce = 101L
        val receiverId = "pactcontract.testnet"
        val blockHashBytes = ByteArray(32) { 0x02.toByte() }

        val actionBytes = NearContractClient.serializeFunctionCallAction(
            methodName = "storage_deposit",
            argsBytes = "{}".toByteArray(Charsets.UTF_8),
            gas = 30_000_000_000_000L,
            depositYocto = BigInteger("1250000000000000000000")
        )

        val txBytes = NearContractClient.serializeTransaction(
            signerId = signerId,
            publicKeyBytes = publicKeyBytes,
            nonce = nonce,
            receiverId = receiverId,
            blockHashBytes = blockHashBytes,
            actionsBorsh = listOf(actionBytes)
        )

        // Verify header
        // Signer ID: 0d000000 (13) + "alice.testnet" in hex
        val expectedSigner = "0d000000" + "alice.testnet".toByteArray(Charsets.UTF_8).toHex()
        assertTrue(txBytes.toHex().startsWith(expectedSigner))

        // Public key: variant 0 (ED25519) + 32 bytes of 0x01
        val expectedPk = "00" + "01".repeat(32)
        assertTrue(txBytes.toHex().substring(expectedSigner.length).startsWith(expectedPk))

        // Actions count: 1 action (01000000)
        assertTrue(txBytes.toHex().endsWith(actionBytes.toHex()))
    }

    // -------------------------------------------------------------------
    // 4. Ed25519 RFC 8032 Known-Answer Verification
    // -------------------------------------------------------------------

    @Test
    fun testEd25519Rfc8032Vector() {
        // RFC 8032 Section 7.1 Test 1 (Empty message)
        val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToBytes()
        val expectedPkHex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        val expectedSigHex = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"

        val privateKeyParams = Ed25519PrivateKeyParameters(seed, 0)
        val publicKeyParams = privateKeyParams.generatePublicKey()
        val actualPkHex = publicKeyParams.encoded.toHex()

        assertEquals("Public key derived from seed must match RFC 8032", expectedPkHex, actualPkHex)

        // Sign empty message
        val signer = Ed25519Signer()
        signer.init(true, privateKeyParams)
        val signature = signer.generateSignature()

        assertEquals("Ed25519 signature must match RFC 8032 test vector", expectedSigHex, signature.toHex())

        // Verify with public key
        val verifier = Ed25519Signer()
        verifier.init(false, publicKeyParams)
        assertTrue("Signature verification must succeed", verifier.verifySignature(signature))
    }

    // -------------------------------------------------------------------
    // 5. SignedTransaction Borsh Structure Test
    // -------------------------------------------------------------------

    @Test
    fun testSignedTransactionStructure() {
        val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToBytes()
        val dummyTxBytes = "test_transaction_payload".toByteArray(Charsets.UTF_8)

        val signedTxBorsh = NearContractClient.signTransaction(dummyTxBytes, seed)

        // Structure must be: raw tx bytes + 0x00 (ED25519 signature enum tag) + 64 bytes signature
        assertEquals(dummyTxBytes.size + 1 + 64, signedTxBorsh.size)
        assertEquals(0.toByte(), signedTxBorsh[dummyTxBytes.size])

        // Verify signature over SHA-256 of dummyTxBytes
        val txHash = MessageDigest.getInstance("SHA-256").digest(dummyTxBytes)
        val sigBytes = signedTxBorsh.copyOfRange(dummyTxBytes.size + 1, signedTxBorsh.size)

        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PrivateKeyParameters(seed, 0).generatePublicKey())
        verifier.update(txHash, 0, txHash.size)
        assertTrue("Signed transaction signature must verify over SHA-256(txBytes)", verifier.verifySignature(sigBytes))
    }

    // -------------------------------------------------------------------
    // 6. Live Testnet Transaction: Storage Deposit via App Signing Path
    // -------------------------------------------------------------------

    @Test
    fun testLiveTestnetStorageDepositExecution() = runBlocking {
        // Account registration via helper.testnet.near.org with fresh Ed25519 key
        val randomSuffix = (100000..999999).random()
        val accountId = "opaque-test-$randomSuffix.testnet"
        val seedBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val privParams = Ed25519PrivateKeyParameters(seedBytes, 0)
        val pubKeyBytes = privParams.generatePublicKey().encoded
        val pubKeyWire = "ed25519:" + Base58.encode(pubKeyBytes)

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val createBody = JsonObject().apply {
            addProperty("newAccountId", accountId)
            addProperty("newAccountPublicKey", pubKeyWire)
        }

        val helperReq = Request.Builder()
            .url("https://helper.testnet.near.org/account")
            .post(createBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        var accountCreated = false
        try {
            httpClient.newCall(helperReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    accountCreated = true
                }
            }
        } catch (e: Exception) {
            println("Helper offline or network unreachable, skipping live tx test: ${e.message}")
            return@runBlocking
        }

        if (!accountCreated) {
            println("Testnet helper could not create account $accountId, skipping live tx test")
            return@runBlocking
        }

        // Wait 3 seconds for state to settle on testnet
        kotlinx.coroutines.delay(3000)

        // Execute storage_deposit on pactcontract.testnet using NearContractClient app signing path
        val client = NearContractClient(
            contractId = "pactcontract.testnet"
        )

        val args = JsonObject().apply {
            addProperty("account_id", accountId)
            addProperty("registration_only", true)
        }

        val result = client.executeRawFunctionCall(
            accountId = accountId,
            publicKeyWire = pubKeyWire,
            seedBytes = seedBytes,
            methodName = "storage_deposit",
            argsJson = args.toString(),
            gas = NearContractClient.GAS_STORAGE_DEPOSIT,
            depositYocto = NearContractClient.STORAGE_DEPOSIT_YOCTO
        )
        println("LIVE TESTNET TRANSACTION RESULT:")
        println("Account: $accountId")
        println("Success: ${result.isSuccess}")
        println("TxHash: ${result.txHash}")
        println("Message: ${result.message}")

        // Write tx record to project root for inspection
        val jsonOutput = JsonObject().apply {
            addProperty("accountId", accountId)
            addProperty("publicKey", pubKeyWire)
            addProperty("isSuccess", result.isSuccess)
            addProperty("txHash", result.txHash)
            addProperty("message", result.message)
            addProperty("explorerUrl", "https://testnet.nearblocks.io/txns/${result.txHash}")
        }
        java.io.File("testnet_tx.json").writeText(jsonOutput.toString())

        assertTrue("Storage deposit must succeed on testnet: ${result.message}", result.isSuccess)
        assertNotNull("Must return transaction hash", result.txHash)
        assertTrue("Transaction hash must be non-empty", result.txHash!!.isNotBlank())
    }

    @Test
    fun testProfileContractIdsAndConfiguration() {
        assertEquals("pactcontract.testnet", NearContractClient.REAL_CONTRACT_ID)
        assertEquals("pact-demo.testnet", NearContractClient.DEMO_CONTRACT_ID)
        assertEquals(NearContractClient.REAL_CONTRACT_ID, NearContractClient.DEFAULT_CONTRACT_ID)
    }

    @Test
    fun testContractClientDynamicRoutingAndDomainSeparation() {
        val clientReal = NearContractClient(contractId = NearContractClient.REAL_CONTRACT_ID)
        val clientDemo = NearContractClient(contractId = NearContractClient.DEMO_CONTRACT_ID)

        assertEquals("pactcontract.testnet", clientReal.contractId)
        assertEquals("pact-demo.testnet", clientDemo.contractId)

        // Serialize storage deposit action for both and verify receiverId separation in transaction
        val action = NearContractClient.serializeFunctionCallAction(
            methodName = "storage_deposit",
            argsBytes = "{}".toByteArray(),
            gas = NearContractClient.GAS_STORAGE_DEPOSIT,
            depositYocto = NearContractClient.STORAGE_DEPOSIT_YOCTO
        )
        val dummyBlock = ByteArray(32) { 0x01 }
        val dummyPubBytes = ByteArray(32) { 0x42 }

        val txReal = NearContractClient.serializeTransaction(
            signerId = "alice.testnet",
            publicKeyBytes = dummyPubBytes,
            nonce = 1L,
            receiverId = clientReal.contractId,
            blockHashBytes = dummyBlock,
            actionsBorsh = listOf(action)
        )

        val txDemo = NearContractClient.serializeTransaction(
            signerId = "alice.testnet",
            publicKeyBytes = dummyPubBytes,
            nonce = 1L,
            receiverId = clientDemo.contractId,
            blockHashBytes = dummyBlock,
            actionsBorsh = listOf(action)
        )

        // The serialized bytes MUST differ because receiverId is part of the signed transaction payload
        org.junit.Assert.assertFalse(txReal.contentEquals(txDemo))
        assertTrue(txReal.isNotEmpty())
        assertTrue(txDemo.isNotEmpty())
    }
}

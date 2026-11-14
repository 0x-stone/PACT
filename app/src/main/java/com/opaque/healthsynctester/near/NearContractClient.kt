package com.opaque.healthsynctester.near

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import com.opaque.healthsynctester.pact.WalletBalances
import com.opaque.healthsynctester.profiles.PactProfiles

/**
 * PACT NEAR Contract Client (Stages D & E).
 *
 * Implements:
 * - On-chain views: get_challenge, get_challenges, is_enrolled, has_submitted,
 *   is_qualifier, has_withdrawn_stake, has_claimed_reward, ft_balance_of,
 *   storage_balance_of, get_stake_yocto.
 * - On-chain transactions (pure Kotlin Borsh serialization + Ed25519 signing):
 *   storage_deposit, enroll, submit_completion, settle_week, claim_reward, withdraw_stake.
 * - Nonce management and verification of final on-chain state before reporting success.
 */
class NearContractClient(
    private val nearIdentity: NearIdentity? = null,
    var contractId: String = DEFAULT_CONTRACT_ID,
    val rpcUrl: String = NearIdentity.DEFAULT_RPC_URL
) {
    val activeContractId: String
        get() = nearIdentity?.activeContractId() ?: contractId
    companion object {
        val REAL_CONTRACT_ID = PactProfiles.REAL.contractId
        val DEMO_CONTRACT_ID = PactProfiles.DEMO.contractId
        val SANDBOX_CONTRACT_ID = PactProfiles.SANDBOX.contractId
        val DEFAULT_CONTRACT_ID = REAL_CONTRACT_ID
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // Standard gas allocations (1 Tgas = 1e12 gas)
        const val GAS_STORAGE_DEPOSIT = 30_000_000_000_000L
        const val GAS_ENROLL = 50_000_000_000_000L
        const val GAS_SUBMIT_COMPLETION = 50_000_000_000_000L
        const val GAS_SETTLE_WEEK = 100_000_000_000_000L
        const val GAS_CLAIM_REWARD = 50_000_000_000_000L
        const val GAS_WITHDRAW_STAKE = 50_000_000_000_000L

        // Exact deposits
        val STORAGE_DEPOSIT_YOCTO = BigInteger("1250000000000000000000") // 0.00125 NEAR
        val STAKE_DEPOSIT_YOCTO = BigInteger("500000000000000000000000")  // 0.5 NEAR
        val SANDBOX_STAKE_DEPOSIT_YOCTO = BigInteger("10000000000000000000000") // 0.01 NEAR

        fun serializeFunctionCallAction(
            methodName: String,
            argsBytes: ByteArray,
            gas: Long,
            depositYocto: BigInteger
        ): ByteArray {
            return BorshWriter().apply {
                writeU8(2) // 2 = FunctionCall action
                writeString(methodName)
                writeBytes(argsBytes)
                writeU64(gas)
                writeU128(depositYocto)
            }.toByteArray()
        }

        fun serializeTransaction(
            signerId: String,
            publicKeyBytes: ByteArray,
            nonce: Long,
            receiverId: String,
            blockHashBytes: ByteArray,
            actionsBorsh: List<ByteArray>
        ): ByteArray {
            return BorshWriter().apply {
                writeString(signerId)
                writeU8(0) // 0 = ED25519 PublicKey
                writeRaw(publicKeyBytes)
                writeU64(nonce)
                writeString(receiverId)
                writeRaw(blockHashBytes)
                writeU32(actionsBorsh.size.toLong())
                for (action in actionsBorsh) {
                    writeRaw(action)
                }
            }.toByteArray()
        }

        fun signTransaction(
            txBytes: ByteArray,
            seedBytes: ByteArray
        ): ByteArray {
            val txHash = MessageDigest.getInstance("SHA-256").digest(txBytes)
            val signer = Ed25519Signer()
            signer.init(true, Ed25519PrivateKeyParameters(seedBytes, 0))
            signer.update(txHash, 0, txHash.size)
            val signature = signer.generateSignature()

            return BorshWriter().apply {
                writeRaw(txBytes)
                writeU8(0) // 0 = ED25519 Signature
                writeRaw(signature)
            }.toByteArray()
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val rpcGateway = com.opaque.healthsynctester.gateway.RpcGateway.getInstance()

    // In-memory caches strictly keyed by (activeContractId, ...)
    data class CachedViewEntry(val timestampMs: Long, val element: com.google.gson.JsonElement)
    private val constantViewCache = java.util.concurrent.ConcurrentHashMap<String, com.google.gson.JsonElement>()
    private val ttlViewCache = java.util.concurrent.ConcurrentHashMap<String, CachedViewEntry>()
    val settledChallengesCache = java.util.concurrent.ConcurrentHashMap<String, ContractChallenge>()
    val enrolledCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    val terminalFlagsCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    val lastKnownAccountBalances = java.util.concurrent.ConcurrentHashMap<String, AccountBalanceDetails>()
    val lastKnownPactBalances = java.util.concurrent.ConcurrentHashMap<String, BigInteger>()

    fun clearCaches() {
        constantViewCache.clear()
        ttlViewCache.clear()
        settledChallengesCache.clear()
        enrolledCache.clear()
        terminalFlagsCache.clear()
    }

    fun getSettledForActiveContract(): List<ContractChallenge> {
        val prefix = "$activeContractId::"
        return settledChallengesCache.entries.filter { it.key.startsWith(prefix) }.map { it.value }
    }

    // -----------------------------------------------------------------------
    // Data Models
    // -----------------------------------------------------------------------

    data class ContractChallenge(
        val challengeId: String,
        val stepsPerDay: Long,
        val daysRequired: Int,
        val startNs: Long,
        val endNs: Long,
        val enrolledCount: Long,
        val submittedCount: Long,
        val qualifierCount: Long,
        val outcome: ChallengeOutcome
    )

    sealed class ChallengeOutcome {
        object Pending : ChallengeOutcome()
        object Void : ChallengeOutcome()
        data class Success(val rewardPerQualifierYocto: BigInteger) : ChallengeOutcome()
        data class Failure(val burnActualYocto: BigInteger) : ChallengeOutcome()
    }

    data class MemberContractStatus(
        val accountId: String,
        val isEnrolled: Boolean,
        val hasSubmitted: Boolean,
        val isQualifier: Boolean,
        val hasWithdrawnStake: Boolean,
        val hasClaimedReward: Boolean,
        val isStorageRegistered: Boolean,
        val nearBalanceYocto: BigInteger,
        val pactBalanceYocto: BigInteger
    )

    data class TxResult(
        val isSuccess: Boolean,
        val txHash: String?,
        val message: String,
        val errorMessage: String? = null
    )

    // -----------------------------------------------------------------------
    // On-Chain View Methods
    // -----------------------------------------------------------------------

    suspend fun getChallenge(challengeId: String): ContractChallenge? = withContext(Dispatchers.IO) {
        val cacheKey = "$activeContractId::$challengeId"
        val settled = settledChallengesCache[cacheKey]
        if (settled != null) return@withContext settled

        val args = JsonObject().apply { addProperty("challenge_id", challengeId) }
        val raw = callView("get_challenge", args) ?: return@withContext null
        val parsed = parseChallenge(raw.asJsonObject)
        if (parsed != null && parsed.outcome !is ChallengeOutcome.Pending) {
            settledChallengesCache[cacheKey] = parsed
        }
        parsed
    }

    suspend fun getChallenges(fromIndex: Long = 0, limit: Long = 20): List<ContractChallenge> = withContext(Dispatchers.IO) {
        val args = JsonObject().apply {
            addProperty("from_index", fromIndex)
            addProperty("limit", limit)
        }
        val raw = callView("get_challenges", args) ?: return@withContext getSettledForActiveContract()
        val list = mutableListOf<ContractChallenge>()
        if (raw.isJsonArray) {
            for (elem in raw.asJsonArray) {
                if (elem.isJsonObject) {
                    parseChallenge(elem.asJsonObject)?.let { ch ->
                        if (ch.outcome !is ChallengeOutcome.Pending) {
                            settledChallengesCache["$activeContractId::${ch.challengeId}"] = ch
                        }
                        list.add(ch)
                    }
                }
            }
        }
        list
    }

    suspend fun getMemberStatus(challengeId: String, accountId: String): MemberContractStatus = withContext(Dispatchers.IO) {
        val enrolledKey = "$activeContractId::$challengeId::$accountId"
        val isEnrolled = enrolledCache[enrolledKey] ?: run {
            val enrolled = callViewBool("is_enrolled", JsonObject().apply {
                addProperty("challenge_id", challengeId)
                addProperty("account_id", accountId)
            })
            if (enrolled) enrolledCache[enrolledKey] = true
            enrolled
        }

        // Avoid querying submission, qualification, withdrawal, and claim if not enrolled!
        val hasSubmitted = if (isEnrolled) {
            terminalFlagsCache["$enrolledKey::submitted"] ?: run {
                val s = callViewBool("has_submitted", JsonObject().apply {
                    addProperty("challenge_id", challengeId)
                    addProperty("account_id", accountId)
                })
                if (s) terminalFlagsCache["$enrolledKey::submitted"] = true
                s
            }
        } else false

        val isQualifier = if (isEnrolled) {
            terminalFlagsCache["$enrolledKey::qualifier"] ?: run {
                val q = callViewBool("is_qualifier", JsonObject().apply {
                    addProperty("challenge_id", challengeId)
                    addProperty("account_id", accountId)
                })
                if (q) terminalFlagsCache["$enrolledKey::qualifier"] = true
                q
            }
        } else false

        val hasWithdrawn = if (isEnrolled) {
            terminalFlagsCache["$enrolledKey::withdrawn"] ?: run {
                val w = callViewBool("has_withdrawn_stake", JsonObject().apply {
                    addProperty("challenge_id", challengeId)
                    addProperty("account_id", accountId)
                })
                if (w) terminalFlagsCache["$enrolledKey::withdrawn"] = true
                w
            }
        } else false

        val hasClaimed = if (isEnrolled) {
            terminalFlagsCache["$enrolledKey::claimed"] ?: run {
                val c = callViewBool("has_claimed_reward", JsonObject().apply {
                    addProperty("challenge_id", challengeId)
                    addProperty("account_id", accountId)
                })
                if (c) terminalFlagsCache["$enrolledKey::claimed"] = true
                c
            }
        } else false

        val storageKey = "$activeContractId::$accountId::storage"
        val isStorageRegistered = terminalFlagsCache[storageKey] ?: run {
            val storageBalance = callView("storage_balance_of", JsonObject().apply {
                addProperty("account_id", accountId)
            })
            val reg = storageBalance != null && !storageBalance.isJsonNull
            if (reg) terminalFlagsCache[storageKey] = true
            reg
        }

        val pactBalance = getPactBalance(accountId)
        val nearBalance = getNearBalance(accountId)

        MemberContractStatus(
            accountId = accountId,
            isEnrolled = isEnrolled,
            hasSubmitted = hasSubmitted,
            isQualifier = isQualifier,
            hasWithdrawnStake = hasWithdrawn,
            hasClaimedReward = hasClaimed,
            isStorageRegistered = isStorageRegistered,
            nearBalanceYocto = nearBalance,
            pactBalanceYocto = pactBalance
        )
    }

    suspend fun isEnrolled(challengeId: String, accountId: String): Boolean {
        val enrolledKey = "$challengeId::$accountId"
        val cached = enrolledCache[enrolledKey]
        if (cached == true) return true
        val enrolled = callViewBool("is_enrolled", JsonObject().apply {
            addProperty("challenge_id", challengeId)
            addProperty("account_id", accountId)
        })
        if (enrolled) enrolledCache[enrolledKey] = true
        return enrolled
    }

    suspend fun hasSubmitted(challengeId: String, accountId: String): Boolean {
        val key = "$challengeId::$accountId::submitted"
        if (terminalFlagsCache[key] == true) return true
        val submitted = callViewBool("has_submitted", JsonObject().apply {
            addProperty("challenge_id", challengeId)
            addProperty("account_id", accountId)
        })
        if (submitted) terminalFlagsCache[key] = true
        return submitted
    }

    suspend fun isQualifier(challengeId: String, accountId: String): Boolean {
        val key = "$challengeId::$accountId::qualifier"
        if (terminalFlagsCache[key] == true) return true
        val qual = callViewBool("is_qualifier", JsonObject().apply {
            addProperty("challenge_id", challengeId)
            addProperty("account_id", accountId)
        })
        if (qual) terminalFlagsCache[key] = true
        return qual
    }

    suspend fun hasWithdrawnStake(challengeId: String, accountId: String): Boolean {
        val key = "$challengeId::$accountId::withdrawn"
        if (terminalFlagsCache[key] == true) return true
        val wd = callViewBool("has_withdrawn_stake", JsonObject().apply {
            addProperty("challenge_id", challengeId)
            addProperty("account_id", accountId)
        })
        if (wd) terminalFlagsCache[key] = true
        return wd
    }

    suspend fun hasClaimedReward(challengeId: String, accountId: String): Boolean {
        val key = "$challengeId::$accountId::claimed"
        if (terminalFlagsCache[key] == true) return true
        val cl = callViewBool("has_claimed_reward", JsonObject().apply {
            addProperty("challenge_id", challengeId)
            addProperty("account_id", accountId)
        })
        if (cl) terminalFlagsCache[key] = true
        return cl
    }

    suspend fun getPactBalance(accountId: String): BigInteger = withContext(Dispatchers.IO) {
        val raw = callView("ft_balance_of", JsonObject().apply {
            addProperty("account_id", accountId)
        })
        if (raw == null) {
            return@withContext lastKnownPactBalances[accountId] ?: BigInteger.ZERO
        }
        val bal = try {
            val str = raw.asString
            if (str.isNullOrBlank()) BigInteger.ZERO else BigInteger(str)
        } catch (_: Exception) {
            lastKnownPactBalances[accountId] ?: BigInteger.ZERO
        }
        lastKnownPactBalances[accountId] = bal
        bal
    }

    suspend fun getNearBalance(accountId: String): BigInteger = withContext(Dispatchers.IO) {
        val details = getAccountBalanceDetails(accountId)
        details.amountYocto
    }

    data class AccountBalanceDetails(
        val amountYocto: BigInteger,
        val lockedStorageYocto: BigInteger,
        val availableNearYocto: BigInteger
    )

    suspend fun getAccountBalanceDetails(accountId: String): AccountBalanceDetails = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", "balance-query")
            addProperty("method", "query")
            add("params", JsonObject().apply {
                addProperty("request_type", "view_account")
                addProperty("finality", "final")
                addProperty("account_id", accountId)
            })
        }
        val resp = rpcPost(body)
        if (resp == null || resp.has("error")) {
            return@withContext lastKnownAccountBalances[accountId]
                ?: AccountBalanceDetails(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO)
        }
        val resObj = resp.getAsJsonObject("result")
        val amountStr = resObj?.get("amount")?.asString ?: "0"
        val storageBytes = resObj?.get("storage_usage")?.asLong ?: 0L
        val amountYocto = try { BigInteger(amountStr) } catch (_: Exception) { BigInteger.ZERO }
        val lockedStorageYocto = BigInteger.valueOf(storageBytes).multiply(WalletBalances.STORAGE_COST_PER_BYTE)
        val availableNearYocto = (amountYocto - lockedStorageYocto).max(BigInteger.ZERO)
        val details = AccountBalanceDetails(amountYocto, lockedStorageYocto, availableNearYocto)
        if (amountYocto > BigInteger.ZERO) {
            lastKnownAccountBalances[accountId] = details
        }
        details
    }

    var chainTimeOffsetNs: Long = 0L

    suspend fun fetchChainTimeOffsetNs(): Long = withContext(Dispatchers.IO) {
        try {
            val body = JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", "block-query")
                addProperty("method", "block")
                add("params", JsonObject().apply {
                    addProperty("finality", "final")
                })
            }
            val resp = rpcPost(body) ?: return@withContext 0L
            val header = resp.getAsJsonObject("result")?.getAsJsonObject("header")
            val timestampStr = header?.get("timestamp")?.asString ?: return@withContext 0L
            val chainNs = timestampStr.toLong()
            val localNs = System.currentTimeMillis() * 1_000_000L
            chainTimeOffsetNs = chainNs - localNs
            chainTimeOffsetNs
        } catch (_: Exception) {
            0L
        }
    }

    fun currentChainTimeNs(): Long = System.currentTimeMillis() * 1_000_000L + chainTimeOffsetNs

    suspend fun getSubmissionGraceSecs(): Long = withContext(Dispatchers.IO) {
        try {
            val raw = callView("get_submission_grace_secs", JsonObject())
            raw?.asLong ?: when (activeContractId) {
                SANDBOX_CONTRACT_ID -> 180L
                DEMO_CONTRACT_ID -> 420L
                else -> 3600L
            }
        } catch (_: Exception) {
            when (activeContractId) {
                SANDBOX_CONTRACT_ID -> 180L
                DEMO_CONTRACT_ID -> 420L
                else -> 3600L
            }
        }
    }

    suspend fun getQuorumPct(): Int = withContext(Dispatchers.IO) {
        try {
            val raw = callView("get_quorum_pct", JsonObject())
            raw?.asInt ?: when (activeContractId) {
                SANDBOX_CONTRACT_ID -> 100
                else -> 70
            }
        } catch (_: Exception) {
            when (activeContractId) {
                SANDBOX_CONTRACT_ID -> 100
                else -> 70
            }
        }
    }

    suspend fun getStakeYocto(): BigInteger = withContext(Dispatchers.IO) {
        try {
            val raw = callView("get_stake_yocto", JsonObject())
            if (raw != null) BigInteger(raw.asString) else when (activeContractId) {
                SANDBOX_CONTRACT_ID -> SANDBOX_STAKE_DEPOSIT_YOCTO
                else -> STAKE_DEPOSIT_YOCTO
            }
        } catch (_: Exception) {
            when (activeContractId) {
                SANDBOX_CONTRACT_ID -> SANDBOX_STAKE_DEPOSIT_YOCTO
                else -> STAKE_DEPOSIT_YOCTO
            }
        }
    }

    // -----------------------------------------------------------------------
    // On-Chain Transaction Execution
    // -----------------------------------------------------------------------

    /**
     * Registers FT storage on pactcontract.testnet (NEP-145).
     * Attached deposit: 0.00125 NEAR.
     */
    suspend fun storageDeposit(accountId: String): TxResult = withContext(Dispatchers.IO) {
        val args = JsonObject().apply {
            addProperty("account_id", accountId)
            addProperty("registration_only", true)
        }
        signAndSendFunctionCall(
            methodName = "storage_deposit",
            argsJson = args.toString(),
            gas = GAS_STORAGE_DEPOSIT,
            depositYocto = STORAGE_DEPOSIT_YOCTO
        )
    }

    /**
     * Enrolls the caller in a challenge.
     * Attached deposit: MUST be exactly 0.5 NEAR.
     * Verifies on-chain enrollment state after transaction completes.
     */
    suspend fun enroll(challengeId: String): TxResult = withContext(Dispatchers.IO) {
        val identity = nearIdentity?.identity()
            ?: return@withContext TxResult(false, null, "No local NEAR account on device")

        // 1. Verify storage deposit first
        val storageBal = callView("storage_balance_of", JsonObject().apply {
            addProperty("account_id", identity.accountId)
        })
        if (storageBal == null || storageBal.isJsonNull) {
            val storageRes = storageDeposit(identity.accountId)
            if (!storageRes.isSuccess) {
                return@withContext TxResult(
                    false,
                    storageRes.txHash,
                    "Failed to register storage deposit: ${storageRes.message}",
                    storageRes.errorMessage
                )
            }
        }

        // 2. Execute enroll transaction
        val args = JsonObject().apply {
            addProperty("challenge_id", challengeId)
        }
        val requiredStake = if (activeContractId == SANDBOX_CONTRACT_ID) {
            SANDBOX_STAKE_DEPOSIT_YOCTO
        } else {
            STAKE_DEPOSIT_YOCTO
        }
        val txRes = signAndSendFunctionCall(
            methodName = "enroll",
            argsJson = args.toString(),
            gas = GAS_ENROLL,
            depositYocto = requiredStake
        )
        val isAlreadyEnrolled = txRes.errorMessage?.contains("already enrolled", ignoreCase = true) == true
        if (!txRes.isSuccess && !isAlreadyEnrolled) {
            return@withContext txRes
        }

        // 3. Confirm on-chain enrollment with bounded polling
        val viewArgs = JsonObject().apply {
            addProperty("challenge_id", challengeId)
            addProperty("account_id", identity.accountId)
        }
        val enrolled = pollViewBool("is_enrolled", viewArgs, expected = true, maxWaitMs = 35000L)
        if (!enrolled) {
            return@withContext TxResult(
                true,
                txRes.txHash,
                "Enrollment pending confirmation...",
                "Still confirming on-chain"
            )
        }

        TxResult(true, txRes.txHash, "Successfully joined challenge $challengeId")
    }

    /**
     * Submits completion attestation on-chain.
     */
    suspend fun submitCompletion(
        challengeId: String,
        accountId: String,
        passed: Boolean,
        expirySecs: Long,
        signatureBase64: String
    ): TxResult = withContext(Dispatchers.IO) {
        val args = JsonObject().apply {
            addProperty("challenge_id", challengeId)
            addProperty("account_id", accountId)
            addProperty("passed", passed)
            addProperty("expiry", expirySecs.toString())
            addProperty("signature", signatureBase64)
        }
        val txRes = signAndSendFunctionCall(
            methodName = "submit_completion",
            argsJson = args.toString(),
            gas = GAS_SUBMIT_COMPLETION,
            depositYocto = BigInteger.ZERO
        )
        val isAlreadySubmitted = txRes.errorMessage?.contains("already submitted", ignoreCase = true) == true
        if (!txRes.isSuccess && !isAlreadySubmitted) return@withContext txRes

        val viewArgs = JsonObject().apply {
            addProperty("challenge_id", challengeId)
            addProperty("account_id", accountId)
        }
        val submitted = pollViewBool("has_submitted", viewArgs, expected = true, maxWaitMs = 35000L)
        if (!submitted) {
            return@withContext TxResult(
                true,
                txRes.txHash,
                "Attestation pending confirmation...",
                "Still confirming on-chain"
            )
        }

        TxResult(true, txRes.txHash, "Attestation confirmed on-chain")
    }

    /**
     * Settles the challenge on-chain (callable by anyone after end + grace).
     */
    suspend fun settleWeek(challengeId: String): TxResult = withContext(Dispatchers.IO) {
        val args = JsonObject().apply {
            addProperty("challenge_id", challengeId)
        }
        val txRes = signAndSendFunctionCall(
            methodName = "settle_week",
            argsJson = args.toString(),
            gas = GAS_SETTLE_WEEK,
            depositYocto = BigInteger.ZERO
        )
        val isAlreadySettled = txRes.errorMessage?.contains("already settled", ignoreCase = true) == true
        if (!txRes.isSuccess && !isAlreadySettled) return@withContext txRes

        val settled = pollChallengeSettled(challengeId, maxWaitMs = 35000L)
        if (!settled) {
            return@withContext TxResult(
                true,
                txRes.txHash,
                "Settlement pending confirmation...",
                "Still confirming on-chain"
            )
        }

        TxResult(true, txRes.txHash, "Challenge settled successfully")
    }

    /**
     * Claims PACT token reward (qualifiers only).
     */
    suspend fun claimReward(challengeId: String): TxResult = withContext(Dispatchers.IO) {
        val args = JsonObject().apply {
            addProperty("challenge_id", challengeId)
        }
        val txRes = signAndSendFunctionCall(
            methodName = "claim_reward",
            argsJson = args.toString(),
            gas = GAS_CLAIM_REWARD,
            depositYocto = BigInteger.ZERO
        )
        val isAlreadyClaimed = txRes.errorMessage?.contains("reward already claimed", ignoreCase = true) == true
        if (!txRes.isSuccess && !isAlreadyClaimed) return@withContext txRes

        val identity = nearIdentity?.identity()
        if (identity != null) {
            val viewArgs = JsonObject().apply {
                addProperty("challenge_id", challengeId)
                addProperty("account_id", identity.accountId)
            }
            val claimed = pollViewBool("has_claimed_reward", viewArgs, expected = true, maxWaitMs = 35000L)
            if (!claimed) {
                return@withContext TxResult(
                    true,
                    txRes.txHash,
                    "Reward claim pending confirmation...",
                    "Still confirming on-chain"
                )
            }
        }

        TxResult(true, txRes.txHash, "Reward claimed successfully")
    }

    /**
     * Withdraws the 0.5 NEAR stake (available after settlement in every outcome).
     */
    suspend fun withdrawStake(challengeId: String): TxResult = withContext(Dispatchers.IO) {
        val args = JsonObject().apply {
            addProperty("challenge_id", challengeId)
        }
        val txRes = signAndSendFunctionCall(
            methodName = "withdraw_stake",
            argsJson = args.toString(),
            gas = GAS_WITHDRAW_STAKE,
            depositYocto = BigInteger.ZERO
        )
        val isAlreadyWithdrawn = txRes.errorMessage?.contains("stake already withdrawn", ignoreCase = true) == true
        if (!txRes.isSuccess && !isAlreadyWithdrawn) return@withContext txRes

        val identity = nearIdentity?.identity()
        if (identity != null) {
            val viewArgs = JsonObject().apply {
                addProperty("challenge_id", challengeId)
                addProperty("account_id", identity.accountId)
            }
            val withdrawn = pollViewBool("has_withdrawn_stake", viewArgs, expected = true, maxWaitMs = 35000L)
            if (!withdrawn) {
                return@withContext TxResult(
                    true,
                    txRes.txHash,
                    "Stake withdrawal pending confirmation...",
                    "Still confirming on-chain"
                )
            }
        }

        TxResult(true, txRes.txHash, "Stake withdrawn successfully")
    }

    suspend fun pollViewBool(
        method: String,
        args: JsonObject,
        expected: Boolean = true,
        maxWaitMs: Long = 35000L,
        pollIntervalMs: Long = 1500L
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxWaitMs) {
            val res = callViewBool(method, args)
            if (res == expected) return true
            delay(pollIntervalMs)
        }
        return callViewBool(method, args) == expected
    }

    suspend fun pollChallengeSettled(
        challengeId: String,
        maxWaitMs: Long = 35000L,
        pollIntervalMs: Long = 1500L
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxWaitMs) {
            val challenge = getChallenge(challengeId)
            if (challenge != null && challenge.outcome !is ChallengeOutcome.Pending) return true
            delay(pollIntervalMs)
        }
        val finalCh = getChallenge(challengeId)
        return finalCh != null && finalCh.outcome !is ChallengeOutcome.Pending
    }

    fun getMinMembers(): Long {
        val elem = callView("get_min_members") ?: return when (activeContractId) {
            SANDBOX_CONTRACT_ID -> 1L
            DEMO_CONTRACT_ID -> 3L
            else -> 5L
        }
        return try {
            elem.asLong
        } catch (_: Exception) {
            when (activeContractId) {
                SANDBOX_CONTRACT_ID -> 1L
                DEMO_CONTRACT_ID -> 3L
                else -> 5L
            }
        }
    }

    // -----------------------------------------------------------------------
    // Core NEAR Transaction Construction, Borsh Serialization & Signing
    // -----------------------------------------------------------------------

    private suspend fun signAndSendFunctionCall(
        methodName: String,
        argsJson: String,
        gas: Long,
        depositYocto: BigInteger
    ): TxResult = withContext(Dispatchers.IO) {
        val identity = nearIdentity?.identity()
            ?: return@withContext TxResult(false, null, "No local NEAR account found on this device")
        val seedBytes = nearIdentity.seed()
            ?: return@withContext TxResult(false, null, "Could not access secure key from keystore")

        executeRawFunctionCall(
            accountId = identity.accountId,
            publicKeyWire = identity.publicKey,
            seedBytes = seedBytes,
            methodName = methodName,
            argsJson = argsJson,
            gas = gas,
            depositYocto = depositYocto
        )
    }

    suspend fun executeRawFunctionCall(
        accountId: String,
        publicKeyWire: String,
        seedBytes: ByteArray,
        methodName: String,
        argsJson: String,
        gas: Long,
        depositYocto: BigInteger
    ): TxResult = withContext(Dispatchers.IO) {
        val pubKeyB58 = publicKeyWire.removePrefix("ed25519:")
        val pubKeyBytes = Base58.decode(pubKeyB58)

        // 1. Fetch current access key (nonce and block_hash)
        val accessKeyQuery = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", "nonce-query")
            addProperty("method", "query")
            add("params", JsonObject().apply {
                addProperty("request_type", "view_access_key")
                addProperty("finality", "final")
                addProperty("account_id", accountId)
                addProperty("public_key", publicKeyWire)
            })
        }
        val keyResp = rpcPost(accessKeyQuery)
            ?: return@withContext TxResult(false, null, "Network error fetching account nonce from NEAR RPC")

        if (keyResp.has("error")) {
            val errDetail = keyResp.get("error").toString()
            return@withContext TxResult(false, null, "Failed to query account access key: $errDetail", errDetail)
        }
        val resObj = keyResp.getAsJsonObject("result")
        val currNonce = resObj?.get("nonce")?.asLong ?: 0L
        val nextNonce = currNonce + 1L
        val blockHashB58 = resObj?.get("block_hash")?.asString
            ?: return@withContext TxResult(false, null, "Missing block_hash in access key response")
        val blockHashBytes = Base58.decode(blockHashB58)

        // 2. Borsh serialize FunctionCall Action
        val actionBorsh = BorshWriter().apply {
            writeU8(2) // 2 = FunctionCall action
            writeString(methodName)
            writeBytes(argsJson.toByteArray(Charsets.UTF_8))
            writeU64(gas)
            writeU128(depositYocto)
        }.toByteArray()

        // 3. Borsh serialize Transaction
        val txBorsh = BorshWriter().apply {
            writeString(accountId)
            writeU8(0) // 0 = ED25519 PublicKey
            writeRaw(pubKeyBytes)
            writeU64(nextNonce)
            writeString(activeContractId)
            writeRaw(blockHashBytes)
            writeU32(1) // 1 action
            writeRaw(actionBorsh)
        }.toByteArray()

        // 4. SHA-256 hash of txBorsh
        val txHash = MessageDigest.getInstance("SHA-256").digest(txBorsh)

        // 5. Ed25519 sign with seed
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seedBytes, 0))
        signer.update(txHash, 0, txHash.size)
        val signature = signer.generateSignature()

        // 6. Borsh serialize SignedTransaction
        val signedTxBorsh = BorshWriter().apply {
            writeRaw(txBorsh)
            writeU8(0) // 0 = ED25519 Signature
            writeRaw(signature)
        }.toByteArray()

        val signedTxB64 = Base64.getEncoder().encodeToString(signedTxBorsh)

        // 7. Broadcast transaction
        val sendBody = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", "broadcast-tx")
            addProperty("method", "broadcast_tx_commit")
            add("params", JsonArray().apply { add(signedTxB64) })
        }

        val sendResp = rpcPost(sendBody)
            ?: return@withContext TxResult(false, null, "Network failure broadcasting transaction to testnet")

        if (sendResp.has("error")) {
            val err = sendResp.get("error").toString()
            return@withContext mapTransactionError(err)
        }

        val resultObj = sendResp.getAsJsonObject("result")
        val finalStatus = resultObj?.getAsJsonObject("status")
        val txOutcome = resultObj?.getAsJsonObject("transaction_outcome")
        val returnedTxHash = txOutcome?.get("id")?.asString

        // Check for Execution Failure in status
        if (finalStatus != null && finalStatus.has("Failure")) {
            val failureObj = finalStatus.get("Failure").toString()
            return@withContext mapTransactionError(failureObj, returnedTxHash)
        }

        // Check for Execution Failure in all receipts (cross-contract calls, callbacks)
        val receiptsOutcome = resultObj?.getAsJsonArray("receipts_outcome")
        if (receiptsOutcome != null) {
            for (elem in receiptsOutcome) {
                val rOutcome = elem.asJsonObject?.getAsJsonObject("outcome")
                val rStatus = rOutcome?.getAsJsonObject("status")
                if (rStatus != null && rStatus.has("Failure")) {
                    val failureObj = rStatus.get("Failure").toString()
                    return@withContext mapTransactionError(failureObj, returnedTxHash)
                }
            }
        }

        TxResult(true, returnedTxHash, "Transaction completed on-chain")
    }

    private fun mapTransactionError(rawError: String, txHash: String? = null): TxResult {
        val cleanMsg = when {
            rawError.contains("NotEnoughBalance") || rawError.contains("LackBalanceForState") ->
                "Insufficient NEAR balance to pay for deposit, storage, and gas fees."
            rawError.contains("already enrolled") ->
                "You are already enrolled in this challenge."
            rawError.contains("enrollment closed") || rawError.contains("already started") ->
                "Enrollment is closed because this challenge has already started."
            rawError.contains("challenge not yet settled") ->
                "The challenge is not yet settled. Please wait for settlement before withdrawing."
            rawError.contains("not enrolled") ->
                "Your account is not enrolled in this challenge."
            rawError.contains("already submitted") ->
                "Completion attestation has already been submitted."
            rawError.contains("reward already claimed") ->
                "Your reward has already been claimed."
            rawError.contains("stake already withdrawn") ->
                "Your stake has already been withdrawn."
            rawError.contains("caller must call storage_deposit") ->
                "Account must register storage before enrolling."
            rawError.contains("InvalidNonce") ->
                "Transaction nonce out of sync. Please retry."
            rawError.contains("Expired") ->
                "Transaction or attestation has expired."
            else -> "Contract transaction failed: $rawError"
        }
        return TxResult(false, txHash, cleanMsg, rawError)
    }

    // -----------------------------------------------------------------------
    // View RPC Helpers
    // -----------------------------------------------------------------------

    private fun callView(method: String, args: JsonObject = JsonObject(), bypassCache: Boolean = false): com.google.gson.JsonElement? {
        val isConstant = method in setOf("get_min_members", "get_quorum_pct", "get_submission_grace_secs", "get_stake_yocto")
        val cacheKey = "$activeContractId::$method::${args.toString()}"

        if (!bypassCache) {
            if (isConstant) {
                val cached = constantViewCache[cacheKey]
                if (cached != null) return cached
            } else {
                val cached = ttlViewCache[cacheKey]
                if (cached != null && (System.currentTimeMillis() - cached.timestampMs) < 6_000L) {
                    return cached.element
                }
            }
        }

        val argsB64 = Base64.getEncoder().encodeToString(args.toString().toByteArray(Charsets.UTF_8))
        val body = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", "view-call")
            addProperty("method", "query")
            add("params", JsonObject().apply {
                addProperty("request_type", "call_function")
                addProperty("finality", "final")
                addProperty("account_id", activeContractId)
                addProperty("method_name", method)
                addProperty("args_base64", argsB64)
            })
        }
        val resp = rpcPost(body)
        if (resp == null || resp.has("error")) {
            return if (!bypassCache) ttlViewCache[cacheKey]?.element else null
        }
        val resultArr = resp.getAsJsonObject("result")?.getAsJsonArray("result")
            ?: return if (!bypassCache) ttlViewCache[cacheKey]?.element else null
        val bytes = ByteArray(resultArr.size()) { resultArr[it].asByte }
        val jsonStr = String(bytes, Charsets.UTF_8)
        val parsed = try {
            gson.fromJson(jsonStr, com.google.gson.JsonElement::class.java)
        } catch (e: Exception) {
            null
        }

        if (parsed != null) {
            if (isConstant) {
                constantViewCache[cacheKey] = parsed
            } else {
                ttlViewCache[cacheKey] = CachedViewEntry(System.currentTimeMillis(), parsed)
            }
        }
        return parsed ?: if (!bypassCache) ttlViewCache[cacheKey]?.element else null
    }

    private fun callViewBool(method: String, args: JsonObject): Boolean {
        val elem = callView(method, args) ?: return false
        return if (elem.isJsonPrimitive && elem.asJsonPrimitive.isBoolean) {
            elem.asBoolean
        } else false
    }

    private fun parseChallenge(obj: JsonObject): ContractChallenge? {
        val id = obj.get("challenge_id")?.asString ?: return null
        val steps = obj.get("steps_per_day")?.asLong ?: 0L
        val days = obj.get("days_required")?.asInt ?: 0
        val startNs = obj.get("start_ns")?.asLong ?: 0L
        val endNs = obj.get("end_ns")?.asLong ?: 0L
        val enrolled = obj.get("enrolled_count")?.asLong ?: 0L
        val submitted = obj.get("submitted_count")?.asLong ?: 0L
        val qualifier = obj.get("qualifier_count")?.asLong ?: 0L

        val outcomeRaw = obj.get("outcome")
        val outcome = if (outcomeRaw != null && outcomeRaw.isJsonPrimitive && outcomeRaw.asString == "Pending") {
            ChallengeOutcome.Pending
        } else if (outcomeRaw != null && outcomeRaw.isJsonPrimitive && outcomeRaw.asString == "Void") {
            ChallengeOutcome.Void
        } else if (outcomeRaw != null && outcomeRaw.isJsonObject && outcomeRaw.asJsonObject.has("Success")) {
            val rewStr = outcomeRaw.asJsonObject.getAsJsonObject("Success").get("reward_per_qualifier")?.asString ?: "0"
            ChallengeOutcome.Success(BigInteger(rewStr))
        } else if (outcomeRaw != null && outcomeRaw.isJsonObject && outcomeRaw.asJsonObject.has("Failure")) {
            val burnStr = outcomeRaw.asJsonObject.getAsJsonObject("Failure").get("burn_actual")?.asString ?: "0"
            ChallengeOutcome.Failure(BigInteger(burnStr))
        } else {
            ChallengeOutcome.Pending
        }

        return ContractChallenge(
            challengeId = id,
            stepsPerDay = steps,
            daysRequired = days,
            startNs = startNs,
            endNs = endNs,
            enrolledCount = enrolled,
            submittedCount = submitted,
            qualifierCount = qualifier,
            outcome = outcome
        )
    }

    private fun rpcPost(body: JsonObject): JsonObject? {
        return runBlocking(Dispatchers.IO) {
            rpcGateway.postRpc(rpcUrl, body)
        }
    }

    // -----------------------------------------------------------------------
    // Borsh Byte Serializer
    // -----------------------------------------------------------------------

    class BorshWriter {
        private val stream = ByteArrayOutputStream()

        fun writeU8(value: Int) {
            stream.write(value and 0xFF)
        }

        fun writeU32(value: Long) {
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(value.toInt())
            stream.write(buf.array())
        }

        fun writeU64(value: Long) {
            val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            buf.putLong(value)
            stream.write(buf.array())
        }

        fun writeU128(value: BigInteger) {
            var temp = value
            val fixed = ByteArray(16)
            for (i in 0 until 16) {
                fixed[i] = temp.and(BigInteger.valueOf(0xFF)).toByte()
                temp = temp.shiftRight(8)
            }
            stream.write(fixed)
        }

        fun writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeU32(bytes.size.toLong())
            stream.write(bytes)
        }

        fun writeBytes(bytes: ByteArray) {
            writeU32(bytes.size.toLong())
            stream.write(bytes)
        }

        fun writeRaw(bytes: ByteArray) {
            stream.write(bytes)
        }

        fun toByteArray(): ByteArray = stream.toByteArray()
    }
}

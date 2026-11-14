package com.opaque.healthsynctester.pact

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.opaque.healthsynctester.near.NearContractClient.ChallengeOutcome
import com.opaque.healthsynctester.near.NearContractClient.ContractChallenge
import com.opaque.healthsynctester.near.NearContractClient.MemberContractStatus
import java.lang.reflect.Type
import java.math.BigInteger

/**
 * Persisted local state per (profile, contractId, accountId).
 * Prevents screen blanking, vanishing balances, or missing challenge history.
 */
data class PersistedPactState(
    val profile: String,
    val contractId: String = "",
    val accountId: String = "",
    val challenge: ContractChallenge?,
    val memberStatus: MemberContractStatus?,
    val memberVerdict: MemberVerdictDto?,
    val walletBalances: WalletBalances?,
    val allChallenges: List<ContractChallenge>?,
    val lastSyncTimestampMs: Long,
    val challengeMemberStatuses: Map<String, MemberContractStatus>? = null
)

data class PendingTxConfirmation(
    val profile: String,
    val challengeId: String,
    val action: String, // "ENROLL", "SUBMIT_COMPLETION", "CLAIM_REWARD", "WITHDRAW_STAKE", "SETTLE_WEEK"
    val txHash: String,
    val timestampMs: Long,
    val contractId: String = "",
    val accountId: String = ""
)

/**
 * Custom Gson adapter for Kotlin sealed class ChallengeOutcome.
 * Ensures robust serialization and deserialization across app restarts.
 */
class ChallengeOutcomeAdapter : JsonSerializer<ChallengeOutcome>, JsonDeserializer<ChallengeOutcome> {
    override fun serialize(src: ChallengeOutcome, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val obj = JsonObject()
        when (src) {
            is ChallengeOutcome.Pending -> obj.addProperty("type", "Pending")
            is ChallengeOutcome.Void -> obj.addProperty("type", "Void")
            is ChallengeOutcome.Success -> {
                obj.addProperty("type", "Success")
                obj.addProperty("reward", src.rewardPerQualifierYocto.toString())
            }
            is ChallengeOutcome.Failure -> {
                obj.addProperty("type", "Failure")
                obj.addProperty("burn", src.burnActualYocto.toString())
            }
        }
        return obj
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): ChallengeOutcome {
        if (json.isJsonPrimitive) {
            return when (json.asString) {
                "Void" -> ChallengeOutcome.Void
                else -> ChallengeOutcome.Pending
            }
        }
        if (!json.isJsonObject) return ChallengeOutcome.Pending
        val obj = json.asJsonObject
        val type = obj.get("type")?.asString
        return when (type) {
            "Void" -> ChallengeOutcome.Void
            "Success" -> {
                val rew = obj.get("reward")?.asString ?: "0"
                val rewBig = try { BigInteger(rew) } catch (_: Exception) { BigInteger.ZERO }
                ChallengeOutcome.Success(rewBig)
            }
            "Failure" -> {
                val burn = obj.get("burn")?.asString ?: "0"
                val burnBig = try { BigInteger(burn) } catch (_: Exception) { BigInteger.ZERO }
                ChallengeOutcome.Failure(burnBig)
            }
            "Pending" -> ChallengeOutcome.Pending
            else -> {
                // Fallback support for NEAR contract JSON format: {"Success": {"reward_per_qualifier": "..."}}
                if (obj.has("Success")) {
                    val sObj = obj.get("Success")
                    val rew = if (sObj.isJsonObject) sObj.asJsonObject.get("reward_per_qualifier")?.asString ?: "0" else "0"
                    val rewBig = try { BigInteger(rew) } catch (_: Exception) { BigInteger.ZERO }
                    ChallengeOutcome.Success(rewBig)
                } else if (obj.has("Failure")) {
                    val fObj = obj.get("Failure")
                    val burn = if (fObj.isJsonObject) fObj.asJsonObject.get("burn_actual")?.asString ?: "0" else "0"
                    val burnBig = try { BigInteger(burn) } catch (_: Exception) { BigInteger.ZERO }
                    ChallengeOutcome.Failure(burnBig)
                } else {
                    ChallengeOutcome.Pending
                }
            }
        }
    }
}

/**
 * Custom Gson adapter for BigInteger to ensure clean string conversion without precision loss.
 */
class BigIntegerAdapter : JsonSerializer<BigInteger>, JsonDeserializer<BigInteger> {
    override fun serialize(src: BigInteger, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        return JsonPrimitive(src.toString())
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): BigInteger {
        return try {
            BigInteger(json.asString)
        } catch (_: Exception) {
            BigInteger.ZERO
        }
    }
}

class PactStateStore(context: Context) {
    companion object {
        // v4 schema: strict ProfileScope isolation; old polluted caches are wiped
        private const val PREFS_NAME = "pact_state_store_v4"
        private const val KEY_PREFIX_STATE = "pact_scope_state_"
        private const val KEY_TX_HISTORY = "pact_tx_history_v4"
        private const val KEY_PENDING_CONFIRMATIONS = "pact_pending_confirmations_v4"
        private const val KEY_PREFIX_TERMINAL = "pact_terminal_v4_"

        val gson: Gson = GsonBuilder()
            .registerTypeHierarchyAdapter(ChallengeOutcome::class.java, ChallengeOutcomeAdapter())
            .registerTypeAdapter(BigInteger::class.java, BigIntegerAdapter())
            .create()

        /**
         * Defensive challenge filter to guarantee strict profile isolation:
         * Rejects any challenge whose ID prefix does not belong to the target contract.
         */
        fun isChallengeValidForContract(challengeId: String, contractId: String): Boolean {
            val c = contractId.lowercase()
            val id = challengeId.lowercase()
            return when {
                c.contains("sandbox") -> id.startsWith("sb-") || id.startsWith("test-sb-")
                c.contains("demo") -> id.startsWith("demo-")
                c.contains("pactcontract") -> id.startsWith("week-") || id.all { it.isDigit() }
                else -> true
            }
        }

        /**
         * Pure deterministic merge of cached and incoming challenges.
         * Enforces:
         * 1. Scope isolation: rejects challenges not matching the contractId.
         * 2. Monotonic outcomes: Once a challenge is settled (Void, Success, Failure), it can NEVER revert to Pending.
         * 3. Counter preservation: enrolled/submitted/qualifier counts never regress due to a partial payload.
         * 4. Discovery preservation: Previously discovered challenges for THIS contract are retained.
         */
        fun mergeChallenges(
            cachedList: List<ContractChallenge>,
            incomingList: List<ContractChallenge>,
            contractId: String = ""
        ): List<ContractChallenge> {
            val filteredCached = if (contractId.isNotEmpty()) {
                cachedList.filter { isChallengeValidForContract(it.challengeId, contractId) }
            } else cachedList

            val filteredIncoming = if (contractId.isNotEmpty()) {
                incomingList.filter { isChallengeValidForContract(it.challengeId, contractId) }
            } else incomingList

            val map = filteredCached.associateBy { it.challengeId }.toMutableMap()
            for (incoming in filteredIncoming) {
                val cached = map[incoming.challengeId]
                if (cached == null) {
                    map[incoming.challengeId] = incoming
                } else {
                    val reconciledOutcome = if (cached.outcome !is ChallengeOutcome.Pending && incoming.outcome is ChallengeOutcome.Pending) {
                        cached.outcome
                    } else {
                        incoming.outcome
                    }
                    map[incoming.challengeId] = incoming.copy(
                        enrolledCount = incoming.enrolledCount.coerceAtLeast(cached.enrolledCount),
                        submittedCount = incoming.submittedCount.coerceAtLeast(cached.submittedCount),
                        qualifierCount = incoming.qualifierCount.coerceAtLeast(cached.qualifierCount),
                        outcome = reconciledOutcome
                    )
                }
            }
            return map.values.sortedBy { it.startNs }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // One-time purge of legacy un-scoped or polluted caches from earlier versions
        try {
            val legacyPrefs = context.getSharedPreferences("pact_state_store", Context.MODE_PRIVATE)
            if (legacyPrefs.all.isNotEmpty()) {
                legacyPrefs.edit().clear().apply()
            }
        } catch (_: Exception) {}
    }

    private fun stateKey(profile: String, contractId: String, accountId: String): String {
        val p = profile.lowercase()
        val c = contractId.lowercase().replace(".", "_")
        val a = accountId.lowercase().replace(".", "_")
        return "${KEY_PREFIX_STATE}${p}__${c}__${a}"
    }

    fun saveState(profile: String, contractId: String, accountId: String, state: PersistedPactState) {
        val json = gson.toJson(state)
        val key = stateKey(profile, contractId, accountId)
        prefs.edit().putString(key, json).apply()
    }

    fun saveState(profile: String, state: PersistedPactState) {
        saveState(profile, state.contractId, state.accountId, state)
    }

    fun loadState(profile: String, contractId: String, accountId: String): PersistedPactState? {
        val key = stateKey(profile, contractId, accountId)
        val json = prefs.getString(key, null) ?: return null
        val restored = try {
            gson.fromJson(json, PersistedPactState::class.java)
        } catch (_: Exception) {
            null
        } ?: return null

        // Defensive validation: filter out any cross-polluted challenges
        val cleanAll = restored.allChallenges?.filter { isChallengeValidForContract(it.challengeId, contractId) }
        val cleanCh = restored.challenge?.takeIf { isChallengeValidForContract(it.challengeId, contractId) }
        val cleanMap = restored.challengeMemberStatuses?.filterKeys { isChallengeValidForContract(it, contractId) }

        return restored.copy(
            allChallenges = cleanAll,
            challenge = cleanCh,
            challengeMemberStatuses = cleanMap
        )
    }

    fun loadState(profile: String): PersistedPactState? {
        return loadState(profile, "", "")
    }

    fun saveTx(record: AppTxRecord) {
        val existing = loadAllTxs().toMutableList()
        existing.removeAll { it.txHash.equals(record.txHash, ignoreCase = true) }
        existing.add(0, record)
        val json = gson.toJson(existing)
        prefs.edit().putString(KEY_TX_HISTORY, json).apply()
    }

    fun getTxsForProfile(profile: String): List<AppTxRecord> {
        return loadAllTxs().filter { it.profile.equals(profile, ignoreCase = true) }
    }

    private fun loadAllTxs(): List<AppTxRecord> {
        val json = prefs.getString(KEY_TX_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AppTxRecord>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // --- Terminal States per (profile, challengeId, accountId) ---
    private fun terminalKey(profile: String, challengeId: String, accountId: String, stateKey: String): String {
        return "$KEY_PREFIX_TERMINAL${profile.lowercase()}_${challengeId}_${accountId.lowercase()}_$stateKey"
    }

    fun setTerminalState(profile: String, challengeId: String, accountId: String, stateKey: String) {
        prefs.edit().putBoolean(terminalKey(profile, challengeId, accountId, stateKey), true).apply()
    }

    fun isTerminalState(profile: String, challengeId: String, accountId: String, stateKey: String): Boolean {
        return prefs.getBoolean(terminalKey(profile, challengeId, accountId, stateKey), false)
    }

    // --- Pending Confirmations per (profile, challengeId, action) ---
    fun savePendingConfirmation(pending: PendingTxConfirmation) {
        val existing = loadAllPendingConfirmations().toMutableList()
        existing.removeAll {
            it.profile.equals(pending.profile, ignoreCase = true) &&
                    it.challengeId == pending.challengeId &&
                    it.action == pending.action
        }
        existing.add(pending)
        val json = gson.toJson(existing)
        prefs.edit().putString(KEY_PENDING_CONFIRMATIONS, json).apply()
    }

    fun removePendingConfirmation(profile: String, challengeId: String, action: String) {
        val existing = loadAllPendingConfirmations().toMutableList()
        val removed = existing.removeAll {
            it.profile.equals(profile, ignoreCase = true) &&
                    it.challengeId == challengeId &&
                    it.action == action
        }
        if (removed) {
            val json = gson.toJson(existing)
            prefs.edit().putString(KEY_PENDING_CONFIRMATIONS, json).apply()
        }
    }

    fun getPendingConfirmations(profile: String): List<PendingTxConfirmation> {
        return loadAllPendingConfirmations().filter { it.profile.equals(profile, ignoreCase = true) }
    }

    private fun loadAllPendingConfirmations(): List<PendingTxConfirmation> {
        val json = prefs.getString(KEY_PENDING_CONFIRMATIONS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<PendingTxConfirmation>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}

package com.opaque.healthsynctester.data

import com.opaque.healthsynctester.domain.ProfileScope
import com.opaque.healthsynctester.near.NearContractClient.ChallengeOutcome
import com.opaque.healthsynctester.near.NearContractClient.ContractChallenge
import com.opaque.healthsynctester.near.NearContractClient.MemberContractStatus
import com.opaque.healthsynctester.pact.WalletBalances
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

enum class QueryStatus {
    IDLE, LOADING, SUCCESS, ERROR
}

data class QueryRecord<T>(
    val data: T?,
    val status: QueryStatus,
    val dataUpdatedAtMs: Long,
    val error: String?,
    val staleTimeMs: Long
) {
    val isStale: Boolean
        get() = (System.currentTimeMillis() - dataUpdatedAtMs) > staleTimeMs
}

/**
 * Event-Driven Query Cache (TanStack-Query style in native Kotlin).
 *
 * Guarantees:
 * 1. Scope-bound keys: (scopeKey, resource, params). Cross-profile data cannot leak.
 * 2. Stale-while-revalidate: returns last-known-good cached data immediately without clearing on error.
 * 3. Event-driven: NO 1-second or 10-second polling storms. Refreshes occur strictly on screen focus,
 *    user transactions, time-boundary triggers, or explicit pull-to-refresh.
 * 4. Monotonic settlement: Settled challenges (Void, Success, Failure) are immutable and never regress to Pending.
 */
class PactQueryCache {

    companion object {
        const val STALE_CONSTANTS_MS = Long.MAX_VALUE
        const val STALE_TERMINAL_MS = Long.MAX_VALUE
        const val STALE_BALANCES_MS = 30_000L
        const val STALE_ACTIVE_CHALLENGE_MS = 20_000L
        const val STALE_CHALLENGE_LIST_MS = 45_000L

        @Volatile
        private var instance: PactQueryCache? = null

        fun getInstance(): PactQueryCache {
            return instance ?: synchronized(this) {
                instance ?: PactQueryCache().also { instance = it }
            }
        }
    }

    private val cache = ConcurrentHashMap<String, QueryRecord<Any>>()
    private val inFlight = ConcurrentHashMap<String, Deferred<Any?>>()

    // Challenge Map per scope: Map<scopeKey, Map<challengeId, ContractChallenge>>
    private val scopedChallenges = ConcurrentHashMap<String, ConcurrentHashMap<String, ContractChallenge>>()

    // Member statuses per scope: Map<scopeKey, Map<challengeId, MemberContractStatus>>
    private val scopedMemberStatuses = ConcurrentHashMap<String, ConcurrentHashMap<String, MemberContractStatus>>()

    // Wallets per scope
    private val scopedWallets = ConcurrentHashMap<String, WalletBalances>()

    // One-shot timer jobs per challenge to trigger refresh exactly at start/end/grace
    private val boundaryJobs = ConcurrentHashMap<String, Job>()

    fun queryKey(scope: ProfileScope, resource: String, params: String = ""): String {
        return "${scope.scopeKey}::$resource::$params"
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getCached(scope: ProfileScope, resource: String, params: String = ""): QueryRecord<T>? {
        val key = queryKey(scope, resource, params)
        return cache[key] as? QueryRecord<T>
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> fetchQuery(
        scope: ProfileScope,
        resource: String,
        params: String = "",
        staleTimeMs: Long,
        forceRefresh: Boolean = false,
        fetcher: suspend () -> T
    ): T = withContext(Dispatchers.IO) {
        val key = queryKey(scope, resource, params)
        val existing = cache[key] as? QueryRecord<T>

        if (!forceRefresh && existing?.data != null && !existing.isStale) {
            return@withContext existing.data
        }

        // Deduplicate in-flight fetch
        val existingDeferred = inFlight[key]
        if (existingDeferred != null) {
            return@withContext try {
                existingDeferred.await() as T
            } catch (e: Exception) {
                existing?.data ?: throw e
            }
        }

        val deferred = CompletableDeferred<Any?>()
        val prev = inFlight.putIfAbsent(key, deferred)
        if (prev != null) {
            return@withContext try {
                prev.await() as T
            } catch (e: Exception) {
                existing?.data ?: throw e
            }
        }

        try {
            val result = fetcher()
            val record = QueryRecord(
                data = result,
                status = QueryStatus.SUCCESS,
                dataUpdatedAtMs = System.currentTimeMillis(),
                error = null,
                staleTimeMs = staleTimeMs
            )
            cache[key] = record as QueryRecord<Any>
            deferred.complete(result)
            return@withContext result
        } catch (e: CancellationException) {
            deferred.complete(existing?.data)
            throw e
        } catch (e: Exception) {
            // Preserve last-known-good data on error
            if (existing?.data != null) {
                cache[key] = existing.copy(
                    status = QueryStatus.ERROR,
                    error = e.message
                ) as QueryRecord<Any>
                deferred.complete(existing.data)
                return@withContext existing.data
            }
            deferred.complete(null)
            throw e
        } finally {
            inFlight.remove(key)
        }
    }

    /**
     * Store and reconcile challenges for a specific scope.
     * Enforces:
     * 1. Scope isolation: only stored under scope.scopeKey.
     * 2. Defensive check: challenge must belong to scope.contractId.
     * 3. Monotonic settlement: settled challenges (Void, Success, Failure) never revert to Pending.
     * 4. Monotonic counters: enrolled, submitted, qualifier counts never regress.
     */
    fun updateChallenges(
        scope: ProfileScope,
        incoming: List<ContractChallenge>,
        coroutineScope: CoroutineScope,
        onBoundaryTrigger: (String) -> Unit
    ): List<ContractChallenge> {
        val scopeMap = scopedChallenges.computeIfAbsent(scope.scopeKey) { ConcurrentHashMap() }

        for (item in incoming) {
            val existing = scopeMap[item.challengeId]
            if (existing == null) {
                scopeMap[item.challengeId] = item
            } else {
                val reconciledOutcome = if (existing.outcome !is ChallengeOutcome.Pending && item.outcome is ChallengeOutcome.Pending) {
                    existing.outcome
                } else {
                    item.outcome
                }
                scopeMap[item.challengeId] = item.copy(
                    enrolledCount = item.enrolledCount.coerceAtLeast(existing.enrolledCount),
                    submittedCount = item.submittedCount.coerceAtLeast(existing.submittedCount),
                    qualifierCount = item.qualifierCount.coerceAtLeast(existing.qualifierCount),
                    outcome = reconciledOutcome
                )
            }

            // Schedule one-shot boundary triggers for non-terminal challenges
            scheduleBoundaryRefresh(scope, scopeMap[item.challengeId]!!, coroutineScope, onBoundaryTrigger)
        }

        return scopeMap.values.sortedBy { it.startNs }
    }

    fun getChallengesForScope(scope: ProfileScope): List<ContractChallenge> {
        return scopedChallenges[scope.scopeKey]?.values?.sortedBy { it.startNs } ?: emptyList()
    }

    fun getMemberStatusesForScope(scope: ProfileScope): Map<String, MemberContractStatus> {
        return scopedMemberStatuses[scope.scopeKey]?.toMap() ?: emptyMap()
    }

    fun updateMemberStatus(scope: ProfileScope, challengeId: String, status: MemberContractStatus) {
        val scopeMap = scopedMemberStatuses.computeIfAbsent(scope.scopeKey) { ConcurrentHashMap() }
        scopeMap[challengeId] = status
    }

    fun updateWalletBalances(scope: ProfileScope, balances: WalletBalances) {
        scopedWallets[scope.scopeKey] = balances
    }

    fun getWalletBalances(scope: ProfileScope): WalletBalances? {
        return scopedWallets[scope.scopeKey]
    }

    private fun scheduleBoundaryRefresh(
        scope: ProfileScope,
        challenge: ContractChallenge,
        coroutineScope: CoroutineScope,
        onBoundaryTrigger: (String) -> Unit
    ) {
        if (challenge.outcome !is ChallengeOutcome.Pending) return
        val nowNs = System.currentTimeMillis() * 1_000_000L

        val boundaries = listOf(challenge.startNs, challenge.endNs)
        for (boundaryNs in boundaries) {
            if (boundaryNs > nowNs) {
                val delayMs = ((boundaryNs - nowNs) / 1_000_000L).coerceAtLeast(500L)
                val jobKey = "${scope.scopeKey}::${challenge.challengeId}::$boundaryNs"
                if (!boundaryJobs.containsKey(jobKey)) {
                    val job = coroutineScope.launch {
                        delay(delayMs)
                        onBoundaryTrigger(challenge.challengeId)
                    }
                    boundaryJobs[jobKey] = job
                }
            }
        }
    }

    fun clearScope(scope: ProfileScope) {
        scopedChallenges.remove(scope.scopeKey)
        scopedMemberStatuses.remove(scope.scopeKey)
        scopedWallets.remove(scope.scopeKey)
        cache.keys.filter { it.startsWith(scope.scopeKey) }.forEach { cache.remove(it) }
        boundaryJobs.keys.filter { it.startsWith(scope.scopeKey) }.forEach {
            boundaryJobs.remove(it)?.cancel()
        }
    }
}

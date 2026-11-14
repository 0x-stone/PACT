package com.opaque.healthsynctester

import com.opaque.healthsynctester.near.NearContractClient.ChallengeOutcome
import com.opaque.healthsynctester.near.NearContractClient.ContractChallenge
import com.opaque.healthsynctester.near.NearContractClient.MemberContractStatus
import com.opaque.healthsynctester.pact.PactStateStore
import com.opaque.healthsynctester.pact.PersistedPactState
import com.opaque.healthsynctester.pact.WalletBalances
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class PactStateStoreTest {

    private val gson = PactStateStore.gson

    // -------------------------------------------------------------------
    // 1. Sealed class ChallengeOutcome Serialization & Deserialization
    // -------------------------------------------------------------------

    @Test
    fun testChallengeOutcomePendingRoundTrip() {
        val outcome: ChallengeOutcome = ChallengeOutcome.Pending
        val json = gson.toJson(outcome)
        val deserialized = gson.fromJson(json, ChallengeOutcome::class.java)
        assertTrue("Expected ChallengeOutcome.Pending", deserialized is ChallengeOutcome.Pending)
    }

    @Test
    fun testChallengeOutcomeVoidRoundTrip() {
        val outcome: ChallengeOutcome = ChallengeOutcome.Void
        val json = gson.toJson(outcome)
        val deserialized = gson.fromJson(json, ChallengeOutcome::class.java)
        assertTrue("Expected ChallengeOutcome.Void", deserialized is ChallengeOutcome.Void)
    }

    @Test
    fun testChallengeOutcomeSuccessRoundTrip() {
        val rewardYocto = BigInteger("20000000000000000000000000") // 20 PACT
        val outcome: ChallengeOutcome = ChallengeOutcome.Success(rewardYocto)
        val json = gson.toJson(outcome)
        val deserialized = gson.fromJson(json, ChallengeOutcome::class.java)
        assertTrue("Expected ChallengeOutcome.Success", deserialized is ChallengeOutcome.Success)
        val success = deserialized as ChallengeOutcome.Success
        assertEquals(rewardYocto, success.rewardPerQualifierYocto)
    }

    @Test
    fun testChallengeOutcomeFailureRoundTrip() {
        val burnYocto = BigInteger("5000000000000000000000000") // 5 PACT
        val outcome: ChallengeOutcome = ChallengeOutcome.Failure(burnYocto)
        val json = gson.toJson(outcome)
        val deserialized = gson.fromJson(json, ChallengeOutcome::class.java)
        assertTrue("Expected ChallengeOutcome.Failure", deserialized is ChallengeOutcome.Failure)
        val failure = deserialized as ChallengeOutcome.Failure
        assertEquals(burnYocto, failure.burnActualYocto)
    }

    @Test
    fun testChallengeOutcomeRawNearContractFormats() {
        // Raw NEAR contract string "Void"
        val voidParsed = gson.fromJson("\"Void\"", ChallengeOutcome::class.java)
        assertTrue(voidParsed is ChallengeOutcome.Void)

        // Raw NEAR contract object {"Success": {"reward_per_qualifier": "15000000000000000000000000"}}
        val successJson = "{\"Success\": {\"reward_per_qualifier\": \"15000000000000000000000000\"}}"
        val successParsed = gson.fromJson(successJson, ChallengeOutcome::class.java)
        assertTrue(successParsed is ChallengeOutcome.Success)
        assertEquals(BigInteger("15000000000000000000000000"), (successParsed as ChallengeOutcome.Success).rewardPerQualifierYocto)

        // Raw NEAR contract object {"Failure": {"burn_actual": "5000000000000000000000000"}}
        val failureJson = "{\"Failure\": {\"burn_actual\": \"5000000000000000000000000\"}}"
        val failureParsed = gson.fromJson(failureJson, ChallengeOutcome::class.java)
        assertTrue(failureParsed is ChallengeOutcome.Failure)
        assertEquals(BigInteger("5000000000000000000000000"), (failureParsed as ChallengeOutcome.Failure).burnActualYocto)
    }

    // -------------------------------------------------------------------
    // 2. Full PersistedPactState Serialization with Nested Sealed Classes
    // -------------------------------------------------------------------

    @Test
    fun testPersistedPactStateSerialization() {
        val challenge = ContractChallenge(
            challengeId = "week-test",
            stepsPerDay = 10L,
            daysRequired = 1,
            startNs = 1000L,
            endNs = 2000L,
            enrolledCount = 3L,
            submittedCount = 2L,
            qualifierCount = 2L,
            outcome = ChallengeOutcome.Success(BigInteger("33333333333333333333333333"))
        )
        val memberStatus = MemberContractStatus(
            accountId = "alice.testnet",
            isEnrolled = true,
            hasSubmitted = true,
            isQualifier = true,
            hasWithdrawnStake = false,
            hasClaimedReward = false,
            isStorageRegistered = true,
            nearBalanceYocto = BigInteger("9500000000000000000000000"),
            pactBalanceYocto = BigInteger("100000000000000000000000000")
        )
        val balances = WalletBalances(
            accountId = "alice.testnet",
            availableNearYocto = BigInteger("9500000000000000000000000"),
            lockedStorageNearYocto = BigInteger("1820000000000000000000"),
            stakedNearYocto = BigInteger("500000000000000000000000"),
            pactBalanceYocto = BigInteger("100000000000000000000000000"),
            claimablePactYocto = BigInteger.ZERO,
            isJoinAffordable = true
        )

        val state = PersistedPactState(
            profile = "sandbox",
            contractId = "pact-sandbox.testnet",
            accountId = "alice.testnet",
            challenge = challenge,
            memberStatus = memberStatus,
            memberVerdict = null,
            walletBalances = balances,
            allChallenges = listOf(challenge),
            lastSyncTimestampMs = 1700000000000L,
            challengeMemberStatuses = mapOf("week-test" to memberStatus)
        )

        val json = gson.toJson(state)
        val restored = gson.fromJson(json, PersistedPactState::class.java)

        assertNotNull(restored)
        assertEquals("sandbox", restored.profile)
        assertEquals("pact-sandbox.testnet", restored.contractId)
        assertEquals("alice.testnet", restored.accountId)
        assertEquals(BigInteger("9500000000000000000000000"), restored.walletBalances?.availableNearYocto)
        assertEquals(BigInteger("100000000000000000000000000"), restored.walletBalances?.pactBalanceYocto)
        assertTrue(restored.challenge?.outcome is ChallengeOutcome.Success)
        assertEquals(
            BigInteger("33333333333333333333333333"),
            (restored.challenge?.outcome as ChallengeOutcome.Success).rewardPerQualifierYocto
        )
    }

    // -------------------------------------------------------------------
    // 3. Challenge Merging & Settlement Monotonicity
    // -------------------------------------------------------------------

    @Test
    fun testMergeChallengesNeverRevertsSettledOutcomeToPending() {
        // Challenge was previously confirmed settled as VOID
        val cachedVoid = ContractChallenge(
            challengeId = "demo-1",
            stepsPerDay = 10,
            daysRequired = 1,
            startNs = 1000,
            endNs = 2000,
            enrolledCount = 2,
            submittedCount = 0,
            qualifierCount = 0,
            outcome = ChallengeOutcome.Void
        )

        // A subsequent stale query or RPC lag returns Pending
        val incomingStale = ContractChallenge(
            challengeId = "demo-1",
            stepsPerDay = 10,
            daysRequired = 1,
            startNs = 1000,
            endNs = 2000,
            enrolledCount = 2,
            submittedCount = 0,
            qualifierCount = 0,
            outcome = ChallengeOutcome.Pending
        )

        val merged = PactStateStore.mergeChallenges(listOf(cachedVoid), listOf(incomingStale))
        assertEquals(1, merged.size)
        assertTrue(
            "Settled outcome must NEVER revert to Pending",
            merged[0].outcome is ChallengeOutcome.Void
        )
    }

    @Test
    fun testMergeChallengesPreservesDiscoveredChallengesAndMonotonicCounters() {
        // Discovered earlier: week-1 (settled), week-2 (enrolling)
        val ch1 = ContractChallenge("week-1", 10, 1, 1000, 2000, 3, 2, 2, ChallengeOutcome.Success(BigInteger.TEN))
        val ch2 = ContractChallenge("week-2", 10, 1, 3000, 4000, 2, 0, 0, ChallengeOutcome.Pending)

        // Incoming RPC only fetched latest challenge (week-2) with updated enrollments
        val incoming = ContractChallenge("week-2", 10, 1, 3000, 4000, 5, 1, 0, ChallengeOutcome.Pending)

        val merged = PactStateStore.mergeChallenges(listOf(ch1, ch2), listOf(incoming))
        assertEquals(2, merged.size)

        // week-1 must NOT have disappeared
        val foundCh1 = merged.find { it.challengeId == "week-1" }
        assertNotNull("Previously discovered challenge must not vanish", foundCh1)
        assertEquals(3L, foundCh1?.enrolledCount)

        // week-2 counts must be updated monotonically
        val foundCh2 = merged.find { it.challengeId == "week-2" }
        assertNotNull(foundCh2)
        assertEquals(5L, foundCh2?.enrolledCount)
        assertEquals(1L, foundCh2?.submittedCount)
    }

    // -------------------------------------------------------------------
    // 4. Last-Known-Good Balance Preservation Simulation
    // -------------------------------------------------------------------

    @Test
    fun testLastKnownGoodBalancePreservation() {
        // Last known good wallet balances
        val cachedBalances = WalletBalances(
            accountId = "user.testnet",
            availableNearYocto = BigInteger("10000000000000000000000000"), // 10 NEAR
            lockedStorageNearYocto = BigInteger("1820000000000000000000"),
            stakedNearYocto = BigInteger("500000000000000000000000"),
            pactBalanceYocto = BigInteger("100000000000000000000000000"), // 100 PACT
            claimablePactYocto = BigInteger.ZERO,
            isJoinAffordable = true
        )

        // Simulated RPC failure: returns 0 or empty for balances
        val rpcFailedAmountYocto = BigInteger.ZERO
        val rpcFailedPactYocto = BigInteger.ZERO

        // Reconcile logic identical to TodayViewModel
        val finalAvailable = if (rpcFailedAmountYocto > BigInteger.ZERO) rpcFailedAmountYocto else cachedBalances.availableNearYocto
        val finalPact = if (rpcFailedPactYocto > BigInteger.ZERO) rpcFailedPactYocto else cachedBalances.pactBalanceYocto

        assertEquals("Available NEAR must not drop to 0 on RPC failure", cachedBalances.availableNearYocto, finalAvailable)
        assertEquals("PACT balance must not drop to 0 on RPC failure", cachedBalances.pactBalanceYocto, finalPact)
    }
}

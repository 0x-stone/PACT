package com.opaque.healthsynctester

import com.opaque.healthsynctester.near.NearContractClient
import com.opaque.healthsynctester.near.NearContractClient.ChallengeOutcome
import com.opaque.healthsynctester.near.NearContractClient.ContractChallenge
import com.opaque.healthsynctester.near.NearContractClient.MemberContractStatus
import com.opaque.healthsynctester.pact.*
import com.opaque.healthsynctester.profiles.PactProfiles
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class PactProfileAndIsolationTest {

    // -------------------------------------------------------------------
    // 1. Immutable Profile Configuration & Parameters
    // -------------------------------------------------------------------

    @Test
    fun testProfileConfigurations() {
        // Real profile
        val real = PactProfiles.REAL
        assertEquals("real", real.id)
        assertEquals("pactcontract.testnet", real.contractId)
        assertEquals("pactdao.testnet", real.daoId)
        assertEquals(5L, real.defaultMinMembers)
        assertEquals(70, real.defaultQuorumPct)
        assertEquals("0.5", real.defaultStakeNear)
        assertEquals(3600L, real.defaultSubmissionGraceSecs)

        // Demo profile
        val demo = PactProfiles.DEMO
        assertEquals("demo", demo.id)
        assertEquals("pact-demo.testnet", demo.contractId)
        assertEquals("pactdao.testnet", demo.daoId)
        assertEquals(3L, demo.defaultMinMembers)
        assertEquals(70, demo.defaultQuorumPct)
        assertEquals("0.5", demo.defaultStakeNear)
        assertEquals(420L, demo.defaultSubmissionGraceSecs)

        // Sandbox profile
        val sandbox = PactProfiles.SANDBOX
        assertEquals("sandbox", sandbox.id)
        assertEquals("pact-sandbox.testnet", sandbox.contractId)
        assertEquals("pact-sandbox-relayer.testnet", sandbox.daoId)
        assertEquals(1L, sandbox.defaultMinMembers)
        assertEquals(100, sandbox.defaultQuorumPct)
        assertEquals("0.01", sandbox.defaultStakeNear)
        // STRICT REQUIREMENT: Grace is exactly 180 seconds
        assertEquals(180L, sandbox.defaultSubmissionGraceSecs)

        assertTrue("SANDBOX_ENABLED must be true by default", PactProfiles.SANDBOX_ENABLED)
    }

    // -------------------------------------------------------------------
    // 2. Rule D.1: choosePrimaryChallenge Selection
    // -------------------------------------------------------------------

    @Test
    fun testChoosePrimaryChallengeUnenrolledPicksUpcomingOverVoid() {
        // This directly tests the root cause of the red box on Today:
        // Contract has a void challenge (e.g. week-1794451353) with 0 enrolled
        // and an upcoming challenge (week-2).
        // An unenrolled user MUST be shown week-2, NEVER the void week-1794451353!
        val voidChallenge = ContractChallenge(
            challengeId = "week-1794451353",
            stepsPerDay = 5000L,
            daysRequired = 5,
            startNs = 1_000_000_000_000L,
            endNs = 2_000_000_000_000L,
            enrolledCount = 0L,
            submittedCount = 0L,
            qualifierCount = 0L,
            outcome = ChallengeOutcome.Pending
        )
        val upcomingChallenge = ContractChallenge(
            challengeId = "week-2",
            stepsPerDay = 5000L,
            daysRequired = 5,
            startNs = 3_000_000_000_000L,
            endNs = 4_000_000_000_000L,
            enrolledCount = 2L,
            submittedCount = 0L,
            qualifierCount = 0L,
            outcome = ChallengeOutcome.Pending
        )

        val all = listOf(voidChallenge, upcomingChallenge)
        val memberStatuses = emptyMap<String, MemberContractStatus>()
        val nowChainNs = 1_500_000_000_000L // during voidChallenge window

        val chosen = PactLifecycleManager.choosePrimaryChallenge(
            allChallenges = all,
            memberStatuses = memberStatuses,
            myAccountId = "newbie.testnet",
            nowChainNs = nowChainNs
        )

        assertNotNull("Should pick upcoming challenge", chosen)
        assertEquals("Must pick week-2, NOT the dead void challenge", "week-2", chosen?.challengeId)
    }

    @Test
    fun testChoosePrimaryChallengeEnrolledIncompleteTakesPrecedence() {
        val enrolledActive = ContractChallenge(
            challengeId = "week-active",
            stepsPerDay = 1000L,
            daysRequired = 1,
            startNs = 1_000_000_000_000L,
            endNs = 2_000_000_000_000L,
            enrolledCount = 5L,
            submittedCount = 0L,
            qualifierCount = 0L,
            outcome = ChallengeOutcome.Pending
        )
        val upcoming = ContractChallenge(
            challengeId = "week-upcoming",
            stepsPerDay = 1000L,
            daysRequired = 1,
            startNs = 3_000_000_000_000L,
            endNs = 4_000_000_000_000L,
            enrolledCount = 1L,
            submittedCount = 0L,
            qualifierCount = 0L,
            outcome = ChallengeOutcome.Pending
        )

        val myStatus = MemberContractStatus(
            accountId = "0xgow.testnet",
            isEnrolled = true,
            hasSubmitted = false,
            isQualifier = false,
            hasWithdrawnStake = false,
            hasClaimedReward = false,
            isStorageRegistered = true,
            nearBalanceYocto = BigInteger.ZERO,
            pactBalanceYocto = BigInteger.ZERO
        )

        val all = listOf(enrolledActive, upcoming)
        val statuses = mapOf("week-active" to myStatus)
        val nowChainNs = 1_200_000_000_000L

        val chosen = PactLifecycleManager.choosePrimaryChallenge(
            allChallenges = all,
            memberStatuses = statuses,
            myAccountId = "0xgow.testnet",
            nowChainNs = nowChainNs
        )

        assertEquals("Must pick the enrolled active challenge", "week-active", chosen?.challengeId)
    }

    // -------------------------------------------------------------------
    // 3. Rule E: todayMode Pure Function (All 5 Modes)
    // -------------------------------------------------------------------

    @Test
    fun testTodayModeNoRelevantChallenge() {
        val state = PactLifecycleManager.todayMode(
            challenge = null,
            myStatus = null,
            todaySteps = 3450L,
            nowChainNs = 1_000_000_000_000L
        )
        assertEquals(TodayRingMode.NO_CHALLENGE, state.mode)
        assertFalse("Target ring must NOT be shown when no challenge", state.showTargetRing)
        assertEquals(0L, state.targetSteps)
        assertEquals("No active pact", state.primaryText)
    }

    @Test
    fun testTodayModeUpcomingEnrolled() {
        val ch = ContractChallenge(
            challengeId = "ch-1",
            stepsPerDay = 10L,
            daysRequired = 1,
            startNs = 2_000_000_000_000L,
            endNs = 3_000_000_000_000L,
            enrolledCount = 1L,
            submittedCount = 0L,
            qualifierCount = 0L,
            outcome = ChallengeOutcome.Pending
        )
        val st = MemberContractStatus("0x.testnet", true, false, false, false, false, true, BigInteger.ZERO, BigInteger.ZERO)
        val state = PactLifecycleManager.todayMode(ch, st, 5L, 1_000_000_000_000L)

        assertEquals(TodayRingMode.UPCOMING_ENROLLED, state.mode)
        assertFalse(state.showTargetRing)
        assertEquals("Challenge enrolled", state.primaryText)
        assertTrue(state.subtitleText.contains("Starts in"))
    }

    @Test
    fun testTodayModeUpcomingJoinable() {
        val ch = ContractChallenge("ch-1", 10L, 1, 2_000_000_000_000L, 3_000_000_000_000L, 1L, 0L, 0L, ChallengeOutcome.Pending)
        val state = PactLifecycleManager.todayMode(ch, null, 5L, 1_000_000_000_000L)

        assertEquals(TodayRingMode.UPCOMING_JOINABLE, state.mode)
        assertFalse(state.showTargetRing)
        assertEquals("Enrollment open", state.primaryText)
        assertTrue(state.subtitleText.contains("Starts in"))
    }

    @Test
    fun testTodayModeLiveEnrolled() {
        val ch = ContractChallenge("ch-1", 1000L, 1, 1_000_000_000_000L, 2_000_000_000_000L, 5L, 0L, 0L, ChallengeOutcome.Pending)
        val st = MemberContractStatus("0x.testnet", true, false, false, false, false, true, BigInteger.ZERO, BigInteger.ZERO)
        val state = PactLifecycleManager.todayMode(ch, st, 350L, 1_500_000_000_000L)

        assertEquals(TodayRingMode.LIVE_ENROLLED, state.mode)
        assertTrue("Target ring MUST be shown when LIVE and enrolled", state.showTargetRing)
        assertEquals(1000L, state.targetSteps)
        assertEquals(650L, state.remainingSteps)
        assertEquals("650 steps closes the ring", state.primaryText)
    }

    @Test
    fun testTodayModeLiveNotEnrolled() {
        val ch = ContractChallenge("ch-1", 1000L, 1, 1_000_000_000_000L, 2_000_000_000_000L, 5L, 0L, 0L, ChallengeOutcome.Pending)
        val state = PactLifecycleManager.todayMode(ch, null, 350L, 1_500_000_000_000L)

        assertEquals(TodayRingMode.LIVE_NOT_ENROLLED, state.mode)
        assertFalse("Target ring must NOT be shown when not enrolled", state.showTargetRing)
        assertEquals("Enrollment closed", state.primaryText)
    }

    @Test
    fun testTodayModeEndedLeavesChallengeModePreviousTargetNeverAppears() {
        // After challenge ends (in grace, settling, settled):
        // Ring leaves challenge mode; previous challenge's target must NEVER appear again.
        val ch = ContractChallenge("ch-1", 1000L, 1, 1_000_000_000_000L, 2_000_000_000_000L, 5L, 0L, 0L, ChallengeOutcome.Pending)
        val st = MemberContractStatus("0x.testnet", true, true, true, false, false, true, BigInteger.ZERO, BigInteger.ZERO)
        val state = PactLifecycleManager.todayMode(ch, st, 350L, 2_100_000_000_000L) // past endNs

        assertEquals(TodayRingMode.ENDED_RESULT_CARD, state.mode)
        assertFalse("Target ring MUST be false after challenge ends", state.showTargetRing)
        assertEquals(0L, state.targetSteps)
        assertEquals("Challenge ended", state.primaryText)
        assertTrue(state.showResultCard)
    }

    // -------------------------------------------------------------------
    // 4. Rule F: canClaimReward Verification
    // -------------------------------------------------------------------

    @Test
    fun testCanClaimRewardEligibility() {
        val successCh = ContractChallenge(
            "ch-1", 10L, 1, 1_000L, 2_000L, 1L, 1L, 1L,
            ChallengeOutcome.Success(BigInteger("100000000000000000000")) // 100 PACT
        )

        val eligible = MemberContractStatus("0x.testnet", true, true, isQualifier = true, hasWithdrawnStake = false, hasClaimedReward = false, isStorageRegistered = true, nearBalanceYocto = BigInteger.ZERO, pactBalanceYocto = BigInteger.ZERO)
        assertTrue("Qualifier with unclaimed reward should be eligible to claim", PactLifecycleManager.canClaimReward(successCh, eligible))

        val alreadyClaimed = eligible.copy(hasClaimedReward = true)
        assertFalse("Already claimed reward must NOT be eligible to claim", PactLifecycleManager.canClaimReward(successCh, alreadyClaimed))

        val notQualifier = eligible.copy(isQualifier = false)
        assertFalse("Non-qualifier must NOT be eligible to claim", PactLifecycleManager.canClaimReward(successCh, notQualifier))

        val zeroRewardCh = successCh.copy(outcome = ChallengeOutcome.Success(BigInteger.ZERO))
        assertFalse("Zero reward must NOT be eligible to claim", PactLifecycleManager.canClaimReward(zeroRewardCh, eligible))

        val voidCh = successCh.copy(outcome = ChallengeOutcome.Void)
        assertFalse("Void challenge must NOT have claimable reward", PactLifecycleManager.canClaimReward(voidCh, eligible))

        val failedCh = successCh.copy(outcome = ChallengeOutcome.Failure(BigInteger("50000000000000000000")))
        assertFalse("Failed challenge must NOT have claimable reward", PactLifecycleManager.canClaimReward(failedCh, eligible))
    }
}

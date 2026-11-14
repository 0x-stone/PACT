package com.opaque.healthsynctester

import com.opaque.healthsynctester.near.NearContractClient.ChallengeOutcome
import com.opaque.healthsynctester.near.NearContractClient.ContractChallenge
import com.opaque.healthsynctester.near.NearContractClient.MemberContractStatus
import com.opaque.healthsynctester.pact.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class PactLifecycleAndWalletTest {

    private val baseChallenge = ContractChallenge(
        challengeId = "demo-test",
        stepsPerDay = 500L,
        daysRequired = 1,
        startNs = 1_000_000_000_000L,       // t = 1,000s
        endNs   = 2_200_000_000_000L,       // t = 2,200s (duration 1,200s)
        enrolledCount = 5L,
        submittedCount = 0L,
        qualifierCount = 0L,
        outcome = ChallengeOutcome.Pending
    )

    private val graceSecs = 420L // 420s grace
    // graceEndNs = 2_200_000_000_000 + 420_000_000_000 = 2_620_000_000_000L

    private val enrolledMember = MemberContractStatus(
        accountId = "0xgow.testnet",
        isEnrolled = true,
        hasSubmitted = false,
        isQualifier = false,
        hasWithdrawnStake = false,
        hasClaimedReward = false,
        isStorageRegistered = true,
        nearBalanceYocto = BigInteger("1000000000000000000000000"),
        pactBalanceYocto = BigInteger.ZERO
    )

    // -----------------------------------------------------------------------
    // 1. Phase State Machine Tests (every transition)
    // -----------------------------------------------------------------------

    @Test
    fun testPhaseUpcomingBeforeStart() {
        val nowChainNs = 500_000_000_000L // 500s < 1,000s
        val phase = PactLifecycleManager.resolvePhase(baseChallenge, enrolledMember, nowChainNs, graceSecs)
        assertEquals(ChallengePhase.UPCOMING, phase)
    }

    @Test
    fun testPhaseLiveDuringChallenge() {
        val nowChainNs = 1_500_000_000_000L // 1,500s
        val phase = PactLifecycleManager.resolvePhase(baseChallenge, enrolledMember, nowChainNs, graceSecs)
        assertEquals(ChallengePhase.LIVE, phase)
    }

    @Test
    fun testPhaseSubmittingDuringGraceWhenNotSubmitted() {
        // Enrolled member during grace who hasn't submitted yet
        val nowChainNs = 2_300_000_000_000L // 2,300s (inside [2200s, 2620s])
        val phase = PactLifecycleManager.resolvePhase(baseChallenge, enrolledMember, nowChainNs, graceSecs)
        assertEquals(ChallengePhase.SUBMITTING, phase)
    }

    @Test
    fun testPhaseGraceDuringGraceWhenAlreadySubmitted() {
        // Enrolled member during grace who already submitted
        val submittedMember = enrolledMember.copy(hasSubmitted = true)
        val nowChainNs = 2_300_000_000_000L
        val phase = PactLifecycleManager.resolvePhase(baseChallenge, submittedMember, nowChainNs, graceSecs)
        assertEquals(ChallengePhase.GRACE, phase)
    }

    @Test
    fun testPhaseSettlingWhenGraceExpiredAndUnsettled() {
        // Root Cause Bug Test: challenge ended, grace ended, but outcome still Pending on-chain
        // The previous code showed "No active challenge" and blanked the screen!
        // Now it must resolve to SETTLING!
        val nowChainNs = 2_700_000_000_000L // 2,700s > 2,620s
        val phase = PactLifecycleManager.resolvePhase(baseChallenge, enrolledMember, nowChainNs, graceSecs)
        assertEquals(ChallengePhase.SETTLING, phase)
    }

    @Test
    fun testPhaseSettledWhenOutcomeResolvedAndUnwithdrawnStake() {
        // Challenge outcome Failure or Success, but user has not withdrawn stake yet
        val settledChallenge = baseChallenge.copy(outcome = ChallengeOutcome.Failure(BigInteger("50000000000000000000")))
        val nowChainNs = 2_700_000_000_000L
        val phase = PactLifecycleManager.resolvePhase(settledChallenge, enrolledMember, nowChainNs, graceSecs)
        assertEquals(ChallengePhase.SETTLED, phase)
    }

    @Test
    fun testPhaseSettledWhenOutcomeSuccessAndUnclaimedReward() {
        val successChallenge = baseChallenge.copy(outcome = ChallengeOutcome.Success(BigInteger("25000000000000000000")))
        val qualifierMember = enrolledMember.copy(
            isQualifier = true,
            hasWithdrawnStake = true,
            hasClaimedReward = false // Still needs to claim reward!
        )
        val nowChainNs = 2_700_000_000_000L
        val phase = PactLifecycleManager.resolvePhase(successChallenge, qualifierMember, nowChainNs, graceSecs)
        assertEquals(ChallengePhase.SETTLED, phase)
    }

    @Test
    fun testPhaseDoneWhenAllClaimsAndWithdrawalsCompleted() {
        val successChallenge = baseChallenge.copy(outcome = ChallengeOutcome.Success(BigInteger("25000000000000000000")))
        val completedMember = enrolledMember.copy(
            isQualifier = true,
            hasWithdrawnStake = true,
            hasClaimedReward = true
        )
        val nowChainNs = 2_700_000_000_000L
        val phase = PactLifecycleManager.resolvePhase(successChallenge, completedMember, nowChainNs, graceSecs)
        assertEquals(ChallengePhase.DONE, phase)
    }

    // -----------------------------------------------------------------------
    // 2. Auto-Submit Decision Logic Tests
    // -----------------------------------------------------------------------

    private val validAttestation = PactAttestationDto(
        schemaTag = "OPAQUE-PACT-ATTESTATION-V1",
        contractId = "pact-demo.testnet",
        challengeId = "demo-test",
        accountId = "0xgow.testnet",
        passed = true,
        expiry = 3_000L, // valid until 3,000s
        signature = "dummy-sig"
    )

    @Test
    fun testAutoSubmitRejectsBeforeChallengeEnd() {
        val nowChainNs = 2_000_000_000_000L // 2,000s < 2,200s
        val decision = PactLifecycleManager.evaluateAutoSubmit(baseChallenge, enrolledMember, validAttestation, nowChainNs, graceSecs)
        assertEquals(AutoSubmitDecision.NotEnded, decision)
    }

    @Test
    fun testAutoSubmitRejectsWhenNotEnrolled() {
        val nowChainNs = 2_300_000_000_000L
        val notEnrolled = enrolledMember.copy(isEnrolled = false)
        val decision = PactLifecycleManager.evaluateAutoSubmit(baseChallenge, notEnrolled, validAttestation, nowChainNs, graceSecs)
        assertEquals(AutoSubmitDecision.NotEnrolled, decision)
    }

    @Test
    fun testAutoSubmitRejectsWhenAlreadySubmitted() {
        val nowChainNs = 2_300_000_000_000L
        val alreadySub = enrolledMember.copy(hasSubmitted = true)
        val decision = PactLifecycleManager.evaluateAutoSubmit(baseChallenge, alreadySub, validAttestation, nowChainNs, graceSecs)
        assertEquals(AutoSubmitDecision.AlreadySubmitted, decision)
    }

    @Test
    fun testAutoSubmitRejectsWhenGracePeriodExpired() {
        val nowChainNs = 2_700_000_000_000L // past graceEndNs 2,620s
        val decision = PactLifecycleManager.evaluateAutoSubmit(baseChallenge, enrolledMember, validAttestation, nowChainNs, graceSecs)
        assertEquals(AutoSubmitDecision.GraceClosed, decision)
    }

    @Test
    fun testAutoSubmitRejectsPassedFalseWithoutTransaction() {
        // Quorum rule: only submit passed=true. If passed=false, send NO tx!
        val nowChainNs = 2_300_000_000_000L
        val failedAttestation = validAttestation.copy(passed = false)
        val decision = PactLifecycleManager.evaluateAutoSubmit(baseChallenge, enrolledMember, failedAttestation, nowChainNs, graceSecs)
        assertEquals(AutoSubmitDecision.NotQualified, decision)
    }

    @Test
    fun testAutoSubmitApprovesValidPassingAttestation() {
        val nowChainNs = 2_300_000_000_000L
        val decision = PactLifecycleManager.evaluateAutoSubmit(baseChallenge, enrolledMember, validAttestation, nowChainNs, graceSecs)
        assertTrue(decision is AutoSubmitDecision.ShouldSubmit)
        assertEquals("demo-test", (decision as AutoSubmitDecision.ShouldSubmit).attestation.challengeId)
    }

    @Test
    fun testAutoSubmitRetriesOnExpiredAttestation() {
        val nowChainNs = 2_300_000_000_000L // now is 2,300s
        val expiredAttestation = validAttestation.copy(expiry = 2_250L) // expired 50s ago
        val decision = PactLifecycleManager.evaluateAutoSubmit(baseChallenge, enrolledMember, expiredAttestation, nowChainNs, graceSecs)
        assertTrue(decision is AutoSubmitDecision.RetryableError)
        assertEquals("Attestation has expired", (decision as AutoSubmitDecision.RetryableError).message)
    }

    // -----------------------------------------------------------------------
    // 3. Settlement Single-Flight & Relayer Evaluation
    // -----------------------------------------------------------------------

    @Test
    fun testSettleRejectsWhenTooEarlyDuringGrace() {
        val nowChainNs = 2_500_000_000_000L // inside grace (2,500s < 2,620s)
        val decision = PactLifecycleManager.evaluateSettle(baseChallenge, nowChainNs, graceSecs, isDemo = true)
        assertEquals(SettleDecision.TooEarly, decision)
    }

    @Test
    fun testSettleDemoModeWaitsForDaemonFirst() {
        // In DEMO mode, immediately after grace expires, daemon settles first (isAppRelayer = false)
        val nowChainNs = 2_630_000_000_000L // 10s past grace
        val decision = PactLifecycleManager.evaluateSettle(baseChallenge, nowChainNs, graceSecs, isDemo = true, marginSecs = 30L)
        assertTrue(decision is SettleDecision.ShouldSettle)
        assertFalse((decision as SettleDecision.ShouldSettle).isAppRelayer)
    }

    @Test
    fun testSettleDemoModeAppRelayerFallbackAfterMargin() {
        // In DEMO mode, after margin (30s) passes without daemon settling, app acts as relayer
        val nowChainNs = 2_660_000_000_000L // 40s past grace (> 30s)
        val decision = PactLifecycleManager.evaluateSettle(baseChallenge, nowChainNs, graceSecs, isDemo = true, marginSecs = 30L)
        assertTrue(decision is SettleDecision.ShouldSettle)
        assertTrue((decision as SettleDecision.ShouldSettle).isAppRelayer)
    }

    @Test
    fun testSettleRealModeAppRelaysDirectly() {
        val nowChainNs = 2_630_000_000_000L // past grace
        val decision = PactLifecycleManager.evaluateSettle(baseChallenge, nowChainNs, graceSecs, isDemo = false)
        assertTrue(decision is SettleDecision.ShouldSettle)
        assertTrue((decision as SettleDecision.ShouldSettle).isAppRelayer)
    }

    // -----------------------------------------------------------------------
    // 4. Wallet Balances & Formatting
    // -----------------------------------------------------------------------

    @Test
    fun testWalletBalanceFormatting() {
        val balances = WalletBalances(
            accountId = "0xgow.testnet",
            availableNearYocto = BigInteger("1250000000000000000000000"), // 1.25 NEAR
            lockedStorageNearYocto = BigInteger("1820000000000000000000"), // 182 bytes * 10^19 = 0.00182 NEAR
            stakedNearYocto = BigInteger("500000000000000000000000"),    // 0.5 NEAR
            pactBalanceYocto = BigInteger("25000000000000000000"),        // 25.00 PACT
            claimablePactYocto = BigInteger("50000000000000000000"),      // 50.00 PACT
            isJoinAffordable = true
        )

        assertEquals("1.25 NEAR", balances.availableNearDisplay)
        assertEquals("0.0018 NEAR", balances.lockedNearDisplay)
        assertEquals("0.5 NEAR", balances.stakedNearDisplay)
        assertEquals("25.00 PACT", balances.pactBalanceDisplay)
        assertEquals("50.00 PACT", balances.claimablePactDisplay)
    }

    // -----------------------------------------------------------------------
    // 5. Late Phases Simulation Harness (Offline)
    // -----------------------------------------------------------------------

    @Test
    fun testEarlyVoidWhenEnrolledCountLessThanMinMembers() {
        val lowEnrollmentChallenge = baseChallenge.copy(enrolledCount = 2L)
        val nowChainNs = 1_500_000_000_000L // 1500s (after startNs 1000s, before endNs 2200s)
        val phase = PactLifecycleManager.resolvePhase(lowEnrollmentChallenge, enrolledMember, nowChainNs, graceSecs, minMembers = 3L)
        assertEquals(ChallengePhase.VOID_EARLY, phase)

        val decision = PactLifecycleManager.evaluateAutoSubmit(lowEnrollmentChallenge, enrolledMember, validAttestation, nowChainNs, graceSecs, minMembers = 3L)
        assertTrue(decision is AutoSubmitDecision.PermanentError)
        assertTrue((decision as AutoSubmitDecision.PermanentError).message.contains("insufficient members"))
    }

    @Test
    fun testAutoSubmitRejectsAccountIdMismatch() {
        val nowChainNs = 2_300_000_000_000L
        val wrongAccountAttestation = validAttestation.copy(accountId = "hacker.testnet")
        val decision = PactLifecycleManager.evaluateAutoSubmit(baseChallenge, enrolledMember, wrongAccountAttestation, nowChainNs, graceSecs)
        assertTrue(decision is AutoSubmitDecision.PermanentError)
        assertTrue((decision as AutoSubmitDecision.PermanentError).message.contains("mismatch"))
    }

    @Test
    fun testAutoSubmitHandlesDistinctAttestationExceptions() {
        val nowChainNs = 2_300_000_000_000L
        val err409 = AttestationException(409, "challenge not ended yet")
        val dec409 = PactLifecycleManager.evaluateAutoSubmit(baseChallenge, enrolledMember, null, nowChainNs, graceSecs, attestationError = err409)
        assertEquals(AutoSubmitDecision.NotEnded, dec409)

        val err410 = AttestationException(410, "settlement window closed")
        val dec410 = PactLifecycleManager.evaluateAutoSubmit(baseChallenge, enrolledMember, null, nowChainNs, graceSecs, attestationError = err410)
        assertEquals(AutoSubmitDecision.GraceClosed, dec410)

        val err404 = AttestationException(404, "unknown challenge")
        val dec404 = PactLifecycleManager.evaluateAutoSubmit(baseChallenge, enrolledMember, null, nowChainNs, graceSecs, attestationError = err404)
        assertTrue(dec404 is AutoSubmitDecision.PermanentError)

        val err422 = AttestationException(422, "no health records")
        val dec422 = PactLifecycleManager.evaluateAutoSubmit(baseChallenge, enrolledMember, null, nowChainNs, graceSecs, attestationError = err422)
        assertTrue(dec422 is AutoSubmitDecision.NeedsHealthSync)
    }

    @Test
    fun testLatePhaseSimulationHarness() {
        // Simulates progression: LIVE -> ENDED -> SUBMITTING -> GRACE -> SETTLING -> SETTLED -> DONE
        // Verifies the entire sequence without waiting for real time.

        var currentStatus = enrolledMember
        var currentChallenge = baseChallenge

        // Step 1: Live (1500s)
        var now = 1_500_000_000_000L
        assertEquals(ChallengePhase.LIVE, PactLifecycleManager.resolvePhase(currentChallenge, currentStatus, now, graceSecs))

        // Step 2: Ended, Submitting (2205s)
        now = 2_205_000_000_000L
        assertEquals(ChallengePhase.SUBMITTING, PactLifecycleManager.resolvePhase(currentChallenge, currentStatus, now, graceSecs))
        val subDecision = PactLifecycleManager.evaluateAutoSubmit(currentChallenge, currentStatus, validAttestation, now, graceSecs)
        assertTrue(subDecision is AutoSubmitDecision.ShouldSubmit)

        // Step 3: Simulated Submission succeeds
        currentStatus = currentStatus.copy(hasSubmitted = true)
        assertEquals(ChallengePhase.GRACE, PactLifecycleManager.resolvePhase(currentChallenge, currentStatus, now, graceSecs))

        // Step 4: Grace window expires (2650s) -> Settling
        now = 2_650_000_000_000L
        assertEquals(ChallengePhase.SETTLING, PactLifecycleManager.resolvePhase(currentChallenge, currentStatus, now, graceSecs))

        // Step 5: Settle succeeds on chain -> Success
        currentChallenge = currentChallenge.copy(outcome = ChallengeOutcome.Success(BigInteger("25000000000000000000")))
        currentStatus = currentStatus.copy(isQualifier = true)
        assertEquals(ChallengePhase.SETTLED, PactLifecycleManager.resolvePhase(currentChallenge, currentStatus, now, graceSecs))

        // Step 6: User claims reward and withdraws stake -> Done
        currentStatus = currentStatus.copy(hasClaimedReward = true, hasWithdrawnStake = true)
        assertEquals(ChallengePhase.DONE, PactLifecycleManager.resolvePhase(currentChallenge, currentStatus, now, graceSecs))
    }
}

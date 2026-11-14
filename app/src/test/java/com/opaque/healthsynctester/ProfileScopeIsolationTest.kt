package com.opaque.healthsynctester

import com.opaque.healthsynctester.data.PactQueryCache
import com.opaque.healthsynctester.domain.ProfileScope
import com.opaque.healthsynctester.gateway.RpcGateway
import com.opaque.healthsynctester.near.NearContractClient.ChallengeOutcome
import com.opaque.healthsynctester.near.NearContractClient.ContractChallenge
import com.opaque.healthsynctester.pact.PactStateStore
import com.opaque.healthsynctester.profiles.PactProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfileScopeIsolationTest {

    @Test
    fun testProfileDistinctConfigurationsAssertion() {
        // Assert current valid profiles pass distinct configuration check
        PactProfiles.assertDistinctConfigurations()

        // Verify that duplicate contract IDs throw IllegalArgumentException
        val duplicateProfiles = listOf(
            PactProfiles.REAL.copy(contractId = "dup-contract.testnet"),
            PactProfiles.DEMO.copy(contractId = "dup-contract.testnet")
        )
        try {
            PactProfiles.assertDistinctConfigurations(duplicateProfiles)
            fail("Expected IllegalArgumentException for duplicate contract IDs")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Duplicate contractId") == true)
        }
    }

    @Test
    fun testProfileScopeKeyFormatting() {
        val sandboxScope = ProfileScope.fromProfile(PactProfiles.SANDBOX, "judge.testnet")
        assertEquals("sandbox", sandboxScope.profile)
        assertEquals("pact-sandbox.testnet", sandboxScope.contractId)
        assertEquals("sandbox__testnet__pact-sandbox_testnet__judge_testnet", sandboxScope.scopeKey)

        val demoScope = ProfileScope.fromProfile(PactProfiles.DEMO, "tester.testnet")
        assertEquals("demo", demoScope.profile)
        assertEquals("pact-demo.testnet", demoScope.contractId)
        assertEquals("demo__testnet__pact-demo_testnet__tester_testnet", demoScope.scopeKey)

        assertNotEquals("Scopes must produce distinct keys", sandboxScope.scopeKey, demoScope.scopeKey)
    }

    @Test
    fun testPactStateStoreChallengeContractIdValidation() {
        val sandboxContract = "pact-sandbox.testnet"
        val demoContract = "pact-demo.testnet"
        val realContract = "pactcontract.testnet"

        // Sandbox challenges start with "sb-"
        assertTrue(PactStateStore.isChallengeValidForContract("sb-123456", sandboxContract))
        assertFalse(PactStateStore.isChallengeValidForContract("sb-123456", demoContract))
        assertFalse(PactStateStore.isChallengeValidForContract("sb-123456", realContract))

        // Demo challenges start with "demo-"
        assertTrue(PactStateStore.isChallengeValidForContract("demo-16", demoContract))
        assertFalse(PactStateStore.isChallengeValidForContract("demo-16", sandboxContract))
        assertFalse(PactStateStore.isChallengeValidForContract("demo-16", realContract))

        // Production challenges start with "week-" or numeric
        assertTrue(PactStateStore.isChallengeValidForContract("week-1", realContract))
        assertTrue(PactStateStore.isChallengeValidForContract("1", realContract))
        assertFalse(PactStateStore.isChallengeValidForContract("week-1", sandboxContract))
        assertFalse(PactStateStore.isChallengeValidForContract("week-1", demoContract))
    }

    @Test
    fun testPactQueryCacheScopeIsolation() {
        val cache = PactQueryCache.getInstance()
        val sandboxScope = ProfileScope.fromProfile(PactProfiles.SANDBOX, "judge.testnet")
        val demoScope = ProfileScope.fromProfile(PactProfiles.DEMO, "demo.testnet")
        val testScope = CoroutineScope(Dispatchers.Unconfined)

        cache.clearScope(sandboxScope)
        cache.clearScope(demoScope)

        val sandboxCh = ContractChallenge(
            challengeId = "sb-1",
            stepsPerDay = 10,
            daysRequired = 1,
            startNs = 1_000_000_000L,
            endNs = 2_000_000_000L,
            enrolledCount = 1,
            submittedCount = 0,
            qualifierCount = 0,
            outcome = ChallengeOutcome.Pending
        )
        val demoCh = ContractChallenge(
            challengeId = "demo-1",
            stepsPerDay = 1000,
            daysRequired = 1,
            startNs = 3_000_000_000L,
            endNs = 4_000_000_000L,
            enrolledCount = 3,
            submittedCount = 0,
            qualifierCount = 0,
            outcome = ChallengeOutcome.Pending
        )

        cache.updateChallenges(sandboxScope, listOf(sandboxCh), testScope) {}
        cache.updateChallenges(demoScope, listOf(demoCh), testScope) {}

        val sandboxResults = cache.getChallengesForScope(sandboxScope)
        val demoResults = cache.getChallengesForScope(demoScope)

        assertEquals(1, sandboxResults.size)
        assertEquals("sb-1", sandboxResults[0].challengeId)

        assertEquals(1, demoResults.size)
        assertEquals("demo-1", demoResults[0].challengeId)

        // Clearing sandbox scope MUST NOT affect demo scope
        cache.clearScope(sandboxScope)
        assertEquals(0, cache.getChallengesForScope(sandboxScope).size)
        assertEquals(1, cache.getChallengesForScope(demoScope).size)
    }

    @Test
    fun testRpcGatewayCooldownState(): Unit = runBlocking {
        val gateway = RpcGateway.getInstance()
        assertEquals(0, gateway.inFlightCount.value)

        val futureTime = System.currentTimeMillis() + 5000L
        gateway.triggerCooldown(5000L)
        assertTrue("Cooldown should be set to future", gateway.cooldownUntilMs.value >= (futureTime - 200L))
    }
}

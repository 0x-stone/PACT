package com.opaque.healthsynctester.profiles

import androidx.compose.ui.graphics.Color

/**
 * Immutable definition of a PACT profile.
 * Every profile specifies its unique contract, DAO, styling, and default on-chain parameters.
 */
data class PactProfile(
    val id: String,
    val label: String,
    val colour: Color,
    val contractId: String,
    val daoId: String,
    val backendProfileId: String,
    val explorerBase: String,
    val defaultSubmissionGraceSecs: Long,
    val defaultStakeNear: String,
    val defaultMinMembers: Long,
    val defaultQuorumPct: Int
)

object PactProfiles {
    val REAL = PactProfile(
        id = "real",
        label = "Production (Real)",
        colour = Color(0xFF1E88E5), // Primary Blue
        contractId = "pactcontract.testnet",
        daoId = "pactdao.testnet",
        backendProfileId = "real",
        explorerBase = "https://testnet.nearblocks.io",
        defaultSubmissionGraceSecs = 3600L,
        defaultStakeNear = "0.5",
        defaultMinMembers = 5L,
        defaultQuorumPct = 70
    )

    val DEMO = PactProfile(
        id = "demo",
        label = "Demo Profile",
        colour = Color(0xFFFF9800), // Accent Orange
        contractId = "pact-demo.testnet",
        daoId = "pactdao.testnet",
        backendProfileId = "demo",
        explorerBase = "https://testnet.nearblocks.io",
        defaultSubmissionGraceSecs = 420L,
        defaultStakeNear = "0.5",
        defaultMinMembers = 3L,
        defaultQuorumPct = 70
    )

    val SANDBOX = PactProfile(
        id = "sandbox",
        label = "Judge Sandbox",
        colour = Color(0xFF2E7D32), // Success Green
        contractId = "pact-sandbox.testnet",
        daoId = "pact-sandbox-relayer.testnet",
        backendProfileId = "sandbox",
        explorerBase = "https://testnet.nearblocks.io",
        defaultSubmissionGraceSecs = 180L,
        defaultStakeNear = "0.01",
        defaultMinMembers = 1L,
        defaultQuorumPct = 100
    )

    val ALL: List<PactProfile> = listOf(REAL, DEMO, SANDBOX)
    val BY_ID: Map<String, PactProfile> = ALL.associateBy { it.id.lowercase() }
    val BY_CONTRACT: Map<String, PactProfile> = ALL.associateBy { it.contractId.lowercase() }

    init {
        assertDistinctConfigurations()
    }

    /**
     * Startup verification: DEMO, SANDBOX, and REAL must have distinct contract IDs.
     */
    fun assertDistinctConfigurations(profiles: List<PactProfile> = ALL) {
        val contractIds = profiles.map { it.contractId.lowercase() }
        require(contractIds.size == contractIds.toSet().size) {
            "FATAL CONFIG ERROR: Duplicate contractId found across profiles: $contractIds"
        }
    }

    /** Build flag: when false, SANDBOX is omitted from the switcher. */
    var SANDBOX_ENABLED: Boolean = true

    fun getAvailableProfiles(): List<PactProfile> {
        return if (SANDBOX_ENABLED) ALL else listOf(REAL, DEMO)
    }

    fun get(idOrContract: String?): PactProfile {
        if (idOrContract.isNullOrBlank()) return DEMO
        val key = idOrContract.trim().lowercase()
        return BY_ID[key] ?: BY_CONTRACT[key] ?: throw IllegalArgumentException(
            "Unknown or invalid profile/contract '$idOrContract'. Valid profiles: ${ALL.map { it.id }}"
        )
    }
}

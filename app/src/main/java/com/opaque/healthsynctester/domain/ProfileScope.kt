package com.opaque.healthsynctester.domain

import com.opaque.healthsynctester.profiles.PactProfile
import com.opaque.healthsynctester.profiles.PactProfiles

/**
 * Immutable ProfileScope identifying the exact boundary for all queries,
 * caches, in-flight requests, and persistent state.
 *
 * Guaranteed invariants:
 * - Every piece of data in the system belongs to exactly one ProfileScope.
 * - No data from one scope can ever be read, merged, or displayed in another.
 */
data class ProfileScope(
    val profile: String,
    val network: String,
    val contractId: String,
    val accountId: String
) {
    val scopeKey: String = "${profile.lowercase()}__${network.lowercase()}__${contractId.lowercase().replace('.', '_')}__${accountId.lowercase().replace('.', '_')}"

    companion object {
        fun fromProfile(profile: PactProfile, accountId: String, network: String = "testnet"): ProfileScope {
            return ProfileScope(
                profile = profile.id.lowercase(),
                network = network.lowercase(),
                contractId = profile.contractId.lowercase(),
                accountId = accountId.lowercase()
            )
        }

        fun defaultScope(accountId: String = ""): ProfileScope {
            return fromProfile(PactProfiles.DEMO, accountId)
        }
    }
}

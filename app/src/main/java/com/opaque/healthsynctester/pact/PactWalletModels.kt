package com.opaque.healthsynctester.pact

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Real on-chain wallet balances and transaction records (Stage E/F).
 *
 * All values derive directly from NEAR RPC and pactcontract / pact-demo views:
 * - Available NEAR: total unspent NEAR minus storage lock requirement.
 * - Locked NEAR: account storage bytes * 10^19 yoctoNEAR.
 * - Staked NEAR: enrolled challenges where stake has not yet been withdrawn (0.5 NEAR each).
 * - PACT Balance: ft_balance_of(account_id).
 * - Claimable PACT: sum of reward_per_qualifier for settled Success challenges where qualifier && !claimed.
 */
data class WalletBalances(
    val accountId: String,
    val availableNearYocto: BigInteger = BigInteger.ZERO,
    val lockedStorageNearYocto: BigInteger = BigInteger.ZERO,
    val stakedNearYocto: BigInteger = BigInteger.ZERO,
    val pactBalanceYocto: BigInteger = BigInteger.ZERO,
    val claimablePactYocto: BigInteger = BigInteger.ZERO,
    val isJoinAffordable: Boolean = true
) {
    companion object {
        val YOCTO_PER_NEAR = BigDecimal("1000000000000000000000000") // 10^24
        val YOCTO_PER_PACT = BigDecimal("1000000000000000000")       // 10^18
        val STORAGE_COST_PER_BYTE = BigInteger("10000000000000000000") // 10^19 yocto/byte

        fun formatNear(yocto: BigInteger): String {
            val bd = BigDecimal(yocto).divide(YOCTO_PER_NEAR, 4, RoundingMode.HALF_UP)
            return "${bd.stripTrailingZeros().toPlainString()} NEAR"
        }

        fun formatPact(yocto: BigInteger): String {
            val bd = BigDecimal(yocto).divide(YOCTO_PER_PACT, 2, RoundingMode.HALF_UP)
            return "${bd.setScale(2, RoundingMode.HALF_UP).toPlainString()} PACT"
        }
    }

    val availableNearDisplay: String get() = formatNear(availableNearYocto)
    val lockedNearDisplay: String get() = formatNear(lockedStorageNearYocto)
    val stakedNearDisplay: String get() = formatNear(stakedNearYocto)
    val pactBalanceDisplay: String get() = formatPact(pactBalanceYocto)
    val claimablePactDisplay: String get() = formatPact(claimablePactYocto)
}

/**
 * Persistent record of a transaction submitted directly by this app instance.
 */
data class AppTxRecord(
    val txHash: String,
    val type: String, // ENROLL, SUBMIT_COMPLETION, CLAIM_REWARD, WITHDRAW_STAKE, SETTLE_WEEK
    val challengeId: String?,
    val timestampMs: Long,
    val profile: String // "demo" or "real"
)

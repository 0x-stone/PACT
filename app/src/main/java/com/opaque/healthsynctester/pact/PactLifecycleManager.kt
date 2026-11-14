package com.opaque.healthsynctester.pact

import com.opaque.healthsynctester.near.NearContractClient.ChallengeOutcome
import com.opaque.healthsynctester.near.NearContractClient.ContractChallenge
import com.opaque.healthsynctester.near.NearContractClient.MemberContractStatus

/**
 * 7 Lifecycle Phases:
 * 1. UPCOMING: before start ("Starts in mm:ss"). Join sheet if not enrolled; "You're in" if enrolled.
 * 2. LIVE: start to end (preview ring, "Ends in mm:ss"). "Enrollment closed" if not enrolled.
 * 3. SUBMITTING: end, until submitted: final sync + foreground auto-submit.
 * 4. GRACE: end to end + grace (already submitted or waiting): "Waiting for other members...", "Keep PACT open".
 * 5. SETTLING: grace over, not yet settled: "Settling...". In DEMO daemon calls, in REAL (or after 30s) app calls.
 * 6. SETTLED: outcome resolved + personal result. Claim reward & Withdraw stake.
 * 7. DONE: past challenge card, all claims and withdrawals completed.
 */
enum class ChallengePhase {
    UPCOMING,
    LIVE,
    VOID_EARLY,
    SUBMITTING,
    GRACE,
    SETTLING,
    SETTLED,
    DONE
}

/**
 * Freshness states for stale data labeling:
 * - LIVE: freshly verified from contract/RPC.
 * - UPDATING: background sync/RPC in flight; showing cached data.
 * - OFFLINE: network error or unreachable; showing cached data with offline notice.
 */
enum class FreshnessState(val label: String) {
    LIVE("Live"),
    UPDATING("Updating..."),
    OFFLINE("Offline · Showing saved data")
}

/**
 * Pure decision model for foreground auto-submission.
 */
sealed class AutoSubmitDecision {
    object NotEnded : AutoSubmitDecision()
    object GraceClosed : AutoSubmitDecision()
    object NotEnrolled : AutoSubmitDecision()
    object AlreadySubmitted : AutoSubmitDecision()
    object NotQualified : AutoSubmitDecision() // passed=false, do NOT submit on-chain
    data class ShouldSubmit(val attestation: PactAttestationDto) : AutoSubmitDecision()
    data class PermanentError(val message: String) : AutoSubmitDecision()
    data class NeedsHealthSync(val message: String) : AutoSubmitDecision()
    data class RetryableError(val message: String) : AutoSubmitDecision()
}

/**
 * Pure decision model for settlement call.
 */
sealed class SettleDecision {
    object TooEarly : SettleDecision()
    object AlreadySettled : SettleDecision()
    data class ShouldSettle(val isAppRelayer: Boolean) : SettleDecision()
}

object PactLifecycleManager {

    /**
     * Resolves the current lifecycle phase strictly from contract data and chain time.
     */
    fun resolvePhase(
        challenge: ContractChallenge,
        memberStatus: MemberContractStatus?,
        nowChainNs: Long,
        graceSecs: Long,
        minMembers: Long = 3L
    ): ChallengePhase {
        val startNs = challenge.startNs
        val endNs = challenge.endNs
        val graceEndNs = endNs + graceSecs * 1_000_000_000L

        // 1. UPCOMING: now < start
        if (nowChainNs < startNs) {
            return ChallengePhase.UPCOMING
        }

        // Challenge window has passed:
        val isSettled = challenge.outcome !is ChallengeOutcome.Pending
        val isEnrolled = memberStatus?.isEnrolled == true
        val hasSubmitted = memberStatus?.hasSubmitted == true

        if (isSettled) {
            if (!isEnrolled || memberStatus == null) return ChallengePhase.DONE

            val hasUnwithdrawnStake = !memberStatus.hasWithdrawnStake
            val isSuccess = challenge.outcome is ChallengeOutcome.Success
            val hasUnclaimedReward = isSuccess && memberStatus.isQualifier && !memberStatus.hasClaimedReward

            return if (hasUnwithdrawnStake || hasUnclaimedReward) {
                ChallengePhase.SETTLED
            } else {
                ChallengePhase.DONE
            }
        }

        // Early void check: enrollment closes at start.
        // If enrolled < minMembers, challenge is definitively void from startNs!
        if (challenge.enrolledCount < minMembers) {
            return if (nowChainNs <= graceEndNs) {
                ChallengePhase.VOID_EARLY
            } else {
                ChallengePhase.SETTLING
            }
        }

        // 2. LIVE: start <= now < end
        if (nowChainNs < endNs) {
            return ChallengePhase.LIVE
        }

        // Unsettled cases:
        return if (nowChainNs <= graceEndNs) {
            // Inside grace period [endNs, graceEndNs]
            if (isEnrolled && !hasSubmitted) {
                ChallengePhase.SUBMITTING
            } else {
                ChallengePhase.GRACE
            }
        } else {
            // After grace period expired, but outcome is still Pending
            ChallengePhase.SETTLING
        }
    }

    /**
     * Evaluates whether auto-submission should occur in foreground.
     */
    fun evaluateAutoSubmit(
        challenge: ContractChallenge,
        memberStatus: MemberContractStatus?,
        attestation: PactAttestationDto?,
        nowChainNs: Long,
        graceSecs: Long,
        attestationError: Throwable? = null,
        minMembers: Long = 3L
    ): AutoSubmitDecision {
        if (challenge.enrolledCount < minMembers) {
            return AutoSubmitDecision.PermanentError("Challenge void: insufficient members (${challenge.enrolledCount} of $minMembers enrolled)")
        }

        if (nowChainNs < challenge.endNs) {
            return AutoSubmitDecision.NotEnded
        }

        if (memberStatus == null || !memberStatus.isEnrolled) {
            return AutoSubmitDecision.NotEnrolled
        }

        if (memberStatus.hasSubmitted) {
            return AutoSubmitDecision.AlreadySubmitted
        }

        val graceEndNs = challenge.endNs + graceSecs * 1_000_000_000L
        if (nowChainNs > graceEndNs) {
            return AutoSubmitDecision.GraceClosed
        }

        if (attestation == null) {
            if (attestationError is AttestationException) {
                return when (attestationError.statusCode) {
                    409 -> AutoSubmitDecision.NotEnded
                    410 -> AutoSubmitDecision.GraceClosed
                    404 -> AutoSubmitDecision.PermanentError("Challenge not found on contract or profile mismatch: ${attestationError.message}")
                    422 -> AutoSubmitDecision.NeedsHealthSync("No health records on server · Sync required")
                    else -> AutoSubmitDecision.RetryableError("Backend server error (${attestationError.statusCode}): ${attestationError.message}")
                }
            } else if (attestationError != null) {
                return AutoSubmitDecision.RetryableError("Network error: ${attestationError.localizedMessage}")
            }
            return AutoSubmitDecision.RetryableError("Attestation not ready")
        }

        if (attestation.challengeId != challenge.challengeId) {
            return AutoSubmitDecision.PermanentError("Attestation challenge ID mismatch: ${attestation.challengeId} != ${challenge.challengeId}")
        }

        if (attestation.accountId.isNotEmpty() && memberStatus.accountId.isNotEmpty() && attestation.accountId != memberStatus.accountId) {
            return AutoSubmitDecision.PermanentError("Attestation account ID mismatch: ${attestation.accountId} != ${memberStatus.accountId}")
        }

        val nowSecs = nowChainNs / 1_000_000_000L
        if (attestation.expiry <= nowSecs) {
            return AutoSubmitDecision.RetryableError("Attestation has expired")
        }

        // Quorum rule: only submit passed=true. Non-submitters do not increment qualifier_count,
        // so submitting passed=false has zero effect on quorum and wastes gas.
        if (!attestation.passed) {
            return AutoSubmitDecision.NotQualified
        }

        return AutoSubmitDecision.ShouldSubmit(attestation)
    }

    /**
     * Evaluates when to invoke settle_week.
     */
    fun evaluateSettle(
        challenge: ContractChallenge,
        nowChainNs: Long,
        graceSecs: Long,
        isDemo: Boolean,
        marginSecs: Long = 30L
    ): SettleDecision {
        if (challenge.outcome !is ChallengeOutcome.Pending) {
            return SettleDecision.AlreadySettled
        }

        val graceEndNs = challenge.endNs + graceSecs * 1_000_000_000L
        if (nowChainNs <= graceEndNs) {
            return SettleDecision.TooEarly
        }

        val elapsedPastGraceSecs = (nowChainNs - graceEndNs) / 1_000_000_000L

        // In DEMO mode, daemon settles immediately. App relayer acts as fallback if daemon has not settled after margin.
        // In REAL mode, anyone can call it after grace + margin.
        return if (!isDemo || elapsedPastGraceSecs >= marginSecs) {
            SettleDecision.ShouldSettle(isAppRelayer = true)
        } else {
            SettleDecision.ShouldSettle(isAppRelayer = false)
        }
    }

    /**
     * Formats milliseconds into mm:ss or hh:mm:ss.
     */
    fun formatRemainingTime(seconds: Long): String {
        if (seconds <= 0) return "00:00"
        val hours = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, mins, secs)
        } else {
            "%02d:%02d".format(mins, secs)
        }
    }

    /**
     * Rule D.1: Source of truth is get_challenges from the selected contract.
     * Choose the primary relevant challenge by rule:
     * (a) one I'm enrolled in that isn't fully complete, else
     * (b) the upcoming or live joinable one, else
     * (c) none.
     */
    fun isChallengeFullyComplete(
        challenge: ContractChallenge,
        memberStatus: MemberContractStatus?
    ): Boolean {
        if (memberStatus == null || !memberStatus.isEnrolled) return true
        if (challenge.outcome is ChallengeOutcome.Pending) return false
        val needsWithdraw = !memberStatus.hasWithdrawnStake
        val isSuccess = challenge.outcome is ChallengeOutcome.Success
        val needsClaim = isSuccess && memberStatus.isQualifier && !memberStatus.hasClaimedReward
        return !needsWithdraw && !needsClaim
    }

    fun choosePrimaryChallenge(
        allChallenges: List<ContractChallenge>,
        memberStatuses: Map<String, MemberContractStatus>,
        myAccountId: String?,
        nowChainNs: Long
    ): ContractChallenge? {
        // (a) one I'm enrolled in that isn't fully complete
        if (!myAccountId.isNullOrBlank()) {
            val enrolledIncomplete = allChallenges.filter { ch ->
                val st = memberStatuses[ch.challengeId]
                st != null && st.isEnrolled && !isChallengeFullyComplete(ch, st)
            }
            // 1. LIVE or currently in-grace / submitting
            val active = enrolledIncomplete.firstOrNull { ch ->
                ch.outcome is ChallengeOutcome.Pending && nowChainNs >= ch.startNs
            }
            if (active != null) return active

            // 2. UPCOMING enrolled
            val upcomingEnrolled = enrolledIncomplete.firstOrNull { ch ->
                ch.outcome is ChallengeOutcome.Pending && nowChainNs < ch.startNs
            }
            if (upcomingEnrolled != null) return upcomingEnrolled

            // 3. Settled incomplete (needs claim or withdraw)
            val settledIncomplete = enrolledIncomplete.firstOrNull { ch ->
                ch.outcome !is ChallengeOutcome.Pending
            }
            if (settledIncomplete != null) return settledIncomplete
        }

        // else (b) the upcoming joinable one
        val upcomingJoinable = allChallenges
            .filter { nowChainNs < it.startNs }
            .minByOrNull { it.startNs }
        if (upcomingJoinable != null) return upcomingJoinable

        // else (c) none
        return null
    }

    /**
     * Rule E: Pure function todayMode(profile, challenge, myState, now)
     * - No relevant challenge: show today's steps as a plain count; NO target, NO "steps closes the ring".
     * - Upcoming (enrolled): "Starts in mm:ss".
     * - Live and enrolled: ring with the challenge target and "N steps closes the ring".
     * - Live and not enrolled: "Enrollment closed", no ring.
     * - Ended, in grace, settling, settled: a result or progress CARD; the ring leaves challenge mode;
     *   the previous challenge's target must never appear again.
     */
    fun todayMode(
        challenge: ContractChallenge?,
        myStatus: MemberContractStatus?,
        todaySteps: Long,
        nowChainNs: Long
    ): TodayModeState {
        if (challenge == null) {
            return TodayModeState(
                mode = TodayRingMode.NO_CHALLENGE,
                showTargetRing = false,
                targetSteps = 0L,
                remainingSteps = 0L,
                primaryText = "No active pact",
                subtitleText = "Check Challenges tab to view open events",
                showResultCard = false
            )
        }

        val isEnrolled = myStatus?.isEnrolled == true
        val startNs = challenge.startNs
        val endNs = challenge.endNs

        // 1. UPCOMING: now < startNs
        if (nowChainNs < startNs) {
            val remainingNs = startNs - nowChainNs
            val timeStr = formatRemainingTime((remainingNs / 1_000_000_000L).coerceAtLeast(0L))
            return if (isEnrolled) {
                TodayModeState(
                    mode = TodayRingMode.UPCOMING_ENROLLED,
                    showTargetRing = false,
                    targetSteps = challenge.stepsPerDay.toLong(),
                    remainingSteps = challenge.stepsPerDay.toLong(),
                    primaryText = "Challenge enrolled",
                    subtitleText = "Starts in $timeStr",
                    showResultCard = false
                )
            } else {
                TodayModeState(
                    mode = TodayRingMode.UPCOMING_JOINABLE,
                    showTargetRing = false,
                    targetSteps = challenge.stepsPerDay.toLong(),
                    remainingSteps = challenge.stepsPerDay.toLong(),
                    primaryText = "Enrollment open",
                    subtitleText = "Starts in $timeStr",
                    showResultCard = false
                )
            }
        }

        // 2. LIVE: startNs <= now < endNs
        if (nowChainNs < endNs) {
            return if (isEnrolled) {
                val target = challenge.stepsPerDay.toLong()
                val rem = (target - todaySteps).coerceAtLeast(0L)
                val ringSubtitle = if (rem > 0) "$rem steps closes the ring" else "You closed the ring"
                TodayModeState(
                    mode = TodayRingMode.LIVE_ENROLLED,
                    showTargetRing = true,
                    targetSteps = target,
                    remainingSteps = rem,
                    primaryText = ringSubtitle,
                    subtitleText = "Target: $target steps/day",
                    showResultCard = false
                )
            } else {
                TodayModeState(
                    mode = TodayRingMode.LIVE_NOT_ENROLLED,
                    showTargetRing = false,
                    targetSteps = 0L,
                    remainingSteps = 0L,
                    primaryText = "Enrollment closed",
                    subtitleText = "Challenge in progress",
                    showResultCard = false
                )
            }
        }

        // 3. ENDED / IN-GRACE / SETTLING / SETTLED (nowChainNs >= endNs)
        // Ring leaves challenge mode; previous challenge's target NEVER appears again.
        return TodayModeState(
            mode = TodayRingMode.ENDED_RESULT_CARD,
            showTargetRing = false,
            targetSteps = 0L,
            remainingSteps = 0L,
            primaryText = "Challenge ended",
            subtitleText = "See challenge card below",
            showResultCard = true
        )
    }

    /**
     * Rule D.3: Accurate explanations with real contract numbers.
     */
    fun formatOutcomeExplanation(
        outcome: ChallengeOutcome,
        enrolledCount: Long,
        minMembers: Long,
        qualifierCount: Long,
        quorumPct: Int
    ): String {
        return when (outcome) {
            is ChallengeOutcome.Void -> {
                if (enrolledCount == 0L) {
                    "Void: no members enrolled"
                } else {
                    "Void: $enrolledCount of $minMembers minimum members enrolled"
                }
            }
            is ChallengeOutcome.Failure -> {
                "Failed: $qualifierCount of $enrolledCount qualified, $quorumPct% needed"
            }
            is ChallengeOutcome.Success -> {
                "Success: $qualifierCount of $enrolledCount qualified (met $quorumPct% goal)"
            }
            is ChallengeOutcome.Pending -> {
                "In Progress: $enrolledCount enrolled, $qualifierCount qualifiers"
            }
        }
    }

    /**
     * Rule F: Show Claim ONLY when SELECTED contract confirms ALL:
     * 1. outcome is Success
     * 2. rewardPerQualifier > 0
     * 3. isQualifier == true
     * 4. hasClaimedReward == false
     */
    fun canClaimReward(
        challenge: ContractChallenge?,
        memberStatus: MemberContractStatus?
    ): Boolean {
        if (challenge == null || memberStatus == null) return false
        val outcome = challenge.outcome as? ChallengeOutcome.Success ?: return false
        if (outcome.rewardPerQualifierYocto <= java.math.BigInteger.ZERO) return false
        return memberStatus.isQualifier && !memberStatus.hasClaimedReward
    }
}

enum class TodayRingMode {
    NO_CHALLENGE,
    UPCOMING_ENROLLED,
    UPCOMING_JOINABLE,
    LIVE_ENROLLED,
    LIVE_NOT_ENROLLED,
    ENDED_RESULT_CARD
}

data class TodayModeState(
    val mode: TodayRingMode,
    val showTargetRing: Boolean,
    val targetSteps: Long,
    val remainingSteps: Long,
    val primaryText: String,
    val subtitleText: String,
    val showResultCard: Boolean
)

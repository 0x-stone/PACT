package com.opaque.healthsynctester.ui.challenge

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.near.NearContractClient.ChallengeOutcome
import com.opaque.healthsynctester.near.NearContractClient.ContractChallenge
import com.opaque.healthsynctester.near.NearContractClient.MemberContractStatus
import com.opaque.healthsynctester.pact.MemberVerdictDto
import com.opaque.healthsynctester.pact.PactLifecycleManager
import com.opaque.healthsynctester.ui.components.*
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactShapes
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography
import java.math.BigDecimal

/**
 * PACT Challenge Result & Settlement Sheet (Stage E).
 *
 * Implements:
 * - Personal Verdict from backend (passed/missed, days completed).
 * - Community Outcome from contract (Success/Failure/Void).
 * - Submit Completion (attestation verification and on-chain submission).
 * - Settlement state & debug "Settle week" action.
 * - Claim Reward (qualifiers only) and Withdraw Stake (always refundable).
 * - Explorer links for all on-chain transactions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChallengeResultSheet(
    challenge: ContractChallenge,
    memberStatus: MemberContractStatus,
    memberVerdict: MemberVerdictDto?,
    isSubmittingAttestation: Boolean,
    isSettling: Boolean,
    isClaimingReward: Boolean,
    isWithdrawingStake: Boolean,
    lastTxHash: String?,
    actionError: String?,
    onSubmitAttestation: () -> Unit,
    onSettleWeek: () -> Unit,
    onClaimReward: () -> Unit,
    onWithdrawStake: () -> Unit,
    onDismiss: () -> Unit,
    quorumPct: Int = 70,
    stakeNear: String = "0.5",
    minMembers: Long = 5L,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val nowNs = System.currentTimeMillis() * 1_000_000L
    val hasEnded = nowNs >= challenge.endNs

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PactColors.BackgroundCream,
        shape = PactShapes.card,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PactSpacing.lg, vertical = PactSpacing.md)
                .navigationBarsPadding()
                .verticalScroll(scrollState)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PactLogoMark(size = 32.dp)
                    Spacer(modifier = Modifier.width(PactSpacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Challenge Result",
                            style = PactTypography.title,
                            color = PactColors.InkText
                        )
                        Text(
                            text = challenge.challengeId,
                            style = PactTypography.caption,
                            color = PactColors.MutedText,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.md))

            // 1. Personal Verdict Card
            PactCard(
                backgroundColor = PactColors.SurfaceWhite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Your Verdict",
                        style = PactTypography.title.copy(fontSize = 16.sp),
                        color = PactColors.InkText
                    )

                    val isPassed = memberVerdict?.passed == true || memberStatus.isQualifier
                    StatusChip(
                        text = if (isPassed) "Qualified ✓" else "Not Qualified",
                        backgroundColor = if (isPassed) PactColors.SuccessGreen.copy(alpha = 0.15f) else PactColors.CardBackground,
                        textColor = if (isPassed) PactColors.SuccessGreen else PactColors.MutedText,
                        borderColor = if (isPassed) PactColors.SuccessGreen else PactColors.CardBorder
                    )
                }

                Spacer(modifier = Modifier.height(PactSpacing.xs))

                val daysCompleted = memberVerdict?.daysCompleted ?: 0
                Text(
                    text = "$daysCompleted of ${challenge.daysRequired} days completed at ${challenge.stepsPerDay} steps/day",
                    style = PactTypography.body,
                    color = PactColors.InkText,
                    softWrap = true
                )

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (memberStatus.isEnrolled) {
                        if (memberStatus.hasSubmitted) "Completion attestation submitted on-chain ✓"
                        else "Enrolled in challenge. Ready for on-chain submission."
                    } else "Not enrolled in this challenge.",
                    style = PactTypography.caption,
                    color = PactColors.MutedText,
                    softWrap = true
                )
            }

            Spacer(modifier = Modifier.height(PactSpacing.md))

            // 2. Community Outcome Card
            PactCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Community Outcome",
                    style = PactTypography.title.copy(fontSize = 16.sp),
                    color = PactColors.InkText
                )
                Spacer(modifier = Modifier.height(PactSpacing.xs))

                when (val outcome = challenge.outcome) {
                    is ChallengeOutcome.Pending -> {
                        Text(
                            text = if (hasEnded) "Challenge Ended · Awaiting Settlement" else "Challenge In Progress",
                            style = PactTypography.body.copy(fontWeight = FontWeight.SemiBold),
                            color = PactColors.PrimaryBlue
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${challenge.enrolledCount} enrolled · ${challenge.qualifierCount} qualifiers attested",
                            style = PactTypography.caption,
                            color = PactColors.MutedText
                        )
                    }

                    is ChallengeOutcome.Void -> {
                        Text(
                            text = if (minMembers <= 1L) "Void: No members enrolled" else "Void Week (< $minMembers members enrolled)",
                            style = PactTypography.body.copy(fontWeight = FontWeight.SemiBold),
                            color = PactColors.MutedText
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (minMembers <= 1L) {
                                "No members enrolled before the enrollment deadline. No PACT was minted or burned. Your $stakeNear NEAR stake is 100% withdrawable below."
                            } else {
                                "Fewer than $minMembers members enrolled. No PACT was minted or burned. Your $stakeNear NEAR stake is 100% withdrawable below."
                            },
                            style = PactTypography.caption,
                            color = PactColors.InkText,
                            softWrap = true
                        )
                    }

                    is ChallengeOutcome.Success -> {
                        Text(
                            text = "Community Success! Quorum Reached",
                            style = PactTypography.body.copy(fontWeight = FontWeight.SemiBold),
                            color = PactColors.SuccessGreen
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val rewardNear = try {
                            BigDecimal(outcome.rewardPerQualifierYocto).divide(BigDecimal("1000000000000000000"))
                        } catch (e: Exception) {
                            BigDecimal.ZERO
                        }
                        Text(
                            text = "Quorum reached! $rewardNear PACT minted to each of the ${challenge.qualifierCount} qualifiers.",
                            style = PactTypography.caption,
                            color = PactColors.InkText,
                            softWrap = true
                        )
                    }

                    is ChallengeOutcome.Failure -> {
                        Text(
                            text = "Quorum Not Met",
                            style = PactTypography.body.copy(fontWeight = FontWeight.SemiBold),
                            color = PactColors.AccentOrange
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val burnNear = try {
                            BigDecimal(outcome.burnActualYocto).divide(BigDecimal("1000000000000000000"))
                        } catch (e: Exception) {
                            BigDecimal.ZERO
                        }
                        Text(
                            text = "Quorum of $quorumPct% was not achieved. $burnNear PACT burned from community reserve. Stakes are still 100% withdrawable.",
                            style = PactTypography.caption,
                            color = PactColors.InkText,
                            softWrap = true
                        )
                    }
                }
            }

            // Error display
            if (actionError != null) {
                Spacer(modifier = Modifier.height(PactSpacing.sm))
                PactCard(
                    backgroundColor = PactColors.SurfaceWhite,
                    borderColor = PactColors.AccentOrange,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = actionError,
                        style = PactTypography.caption,
                        color = PactColors.AccentOrange,
                        softWrap = true
                    )
                }
            }

            // Transaction Hash & Explorer Link
            if (lastTxHash != null) {
                Spacer(modifier = Modifier.height(PactSpacing.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val url = "https://testnet.nearblocks.io/txns/$lastTxHash"
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                        .padding(vertical = PactSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "View Explorer",
                        tint = PactColors.PrimaryBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(PactSpacing.xs))
                    Text(
                        text = "View TX: ${lastTxHash.take(12)}...${lastTxHash.takeLast(6)}",
                        style = PactTypography.caption.copy(fontFamily = FontFamily.Monospace),
                        color = PactColors.PrimaryBlue
                    )
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.lg))

            // 3. Actions Section

            // Automatic Lifecycle Progress for Submission
            val isGraceOpen = hasEnded && (challenge.outcome is ChallengeOutcome.Pending) && (nowNs <= challenge.endNs + (420L * 1_000_000_000L))
            if (memberStatus.isEnrolled) {
                if (memberStatus.hasSubmitted) {
                    StatusChip(
                        text = "Completion Submitted ✓",
                        backgroundColor = PactColors.SuccessGreen.copy(alpha = 0.15f),
                        textColor = PactColors.SuccessGreen,
                        borderColor = PactColors.SuccessGreen
                    )
                    Spacer(modifier = Modifier.height(PactSpacing.sm))
                } else if (isGraceOpen) {
                    PactCard(
                        backgroundColor = PactColors.PrimaryBlue.copy(alpha = 0.08f),
                        borderColor = PactColors.PrimaryBlue.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = PactColors.PrimaryBlue
                            )
                            Spacer(modifier = Modifier.width(PactSpacing.sm))
                            Text(
                                text = "Verifying step target & submitting completion automatically...",
                                style = PactTypography.caption.copy(fontWeight = FontWeight.Medium),
                                color = PactColors.PrimaryBlue
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(PactSpacing.sm))
                } else if (hasEnded && challenge.outcome is ChallengeOutcome.Pending) {
                    PactCard(
                        backgroundColor = PactColors.SurfaceWhite,
                        borderColor = PactColors.CardBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "The submission window closed before your attestation was sent. Your stake is still withdrawable.",
                            style = PactTypography.body.copy(fontSize = 13.sp),
                            color = PactColors.MutedText
                        )
                    }
                    Spacer(modifier = Modifier.height(PactSpacing.sm))
                }
            }

            // Settlement in Progress Notice (Automatic flow)
            if (hasEnded && challenge.outcome is ChallengeOutcome.Pending && !isGraceOpen) {
                PactCard(
                    backgroundColor = PactColors.CardBackground,
                    borderColor = PactColors.CardBorder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = PactColors.MutedText
                        )
                        Spacer(modifier = Modifier.width(PactSpacing.sm))
                        Text(
                            text = "Grace period ended · Settlement in progress...",
                            style = PactTypography.caption,
                            color = PactColors.MutedText
                        )
                    }
                }
                Spacer(modifier = Modifier.height(PactSpacing.sm))
            }

            // Action: Claim Reward (Strictly governed by Rule F)
            if (challenge.outcome is ChallengeOutcome.Success && memberStatus.isQualifier) {
                if (memberStatus.hasClaimedReward) {
                    StatusChip(
                        text = "Reward Claimed ✓",
                        backgroundColor = PactColors.SuccessGreen.copy(alpha = 0.15f),
                        textColor = PactColors.SuccessGreen,
                        borderColor = PactColors.SuccessGreen
                    )
                } else if (PactLifecycleManager.canClaimReward(challenge, memberStatus)) {
                    val rewardPact = try {
                        BigDecimal((challenge.outcome as ChallengeOutcome.Success).rewardPerQualifierYocto).divide(BigDecimal("1000000000000000000"))
                    } catch (e: Exception) {
                        BigDecimal.ZERO
                    }
                    if (rewardPact > BigDecimal.ZERO) {
                        PrimaryButton(
                            text = if (isClaimingReward) "Claiming Reward..." else "Claim $rewardPact PACT Reward",
                            onClick = onClaimReward,
                            enabled = !isClaimingReward,
                            loading = isClaimingReward
                        )
                    }
                }
                Spacer(modifier = Modifier.height(PactSpacing.sm))
            }

            // Action: Withdraw Stake
            if (memberStatus.isEnrolled && challenge.outcome !is ChallengeOutcome.Pending) {
                if (memberStatus.hasWithdrawnStake) {
                    StatusChip(
                        text = "$stakeNear NEAR Stake Withdrawn ✓",
                        backgroundColor = PactColors.CardBackground,
                        textColor = PactColors.MutedText
                    )
                } else {
                    Button(
                        onClick = onWithdrawStake,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PactSpacing.buttonHeight),
                        shape = PactShapes.button,
                        enabled = !isWithdrawingStake,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PactColors.SuccessGreen,
                            contentColor = androidx.compose.ui.graphics.Color.White
                        )
                    ) {
                        Text(
                            text = if (isWithdrawingStake) "Withdrawing Stake..." else "Withdraw $stakeNear NEAR Stake",
                            style = PactTypography.title.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(PactSpacing.sm))
            }

            Spacer(modifier = Modifier.height(PactSpacing.md))
        }
    }
}

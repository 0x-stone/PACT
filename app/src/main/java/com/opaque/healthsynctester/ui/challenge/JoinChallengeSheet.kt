package com.opaque.healthsynctester.ui.challenge

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.pact.ChallengeInfo
import com.opaque.healthsynctester.ui.components.*
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactShapes
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography
import java.math.BigDecimal
import java.math.BigInteger

/**
 * PACT Challenge Join Sheet (Stage D).
 *
 * Explains:
 * - 3 separate costs: Refundable stake (0.5 NEAR), Storage deposit (0.00125 NEAR, refundable via unregister), Network fees (~0.005 NEAR, not refunded).
 * - Honest outcomes: Hit target -> PACT minted; Miss target -> PACT burned; Stake ALWAYS returned.
 * - Current account balance check (fails plainly if balance is too low).
 * - Calm, step-by-step progress states (storage deposit -> enroll -> verify on-chain).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinChallengeSheet(
    challenge: ChallengeInfo,
    nearBalanceYocto: BigInteger,
    isEnrolled: Boolean,
    isJoining: Boolean,
    joinStepText: String?,
    joinError: String?,
    isSandbox: Boolean = false,
    onJoin: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nearBalance = try {
        BigDecimal(nearBalanceYocto).divide(BigDecimal("1000000000000000000000000"))
    } catch (e: Exception) {
        BigDecimal.ZERO
    }

    val stakeDisplay = if (isSandbox) "0.01 NEAR" else "0.50 NEAR"
    val minRequiredNear = if (isSandbox) BigDecimal("0.02") else BigDecimal("0.51")
    val hasEnoughBalance = nearBalance >= minRequiredNear

    val nowNs = System.currentTimeMillis() * 1_000_000L
    val startNs = challenge.startNs?.toLongOrNull() ?: 0L
    val hasStarted = startNs > 0 && nowNs >= startNs

    ModalBottomSheet(
        onDismissRequest = { if (!isJoining) onDismiss() },
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
                            text = "Join Challenge",
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
                IconButton(
                    onClick = onDismiss,
                    enabled = !isJoining
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.md))

            // Rules Summary Card
            PactCard(
                backgroundColor = PactColors.SurfaceWhite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Weekly Challenge Target",
                    style = PactTypography.caption,
                    color = PactColors.MutedText
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${challenge.stepsPerDay} steps/day · ${challenge.daysRequired} days required",
                    style = PactTypography.title.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    color = PactColors.InkText
                )
                Spacer(modifier = Modifier.height(PactSpacing.xs))
                Text(
                    text = "Hit the target on ${challenge.daysRequired} different days to qualify for the community PACT mint.",
                    style = PactTypography.body.copy(fontSize = 13.sp),
                    color = PactColors.InkText,
                    softWrap = true
                )
            }

            Spacer(modifier = Modifier.height(PactSpacing.md))

            // Honest Costs Breakdown Card
            PactCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Cost & Deposit Breakdown",
                    style = PactTypography.title.copy(fontSize = 15.sp),
                    color = PactColors.InkText
                )
                Spacer(modifier = Modifier.height(PactSpacing.sm))

                // 1. Stake
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Refundable Stake",
                            style = PactTypography.body.copy(fontWeight = FontWeight.SemiBold),
                            color = PactColors.InkText
                        )
                        Text(
                            text = "100% returned to you after settlement",
                            style = PactTypography.caption,
                            color = PactColors.SuccessGreen
                        )
                    }
                    Text(
                        text = stakeDisplay,
                        style = PactTypography.title.copy(fontSize = 15.sp),
                        color = PactColors.InkText
                    )
                }

                Spacer(modifier = Modifier.height(PactSpacing.sm))
                HorizontalDivider(color = PactColors.CardBorder, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(PactSpacing.sm))

                // 2. Storage
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Storage Deposit (NEP-145)",
                            style = PactTypography.body,
                            color = PactColors.InkText
                        )
                        Text(
                            text = "Registers account for PACT tokens; refundable if balance is 0",
                            style = PactTypography.caption,
                            color = PactColors.MutedText
                        )
                    }
                    Text(
                        text = "0.00125 NEAR",
                        style = PactTypography.caption,
                        color = PactColors.InkText
                    )
                }

                Spacer(modifier = Modifier.height(PactSpacing.sm))
                HorizontalDivider(color = PactColors.CardBorder, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(PactSpacing.sm))

                // 3. Network Fees
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Estimated Network Gas",
                            style = PactTypography.body,
                            color = PactColors.InkText
                        )
                        Text(
                            text = "Paid to testnet validators; never refunded",
                            style = PactTypography.caption,
                            color = PactColors.MutedText
                        )
                    }
                    Text(
                        text = "~0.008 NEAR",
                        style = PactTypography.caption,
                        color = PactColors.MutedText
                    )
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.sm))

            // Balance indicator & Insufficient balance warning
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PactSpacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your balance: %.3f NEAR".format(nearBalance),
                    style = PactTypography.caption.copy(fontWeight = FontWeight.Medium),
                    color = if (hasEnoughBalance) PactColors.InkText else PactColors.ErrorRed
                )
                if (!hasEnoughBalance) {
                    Text(
                        text = "Min $minRequiredNear NEAR required",
                        style = PactTypography.caption,
                        color = PactColors.ErrorRed
                    )
                }
            }

            if (!hasEnoughBalance) {
                Spacer(modifier = Modifier.height(PactSpacing.xs))
                PactCard(
                    backgroundColor = PactColors.SurfaceWhite,
                    borderColor = PactColors.ErrorRed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Insufficient NEAR Balance",
                        style = PactTypography.title.copy(fontSize = 14.sp),
                        color = PactColors.ErrorRed
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Your account needs at least $minRequiredNear NEAR to cover the refundable stake, storage, and gas. Request funds from NEAR testnet faucet or transfer to your account.",
                        style = PactTypography.caption,
                        color = PactColors.MutedText,
                        softWrap = true
                    )
                }
            }

            // Error display
            if (joinError != null) {
                Spacer(modifier = Modifier.height(PactSpacing.sm))
                PactCard(
                    backgroundColor = PactColors.SurfaceWhite,
                    borderColor = PactColors.AccentOrange,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = joinError,
                        style = PactTypography.caption,
                        color = PactColors.AccentOrange,
                        softWrap = true
                    )
                }
            }

            // In-flight progress status
            if (isJoining && joinStepText != null) {
                Spacer(modifier = Modifier.height(PactSpacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = PactColors.PrimaryBlue
                    )
                    Spacer(modifier = Modifier.width(PactSpacing.sm))
                    Text(
                        text = joinStepText,
                        style = PactTypography.caption.copy(fontWeight = FontWeight.Medium),
                        color = PactColors.PrimaryBlue
                    )
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.lg))

            // Action button
            if (hasStarted) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PactSpacing.buttonHeight),
                    shape = PactShapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PactColors.CardBackground,
                        contentColor = PactColors.MutedText
                    )
                ) {
                    Text(
                        text = "Enrollment Closed",
                        style = PactTypography.title.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    )
                }
            } else if (isEnrolled) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PactSpacing.buttonHeight),
                    shape = PactShapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PactColors.SuccessGreen,
                        contentColor = androidx.compose.ui.graphics.Color.White
                    )
                ) {
                    Text(
                        text = "Already Enrolled ✓",
                        style = PactTypography.title.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    )
                }
            } else {
                PrimaryButton(
                    text = if (isJoining) "Joining..." else "Deposit $stakeDisplay & Join",
                    onClick = onJoin,
                    enabled = hasEnoughBalance && !isJoining && !hasStarted,
                    loading = isJoining
                )
            }

            Spacer(modifier = Modifier.height(PactSpacing.sm))
        }
    }
}

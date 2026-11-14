package com.opaque.healthsynctester.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.near.NearContractClient.ChallengeOutcome
import com.opaque.healthsynctester.pact.ChallengePhase
import com.opaque.healthsynctester.pact.FreshnessState
import com.opaque.healthsynctester.pact.PactLifecycleManager
import com.opaque.healthsynctester.ui.components.*
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactShapes
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography
import java.text.NumberFormat
import java.util.Calendar
import com.opaque.healthsynctester.domain.ProfileScope
import com.opaque.healthsynctester.ui.common.DebugOverlay
import java.util.Locale

@Composable
fun TodayScreen(
    uiState: TodayUiState,
    accountId: String,
    onRefresh: () -> Unit,
    onOpenJoin: () -> Unit,
    onOpenResult: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onClaimReward: () -> Unit = {},
    onCreateSandboxChallenge: () -> Unit = {},
    isCreatingSandbox: Boolean = false,
    sandboxStatusMessage: String? = null,
    showDebugOverlay: Boolean = false,
    onToggleDebugOverlay: () -> Unit = {},
    debugScope: ProfileScope? = null,
    inFlightRpcCount: Int = 0,
    cooldownUntilMs: Long = 0L,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val numberFormat = remember { NumberFormat.getNumberInstance(Locale.US) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PactColors.BackgroundCream)
            .statusBarsPadding()
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PactSpacing.md, vertical = PactSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PactLogoMark(size = 36.dp)
                Spacer(modifier = Modifier.width(PactSpacing.sm))
                Column {
                    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                    val greeting = when (hour) {
                        in 5..11 -> "Good morning"
                        in 12..16 -> "Good afternoon"
                        else -> "Good evening"
                    }
                    val cleanName = accountId.removeSuffix(".testnet").replaceFirstChar { it.uppercase() }
                    Text(
                        text = "$greeting, $cleanName",
                        style = PactTypography.caption.copy(fontSize = 12.sp),
                        color = PactColors.MutedText,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Today",
                        style = PactTypography.heroNumber.copy(fontSize = 26.sp),
                        color = PactColors.InkText,
                        modifier = Modifier.clickable { onToggleDebugOverlay() }
                    )
                }
            }

            // Profile Tag (Top Right)
            val isDemo = (uiState as? TodayUiState.Content)?.isDemoMode == true
            val isSandbox = (uiState as? TodayUiState.Content)?.isSandboxMode == true
            StatusChip(
                text = when {
                    isSandbox -> "Judge Sandbox"
                    isDemo -> "Demo Mode"
                    else -> "Production"
                },
                backgroundColor = when {
                    isSandbox -> PactColors.SuccessGreen.copy(alpha = 0.15f)
                    isDemo -> PactColors.AccentOrange.copy(alpha = 0.15f)
                    else -> PactColors.SurfaceWhite
                },
                textColor = when {
                    isSandbox -> PactColors.SuccessGreen
                    isDemo -> PactColors.AccentOrange
                    else -> PactColors.InkText
                },
                modifier = Modifier.clickable { onOpenProfile() }
            )
        }

        // Optional Debug Overlay
        if (showDebugOverlay && debugScope != null) {
            DebugOverlay(
                scope = debugScope,
                inFlightRpcCount = inFlightRpcCount,
                cooldownUntilMs = cooldownUntilMs,
                onDismiss = onToggleDebugOverlay
            )
        }

        // Freshness Indicator & Sync status bar
        val content = uiState as? TodayUiState.Content
        if (content != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PactSpacing.md, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stale data labeling
                val (freshBg, freshFg) = when (content.freshness) {
                    FreshnessState.LIVE -> PactColors.SuccessGreen.copy(alpha = 0.1f) to PactColors.SuccessGreen
                    FreshnessState.UPDATING -> PactColors.PrimaryBlue.copy(alpha = 0.1f) to PactColors.PrimaryBlue
                    FreshnessState.OFFLINE -> PactColors.CardBackground to PactColors.MutedText
                }
                StatusChip(
                    text = content.freshness.label,
                    backgroundColor = freshBg,
                    textColor = freshFg
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onRefresh() }
                ) {
                    Text(
                        text = content.syncMessage ?: content.lastSyncText,
                        style = PactTypography.caption.copy(fontSize = 11.sp),
                        color = PactColors.MutedText
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Sync",
                        tint = PactColors.MutedText,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // Main Content Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (uiState) {
                is TodayUiState.Loading -> {
                    LoadingState(message = "Verifying live pact on-chain...")
                }

                is TodayUiState.Error -> {
                    ErrorState(
                        message = uiState.message,
                        onRetry = onRefresh
                    )
                }

                is TodayUiState.Content -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = PactSpacing.md)
                            .verticalScroll(scrollState),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(PactSpacing.xs))

                        val hasChallenge = uiState.contractChallenge != null
                        val target = uiState.contractChallenge?.stepsPerDay ?: 0L
                        val isEnrolled = uiState.memberStatus?.isEnrolled == true
                        val totalSegments = 60
                        val qualifiedRatio = if (target > 0) (uiState.todaySteps.toFloat() / target).coerceIn(0f, 1f) else 0f
                        val userQualifiedSegments = (totalSegments * qualifiedRatio).toInt().coerceIn(0, totalSegments)
                        val isGoalAchieved = hasChallenge && target > 0 && uiState.todaySteps >= target

                        if (uiState.isSandboxMode) {
                            PactCard(
                                backgroundColor = PactColors.SuccessGreen.copy(alpha = 0.06f),
                                borderColor = PactColors.SuccessGreen.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = PactSpacing.xs)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "Judge Sandbox",
                                                style = PactTypography.title.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                                                color = PactColors.SuccessGreen
                                            )
                                            Spacer(modifier = Modifier.width(PactSpacing.xs))
                                            StatusChip(
                                                text = "SELF-SERVICE",
                                                backgroundColor = PactColors.SuccessGreen.copy(alpha = 0.15f),
                                                textColor = PactColors.SuccessGreen
                                            )
                                        }
                                        StatusChip(
                                            text = uiState.phase.name,
                                            backgroundColor = PactColors.SurfaceWhite,
                                            textColor = PactColors.InkText
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Target: 10 steps · 5 min duration · 180s grace · 0.01 NEAR stake",
                                        style = PactTypography.caption.copy(fontSize = 12.sp),
                                        color = PactColors.MutedText
                                    )

                                    if (sandboxStatusMessage != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = sandboxStatusMessage,
                                            style = PactTypography.caption.copy(color = PactColors.SuccessGreen, fontWeight = FontWeight.SemiBold)
                                        )
                                    }

                                    val canStartChallenge = !hasChallenge ||
                                        uiState.phase == ChallengePhase.SETTLED ||
                                        uiState.phase == ChallengePhase.DONE ||
                                        uiState.phase == ChallengePhase.VOID_EARLY ||
                                        (uiState.contractChallenge != null && System.currentTimeMillis() * 1_000_000L >= uiState.contractChallenge.endNs)
                                    if (canStartChallenge) {
                                        Spacer(modifier = Modifier.height(PactSpacing.sm))
                                        PrimaryButton(
                                            text = if (isCreatingSandbox) "Creating Challenge on NEAR..." else "Start 5-Min Challenge (10 Steps)",
                                            onClick = onCreateSandboxChallenge,
                                            enabled = !isCreatingSandbox,
                                            loading = isCreatingSandbox,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.height(PactSpacing.xs))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Active ID: ${uiState.contractChallenge?.challengeId}",
                                                style = PactTypography.caption.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp),
                                                color = PactColors.MutedText
                                            )
                                            Text(
                                                text = "${PactLifecycleManager.formatRemainingTime(uiState.timeRemainingSecs)} remaining",
                                                style = PactTypography.caption.copy(fontWeight = FontWeight.Bold, color = PactColors.PrimaryBlue)
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(PactSpacing.xs))
                        }

                        // Compute pure today ring mode state according to Rule E
                        val nowTimeNs = System.currentTimeMillis() * 1_000_000L
                        val ringModeState = PactLifecycleManager.todayMode(
                            challenge = uiState.contractChallenge,
                            myStatus = uiState.memberStatus,
                            todaySteps = uiState.todaySteps,
                            nowChainNs = nowTimeNs
                        )

                        // 1. Hero Progress Ring or Early Void Card
                        if (uiState.phase == ChallengePhase.VOID_EARLY) {
                            val ch = uiState.contractChallenge
                            val enrolledCount = ch?.enrolledCount ?: 0L
                            val minMembers = uiState.minMembers
                            val isEnrolled = uiState.memberStatus?.isEnrolled == true
                            val graceEndSecs = ((ch?.endNs ?: 0L) / 1_000_000_000L) + uiState.submissionGraceSecs
                            val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).apply { timeZone = java.util.TimeZone.getDefault() }
                            val formattedTime = format.format(java.util.Date(graceEndSecs * 1000L))

                            PactCard(
                                backgroundColor = PactColors.AccentOrange.copy(alpha = 0.08f),
                                borderColor = PactColors.AccentOrange.copy(alpha = 0.3f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = PactSpacing.sm)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(PactSpacing.md)
                                ) {
                                    Text(
                                        text = if (enrolledCount == 0L) "Void: no members enrolled" else "Void: not enough members ($enrolledCount of $minMembers enrolled)",
                                        style = PactTypography.title.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                                        color = PactColors.AccentOrange,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(PactSpacing.xs))
                                    Text(
                                        text = if (isEnrolled) {
                                            "Enrollment closed below minimum members requirement.\nYour stake of ${uiState.stakeNear} NEAR is returned in full at $formattedTime (${PactLifecycleManager.formatRemainingTime(uiState.timeRemainingSecs)} remaining).\nSettlement and withdrawal will open automatically after the window closes."
                                        } else {
                                            "Enrollment closed below the minimum requirement of $minMembers members ($enrolledCount enrolled).\nThis challenge is void and settlement will finalize at $formattedTime."
                                        },
                                        style = PactTypography.body.copy(fontSize = 14.sp),
                                        color = PactColors.InkText,
                                        textAlign = TextAlign.Center
                                    )
                                    if (!isEnrolled && uiState.upcomingChallenge != null) {
                                        Spacer(modifier = Modifier.height(PactSpacing.sm))
                                        PrimaryButton(
                                            text = "View Upcoming Challenge (${uiState.upcomingChallenge.challengeId})",
                                            onClick = onOpenJoin,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        } else {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = PactSpacing.sm)
                            ) {
                                PactRing(
                                    totalSegments = totalSegments,
                                    qualifiedCount = if (ringModeState.showTargetRing) userQualifiedSegments else 0,
                                    userSegmentIndex = if (!ringModeState.showTargetRing || isGoalAchieved) null else userQualifiedSegments,
                                    ringSize = 250.dp,
                                    centerContent = {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = numberFormat.format(uiState.todaySteps),
                                                style = PactTypography.heroNumber.copy(fontSize = 40.sp),
                                                color = PactColors.InkText
                                            )
                                            Text(
                                                text = if (ringModeState.showTargetRing) "of ${numberFormat.format(target)} steps" else "steps today",
                                                style = PactTypography.caption,
                                                color = PactColors.MutedText
                                            )
                                            if (ringModeState.showTargetRing && isGoalAchieved) {
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "You qualified",
                                                    style = PactTypography.caption.copy(color = PactColors.PrimaryBlue, fontWeight = FontWeight.Bold)
                                                )
                                            }
                                        }
                                    }
                                )
                            }

                            // Subtitle under ring driven strictly by pure ringModeState
                            Text(
                                text = ringModeState.primaryText,
                                style = PactTypography.title.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                                color = PactColors.InkText,
                                textAlign = TextAlign.Center
                            )

                            if (ringModeState.showTargetRing) {
                                val enrolledCount = (uiState.contractChallenge?.enrolledCount?.toInt() ?: uiState.minMembers.toInt()).coerceAtLeast(1)
                                val qualifiers = uiState.contractChallenge?.qualifierCount?.toInt() ?: 0
                                val quorum = uiState.quorumPct
                                val needed = ((enrolledCount * quorum + 99) / 100).coerceAtLeast(1)
                                Text(
                                    text = "$qualifiers of $enrolledCount qualified. $needed hits the $quorum% goal.",
                                    style = PactTypography.caption,
                                    color = PactColors.MutedText,
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                Text(
                                    text = ringModeState.subtitleText,
                                    style = PactTypography.caption,
                                    color = PactColors.MutedText,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(PactSpacing.sm))

                        // 2. Verified Privately Card
                        PactCard(
                            backgroundColor = PactColors.SurfaceWhite,
                            borderColor = PactColors.PrimaryBlue.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Verified by PACT",
                                    style = PactTypography.body.copy(fontWeight = FontWeight.Bold, color = PactColors.PrimaryBlue)
                                )
                                Text(
                                    text = "Only pass/fail goes on-chain",
                                    style = PactTypography.caption,
                                    color = PactColors.MutedText
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            val daysCompleted = uiState.memberVerdict?.daysCompleted ?: (if (isGoalAchieved) 1 else 0)
                            val daysRequired = uiState.contractChallenge?.daysRequired ?: 1
                            Text(
                                text = "Today counts toward your pact. Day $daysCompleted of $daysRequired complete.",
                                style = PactTypography.caption,
                                color = PactColors.InkText
                            )
                        }

                        Spacer(modifier = Modifier.height(PactSpacing.sm))

                        // 3. Compact Wallet Balance Summary on Today
                        val balances = uiState.walletBalances
                        if (balances != null) {
                            PactCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(text = "Available NEAR", style = PactTypography.caption, color = PactColors.MutedText)
                                        Text(text = balances.availableNearDisplay, style = PactTypography.title.copy(fontSize = 15.sp), color = PactColors.InkText)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(text = "Staked NEAR", style = PactTypography.caption, color = PactColors.MutedText)
                                        Text(text = balances.stakedNearDisplay, style = PactTypography.title.copy(fontSize = 15.sp), color = PactColors.InkText)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(text = "Claimable PACT", style = PactTypography.caption, color = PactColors.MutedText)
                                        Text(text = balances.claimablePactDisplay, style = PactTypography.title.copy(fontSize = 15.sp, color = PactColors.PrimaryBlue))
                                    }
                                }

                                if (balances.claimablePactYocto > java.math.BigInteger.ZERO) {
                                    Spacer(modifier = Modifier.height(PactSpacing.xs))
                                    PrimaryButton(
                                        text = "Claim ${balances.claimablePactDisplay}",
                                        onClick = onClaimReward,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(PactSpacing.sm))
                        }

                        // 4. Lifecycle Phase Card
                        val ch = uiState.contractChallenge
                        if (ch != null) {
                            PactCard(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onOpenResult
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = ch.challengeId,
                                            style = PactTypography.title.copy(fontSize = 17.sp),
                                            color = PactColors.InkText
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = when (uiState.phase) {
                                                ChallengePhase.UPCOMING -> "Starts in ${PactLifecycleManager.formatRemainingTime(uiState.timeRemainingSecs)}"
                                                ChallengePhase.LIVE -> "Ends in ${PactLifecycleManager.formatRemainingTime(uiState.timeRemainingSecs)}"
                                                ChallengePhase.VOID_EARLY -> "Void · < ${uiState.minMembers} Enrolled"
                                                ChallengePhase.SUBMITTING -> "Submission window open · ${PactLifecycleManager.formatRemainingTime(uiState.timeRemainingSecs)} left"
                                                ChallengePhase.GRACE -> "Grace countdown · Settlement in ${PactLifecycleManager.formatRemainingTime(uiState.timeRemainingSecs)}"
                                                ChallengePhase.SETTLING -> "Settling challenge on-chain..."
                                                ChallengePhase.SETTLED -> when (ch.outcome) {
                                                    is ChallengeOutcome.Success -> "Success · Quorum Reached!"
                                                    is ChallengeOutcome.Failure -> "Failure · Quorum Not Met"
                                                    is ChallengeOutcome.Void -> "Void · < ${uiState.minMembers} Enrolled"
                                                    else -> "Settled"
                                                }
                                                ChallengePhase.DONE -> "Past Challenge Completed"
                                            },
                                            style = PactTypography.body.copy(fontWeight = FontWeight.SemiBold),
                                            color = when (uiState.phase) {
                                                ChallengePhase.SETTLED -> if (ch.outcome is ChallengeOutcome.Success) PactColors.SuccessGreen else PactColors.AccentOrange
                                                ChallengePhase.VOID_EARLY -> PactColors.AccentOrange
                                                ChallengePhase.SUBMITTING, ChallengePhase.GRACE -> PactColors.PrimaryBlue
                                                else -> PactColors.InkText
                                            }
                                        )
                                    }

                                    StatusChip(
                                        text = if (isEnrolled) "Enrolled ✓" else "Not Joined",
                                        backgroundColor = if (isEnrolled) PactColors.SuccessGreen.copy(alpha = 0.15f) else PactColors.CardBackground,
                                        textColor = if (isEnrolled) PactColors.SuccessGreen else PactColors.MutedText
                                    )
                                }

                                if (uiState.autoSubmitStatusText != null) {
                                    Spacer(modifier = Modifier.height(PactSpacing.xs))
                                    Text(
                                        text = uiState.autoSubmitStatusText,
                                        style = PactTypography.caption.copy(color = PactColors.PrimaryBlue, fontWeight = FontWeight.SemiBold)
                                    )
                                }

                                if (uiState.phase == ChallengePhase.GRACE) {
                                    Spacer(modifier = Modifier.height(PactSpacing.xs))
                                    Text(
                                        text = "Keep PACT open until settlement.",
                                        style = PactTypography.caption.copy(color = PactColors.AccentOrange, fontWeight = FontWeight.Bold)
                                    )
                                }

                                Spacer(modifier = Modifier.height(PactSpacing.sm))

                                // Actions on Card
                                when {
                                    !isEnrolled && uiState.phase == ChallengePhase.UPCOMING -> {
                                        PrimaryButton(
                                            text = "Join Challenge (${uiState.stakeNear} NEAR Stake)",
                                            onClick = onOpenJoin
                                        )
                                    }
                                    isEnrolled && (uiState.phase == ChallengePhase.SETTLED || uiState.phase == ChallengePhase.DONE) -> {
                                        PrimaryButton(
                                            text = "View Result & Actions",
                                            onClick = onOpenResult
                                        )
                                    }
                                    else -> {
                                        SecondaryButton(
                                            text = "View Details",
                                            onClick = onOpenResult
                                        )
                                    }
                                }
                            }
                        } else {
                            PactCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "No active challenge",
                                    style = PactTypography.title,
                                    color = PactColors.InkText
                                )
                                Spacer(modifier = Modifier.height(PactSpacing.xs))
                                Text(
                                    text = "Check the Challenges tab to view open and upcoming events.",
                                    style = PactTypography.body,
                                    color = PactColors.MutedText
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(PactSpacing.xxl))
                    }
                }
            }
        }
    }
}

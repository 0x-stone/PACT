package com.opaque.healthsynctester.ui.challenges

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.near.NearContractClient.ChallengeOutcome
import com.opaque.healthsynctester.near.NearContractClient.ContractChallenge
import com.opaque.healthsynctester.near.NearContractClient.MemberContractStatus
import com.opaque.healthsynctester.pact.PactLifecycleManager
import com.opaque.healthsynctester.pact.PactStateStore
import com.opaque.healthsynctester.ui.components.PactCard
import com.opaque.healthsynctester.ui.components.PrimaryButton
import com.opaque.healthsynctester.ui.components.StatusChip
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactShapes
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography
import com.opaque.healthsynctester.profiles.PactProfiles
import java.text.NumberFormat
import java.util.Locale

enum class ChallengeSectionFilter(val label: String) {
    ALL("All"),
    AVAILABLE("Available to join"),
    JOINED_IN_PROGRESS("Joined"),
    AWAITING_SETTLEMENT("Awaiting settlement"),
    NEEDS_CLAIM_WITHDRAW("Needs claim or withdraw"),
    COMPLETED("Completed")
}

@Composable
fun ChallengesScreen(
    challenges: List<ContractChallenge>,
    enrolledChallengeIds: Set<String>,
    challengeMemberStatuses: Map<String, MemberContractStatus> = emptyMap(),
    isCached: Boolean = false,
    nowChainNs: Long,
    quorumPct: Int = 70,
    stakeNear: String = "0.5",
    minMembers: Long = 5L,
    activeProfile: String = "real",
    onJoinChallenge: (ContractChallenge) -> Unit,
    onOpenResult: (ContractChallenge) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf(ChallengeSectionFilter.ALL) }
    var selectedChallenge by remember { mutableStateOf<ContractChallenge?>(null) }
    val numberFormat = remember { NumberFormat.getNumberInstance(Locale.US) }

    if (selectedChallenge != null) {
        val ch = selectedChallenge!!
        val isEnrolled = enrolledChallengeIds.contains(ch.challengeId) ||
                challengeMemberStatuses[ch.challengeId]?.isEnrolled == true
        val status = challengeMemberStatuses[ch.challengeId]
        ChallengeDetailView(
            challenge = ch,
            isEnrolled = isEnrolled,
            memberStatus = status,
            nowChainNs = nowChainNs,
            quorumPct = quorumPct,
            stakeNear = stakeNear,
            minMembers = minMembers,
            onBack = { selectedChallenge = null },
            onJoin = {
                selectedChallenge = null
                onJoinChallenge(ch)
            },
            onOpenResult = {
                selectedChallenge = null
                onOpenResult(ch)
            }
        )
        return
    }

    val activeContractId = try { PactProfiles.get(activeProfile).contractId } catch (_: Exception) { "" }
    val validChallenges = challenges.filter { ch ->
        activeContractId.isEmpty() || PactStateStore.isChallengeValidForContract(ch.challengeId, activeContractId)
    }

    // Partition challenges strictly per section requirements ensuring NO challenge is dropped:
    val (joined, unjoined) = validChallenges.partition { ch ->
        enrolledChallengeIds.contains(ch.challengeId) ||
                challengeMemberStatuses[ch.challengeId]?.isEnrolled == true
    }

    // 1. Available to join: Not joined, pending outcome, before start
    val availableToJoin = unjoined.filter { ch ->
        ch.outcome is ChallengeOutcome.Pending && nowChainNs < ch.startNs
    }.sortedBy { it.startNs }

    // 2. Joined (in progress): Joined, pending outcome, before end
    val joinedInProgress = joined.filter { ch ->
        ch.outcome is ChallengeOutcome.Pending && nowChainNs < ch.endNs
    }.sortedBy { it.endNs }

    // 3. Other in progress: Unjoined, pending outcome, between start and end (spectator / live)
    val otherInProgress = unjoined.filter { ch ->
        ch.outcome is ChallengeOutcome.Pending && nowChainNs in ch.startNs until ch.endNs
    }.sortedBy { it.endNs }

    // 4. Awaiting settlement: Pending outcome, after end (grace or settling) - includes joined & unjoined
    val awaitingSettlementAll = validChallenges.filter { ch ->
        ch.outcome is ChallengeOutcome.Pending && nowChainNs >= ch.endNs
    }.sortedByDescending { it.endNs }

    // 5. Needs claim or withdraw: Joined, settled outcome, with unwithdrawn stake or unclaimed reward
    val needsClaimOrWithdraw = joined.filter { ch ->
        if (ch.outcome is ChallengeOutcome.Pending) false
        else {
            val st = challengeMemberStatuses[ch.challengeId]
            if (st != null) {
                val needsWithdraw = !st.hasWithdrawnStake
                val needsClaim = ch.outcome is ChallengeOutcome.Success && st.isQualifier && !st.hasClaimedReward
                needsWithdraw || needsClaim
            } else true // Default visible until status fetched
        }
    }.sortedByDescending { it.endNs }

    // 6. Past / Completed: Any settled challenge that doesn't need claim or withdraw
    val pastSettledChallenges = validChallenges.filter { ch ->
        if (ch.outcome is ChallengeOutcome.Pending) false
        else !needsClaimOrWithdraw.contains(ch)
    }.sortedByDescending { it.endNs }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PactColors.BackgroundCream)
            .statusBarsPadding()
            .padding(horizontal = PactSpacing.md)
    ) {
        Spacer(modifier = Modifier.height(PactSpacing.sm))

        // Title, Cached indicator & Profile chip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Challenges",
                style = PactTypography.heroNumber.copy(fontSize = 28.sp),
                color = PactColors.InkText
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCached) {
                    Box(
                        modifier = Modifier
                            .background(PactColors.AccentOrange.copy(alpha = 0.15f), shape = PactShapes.chip)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Cached",
                            style = PactTypography.caption.copy(fontWeight = FontWeight.Bold),
                            color = PactColors.AccentOrange
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                val prof = PactProfiles.get(activeProfile)
                StatusChip(
                    text = prof.label,
                    backgroundColor = prof.colour.copy(alpha = 0.15f),
                    textColor = prof.colour
                )
            }
        }

        Spacer(modifier = Modifier.height(PactSpacing.sm))

        // Filter chips row (horizontally scrollable)
        val chipScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(chipScroll),
            horizontalArrangement = Arrangement.spacedBy(PactSpacing.xs)
        ) {
            ChallengeSectionFilter.values().forEach { filter ->
                val isSelected = filter == selectedFilter
                val bg = if (isSelected) PactColors.InkText else PactColors.SurfaceWhite
                val textColor = if (isSelected) PactColors.SurfaceWhite else PactColors.InkText

                val count = when (filter) {
                    ChallengeSectionFilter.ALL -> challenges.size
                    ChallengeSectionFilter.AVAILABLE -> availableToJoin.size
                    ChallengeSectionFilter.JOINED_IN_PROGRESS -> joinedInProgress.size
                    ChallengeSectionFilter.AWAITING_SETTLEMENT -> awaitingSettlementAll.size
                    ChallengeSectionFilter.NEEDS_CLAIM_WITHDRAW -> needsClaimOrWithdraw.size
                    ChallengeSectionFilter.COMPLETED -> pastSettledChallenges.size
                }

                Box(
                    modifier = Modifier
                        .clickable { selectedFilter = filter }
                        .background(bg, shape = PactShapes.chip)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${filter.label} ($count)",
                        style = PactTypography.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = textColor
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(PactSpacing.md))

        if (challenges.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isCached) "Cached state: no challenges found." else "No challenges found on-chain.",
                    style = PactTypography.body,
                    color = PactColors.MutedText
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(PactSpacing.sm),
                contentPadding = PaddingValues(bottom = PactSpacing.lg)
            ) {
                fun renderSection(
                    title: String,
                    items: List<ContractChallenge>,
                    emptyText: String,
                    highlightHeader: Boolean = false
                ) {
                    if (items.isNotEmpty() || selectedFilter != ChallengeSectionFilter.ALL) {
                        item {
                            Spacer(modifier = Modifier.height(PactSpacing.xs))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = title.uppercase(),
                                    style = PactTypography.caption.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    ),
                                    color = if (highlightHeader) PactColors.AccentOrange else PactColors.MutedText
                                )
                                Text(
                                    text = "${items.size}",
                                    style = PactTypography.caption.copy(fontWeight = FontWeight.Bold),
                                    color = if (highlightHeader) PactColors.AccentOrange else PactColors.MutedText
                                )
                            }
                        }
                        if (items.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = PactSpacing.md),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = emptyText, style = PactTypography.body, color = PactColors.MutedText)
                                }
                            }
                        } else {
                            items(items, key = { "${it.challengeId}__$activeProfile" }) { ch ->
                                ChallengeCard(
                                    challenge = ch,
                                    memberStatus = challengeMemberStatuses[ch.challengeId],
                                    nowChainNs = nowChainNs,
                                    numberFormat = numberFormat,
                                    quorumPct = quorumPct,
                                    stakeNear = stakeNear,
                                    onClick = { selectedChallenge = ch }
                                )
                            }
                        }
                    }
                }

                when (selectedFilter) {
                    ChallengeSectionFilter.ALL -> {
                        // 1. Needs claim or withdraw (prominent at top)
                        if (needsClaimOrWithdraw.isNotEmpty()) {
                            renderSection("Needs claim or withdraw", needsClaimOrWithdraw, "", highlightHeader = true)
                        }
                        // 2. Joined (in progress)
                        if (joinedInProgress.isNotEmpty()) {
                            renderSection("Joined (in progress)", joinedInProgress, "")
                        }
                        // 3. Live challenges (spectator / unjoined in progress)
                        if (otherInProgress.isNotEmpty()) {
                            renderSection("Live challenges", otherInProgress, "")
                        }
                        // 4. Available to join
                        if (availableToJoin.isNotEmpty()) {
                            renderSection("Available to join", availableToJoin, "")
                        }
                        // 5. Awaiting settlement (all)
                        if (awaitingSettlementAll.isNotEmpty()) {
                            renderSection("Awaiting settlement", awaitingSettlementAll, "")
                        }
                        // 6. Past / Completed challenges (all settled)
                        if (pastSettledChallenges.isNotEmpty()) {
                            renderSection("Past challenges", pastSettledChallenges, "")
                        }
                    }
                    ChallengeSectionFilter.AVAILABLE -> {
                        renderSection("Available to join", availableToJoin, "No upcoming challenges open to join.")
                    }
                    ChallengeSectionFilter.JOINED_IN_PROGRESS -> {
                        renderSection("Joined (in progress)", joinedInProgress, "No joined challenges currently in progress.")
                    }
                    ChallengeSectionFilter.AWAITING_SETTLEMENT -> {
                        renderSection("Awaiting settlement", awaitingSettlementAll, "No challenges currently in grace or settling.")
                    }
                    ChallengeSectionFilter.NEEDS_CLAIM_WITHDRAW -> {
                        renderSection("Needs claim or withdraw", needsClaimOrWithdraw, "No rewards or stakes waiting to be claimed.", highlightHeader = true)
                    }
                    ChallengeSectionFilter.COMPLETED -> {
                        renderSection("Completed / Past", pastSettledChallenges, "No completed challenges recorded.")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChallengeCard(
    challenge: ContractChallenge,
    memberStatus: MemberContractStatus?,
    nowChainNs: Long,
    numberFormat: NumberFormat,
    quorumPct: Int = 70,
    stakeNear: String = "0.5",
    onClick: () -> Unit
) {
    val isEnrolled = memberStatus?.isEnrolled == true
    val isEnded = nowChainNs >= challenge.endNs
    val isLive = challenge.startNs <= nowChainNs && nowChainNs < challenge.endNs

    val (statusLabel, statusBg, statusTextColor) = when {
        challenge.outcome !is ChallengeOutcome.Pending -> {
            if (isEnrolled) {
                val needsWithdraw = memberStatus != null && !memberStatus.hasWithdrawnStake
                val needsClaim = PactLifecycleManager.canClaimReward(challenge, memberStatus)
                when {
                    needsClaim && needsWithdraw -> Triple("Claim & Withdraw", PactColors.AccentOrange.copy(alpha = 0.15f), PactColors.AccentOrange)
                    needsClaim -> Triple("Claim PACT", PactColors.PrimaryBlue.copy(alpha = 0.15f), PactColors.PrimaryBlue)
                    needsWithdraw -> Triple("Withdraw Stake", PactColors.PrimaryBlue.copy(alpha = 0.15f), PactColors.PrimaryBlue)
                    else -> Triple("Completed ✓", PactColors.CardBackground, PactColors.MutedText)
                }
            } else {
                Triple("Settled", PactColors.CardBackground, PactColors.MutedText)
            }
        }
        isEnded -> {
            Triple("Awaiting Settlement", PactColors.AccentOrange.copy(alpha = 0.12f), PactColors.AccentOrange)
        }
        isLive -> {
            Triple("Live Now", PactColors.PrimaryBlue.copy(alpha = 0.1f), PactColors.PrimaryBlue)
        }
        else -> {
            val secsUntil = ((challenge.startNs - nowChainNs) / 1_000_000_000L).coerceAtLeast(0L)
            Triple("Starts in ${PactLifecycleManager.formatRemainingTime(secsUntil)}", PactColors.CardBackground, PactColors.InkText)
        }
    }

    PactCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusChip(
                text = statusLabel,
                backgroundColor = statusBg,
                textColor = statusTextColor
            )
            Text(
                text = if (isEnrolled && memberStatus?.isQualifier == true) "Qualified ✓ · ${challenge.enrolledCount} joined"
                else "${challenge.enrolledCount} joined",
                style = PactTypography.caption,
                color = PactColors.MutedText
            )
        }

        Spacer(modifier = Modifier.height(PactSpacing.xs))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = challenge.challengeId,
                    style = PactTypography.title.copy(fontSize = 17.sp),
                    color = PactColors.InkText
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${numberFormat.format(challenge.stepsPerDay)} steps on ${challenge.daysRequired} day${if (challenge.daysRequired > 1) "s" else ""} · $quorumPct% goal",
                    style = PactTypography.caption,
                    color = PactColors.MutedText
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$stakeNear NEAR stake",
                    style = PactTypography.body.copy(fontWeight = FontWeight.SemiBold),
                    color = PactColors.InkText
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Open",
                tint = PactColors.MutedText
            )
        }
    }
}

@Composable
fun ChallengeDetailView(
    challenge: ContractChallenge,
    isEnrolled: Boolean,
    memberStatus: MemberContractStatus?,
    nowChainNs: Long,
    quorumPct: Int = 70,
    stakeNear: String = "0.5",
    minMembers: Long = 5L,
    onBack: () -> Unit,
    onJoin: () -> Unit,
    onOpenResult: () -> Unit,
    modifier: Modifier = Modifier
) {
    val numberFormat = remember { NumberFormat.getNumberInstance(Locale.US) }
    val isEnded = nowChainNs >= challenge.endNs
    val isUpcoming = nowChainNs < challenge.startNs
    val neededQualifiers = (challenge.enrolledCount * quorumPct / 100).coerceAtLeast(1)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PactColors.BackgroundCream)
            .statusBarsPadding()
            .padding(horizontal = PactSpacing.md)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(PactSpacing.sm))

        // Top bar with back button & tag
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = PactColors.InkText
                )
            }
            StatusChip(
                text = if (challenge.daysRequired > 1) "Weekly" else "Daily",
                backgroundColor = PactColors.SurfaceWhite,
                textColor = PactColors.InkText
            )
        }

        Spacer(modifier = Modifier.height(PactSpacing.sm))

        Text(
            text = challenge.challengeId,
            style = PactTypography.heroNumber.copy(fontSize = 26.sp),
            color = PactColors.InkText
        )
        Text(
            text = "${numberFormat.format(challenge.stepsPerDay)} steps on ${challenge.daysRequired} day${if (challenge.daysRequired > 1) "s" else ""}",
            style = PactTypography.caption,
            color = PactColors.MutedText
        )

        Spacer(modifier = Modifier.height(PactSpacing.md))

        // HOW IT WORKS card
        PactCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "HOW IT WORKS",
                style = PactTypography.caption.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = PactColors.MutedText
            )
            Spacer(modifier = Modifier.height(PactSpacing.xs))
            Text(
                text = "If $quorumPct% of the community qualifies, everyone gets their stake back plus a share of token rewards. If not, stakes are still returned in full and tokens are burned from the reserve.",
                style = PactTypography.body,
                color = PactColors.InkText
            )
            Spacer(modifier = Modifier.height(PactSpacing.xs))
            Text(
                text = "Rules verified on-chain",
                style = PactTypography.caption.copy(fontWeight = FontWeight.SemiBold),
                color = PactColors.PrimaryBlue
            )
        }

        Spacer(modifier = Modifier.height(PactSpacing.sm))

        // COMMUNITY GOAL card
        PactCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "COMMUNITY GOAL",
                    style = PactTypography.caption.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                    color = PactColors.MutedText
                )
                Text(
                    text = "${challenge.enrolledCount} joined",
                    style = PactTypography.caption,
                    color = PactColors.MutedText
                )
            }
            Spacer(modifier = Modifier.height(PactSpacing.xs))
            Text(
                text = "Needs $neededQualifiers to qualify ($quorumPct% goal)",
                style = PactTypography.title.copy(fontSize = 18.sp),
                color = PactColors.InkText
            )
            Spacer(modifier = Modifier.height(PactSpacing.xs))
            val ratio = if (challenge.enrolledCount > 0) {
                (challenge.qualifierCount.toFloat() / challenge.enrolledCount).coerceIn(0f, 1f)
            } else 0f
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = PactColors.PrimaryBlue,
                trackColor = PactColors.RingInactive
            )
        }

        Spacer(modifier = Modifier.height(PactSpacing.sm))

        // Breakdown table card
        PactCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Your stake", style = PactTypography.caption, color = PactColors.MutedText)
                Text(text = "$stakeNear NEAR (refundable)", style = PactTypography.body.copy(fontWeight = FontWeight.SemiBold), color = PactColors.InkText)
            }
            Spacer(modifier = Modifier.height(PactSpacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Submission window", style = PactTypography.caption, color = PactColors.MutedText)
                Text(text = if (challenge.daysRequired > 1) "Sunday 23:59 UTC" else "Challenge End", style = PactTypography.body.copy(fontWeight = FontWeight.SemiBold), color = PactColors.InkText)
            }
            Spacer(modifier = Modifier.height(PactSpacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Shared with community", style = PactTypography.caption, color = PactColors.MutedText)
                Text(text = "Pass or fail only", style = PactTypography.body.copy(fontWeight = FontWeight.SemiBold), color = PactColors.InkText)
            }
        }

        Spacer(modifier = Modifier.height(PactSpacing.lg))

        // Action Button
        when {
            isEnrolled -> {
                val buttonText = when {
                    challenge.outcome !is ChallengeOutcome.Pending -> {
                        val needsWithdraw = memberStatus != null && !memberStatus.hasWithdrawnStake
                        val needsClaim = PactLifecycleManager.canClaimReward(challenge, memberStatus)
                        when {
                            needsClaim && needsWithdraw -> "Claim Reward & Withdraw Stake"
                            needsClaim -> "Claim PACT Reward"
                            needsWithdraw -> "Withdraw $stakeNear NEAR Stake"
                            else -> "View Completed Pact"
                        }
                    }
                    isEnded -> "View Settlement Status"
                    else -> "You're Enrolled ✓"
                }
                PrimaryButton(
                    text = buttonText,
                    onClick = {
                        if (isEnded || challenge.outcome !is ChallengeOutcome.Pending) {
                            onOpenResult()
                        }
                    },
                    enabled = isEnded || challenge.outcome !is ChallengeOutcome.Pending
                )
            }
            isUpcoming -> {
                PrimaryButton(
                    text = "Stake $stakeNear NEAR and Join",
                    onClick = onJoin
                )
            }
            else -> {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PactSpacing.buttonHeight),
                    shape = PactShapes.button,
                    colors = ButtonDefaults.buttonColors(disabledContainerColor = PactColors.CardBackground)
                ) {
                    Text(text = "Enrollment Closed", color = PactColors.MutedText)
                }
            }
        }

        Spacer(modifier = Modifier.height(PactSpacing.xxl))
    }
}

package com.opaque.healthsynctester.ui.community

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.near.NearContractClient.ContractChallenge
import com.opaque.healthsynctester.pact.CommunitySummaryDto
import com.opaque.healthsynctester.ui.components.PactCard
import com.opaque.healthsynctester.ui.components.PactLogoMark
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography

import com.opaque.healthsynctester.profiles.PactProfiles
import com.opaque.healthsynctester.ui.components.StatusChip

@Composable
fun CommunityScreen(
    challenge: ContractChallenge?,
    communitySummary: CommunitySummaryDto?,
    daoTreasuryNearDisplay: String,
    quorumPct: Int = 70,
    mintPact: Int = 100,
    burnPact: Int = 50,
    daoName: String = "Sputnik DAO (pactdao.testnet)",
    activeProfile: String = "real",
    modifier: Modifier = Modifier
) {
    val enrolled = (communitySummary?.enrolled ?: challenge?.enrolledCount?.toInt() ?: 1).coerceAtLeast(1)
    val qualifiers = challenge?.qualifierCount?.toInt() ?: 0
    val onTrackPct = communitySummary?.onTrackPct ?: (if (enrolled > 0) (qualifiers * 100 / enrolled) else 0)
    val targetPct = quorumPct

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PactColors.BackgroundCream)
            .statusBarsPadding()
            .padding(horizontal = PactSpacing.md)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(PactSpacing.sm))

        // Community Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                PactLogoMark(size = 40.dp)
                Spacer(modifier = Modifier.width(PactSpacing.sm))
                Column {
                    Text(
                        text = "PACT DAO Community",
                        style = PactTypography.title.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        color = PactColors.InkText
                    )
                    Text(
                        text = "$enrolled members enrolled",
                        style = PactTypography.caption,
                        color = PactColors.MutedText
                    )
                }
            }
            val prof = PactProfiles.get(activeProfile)
            StatusChip(
                text = prof.label,
                backgroundColor = prof.colour.copy(alpha = 0.15f),
                textColor = prof.colour
            )
        }

        Spacer(modifier = Modifier.height(PactSpacing.md))

        // 1. COLLECTIVE PROGRESS card
        PactCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "COLLECTIVE PROGRESS",
                    style = PactTypography.caption.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                    color = PactColors.MutedText
                )
                Text(
                    text = "$quorumPct% Quorum Goal",
                    style = PactTypography.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = PactColors.PrimaryBlue
                )
            }

            Spacer(modifier = Modifier.height(PactSpacing.xs))

            Text(
                text = "$qualifiers of $enrolled qualified",
                style = PactTypography.heroNumber.copy(fontSize = 26.sp),
                color = PactColors.InkText
            )
            Text(
                text = "$onTrackPct% of community currently on track",
                style = PactTypography.caption,
                color = PactColors.MutedText
            )

            Spacer(modifier = Modifier.height(PactSpacing.sm))

            val ratio = (onTrackPct / 100f).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = if (onTrackPct >= targetPct) PactColors.SuccessGreen else PactColors.PrimaryBlue,
                trackColor = PactColors.RingInactive
            )
        }

        Spacer(modifier = Modifier.height(PactSpacing.sm))

        // 2. TREASURY & REWARDS card
        PactCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "TREASURY & REWARDS",
                style = PactTypography.caption.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = PactColors.MutedText
            )
            Spacer(modifier = Modifier.height(PactSpacing.xs))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "DAO Treasury",
                        style = PactTypography.caption,
                        color = PactColors.MutedText
                    )
                    Text(
                        text = daoTreasuryNearDisplay,
                        style = PactTypography.title.copy(fontSize = 18.sp),
                        color = PactColors.InkText
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Reward Pool",
                        style = PactTypography.caption,
                        color = PactColors.MutedText
                    )
                    Text(
                        text = "$mintPact.00 PACT",
                        style = PactTypography.title.copy(fontSize = 18.sp),
                        color = PactColors.PrimaryBlue
                    )
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.xs))
            Text(
                text = "If community hits $quorumPct% goal, $mintPact PACT is minted to qualifiers. If not, $burnPact PACT is burned from the reserve.",
                style = PactTypography.caption,
                color = PactColors.MutedText
            )
        }

        Spacer(modifier = Modifier.height(PactSpacing.sm))

        // 3. DAO GOVERNANCE card (Read-Only)
        PactCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "DAO GOVERNANCE",
                style = PactTypography.caption.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = PactColors.MutedText
            )
            Spacer(modifier = Modifier.height(PactSpacing.xs))
            Text(
                text = daoName,
                style = PactTypography.body.copy(fontWeight = FontWeight.SemiBold),
                color = PactColors.InkText
            )
            Text(
                text = "Council-governed policy. Challenges and parameter updates are proposed and voted on-chain by the council.",
                style = PactTypography.caption,
                color = PactColors.MutedText
            )
        }

        Spacer(modifier = Modifier.height(PactSpacing.xxl))
    }
}

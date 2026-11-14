package com.opaque.healthsynctester.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.near.NearContractClient
import com.opaque.healthsynctester.profiles.PactProfile
import com.opaque.healthsynctester.profiles.PactProfiles
import com.opaque.healthsynctester.profiles.RequestLogEntry
import com.opaque.healthsynctester.profiles.RequestLogTracker
import com.opaque.healthsynctester.ui.components.PactCard
import com.opaque.healthsynctester.ui.components.StatusChip
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactShapes
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(
    activeProfileId: String,
    contractClient: NearContractClient,
    backendUrl: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val profile = PactProfiles.get(activeProfileId)

    var liveMinMembers by remember { mutableStateOf<Long?>(null) }
    var liveQuorumPct by remember { mutableStateOf<Int?>(null) }
    var liveStakeNear by remember { mutableStateOf<String?>(null) }
    var liveGraceSecs by remember { mutableStateOf<Long?>(null) }
    var isLoadingViews by remember { mutableStateOf(false) }
    var viewsError by remember { mutableStateOf<String?>(null) }

    fun queryLiveViews() {
        isLoadingViews = true
        viewsError = null
        coroutineScope.launch {
            try {
                liveMinMembers = contractClient.getMinMembers()
                liveQuorumPct = contractClient.getQuorumPct()
                val stakeYocto = contractClient.getStakeYocto()
                liveStakeNear = if (stakeYocto != null) {
                    try {
                        java.math.BigDecimal(stakeYocto)
                            .divide(java.math.BigDecimal("1000000000000000000000000"))
                            .stripTrailingZeros()
                            .toPlainString() + " NEAR"
                    } catch (_: Exception) {
                        "$stakeYocto yocto"
                    }
                } else null
                liveGraceSecs = contractClient.getSubmissionGraceSecs()
            } catch (e: Exception) {
                viewsError = e.message ?: "Failed to read contract views"
            } finally {
                isLoadingViews = false
            }
        }
    }

    LaunchedEffect(activeProfileId) {
        queryLiveViews()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PactColors.BackgroundCream,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PactSpacing.md)
                .padding(bottom = PactSpacing.xl)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "System Diagnostics",
                        style = PactTypography.title.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        color = PactColors.InkText
                    )
                    Spacer(modifier = Modifier.width(PactSpacing.xs))
                    StatusChip(
                        text = profile.label,
                        backgroundColor = profile.colour.copy(alpha = 0.15f),
                        textColor = profile.colour
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = PactColors.InkText
                    )
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.sm))

            // Profile & Contract metadata card
            PactCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "ACTIVE PROFILE CONFIGURATION",
                    style = PactTypography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = PactColors.MutedText
                )
                Spacer(modifier = Modifier.height(PactSpacing.xs))
                DiagRow("Profile ID", profile.id)
                DiagRow("Contract Account", profile.contractId, mono = true)
                DiagRow("DAO / Relayer", profile.daoId, mono = true)
                DiagRow("Backend URL", backendUrl)
                DiagRow("Explorer Base", profile.explorerBase)
            }

            Spacer(modifier = Modifier.height(PactSpacing.sm))

            // LIVE On-Chain Contract Views
            PactCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LIVE ON-CHAIN CONTRACT VIEWS",
                        style = PactTypography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = PactColors.MutedText
                    )
                    IconButton(
                        onClick = { queryLiveViews() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh views",
                            tint = PactColors.PrimaryBlue,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(PactSpacing.xs))

                if (isLoadingViews) {
                    Text(
                        text = "Reading views from ${profile.contractId}...",
                        style = PactTypography.caption,
                        color = PactColors.MutedText
                    )
                } else if (viewsError != null) {
                    Text(
                        text = "Error reading on-chain views: $viewsError",
                        style = PactTypography.caption,
                        color = PactColors.ErrorRed
                    )
                } else {
                    DiagRow("get_min_members", "${liveMinMembers ?: "N/A"}")
                    DiagRow("get_quorum_pct", "${liveQuorumPct ?: "N/A"}%")
                    DiagRow("get_stake_yocto", "${liveStakeNear ?: "N/A"}")
                    DiagRow("get_submission_grace_secs", "${liveGraceSecs ?: "N/A"} s")
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.sm))

            // Network Request History (last 20 requests)
            PactCard(modifier = Modifier.fillMaxWidth()) {
                val reqs = RequestLogTracker.logs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RECENT REQUESTS (${reqs.size}/20)",
                        style = PactTypography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = PactColors.MutedText
                    )
                    TextButton(onClick = { RequestLogTracker.clear() }) {
                        Text(text = "Clear", style = PactTypography.caption, color = PactColors.PrimaryBlue)
                    }
                }
                Spacer(modifier = Modifier.height(PactSpacing.xs))

                if (reqs.isEmpty()) {
                    Text(
                        text = "No outbound network requests recorded yet.",
                        style = PactTypography.caption,
                        color = PactColors.MutedText
                    )
                } else {
                    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        reqs.take(10).forEach { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = entry.method,
                                            style = PactTypography.caption.copy(fontWeight = FontWeight.Bold),
                                            color = PactColors.InkText
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = entry.path,
                                            style = PactTypography.caption.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                            color = PactColors.InkText,
                                            maxLines = 1
                                        )
                                    }
                                    Text(
                                        text = "${dateFormat.format(Date(entry.timestampMs))} · Profile: ${entry.profileHeader ?: "none"}",
                                        style = PactTypography.caption.copy(fontSize = 10.sp),
                                        color = PactColors.MutedText
                                    )
                                }
                                val statusColor = if (entry.statusCode in 200..299) PactColors.SuccessGreen else PactColors.ErrorRed
                                Text(
                                    text = "${entry.statusCode}",
                                    style = PactTypography.caption.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                                    color = statusColor
                                )
                            }
                            HorizontalDivider(color = PactColors.CardBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = PactTypography.caption.copy(color = PactColors.MutedText)
        )
        Text(
            text = value,
            style = PactTypography.caption.copy(
                fontWeight = FontWeight.SemiBold,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                color = PactColors.InkText
            )
        )
    }
}

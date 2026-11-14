package com.opaque.healthsynctester.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.HealthConnectManager
import com.opaque.healthsynctester.OriginAuditItem
import com.opaque.healthsynctester.ui.components.PactCard
import com.opaque.healthsynctester.ui.components.StatusChip
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography

/**
 * Debug-only Origin Audit Screen (Stage C).
 *
 * Inspects all real Health Connect Step records from the last 7 days:
 * - Lists distinct dataOrigin and recordingMethod pairs
 * - States whether each passes the backend allowlist & method filter
 * - Identifies SPN suffix and verifies persistence across reinstalls.
 */
@Composable
fun OriginAuditScreen(
    auditItems: List<OriginAuditItem>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PactColors.BackgroundCream)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = PactSpacing.lg)
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = PactSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .offset(x = (-12).dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = PactColors.InkText
                )
            }
            Text(
                text = "Health Connect Origin Audit",
                style = PactTypography.title,
                color = PactColors.InkText,
                modifier = Modifier.weight(1f),
                softWrap = true
            )
        }

        Spacer(modifier = Modifier.height(PactSpacing.xs))

        // Explanatory Info Card
        PactCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = PactColors.SurfaceWhite
        ) {
            Text(
                text = "Backend Eligibility Audit",
                style = PactTypography.title,
                color = PactColors.InkText
            )
            Spacer(modifier = Modifier.height(PactSpacing.xs))
            Text(
                text = "Health Connect dataOrigin represents the sensor-owning package. PACT admits Google Play services, Google Fit, Samsung Health, Xiaomi, and device SPNs starting with '${HealthConnectManager.SPN_PREFIX}'.",
                style = PactTypography.caption,
                color = PactColors.MutedText,
                lineHeight = 18.sp,
                softWrap = true
            )
        }

        Spacer(modifier = Modifier.height(PactSpacing.md))

        if (auditItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No step records found in Health Connect for the last 7 days.",
                    style = PactTypography.body,
                    color = PactColors.MutedText,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(PactSpacing.sm)
            ) {
                items(auditItems) { item ->
                    PactCard(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = if (item.passesAllowlist) PactColors.PrimaryBlue else PactColors.AccentOrange
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (item.passesAllowlist) "PASSES FILTER" else "DROPPED BY BACKEND",
                                style = PactTypography.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                color = if (item.passesAllowlist) PactColors.PrimaryBlue else PactColors.AccentOrange,
                                modifier = Modifier.weight(1f, fill = false),
                                softWrap = true
                            )
                            Spacer(modifier = Modifier.width(PactSpacing.xs))
                            StatusChip(
                                text = "${item.recordCount} records",
                                backgroundColor = PactColors.BackgroundCream,
                                textColor = PactColors.InkText
                            )
                        }

                        Spacer(modifier = Modifier.height(PactSpacing.xs))

                        Text(
                            text = item.dataOrigin,
                            style = PactTypography.caption.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            ),
                            color = PactColors.InkText,
                            fontSize = 12.sp,
                            softWrap = true
                        )

                        Spacer(modifier = Modifier.height(PactSpacing.xs))

                        val methodLabel = when (item.recordingMethod) {
                            1 -> "1: ACTIVELY_RECORDED"
                            2 -> "2: AUTOMATICALLY_RECORDED"
                            3 -> "3: MANUAL_ENTRY (Dropped)"
                            else -> "0: UNKNOWN (Dropped)"
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Method: $methodLabel",
                                style = PactTypography.caption,
                                color = PactColors.MutedText,
                                modifier = Modifier.weight(1f),
                                softWrap = true
                            )
                            Spacer(modifier = Modifier.width(PactSpacing.xs))
                            Text(
                                text = "Total: ${item.totalSteps} steps",
                                style = PactTypography.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                                color = PactColors.InkText
                            )
                        }

                        if (item.dataOrigin.startsWith(HealthConnectManager.SPN_PREFIX)) {
                            val suffix = item.dataOrigin.removePrefix(HealthConnectManager.SPN_PREFIX)
                            Spacer(modifier = Modifier.height(PactSpacing.xs))
                            Text(
                                text = "SPN Suffix: $suffix",
                                style = PactTypography.caption.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                ),
                                color = PactColors.PrimaryBlue,
                                fontSize = 11.sp,
                                softWrap = true
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "(Compare across reinstalls to verify persistent attribution)",
                                style = PactTypography.caption,
                                color = PactColors.MutedText,
                                fontSize = 11.sp,
                                softWrap = true
                            )
                        } else if (!item.passesAllowlist) {
                            Spacer(modifier = Modifier.height(PactSpacing.xs))
                            Text(
                                text = "Reason: ${item.reason}",
                                style = PactTypography.caption,
                                color = PactColors.AccentOrange,
                                fontSize = 11.sp,
                                softWrap = true
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(PactSpacing.md))
    }
}

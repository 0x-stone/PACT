package com.opaque.healthsynctester.ui.me

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.pact.AppTxRecord
import com.opaque.healthsynctester.pact.WalletBalances
import com.opaque.healthsynctester.ui.components.PactCard
import com.opaque.healthsynctester.ui.components.PrimaryButton
import com.opaque.healthsynctester.ui.components.SecondaryButton
import com.opaque.healthsynctester.ui.components.StatusChip
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactShapes
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import com.opaque.healthsynctester.profiles.PactProfiles

@Composable
fun MeScreen(
    accountId: String,
    walletBalances: WalletBalances?,
    isDemoMode: Boolean,
    activeProfile: String = if (isDemoMode) "demo" else "real",
    txHistory: List<AppTxRecord>,
    onToggleDemoMode: () -> Unit,
    onOpenProfileSheet: () -> Unit = onToggleDemoMode,
    onOpenOriginAudit: () -> Unit,
    onOpenKeyBackup: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showReceiveDialog by remember { mutableStateOf(false) }

    if (showReceiveDialog) {
        AlertDialog(
            onDismissRequest = { showReceiveDialog = false },
            title = {
                Text(
                    text = "Receive NEAR",
                    style = PactTypography.title,
                    color = PactColors.InkText
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.QrCode,
                        contentDescription = "QR Code",
                        modifier = Modifier.size(72.dp),
                        tint = PactColors.PrimaryBlue
                    )
                    Spacer(modifier = Modifier.height(PactSpacing.sm))
                    Text(
                        text = "Share your NEAR account ID to receive funds on NEAR Testnet:",
                        style = PactTypography.body,
                        color = PactColors.InkText
                    )
                    Spacer(modifier = Modifier.height(PactSpacing.xs))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(PactColors.CardBackground, shape = PactShapes.card)
                            .padding(PactSpacing.sm)
                    ) {
                        Text(
                            text = accountId,
                            style = PactTypography.body.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                            color = PactColors.InkText
                        )
                    }
                }
            },
            confirmButton = {
                PrimaryButton(
                    text = "Copy Account ID",
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("NEAR Account ID", accountId))
                        Toast.makeText(context, "Account ID copied to clipboard", Toast.LENGTH_SHORT).show()
                        showReceiveDialog = false
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showReceiveDialog = false }) {
                    Text(text = "Close", color = PactColors.MutedText)
                }
            },
            containerColor = PactColors.SurfaceWhite,
            shape = PactShapes.card
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PactColors.BackgroundCream)
            .statusBarsPadding()
            .padding(horizontal = PactSpacing.md)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(PactSpacing.sm))

        // Profile Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Profile & Wallet",
                style = PactTypography.heroNumber.copy(fontSize = 28.sp),
                color = PactColors.InkText
            )
            val prof = PactProfiles.get(activeProfile)
            StatusChip(
                text = prof.label,
                backgroundColor = prof.colour.copy(alpha = 0.15f),
                textColor = prof.colour,
                modifier = Modifier.clickable { onOpenProfileSheet() }
            )
        }

        Spacer(modifier = Modifier.height(PactSpacing.sm))

        // Account ID card with copy & receive
        PactCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Connected Account",
                        style = PactTypography.caption,
                        color = PactColors.MutedText
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = accountId,
                        style = PactTypography.title.copy(fontSize = 17.sp, fontFamily = FontFamily.Monospace),
                        color = PactColors.InkText
                    )
                }

                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("NEAR Account ID", accountId))
                    Toast.makeText(context, "Account ID copied", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy ID", tint = PactColors.PrimaryBlue)
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.xs))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PactSpacing.xs)
            ) {
                SecondaryButton(
                    text = "Receive Funds",
                    onClick = { showReceiveDialog = true },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(PactSpacing.md))

        // 1. WALLET BALANCES (Separate Real Rows)
        PactCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "WALLET BALANCES",
                style = PactTypography.caption.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = PactColors.MutedText
            )
            Spacer(modifier = Modifier.height(PactSpacing.sm))

            BalanceRow("Available NEAR", walletBalances?.availableNearDisplay ?: "0 NEAR")
            Spacer(modifier = Modifier.height(PactSpacing.xs))
            BalanceRow("NEAR Locked (Storage)", walletBalances?.lockedNearDisplay ?: "0 NEAR")
            Spacer(modifier = Modifier.height(PactSpacing.xs))
            BalanceRow("NEAR Staked in Challenges", walletBalances?.stakedNearDisplay ?: "0 NEAR")
            Spacer(modifier = Modifier.height(PactSpacing.xs))
            HorizontalDivider(color = PactColors.Divider)
            Spacer(modifier = Modifier.height(PactSpacing.xs))
            BalanceRow("PACT Balance", walletBalances?.pactBalanceDisplay ?: "0.00 PACT")
            Spacer(modifier = Modifier.height(PactSpacing.xs))
            BalanceRow("Claimable PACT", walletBalances?.claimablePactDisplay ?: "0.00 PACT", isAccent = true)

            if (walletBalances != null && !walletBalances.isJoinAffordable) {
                Spacer(modifier = Modifier.height(PactSpacing.sm))
                val reqNear = if (activeProfile == "sandbox") "0.01" else "0.5"
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PactColors.CardBackground, shape = PactShapes.card)
                        .padding(PactSpacing.xs)
                ) {
                    Text(
                        text = "Balance low: $reqNear NEAR needed to join challenges.",
                        style = PactTypography.caption,
                        color = PactColors.AccentOrange
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(PactSpacing.md))

        // 2. PROFILE ENVIRONMENT SWITCH (DEMO VS REAL VS SANDBOX)
        PactCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenProfileSheet
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val prof = PactProfiles.get(activeProfile)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = prof.label.uppercase(),
                        style = PactTypography.title.copy(fontSize = 16.sp),
                        color = prof.colour
                    )
                    Text(
                        text = "${prof.contractId} · ${prof.defaultSubmissionGraceSecs}s grace · ${prof.defaultStakeNear} NEAR stake",
                        style = PactTypography.caption,
                        color = PactColors.MutedText
                    )
                }
                StatusChip(
                    text = "SWITCH",
                    backgroundColor = PactColors.CardBackground,
                    textColor = PactColors.PrimaryBlue
                )
            }
        }

        Spacer(modifier = Modifier.height(PactSpacing.md))

        // 3. RECENT TRANSACTIONS (From Real App-Submitted TXs)
        PactCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "RECENT TRANSACTIONS",
                style = PactTypography.caption.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = PactColors.MutedText
            )
            Spacer(modifier = Modifier.height(PactSpacing.xs))

            if (txHistory.isEmpty()) {
                Text(
                    text = "No on-chain transactions submitted yet from this device.",
                    style = PactTypography.caption,
                    color = PactColors.MutedText
                )
            } else {
                val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
                txHistory.take(5).forEach { tx ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val url = "https://testnet.nearblocks.io/txns/${tx.txHash}"
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = tx.type.replace("_", " "),
                                style = PactTypography.body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                                color = PactColors.InkText
                            )
                            Text(
                                text = "${tx.challengeId ?: ""} · ${dateFormat.format(Date(tx.timestampMs))}",
                                style = PactTypography.caption.copy(fontSize = 11.sp),
                                color = PactColors.MutedText
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${tx.txHash.take(6)}...${tx.txHash.takeLast(4)}",
                                style = PactTypography.caption.copy(fontFamily = FontFamily.Monospace),
                                color = PactColors.PrimaryBlue
                            )
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = "View Explorer",
                                tint = PactColors.PrimaryBlue,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(PactSpacing.md))

        // 4. SETTINGS & AUDIT
        PactCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "SECURITY & AUDIT",
                style = PactTypography.caption.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = PactColors.MutedText
            )
            Spacer(modifier = Modifier.height(PactSpacing.xs))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenOriginAudit() }
                    .padding(vertical = PactSpacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Origin Audit (Health Connect)", style = PactTypography.body, color = PactColors.InkText)
                Text(text = "View >", style = PactTypography.caption, color = PactColors.PrimaryBlue)
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenKeyBackup() }
                    .padding(vertical = PactSpacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Backup Recovery Key", style = PactTypography.body, color = PactColors.InkText)
                Text(text = "View >", style = PactTypography.caption, color = PactColors.PrimaryBlue)
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenDiagnostics() }
                    .padding(vertical = PactSpacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "System Diagnostics", style = PactTypography.body, color = PactColors.InkText)
                Text(text = "View >", style = PactTypography.caption, color = PactColors.PrimaryBlue)
            }
        }

        Spacer(modifier = Modifier.height(PactSpacing.md))

        // Sign out
        SecondaryButton(
            text = "Sign Out",
            onClick = onSignOut
        )

        Spacer(modifier = Modifier.height(PactSpacing.xxl))
    }
}

@Composable
private fun BalanceRow(label: String, value: String, isAccent: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = PactTypography.body.copy(fontSize = 14.sp),
            color = PactColors.MutedText
        )
        Text(
            text = value,
            style = PactTypography.body.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = if (isAccent) PactColors.PrimaryBlue else PactColors.InkText
        )
    }
}

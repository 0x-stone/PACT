package com.opaque.healthsynctester.ui.auth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.near.NearSession
import com.opaque.healthsynctester.profiles.PactProfiles
import com.opaque.healthsynctester.ui.components.PactCard
import com.opaque.healthsynctester.ui.components.PactLogoMark
import com.opaque.healthsynctester.ui.components.SecondaryButton
import com.opaque.healthsynctester.ui.components.StatusChip
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactShapes
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography

/**
 * PACT Minimal Profile Sheet.
 *
 * Provides:
 * - Current account ID & public key.
 * - Hardware key backup notice.
 * - Sign-out action (drops session; keeps encrypted key).
 * - Delete local account action (removes key from keystore).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(
    accountId: String,
    publicKey: String,
    session: NearSession?,
    isDemoMode: Boolean = false,
    activeProfile: String = if (isDemoMode) "demo" else "real",
    onSelectProfile: (String) -> Unit = {},
    onToggleDemoMode: (Boolean) -> Unit = {},
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onOpenBackup: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

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
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PactLogoMark(size = 32.dp)
                    Spacer(modifier = Modifier.width(PactSpacing.xs))
                    Text(text = "Profile & Account", style = PactTypography.title)
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.md))

            // Account Info Card
            PactCard(
                backgroundColor = PactColors.SurfaceWhite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = accountId,
                            style = PactTypography.title.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                            color = PactColors.InkText,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(PactSpacing.xs))
                        StatusChip(text = "Testnet")
                    }

                    Spacer(modifier = Modifier.height(PactSpacing.xs))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (publicKey.length > 20) {
                                "${publicKey.take(12)}...${publicKey.takeLast(6)}"
                            } else publicKey,
                            style = PactTypography.caption.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            color = PactColors.MutedText,
                            modifier = Modifier.weight(1f),
                            softWrap = true
                        )
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("NEAR Public Key", publicKey))
                                Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Public Key",
                                tint = PactColors.PrimaryBlue,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (session != null) {
                        Spacer(modifier = Modifier.height(PactSpacing.xs))
                        Text(
                            text = "Authenticated with backend",
                            style = PactTypography.caption.copy(color = PactColors.SuccessGreen)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.md))

            // Deployment Profile selector (REAL / DEMO / SANDBOX)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "DEPLOYMENT PROFILE",
                    style = PactTypography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = PactColors.MutedText
                )
                Spacer(modifier = Modifier.height(PactSpacing.xs))

                PactProfiles.getAvailableProfiles().forEach { prof ->
                    val isSelected = activeProfile == prof.id
                    val activeColor = prof.colour
                    val profDesc = "${prof.contractId} · ${prof.defaultSubmissionGraceSecs}s grace · ${prof.defaultStakeNear} NEAR"

                    PactCard(
                        backgroundColor = if (isSelected) activeColor.copy(alpha = 0.06f) else PactColors.SurfaceWhite,
                        borderColor = if (isSelected) activeColor else PactColors.CardBorder,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        onClick = {
                            onSelectProfile(prof.id)
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = prof.label,
                                        style = PactTypography.title.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                        color = if (isSelected) activeColor else PactColors.InkText
                                    )
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(PactSpacing.xs))
                                        StatusChip(
                                            text = "ACTIVE",
                                            backgroundColor = activeColor.copy(alpha = 0.15f),
                                            textColor = activeColor
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = profDesc,
                                    style = PactTypography.caption.copy(fontSize = 12.sp, lineHeight = 15.sp),
                                    color = PactColors.MutedText
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.md))

            // Back up / Export private key action card
            PactCard(
                backgroundColor = PactColors.SurfaceWhite,
                borderColor = PactColors.AccentOrange,
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenBackup
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Back up / Export private key",
                            style = PactTypography.title.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            color = PactColors.AccentOrange
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Reveal or export your private key using device authentication.",
                            style = PactTypography.caption.copy(lineHeight = 16.sp),
                            color = PactColors.MutedText
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.lg))

            // Actions
            SecondaryButton(
                text = "Sign out",
                onClick = onSignOut
            )

            Spacer(modifier = Modifier.height(PactSpacing.xs))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                TextButton(onClick = { showDeleteConfirmDialog = true }) {
                    Text(
                        text = "Remove account from this device",
                        color = PactColors.ErrorRed,
                        style = PactTypography.caption.copy(fontSize = 13.sp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.sm))
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = {
                Text(text = "Delete local key?", style = PactTypography.title)
            },
            text = {
                Text(
                    text = "This will permanently remove the encrypted key for $accountId from this phone. You will not be able to sign in as $accountId again unless you have a backup of the key.",
                    style = PactTypography.body.copy(fontSize = 14.sp)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteAccount()
                    }
                ) {
                    Text("Delete Key", color = PactColors.ErrorRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = PactColors.InkText)
                }
            },
            shape = PactShapes.card,
            containerColor = PactColors.SurfaceWhite
        )
    }
}

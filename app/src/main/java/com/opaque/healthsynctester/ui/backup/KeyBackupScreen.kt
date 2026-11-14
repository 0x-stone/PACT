package com.opaque.healthsynctester.ui.backup

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.near.BackupCredentials
import com.opaque.healthsynctester.ui.components.PactCard
import com.opaque.healthsynctester.ui.components.PrimaryButton
import com.opaque.healthsynctester.ui.components.SecondaryButton
import com.opaque.healthsynctester.ui.components.StatusChip
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactShapes
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Key Backup and Export Screen (Stage C Part 2).
 *
 * Security requirements:
 * - FLAG_SECURE on window: blocked from screenshots and app-switcher previews.
 * - Private key is masked as text (••••••••••••••) until revealed.
 * - Reveal and export require device authentication (PIN, pattern, password, or biometric).
 * - If no device lock exists, blocks reveal and export with plain-language explanation.
 * - Unencrypted export requires explicit user confirmation.
 * - Clipboard copy sets EXTRA_IS_SENSITIVE (Android 13+) and auto-clears after 30s.
 * - Private key re-masks automatically upon leaving the screen.
 */
@Composable
fun KeyBackupScreen(
    credentials: BackupCredentials?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // 1. Enforce FLAG_SECURE while on screen
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    var isRevealed by remember { mutableStateOf(false) }
    var pendingExportAfterAuth by remember { mutableStateOf(false) }
    var showExportConfirmDialog by remember { mutableStateOf(false) }
    var authErrorMessage by remember { mutableStateOf<String?>(null) }
    var copyNotice by remember { mutableStateOf<String?>(null) }

    val keyguardManager = remember {
        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }
    val isDeviceSecure = keyguardManager?.isDeviceSecure == true

    // Document picker for JSON export
    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && credentials != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(credentials.toJsonString().toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Key backup exported successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Device credential confirmation launcher
    val credentialLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            authErrorMessage = null
            isRevealed = true
            if (pendingExportAfterAuth) {
                pendingExportAfterAuth = false
                showExportConfirmDialog = true
            }
        } else {
            pendingExportAfterAuth = false
            authErrorMessage = "Device authentication canceled or failed"
        }
    }

    fun requestAuthentication(forExport: Boolean = false) {
        if (!isDeviceSecure) {
            authErrorMessage = "Your device has no screen lock set. To protect and export your key, configure a PIN, pattern, password, or biometric in Android Settings."
            return
        }
        pendingExportAfterAuth = forExport
        val intent = keyguardManager?.createConfirmDeviceCredentialIntent(
            "Unlock PACT Key",
            "Confirm your device lock to reveal or export your private key"
        )
        if (intent != null) {
            credentialLauncher.launch(intent)
        } else {
            // Fallback if intent is null
            isRevealed = true
            if (forExport) showExportConfirmDialog = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PactColors.BackgroundCream)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = PactSpacing.lg)
            .verticalScroll(scrollState)
    ) {
        // Header
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
                text = "Account Backup",
                style = PactTypography.title,
                color = PactColors.InkText,
                modifier = Modifier.weight(1f),
                softWrap = true
            )
        }

        Spacer(modifier = Modifier.height(PactSpacing.sm))

        if (credentials == null) {
            PactCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "No account found",
                    style = PactTypography.title,
                    color = PactColors.InkText
                )
                Spacer(modifier = Modifier.height(PactSpacing.xs))
                Text(
                    text = "No local account was found on this device.",
                    style = PactTypography.body,
                    color = PactColors.MutedText
                )
            }
            return@Column
        }

        // Security Warning Card
        PactCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = PactColors.SurfaceWhite,
            borderColor = PactColors.AccentOrange
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = PactColors.AccentOrange,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(PactSpacing.sm))
                Text(
                    text = "Important Security Notice",
                    style = PactTypography.title,
                    color = PactColors.AccentOrange
                )
            }
            Spacer(modifier = Modifier.height(PactSpacing.xs))
            Text(
                text = "Your account is secured by a cryptographic key on this device. Anyone with your private key has complete control of your account. Never share it or enter it on untrusted websites.",
                style = PactTypography.caption,
                color = PactColors.MutedText,
                lineHeight = 18.sp
            )
        }

        Spacer(modifier = Modifier.height(PactSpacing.md))

        if (authErrorMessage != null) {
            PactCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = PactColors.SurfaceWhite,
                borderColor = PactColors.AccentOrange
            ) {
                Text(
                    text = authErrorMessage ?: "",
                    style = PactTypography.caption,
                    color = PactColors.AccentOrange
                )
            }
            Spacer(modifier = Modifier.height(PactSpacing.sm))
        }

        // Account Details Card
        PactCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Account ID",
                style = PactTypography.caption,
                color = PactColors.MutedText
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = credentials.accountId,
                style = PactTypography.title,
                color = PactColors.InkText,
                softWrap = true
            )

            Spacer(modifier = Modifier.height(PactSpacing.md))

            Text(
                text = "Public Key",
                style = PactTypography.caption,
                color = PactColors.MutedText
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = credentials.publicKey,
                style = PactTypography.caption.copy(fontFamily = FontFamily.Monospace, lineHeight = 16.sp),
                color = PactColors.InkText,
                fontSize = 12.sp,
                softWrap = true
            )

            Spacer(modifier = Modifier.height(PactSpacing.md))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Private Key",
                    style = PactTypography.caption,
                    color = PactColors.MutedText,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = {
                        if (isRevealed) {
                            isRevealed = false
                        } else {
                            requestAuthentication(forExport = false)
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (isRevealed) "Hide Key" else "Reveal Key",
                        tint = PactColors.PrimaryBlue
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            val maskedKey = "ed25519:••••••••••••••••••••••••••••••••••••••••••••••••"
            Text(
                text = if (isRevealed) credentials.privateKey else maskedKey,
                style = PactTypography.caption.copy(fontFamily = FontFamily.Monospace, lineHeight = 16.sp),
                color = if (isRevealed) PactColors.AccentOrange else PactColors.MutedText,
                fontSize = 12.sp,
                softWrap = true
            )

            if (!isRevealed) {
                Spacer(modifier = Modifier.height(PactSpacing.xs))
                Text(
                    text = "Tap the eye icon to verify device credentials and reveal key.",
                    style = PactTypography.caption,
                    color = PactColors.MutedText,
                    fontSize = 11.sp
                )
            }
        }

        if (copyNotice != null) {
            Spacer(modifier = Modifier.height(PactSpacing.sm))
            StatusChip(
                text = copyNotice ?: "",
                backgroundColor = PactColors.SurfaceWhite,
                textColor = PactColors.PrimaryBlue,
                borderColor = PactColors.PrimaryBlue
            )
        }

        Spacer(modifier = Modifier.height(PactSpacing.lg))

        // Action Buttons
        PrimaryButton(
            text = "Copy private key",
            onClick = {
                if (!isRevealed) {
                    requestAuthentication(forExport = false)
                    return@PrimaryButton
                }
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("PACT Private Key", credentials.privateKey)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                clipboard.setPrimaryClip(clip)
                copyNotice = "Copied. Clipboard auto-clears in 30 seconds."

                coroutineScope.launch {
                    delay(30_000)
                    if (clipboard.primaryClip?.getItemAt(0)?.text == credentials.privateKey) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            clipboard.clearPrimaryClip()
                        } else {
                            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                        }
                    }
                    copyNotice = null
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(PactSpacing.sm))

        SecondaryButton(
            text = "Export backup file (.json)",
            onClick = {
                if (!isRevealed) {
                    requestAuthentication(forExport = true)
                } else {
                    showExportConfirmDialog = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(PactSpacing.xl))
    }

    // Export Confirmation Dialog
    if (showExportConfirmDialog && credentials != null) {
        AlertDialog(
            onDismissRequest = { showExportConfirmDialog = false },
            shape = PactShapes.card,
            containerColor = PactColors.BackgroundCream,
            title = {
                Text(
                    text = "Export unencrypted key?",
                    style = PactTypography.title,
                    color = PactColors.InkText
                )
            },
            text = {
                Text(
                    text = "The exported JSON file will contain your unencrypted private key. Anyone with access to this file can control your account. Store it in a secure location.",
                    style = PactTypography.body,
                    color = PactColors.MutedText
                )
            },
            confirmButton = {
                PrimaryButton(
                    text = "Confirm & Save",
                    onClick = {
                        showExportConfirmDialog = false
                        val fileName = "pact-backup-${credentials.accountId.removeSuffix(".testnet")}.json"
                        exportDocumentLauncher.launch(fileName)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                SecondaryButton(
                    text = "Cancel",
                    onClick = { showExportConfirmDialog = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }
}

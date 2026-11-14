package com.opaque.healthsynctester.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.ui.components.PactCard
import com.opaque.healthsynctester.ui.components.PactTextField
import com.opaque.healthsynctester.ui.components.PrimaryButton
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography

/**
 * PACT Account Import Screen.
 *
 * Allows users who already hold a NEAR testnet key to import it into
 * the device's hardware keystore. Clearly warns against mainnet key usage.
 */
@Composable
fun ImportAccountScreen(
    onImportSubmit: (accountId: String, privateKey: String) -> Unit,
    onBack: () -> Unit,
    isImporting: Boolean,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    var accountIdInput by remember { mutableStateOf("") }
    var privateKeyInput by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    val isButtonEnabled = accountIdInput.isNotBlank() && privateKeyInput.isNotBlank() && !isImporting

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PactColors.BackgroundCream)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = PactSpacing.lg, vertical = PactSpacing.md)
            .verticalScroll(scrollState)
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

        Spacer(modifier = Modifier.height(PactSpacing.md))

        Text(
            text = "Import account",
            style = PactTypography.headline,
            color = PactColors.InkText
        )

        Spacer(modifier = Modifier.height(PactSpacing.xxs))

        Text(
            text = "Enter your testnet account name and its Ed25519 private key.",
            style = PactTypography.body,
            color = PactColors.MutedText
        )

        Spacer(modifier = Modifier.height(PactSpacing.lg))

        // Security Warning Card
        PactCard(
            backgroundColor = Color(0xFFFFF3CD),
            borderColor = Color(0xFFFFEEBA),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Notice: Only import testnet developer keys. Never import your mainnet private keys or keys holding real funds into this testing app.",
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF856404)
            )
        }

        Spacer(modifier = Modifier.height(PactSpacing.lg))

        PactTextField(
            value = accountIdInput,
            onValueChange = { accountIdInput = it },
            label = "Account ID",
            placeholder = "e.g. alice.testnet",
            suffixText = if (!accountIdInput.contains(".")) ".testnet" else null
        )

        Spacer(modifier = Modifier.height(PactSpacing.md))

        PactTextField(
            value = privateKeyInput,
            onValueChange = { privateKeyInput = it },
            label = "Ed25519 Private Key (base58)",
            placeholder = "ed25519:...",
            singleLine = false
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(PactSpacing.md))
            Text(
                text = errorMessage,
                style = PactTypography.caption,
                color = PactColors.ErrorRed
            )
        }

        Spacer(modifier = Modifier.height(PactSpacing.xl))

        PrimaryButton(
            text = "Import and sign in",
            onClick = { onImportSubmit(accountIdInput, privateKeyInput) },
            enabled = isButtonEnabled,
            loading = isImporting
        )

        Spacer(modifier = Modifier.height(PactSpacing.lg))
    }
}

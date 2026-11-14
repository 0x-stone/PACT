package com.opaque.healthsynctester.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.ui.components.PactCard
import com.opaque.healthsynctester.ui.components.PactLogoMark
import com.opaque.healthsynctester.ui.components.PrimaryButton
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactShapes
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography

/**
 * PACT Welcome Back Screen (Returning User).
 *
 * When an account key is already secured on this device, allows the user to
 * sign in with a single tap via NEP-413 challenge signing.
 */
@Composable
fun WelcomeBackScreen(
    accountId: String,
    isSigningIn: Boolean,
    errorMessage: String?,
    onSignIn: () -> Unit,
    onUseAnotherAccount: () -> Unit,
    onDeleteAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PactColors.BackgroundCream)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = PactSpacing.lg, vertical = PactSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.weight(0.6f))

        // Hero Info
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            PactLogoMark(size = 72.dp)

            Spacer(modifier = Modifier.height(PactSpacing.lg))

            Text(
                text = "Welcome back",
                style = PactTypography.headline,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(PactSpacing.md))

            PactCard(
                backgroundColor = PactColors.SurfaceWhite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = accountId,
                        style = PactTypography.title.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                        color = PactColors.PrimaryBlue
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Key secured in Android Keystore",
                        style = PactTypography.caption
                    )
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(PactSpacing.md))
                Text(
                    text = errorMessage,
                    style = PactTypography.body,
                    color = PactColors.ErrorRed,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Actions
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PrimaryButton(
                text = "Sign in",
                onClick = onSignIn,
                loading = isSigningIn
            )

            Spacer(modifier = Modifier.height(PactSpacing.xs))

            TextButton(
                onClick = onUseAnotherAccount,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text(
                    text = "Sign in with another account",
                    style = PactTypography.body.copy(fontSize = 14.sp),
                    color = PactColors.MutedText
                )
            }

            TextButton(
                onClick = { showDeleteConfirmation = true },
                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
            ) {
                Text(
                    text = "Remove account from this device",
                    style = PactTypography.caption.copy(fontSize = 13.sp),
                    color = PactColors.ErrorRed
                )
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = {
                Text(
                    text = "Remove local account?",
                    style = PactTypography.title
                )
            },
            text = {
                Text(
                    text = "This will permanently delete the encrypted key for $accountId from this device. Because the key is only on this device, the account cannot be recovered unless you previously exported the private key.",
                    style = PactTypography.body.copy(fontSize = 14.sp),
                    color = PactColors.InkText
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDeleteAccount()
                    }
                ) {
                    Text("Remove", color = PactColors.ErrorRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel", color = PactColors.InkText)
                }
            },
            shape = PactShapes.card,
            containerColor = PactColors.SurfaceWhite
        )
    }
}

package com.opaque.healthsynctester.ui.health

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import com.opaque.healthsynctester.HealthConnectAvailability
import com.opaque.healthsynctester.HealthConnectManager
import com.opaque.healthsynctester.ui.components.PactCard
import com.opaque.healthsynctester.ui.components.PactLogoMark
import com.opaque.healthsynctester.ui.components.PrimaryButton
import com.opaque.healthsynctester.ui.components.SecondaryButton
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography

/**
 * Health Connect permissions and onboarding screen (Stage C).
 *
 * Implements accurate privacy wording:
 * "Your step data is synced to PACT to check your challenge.
 *  Raw health records are never published on-chain."
 */
@Composable
fun HealthPermissionScreen(
    healthAvailability: HealthConnectAvailability,
    onPermissionGranted: () -> Unit,
    onSkipLater: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var permissionDeniedOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { grantedPermissions ->
        if (grantedPermissions.contains(HealthConnectManager(context).stepPermission)) {
            onPermissionGranted()
        } else {
            permissionDeniedOnce = true
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PactColors.BackgroundCream)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = PactSpacing.lg, vertical = PactSpacing.xl)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(modifier = Modifier.height(PactSpacing.lg))
            PactLogoMark(size = 72.dp)
            Spacer(modifier = Modifier.height(PactSpacing.xl))

            Text(
                text = "Connect your steps",
                style = PactTypography.headline,
                color = PactColors.InkText,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(PactSpacing.sm))

            Text(
                text = "PACT tracks your daily step count to verify challenge qualifications and keep community pledges honest.",
                style = PactTypography.body,
                color = PactColors.MutedText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = PactSpacing.sm)
            )

            Spacer(modifier = Modifier.height(PactSpacing.xl))

            // Honest privacy callout card
            PactCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Honest Privacy",
                    style = PactTypography.title,
                    color = PactColors.InkText
                )
                Spacer(modifier = Modifier.height(PactSpacing.xs))
                Text(
                    text = "Your step data is synced to PACT to check your challenge. Raw health records are never published on-chain — on-chain settlement receives only a verified pass or fail attestation.",
                    style = PactTypography.caption,
                    color = PactColors.MutedText,
                    lineHeight = 20.sp
                )
            }

            if (permissionDeniedOnce) {
                Spacer(modifier = Modifier.height(PactSpacing.md))
                PactCard(
                    backgroundColor = PactColors.SurfaceWhite,
                    borderColor = PactColors.AccentOrange,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Permission required",
                        style = PactTypography.title,
                        color = PactColors.AccentOrange
                    )
                    Spacer(modifier = Modifier.height(PactSpacing.xs))
                    Text(
                        text = "Physical Activity Steps (StepsRecord) permission is required by PACT to verify challenge steps. Please grant access below or in Android Settings > Apps > Health Connect > Permissions.",
                        style = PactTypography.caption,
                        color = PactColors.MutedText
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (healthAvailability) {
                is HealthConnectAvailability.Available -> {
                    PrimaryButton(
                        text = if (permissionDeniedOnce) "Grant step permission" else "Connect Health Connect",
                        onClick = {
                            permissionLauncher.launch(setOf(HealthConnectManager(context).stepPermission))
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is HealthConnectAvailability.UpdateRequired -> {
                    PrimaryButton(
                        text = "Update Health Connect",
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=com.google.android.apps.healthdata")
                            )
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is HealthConnectAvailability.NotSupported -> {
                    PrimaryButton(
                        text = "Install Health Connect",
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=com.google.android.apps.healthdata")
                            )
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.sm))

            SecondaryButton(
                text = "I'll do this later",
                onClick = onSkipLater,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

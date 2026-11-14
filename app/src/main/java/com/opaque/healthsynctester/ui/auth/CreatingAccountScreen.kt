package com.opaque.healthsynctester.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.near.CreationStep
import com.opaque.healthsynctester.ui.components.PactCard
import com.opaque.healthsynctester.ui.components.PactLogoMark
import com.opaque.healthsynctester.ui.components.PrimaryButton
import com.opaque.healthsynctester.ui.components.SecondaryButton
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactShapes
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography

/**
 * PACT Account Creation Screen with 3 distinct progress steps:
 * 1. "Creating your account" (registering with testnet helper)
 * 2. "Securing your key" (wrapping with AndroidKeyStore AES-256-GCM)
 * 3. "Signing you in" (NEP-413 backend challenge exchange)
 */
@Composable
fun CreatingAccountScreen(
    accountId: String,
    currentStep: CreationStep,
    errorMessage: String?,
    onRetry: () -> Unit,
    onBackToName: () -> Unit,
    modifier: Modifier = Modifier
) {
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
        Spacer(modifier = Modifier.weight(0.5f))

        // Center Content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            PactLogoMark(size = 64.dp)

            Spacer(modifier = Modifier.height(PactSpacing.lg))

            Text(
                text = if (errorMessage != null) "Couldn't finish setup" else "Setting up your account",
                style = PactTypography.headline,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(PactSpacing.xxs))

            Text(
                text = accountId,
                style = PactTypography.title.copy(fontSize = 16.sp),
                color = PactColors.PrimaryBlue,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(PactSpacing.xl))

            // 3 Progress Steps Card
            PactCard(
                backgroundColor = PactColors.SurfaceWhite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(PactSpacing.md)
                ) {
                    StepRow(
                        stepNumber = 1,
                        label = "Creating your account",
                        step = CreationStep.CREATING_ACCOUNT,
                        currentStep = currentStep,
                        hasError = errorMessage != null && currentStep == CreationStep.CREATING_ACCOUNT
                    )

                    StepRow(
                        stepNumber = 2,
                        label = "Securing your key",
                        step = CreationStep.SECURING_KEY,
                        currentStep = currentStep,
                        hasError = errorMessage != null && currentStep == CreationStep.SECURING_KEY
                    )

                    StepRow(
                        stepNumber = 3,
                        label = "Signing you in",
                        step = CreationStep.SIGNING_IN,
                        currentStep = currentStep,
                        hasError = errorMessage != null && currentStep == CreationStep.SIGNING_IN
                    )
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(PactSpacing.md))
                Text(
                    text = errorMessage,
                    style = PactTypography.body,
                    color = PactColors.ErrorRed,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = PactSpacing.sm)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Error actions (if any)
        if (errorMessage != null) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(PactSpacing.xs)
            ) {
                PrimaryButton(
                    text = "Try again",
                    onClick = onRetry
                )
                SecondaryButton(
                    text = "Choose another name",
                    onClick = onBackToName
                )
            }
        } else {
            Spacer(modifier = Modifier.height(PactSpacing.xxl))
        }
    }
}

@Composable
private fun StepRow(
    stepNumber: Int,
    label: String,
    step: CreationStep,
    currentStep: CreationStep,
    hasError: Boolean
) {
    val isCompleted = step.ordinal < currentStep.ordinal
    val isActive = step == currentStep && !hasError

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Step Icon/Indicator
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    color = when {
                        hasError -> PactColors.ErrorRed
                        isCompleted -> PactColors.PrimaryBlue
                        isActive -> PactColors.PrimaryBlue.copy(alpha = 0.12f)
                        else -> PactColors.CardBackground
                    },
                    shape = PactShapes.button
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                isCompleted -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Completed",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
                isActive -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = PactColors.PrimaryBlue
                    )
                }
                hasError -> {
                    Text(
                        text = "!",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                else -> {
                    Text(
                        text = "$stepNumber",
                        color = PactColors.MutedText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(PactSpacing.md))

        Text(
            text = label,
            style = PactTypography.body,
            fontWeight = if (isActive || isCompleted) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isActive || isCompleted) PactColors.InkText else PactColors.MutedText
        )
    }
}

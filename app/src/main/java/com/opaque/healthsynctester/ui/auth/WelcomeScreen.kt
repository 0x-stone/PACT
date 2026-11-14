package com.opaque.healthsynctester.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.ui.components.PactLogoMark
import com.opaque.healthsynctester.ui.components.PrimaryButton
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography

/**
 * PACT Welcome Screen (New User).
 *
 * Clean, calm, uncluttered. Logo, one-line promise, "Get started",
 * and an optional secondary "I already have an account" import action.
 */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onImportAccount: () -> Unit,
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
        Spacer(modifier = Modifier.weight(0.6f))

        // Hero Logo & Promise
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            PactLogoMark(size = 80.dp)

            Spacer(modifier = Modifier.height(PactSpacing.xl))

            Text(
                text = "Build the habit.",
                style = PactTypography.headline,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(PactSpacing.sm))

            Text(
                text = "Daily steps, a community pact, and real stakes on NEAR.",
                style = PactTypography.body,
                color = PactColors.MutedText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = PactSpacing.md)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Actions
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PrimaryButton(
                text = "Get started",
                onClick = onGetStarted
            )

            Spacer(modifier = Modifier.height(PactSpacing.xs))

            TextButton(
                onClick = onImportAccount,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text(
                    text = "I already have an account",
                    style = PactTypography.body.copy(fontSize = 14.sp),
                    color = PactColors.MutedText
                )
            }
        }
    }
}

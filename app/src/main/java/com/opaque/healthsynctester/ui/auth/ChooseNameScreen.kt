package com.opaque.healthsynctester.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.near.AvailabilityResult
import com.opaque.healthsynctester.ui.components.PactTextField
import com.opaque.healthsynctester.ui.components.PrimaryButton
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography

/**
 * PACT Choose Name Screen.
 *
 * Shows username input with ".testnet" suffix, live availability check with
 * debounce, clear formatting rules, and key security disclosure.
 */
@Composable
fun ChooseNameScreen(
    inputName: String,
    onNameChanged: (String) -> Unit,
    isChecking: Boolean,
    availability: AvailabilityResult?,
    onCreateAccount: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAvailable = availability is AvailabilityResult.Available
    val isButtonEnabled = inputName.isNotBlank() && isAvailable && !isChecking

    val errorText = when (availability) {
        is AvailabilityResult.Taken -> availability.message
        is AvailabilityResult.Invalid -> availability.reason
        is AvailabilityResult.Error -> availability.message
        else -> null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PactColors.BackgroundCream)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = PactSpacing.lg, vertical = PactSpacing.md)
    ) {
        // Back Navigation Affordance
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

        // Title & Description
        Text(
            text = "Choose your name",
            style = PactTypography.headline,
            color = PactColors.InkText
        )

        Spacer(modifier = Modifier.height(PactSpacing.xxs))

        Text(
            text = "Your public identity on NEAR testnet.",
            style = PactTypography.body,
            color = PactColors.MutedText
        )

        Spacer(modifier = Modifier.height(PactSpacing.xl))

        // Text Input
        PactTextField(
            value = inputName,
            onValueChange = onNameChanged,
            label = "Username",
            placeholder = "e.g. maxwell",
            suffixText = ".testnet",
            errorMessage = errorText
        )

        // Availability feedback line
        Spacer(modifier = Modifier.height(PactSpacing.xs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PactSpacing.xs)
                .height(24.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            when {
                isChecking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = PactColors.PrimaryBlue
                        )
                        Spacer(modifier = Modifier.width(PactSpacing.xs))
                        Text(
                            text = "Checking availability on testnet...",
                            style = PactTypography.caption
                        )
                    }
                }
                isAvailable -> {
                    Text(
                        text = "✓ $inputName.testnet is available",
                        style = PactTypography.caption.copy(
                            color = PactColors.SuccessGreen,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Key security disclosure
        Text(
            text = "Your key is generated and secured on this device. Losing your device loses access.",
            style = PactTypography.caption.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = PactColors.MutedText,
            modifier = Modifier.padding(bottom = PactSpacing.md)
        )

        // Submit Button
        PrimaryButton(
            text = "Create account",
            onClick = { onCreateAccount(inputName) },
            enabled = isButtonEnabled
        )

        Spacer(modifier = Modifier.height(PactSpacing.md))
    }
}

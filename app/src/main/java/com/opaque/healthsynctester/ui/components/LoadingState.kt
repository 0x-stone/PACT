package com.opaque.healthsynctester.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography

/**
 * PACT Calm Loading State.
 *
 * Centered spinner with clear, plain-language status message.
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String = "Loading..."
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(PactSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(36.dp),
            color = PactColors.PrimaryBlue,
            strokeWidth = 3.dp
        )
        Spacer(modifier = Modifier.height(PactSpacing.md))
        Text(
            text = message,
            style = PactTypography.body,
            color = PactColors.MutedText
        )
    }
}

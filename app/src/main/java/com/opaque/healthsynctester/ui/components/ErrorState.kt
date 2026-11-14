package com.opaque.healthsynctester.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography

/**
 * PACT Calm Error State.
 *
 * Displays a plain-language explanation and a clear retry action.
 * Never displays raw exception traces, error codes, or technical keys.
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "Something went wrong",
    retryLabel: String = "Try again",
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(PactSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = PactTypography.title,
            color = PactColors.InkText,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(PactSpacing.xs))
        Text(
            text = message,
            style = PactTypography.body,
            color = PactColors.MutedText,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(PactSpacing.lg))
            PrimaryButton(
                text = retryLabel,
                onClick = onRetry,
                modifier = Modifier.widthIn(max = 240.dp)
            )
        }
    }
}

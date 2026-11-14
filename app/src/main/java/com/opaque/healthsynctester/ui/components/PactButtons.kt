package com.opaque.healthsynctester.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactShapes
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography

/**
 * PACT Primary Action Button.
 *
 * 52dp height for comfortable touch target (>= 48dp accessibility standard).
 * Calm loading state without layout shift.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(PactSpacing.buttonHeight)
            .semantics { role = Role.Button },
        enabled = enabled && !loading,
        shape = PactShapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = PactColors.PrimaryBlue,
            contentColor = Color.White,
            disabledContainerColor = PactColors.RingInactive,
            disabledContentColor = PactColors.MutedText
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 1.dp
        ),
        contentPadding = PaddingValues(horizontal = PactSpacing.lg, vertical = PactSpacing.sm)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.5.dp
            )
        } else {
            Text(
                text = text,
                style = PactTypography.button,
                color = if (enabled) Color.White else PactColors.MutedText
            )
        }
    }
}

/**
 * PACT Secondary / Tertiary Action Button.
 *
 * Calm subtle surface with border, matching the rounded card aesthetic.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(PactSpacing.buttonHeight)
            .semantics { role = Role.Button },
        enabled = enabled && !loading,
        shape = PactShapes.button,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = PactColors.CardBackground,
            contentColor = PactColors.InkText,
            disabledContainerColor = PactColors.CardBackground.copy(alpha = 0.5f),
            disabledContentColor = PactColors.MutedText
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) PactColors.CardBorder else PactColors.CardBorder.copy(alpha = 0.5f)
        ),
        contentPadding = PaddingValues(horizontal = PactSpacing.lg, vertical = PactSpacing.sm)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = PactColors.InkText,
                strokeWidth = 2.5.dp
            )
        } else {
            Text(
                text = text,
                style = PactTypography.button,
                color = if (enabled) PactColors.InkText else PactColors.MutedText
            )
        }
    }
}

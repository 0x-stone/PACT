package com.opaque.healthsynctester.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactShapes
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography

/**
 * PACT Text Field with inline validation support.
 *
 * Designed with a consistent 16dp corner radius, comfortable padding (min 52dp height),
 * clear focus indicator, and calm error message rendering.
 */
@Composable
fun PactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    errorMessage: String? = null,
    isError: Boolean = errorMessage != null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    suffixText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    var isFocused by remember { mutableStateOf(false) }

    val borderColor = when {
        isError -> PactColors.ErrorRed
        isFocused -> PactColors.PrimaryBlue
        else -> PactColors.CardBorder
    }

    val borderWidth = if (isFocused || isError) 1.5.dp else 1.dp

    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                text = label,
                style = PactTypography.caption,
                color = if (isError) PactColors.ErrorRed else PactColors.MutedText,
                modifier = Modifier.padding(bottom = PactSpacing.xxs)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = PactSpacing.buttonHeight)
                .background(
                    color = if (enabled) PactColors.SurfaceWhite else PactColors.CardBackground,
                    shape = PactShapes.input
                )
                .border(
                    width = borderWidth,
                    color = borderColor,
                    shape = PactShapes.input
                )
                .padding(horizontal = PactSpacing.md, vertical = PactSpacing.sm)
                .onFocusChanged { isFocused = it.isFocused },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = PactTypography.body,
                        color = PactColors.MutedText.copy(alpha = 0.6f)
                    )
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    singleLine = singleLine,
                    textStyle = PactTypography.body.copy(
                        color = if (enabled) PactColors.InkText else PactColors.MutedText
                    ),
                    cursorBrush = SolidColor(PactColors.PrimaryBlue),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    visualTransformation = visualTransformation
                )
            }

            if (suffixText != null) {
                Text(
                    text = suffixText,
                    style = PactTypography.body,
                    color = PactColors.MutedText,
                    modifier = Modifier.padding(start = PactSpacing.xxs)
                )
            }
        }

        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = PactTypography.caption,
                color = PactColors.ErrorRed,
                modifier = Modifier.padding(top = PactSpacing.xxs, start = PactSpacing.xs)
            )
        }
    }
}

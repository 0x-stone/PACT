package com.opaque.healthsynctester.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactShapes
import com.opaque.healthsynctester.ui.theme.PactSpacing

/**
 * PACT Status Chip.
 *
 * Pill shape container matching the "Campus Striders" header badge and status labels.
 */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = PactColors.SurfaceWhite,
    textColor: Color = PactColors.InkText,
    borderColor: Color = PactColors.CardBorder
) {
    Surface(
        modifier = modifier,
        shape = PactShapes.chip,
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.padding(horizontal = PactSpacing.sm, vertical = 6.dp)
        )
    }
}

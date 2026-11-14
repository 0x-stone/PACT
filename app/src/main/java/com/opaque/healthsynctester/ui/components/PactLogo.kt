package com.opaque.healthsynctester.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactSpacing

/**
 * PACT Logo Mark: clean vector rendering of the Logo B two-halves mark.
 *
 * Preserves the exact geometry:
 * - Blue top-left arc (#2338E6)
 * - Orange bottom-right arc (#FF4A1C)
 * - Interlocking rounded stroke caps with graceful gap.
 */
@Composable
fun PactLogoMark(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    blueColor: Color = PactColors.PrimaryBlue,
    orangeColor: Color = PactColors.AccentOrange
) {
    Canvas(
        modifier = modifier
            .size(size)
            .padding(size * 0.08f)
    ) {
        val strokeWidth = this.size.minDimension * 0.17f
        val arcPadding = strokeWidth / 2f
        val arcSize = Size(this.size.width - strokeWidth, this.size.height - strokeWidth)
        val topLeft = Offset(arcPadding, arcPadding)
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

        // Top-left blue arc: sweeps ~155 degrees on the left side
        drawArc(
            color = blueColor,
            startAngle = 104f,
            sweepAngle = 152f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke
        )

        // Bottom-right orange arc: sweeps ~152 degrees on the right side
        drawArc(
            color = orangeColor,
            startAngle = 284f,
            sweepAngle = 152f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke
        )
    }
}

/**
 * PACT Full Brand Lockup: mark + "P A C T" uppercase geometric wordmark.
 */
@Composable
fun PactLogoWordmark(
    modifier: Modifier = Modifier,
    markSize: Dp = 64.dp,
    showWordmark: Boolean = true
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PactLogoMark(size = markSize)
        if (showWordmark) {
            Spacer(modifier = Modifier.height(PactSpacing.sm))
            Text(
                text = "P A C T",
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                letterSpacing = 6.sp,
                color = PactColors.InkText
            )
        }
    }
}

package com.opaque.healthsynctester.ui.components

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * PACT Segmented Ring Component.
 *
 * Scales gracefully from 5 to 100 segments with smooth radial capsules.
 * Supports:
 * - [totalSegments]: 5..100 total capacity
 * - [qualifiedCount]: 0..totalSegments qualified members (rendered in PrimaryBlue)
 * - [userSegmentIndex]: Optional 0-based index for the current user (rendered in AccentOrange)
 * - Smooth 250ms filling motion that respects the system reduce-motion setting.
 * - Center content slot for step count, targets, and user status.
 */
@Composable
fun PactRing(
    totalSegments: Int,
    qualifiedCount: Int,
    modifier: Modifier = Modifier,
    userSegmentIndex: Int? = null,
    ringSize: Dp = 270.dp,
    animate: Boolean = true,
    centerContent: @Composable (BoxScope.() -> Unit)? = null
) {
    val clampedTotal = totalSegments.coerceIn(5, 100)
    val clampedQualified = qualifiedCount.coerceIn(0, clampedTotal)
    val context = LocalContext.current

    // Check system animator duration scale (0.0 = animations disabled / reduce motion)
    val reduceMotion = remember(context) {
        try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            scale == 0f
        } catch (e: Exception) {
            false
        }
    }

    val animationDuration = if (!animate || reduceMotion) 0 else 250

    val animatedQualified by animateFloatAsState(
        targetValue = clampedQualified.toFloat(),
        animationSpec = tween(durationMillis = animationDuration, easing = FastOutSlowInEasing),
        label = "ring_qualified_progress"
    )

    val accessibilityDesc = buildString {
        append("$clampedQualified of $clampedTotal members qualified.")
        if (userSegmentIndex != null) {
            append(" You are segment ${userSegmentIndex + 1}.")
        }
    }

    Box(
        modifier = modifier
            .size(ringSize)
            .semantics { contentDescription = accessibilityDesc },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = min(size.width, size.height) / 2f - 8.dp.toPx()
            val segmentLength = outerRadius * 0.22f
            val innerRadius = outerRadius - segmentLength
            val midRadius = (innerRadius + outerRadius) / 2f

            // Compute dynamic stroke width per segment count
            val circumference = 2f * Math.PI.toFloat() * midRadius
            val segmentPitch = circumference / clampedTotal
            val strokeWidth = (segmentPitch * 0.54f).coerceIn(3.dp.toPx(), segmentLength * 0.42f)

            for (i in 0 until clampedTotal) {
                // Angle starting at top (-90 degrees) moving clockwise
                val angleDeg = -90f + (i * 360f / clampedTotal)
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val cosA = cos(angleRad).toFloat()
                val sinA = sin(angleRad).toFloat()

                val start = Offset(
                    x = center.x + innerRadius * cosA,
                    y = center.y + innerRadius * sinA
                )
                val end = Offset(
                    x = center.x + outerRadius * cosA,
                    y = center.y + outerRadius * sinA
                )

                val segmentColor = when {
                    // User's own segment takes precedence with AccentOrange
                    userSegmentIndex != null && i == userSegmentIndex -> PactColors.AccentOrange
                    // Animated qualified progression
                    i < animatedQualified.toInt() -> PactColors.PrimaryBlue
                    // Fractional transition for the leading qualified segment
                    i == animatedQualified.toInt() && (animatedQualified - i) > 0.05f -> {
                        val fraction = (animatedQualified - i)
                        PactColors.PrimaryBlue.copy(alpha = fraction.coerceIn(0.2f, 1f))
                    }
                    else -> PactColors.RingInactive
                }

                drawLine(
                    color = segmentColor,
                    start = start,
                    end = end,
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
        }

        // Center content overlay
        if (centerContent != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(42.dp),
                contentAlignment = Alignment.Center,
                content = centerContent
            )
        }
    }
}

/**
 * Ready-to-use centered content matching the Today screen design:
 * - Current step count (hero number)
 * - Step target subtitle ("of 8,000 steps")
 * - User segment indicator ("You are segment 42")
 */
@Composable
fun PactRingTodayContent(
    currentSteps: Int,
    targetSteps: Int,
    modifier: Modifier = Modifier,
    userSegmentNumber: Int? = null
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "%,d".format(currentSteps),
            style = PactTypography.heroNumber,
            textAlign = TextAlign.Center
        )
        Text(
            text = "of %,d steps".format(targetSteps),
            style = PactTypography.caption,
            color = PactColors.MutedText,
            textAlign = TextAlign.Center
        )
        if (userSegmentNumber != null) {
            Spacer(modifier = Modifier.height(PactSpacing.xs))
            Text(
                text = "You are segment $userSegmentNumber",
                style = PactTypography.caption.copy(
                    fontWeight = FontWeight.Bold,
                    color = PactColors.AccentOrange,
                    fontSize = 13.sp
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}

package com.opaque.healthsynctester.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * PACT Typography Scale (5 primary sizes).
 *
 * Clean geometric sans matching the design files with high legibility,
 * clear hierarchy, and generous line heights.
 */
object PactTypography {
    val defaultFontFamily = FontFamily.SansSerif

    // 1. Hero / Key Number (e.g., 6,420 steps)
    val heroNumber = TextStyle(
        fontFamily = defaultFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.5).sp,
        color = PactColors.InkText
    )

    // 2. Headline (e.g., "Today", "Welcome")
    val headline = TextStyle(
        fontFamily = defaultFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.2).sp,
        color = PactColors.InkText
    )

    // 3. Section Title / Lead (e.g., "1,580 steps closes the ring", "7-Day Streak Pact")
    val title = TextStyle(
        fontFamily = defaultFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        color = PactColors.InkText
    )

    // 4. Body (e.g., "41 of 60 have qualified", descriptions)
    val body = TextStyle(
        fontFamily = defaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
        color = PactColors.InkText
    )

    // 5. Label / Caption (e.g., "Distance", "Active", "Streak", chip text)
    val caption = TextStyle(
        fontFamily = defaultFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        color = PactColors.MutedText
    )

    // Button label
    val button = TextStyle(
        fontFamily = defaultFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
}

val PactMaterialTypography = Typography(
    displayLarge = PactTypography.heroNumber,
    headlineLarge = PactTypography.headline,
    titleMedium = PactTypography.title,
    bodyLarge = PactTypography.body,
    bodyMedium = PactTypography.body.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = PactTypography.caption
)

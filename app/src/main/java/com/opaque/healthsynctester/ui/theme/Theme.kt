package com.opaque.healthsynctester.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * PACT Material 3 Color Scheme.
 * Light mode first, calm, high-contrast WCAG AA.
 */
private val PactLightColorScheme = lightColorScheme(
    primary = PactColors.PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = PactColors.CardBackground,
    onPrimaryContainer = PactColors.InkText,
    secondary = PactColors.AccentOrange,
    onSecondary = Color.White,
    secondaryContainer = PactColors.CardBackground,
    onSecondaryContainer = PactColors.InkText,
    background = PactColors.BackgroundCream,
    onBackground = PactColors.InkText,
    surface = PactColors.SurfaceWhite,
    onSurface = PactColors.InkText,
    surfaceVariant = PactColors.CardBackground,
    onSurfaceVariant = PactColors.MutedText,
    outline = PactColors.CardBorder,
    error = PactColors.ErrorRed,
    onError = Color.White,
    errorContainer = PactColors.ErrorBackground,
    onErrorContainer = PactColors.ErrorRed
)

@Composable
fun PactTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PactLightColorScheme,
        typography = PactMaterialTypography,
        content = content
    )
}

/** Legacy alias so existing activities continue to compile seamlessly. */
@Composable
fun HealthSyncTesterTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    PactTheme(content = content)
}

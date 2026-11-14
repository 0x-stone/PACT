package com.opaque.healthsynctester.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.ui.components.*
import com.opaque.healthsynctester.ui.theme.*

/**
 * PACT Component Gallery (Debug Only).
 *
 * Demonstrates all design system tokens and components in normal, loading,
 * error, and active states. All numbers and data displayed here are strictly
 * debug fixture data, labeled prominently by the top banner.
 */
@Composable
fun GalleryScreen(
    onSwitchToLegacyTester: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    // Interactive PactRing test bench state
    var totalSegments by remember { mutableStateOf(60) }
    var qualifiedCount by remember { mutableStateOf(41) }
    var userSegmentIndex by remember { mutableStateOf<Int?>(41) } // 42nd segment (0-based 41)

    // Interactive button state
    var buttonClickCount by remember { mutableStateOf(0) }
    var buttonLoading by remember { mutableStateOf(false) }

    // Interactive text field state
    var textInputNormal by remember { mutableStateOf("maxwell") }
    var textInputError by remember { mutableStateOf("alice") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PactColors.BackgroundCream)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(scrollState)
    ) {
        // ---------------------------------------------------------------
        // Top Header & Debug Banner
        // ---------------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFF3CD))
                .border(width = 1.dp, color = Color(0xFFFFEEBA))
                .padding(horizontal = PactSpacing.md, vertical = PactSpacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "DEBUG: Component Gallery (Fixture Data)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF856404)
                )
                TextButton(
                    onClick = onSwitchToLegacyTester,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Switch to Tester",
                        tint = PactColors.PrimaryBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Legacy Tester",
                        fontSize = 11.sp,
                        color = PactColors.PrimaryBlue
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PactSpacing.md)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PactLogoMark(size = 36.dp)
                    Spacer(modifier = Modifier.width(PactSpacing.xs))
                    Column {
                        Text(text = "PACT", style = PactTypography.title)
                        Text(
                            text = "Design System Gallery",
                            style = PactTypography.caption
                        )
                    }
                }
                StatusChip(text = "Stage A · Foundation")
            }

            Spacer(modifier = Modifier.height(PactSpacing.lg))

            // ---------------------------------------------------------------
            // 1. Brand Mark & Wordmark
            // ---------------------------------------------------------------
            SectionCard(title = "1. Logo Mark & Wordmark") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PactLogoMark(size = 32.dp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "32dp", style = PactTypography.caption)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PactLogoMark(size = 48.dp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "48dp", style = PactTypography.caption)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PactLogoMark(size = 64.dp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "64dp", style = PactTypography.caption)
                    }
                }
                Spacer(modifier = Modifier.height(PactSpacing.md))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PactColors.BackgroundCream, shape = PactShapes.card)
                        .padding(PactSpacing.md),
                    contentAlignment = Alignment.Center
                ) {
                    PactLogoWordmark(markSize = 56.dp)
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.lg))

            // ---------------------------------------------------------------
            // 2. PactRing Test Bench (5, 20, 50, 60, 100 segments)
            // ---------------------------------------------------------------
            SectionCard(title = "2. Segmented Ring (PactRing)") {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    PactRing(
                        totalSegments = totalSegments,
                        qualifiedCount = qualifiedCount,
                        userSegmentIndex = userSegmentIndex,
                        ringSize = 260.dp,
                        centerContent = {
                            PactRingTodayContent(
                                currentSteps = 6420,
                                targetSteps = 8000,
                                userSegmentNumber = userSegmentIndex?.let { it + 1 }
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(PactSpacing.md))

                Text(
                    text = "$qualifiedCount of $totalSegments qualified. " +
                        (userSegmentIndex?.let { "You are segment ${it + 1}." } ?: "No user segment."),
                    style = PactTypography.body,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(PactSpacing.sm))

                // Presets
                Text(text = "Quick Presets:", style = PactTypography.caption)
                Spacer(modifier = Modifier.height(PactSpacing.xxs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PactSpacing.xs)
                ) {
                    PresetButton(label = "5 segs") {
                        totalSegments = 5
                        qualifiedCount = 3
                        userSegmentIndex = 3
                    }
                    PresetButton(label = "20 segs") {
                        totalSegments = 20
                        qualifiedCount = 14
                        userSegmentIndex = 14
                    }
                    PresetButton(label = "50 segs") {
                        totalSegments = 50
                        qualifiedCount = 35
                        userSegmentIndex = 35
                    }
                    PresetButton(label = "60 (Design)") {
                        totalSegments = 60
                        qualifiedCount = 41
                        userSegmentIndex = 41
                    }
                    PresetButton(label = "100 segs") {
                        totalSegments = 100
                        qualifiedCount = 70
                        userSegmentIndex = 70
                    }
                }

                Spacer(modifier = Modifier.height(PactSpacing.xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PactSpacing.xs)
                ) {
                    PresetButton(label = "Zero qualified") {
                        qualifiedCount = 0
                    }
                    PresetButton(label = "All qualified") {
                        qualifiedCount = totalSegments
                    }
                    PresetButton(label = if (userSegmentIndex == null) "Highlight user" else "Clear user") {
                        userSegmentIndex = if (userSegmentIndex == null) 41.coerceAtMost(totalSegments - 1) else null
                    }
                }

                Spacer(modifier = Modifier.height(PactSpacing.md))

                // Sliders
                Text(text = "Total Segments: $totalSegments (5..100)", style = PactTypography.caption)
                Slider(
                    value = totalSegments.toFloat(),
                    onValueChange = {
                        totalSegments = it.toInt()
                        if (qualifiedCount > totalSegments) qualifiedCount = totalSegments
                        userSegmentIndex = userSegmentIndex?.coerceAtMost(totalSegments - 1)
                    },
                    valueRange = 5f..100f,
                    colors = SliderDefaults.colors(thumbColor = PactColors.PrimaryBlue, activeTrackColor = PactColors.PrimaryBlue)
                )

                Text(text = "Qualified Count: $qualifiedCount (0..$totalSegments)", style = PactTypography.caption)
                Slider(
                    value = qualifiedCount.toFloat(),
                    onValueChange = { qualifiedCount = it.toInt() },
                    valueRange = 0f..totalSegments.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = PactColors.PrimaryBlue, activeTrackColor = PactColors.PrimaryBlue)
                )
            }

            Spacer(modifier = Modifier.height(PactSpacing.lg))

            // ---------------------------------------------------------------
            // 3. Buttons
            // ---------------------------------------------------------------
            SectionCard(title = "3. Buttons (Primary & Secondary)") {
                PrimaryButton(
                    text = if (buttonLoading) "Loading..." else "Primary Action (Clicked $buttonClickCount)",
                    onClick = {
                        buttonClickCount++
                        buttonLoading = true
                    },
                    loading = buttonLoading
                )
                if (buttonLoading) {
                    Spacer(modifier = Modifier.height(PactSpacing.xxs))
                    TextButton(onClick = { buttonLoading = false }) {
                        Text(text = "Reset loading state", style = PactTypography.caption)
                    }
                }

                Spacer(modifier = Modifier.height(PactSpacing.sm))

                PrimaryButton(
                    text = "Primary Button (Disabled)",
                    onClick = {},
                    enabled = false
                )

                Spacer(modifier = Modifier.height(PactSpacing.md))

                SecondaryButton(
                    text = "Secondary Action",
                    onClick = { buttonClickCount++ }
                )

                Spacer(modifier = Modifier.height(PactSpacing.sm))

                SecondaryButton(
                    text = "Secondary Button (Disabled)",
                    onClick = {},
                    enabled = false
                )
            }

            Spacer(modifier = Modifier.height(PactSpacing.lg))

            // ---------------------------------------------------------------
            // 4. Text Fields with Validation
            // ---------------------------------------------------------------
            SectionCard(title = "4. Text Fields with Inline Validation") {
                PactTextField(
                    value = textInputNormal,
                    onValueChange = { textInputNormal = it },
                    label = "Choose your username",
                    placeholder = "e.g. maxwell",
                    suffixText = ".testnet"
                )

                Spacer(modifier = Modifier.height(PactSpacing.md))

                PactTextField(
                    value = textInputError,
                    onValueChange = { textInputError = it },
                    label = "Username availability",
                    placeholder = "username",
                    suffixText = ".testnet",
                    errorMessage = "'alice.testnet' is already taken on NEAR testnet"
                )

                Spacer(modifier = Modifier.height(PactSpacing.md))

                PactTextField(
                    value = "read-only-account.testnet",
                    onValueChange = {},
                    label = "Locked Account ID",
                    enabled = false
                )
            }

            Spacer(modifier = Modifier.height(PactSpacing.lg))

            // ---------------------------------------------------------------
            // 5. Cards & Dashboard Metrics
            // ---------------------------------------------------------------
            SectionCard(title = "5. Cards & Dashboard Metrics") {
                // 3 Metric Cards row (matching Today screen)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PactSpacing.xs)
                ) {
                    PactMetricCard(
                        value = "4.8 km",
                        label = "Distance",
                        modifier = Modifier.weight(1f)
                    )
                    PactMetricCard(
                        value = "52 min",
                        label = "Active",
                        modifier = Modifier.weight(1f)
                    )
                    PactMetricCard(
                        value = "6 days",
                        label = "Streak",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(PactSpacing.md))

                // Streak Challenge Card (matching Today screen)
                PactCard(onClick = {}) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "7-Day Streak Pact · Day 4",
                                style = PactTypography.title.copy(fontSize = 16.sp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Your stake 0.5 NEAR · Pool 12.5 NEAR",
                                style = PactTypography.caption
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "View Challenge",
                            tint = PactColors.MutedText
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.lg))

            // ---------------------------------------------------------------
            // 6. Status Chips
            // ---------------------------------------------------------------
            SectionCard(title = "6. Status Chips") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PactSpacing.xs)
                ) {
                    StatusChip(text = "Campus Striders")
                    StatusChip(
                        text = "Qualified",
                        backgroundColor = Color(0xFFE8F5E9),
                        textColor = PactColors.SuccessGreen,
                        borderColor = Color(0xFFA5D6A7)
                    )
                    StatusChip(
                        text = "Day 4 / 7",
                        backgroundColor = PactColors.CardBackground
                    )
                }
            }

            Spacer(modifier = Modifier.height(PactSpacing.lg))

            // ---------------------------------------------------------------
            // 7. Loading and Error States
            // ---------------------------------------------------------------
            SectionCard(title = "7. Loading & Error States") {
                LoadingState(message = "Syncing step records with Health Connect...")

                Spacer(modifier = Modifier.height(PactSpacing.md))

                ErrorState(
                    title = "Connection Unavailable",
                    message = "Could not reach the PACT backend at 192.168.1.8:8000. Please ensure you are on the same Wi-Fi.",
                    retryLabel = "Retry Connection",
                    onRetry = {}
                )
            }

            Spacer(modifier = Modifier.height(PactSpacing.xxl))
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = PactShapes.card,
        color = PactColors.SurfaceWhite,
        border = androidx.compose.foundation.BorderStroke(1.dp, PactColors.CardBorder)
    ) {
        Column(
            modifier = Modifier.padding(PactSpacing.md)
        ) {
            Text(
                text = title,
                style = PactTypography.title.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                color = PactColors.InkText
            )
            Spacer(modifier = Modifier.height(PactSpacing.md))
            content()
        }
    }
}

@Composable
private fun PresetButton(
    label: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        shape = PactShapes.chip,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PactColors.InkText),
        border = androidx.compose.foundation.BorderStroke(1.dp, PactColors.CardBorder),
        modifier = Modifier.height(32.dp)
    ) {
        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

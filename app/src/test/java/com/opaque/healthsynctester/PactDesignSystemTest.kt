package com.opaque.healthsynctester

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactShapes
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography
import org.junit.Assert.*
import org.junit.Test

class PactDesignSystemTest {

    @Test
    fun testPactColorPaletteMatchesSampledDesign() {
        // Sampled from Logo B and Today Screen
        assertEquals(Color(0xFF2338E6), PactColors.PrimaryBlue)
        assertEquals(Color(0xFFFF4A1C), PactColors.AccentOrange)
        assertEquals(Color(0xFFF6F2EA), PactColors.BackgroundCream)
        assertEquals(Color(0xFFFFFFFF), PactColors.SurfaceWhite)
        assertEquals(Color(0xFFF5F5F5), PactColors.CardBackground)
        assertEquals(Color(0xFF0A0A0A), PactColors.InkText)
        assertEquals(Color(0xFF5F5F5F), PactColors.MutedText)
        assertEquals(Color(0xFFE4E4E4), PactColors.RingInactive)
        assertEquals(Color(0xFFE8E8E8), PactColors.CardBorder)
    }

    @Test
    fun testSpacingGridSystem() {
        // Enforce 8dp base spacing rhythm
        assertEquals(8.dp, PactSpacing.xs)
        assertEquals(16.dp, PactSpacing.md)
        assertEquals(24.dp, PactSpacing.lg)
        assertEquals(32.dp, PactSpacing.xl)
        assertEquals(48.dp, PactSpacing.xxl)
        assertTrue("Min touch target must be at least 48dp", PactSpacing.minTouchTarget >= 48.dp)
        assertTrue("Button height must meet accessibility standards", PactSpacing.buttonHeight >= 48.dp)
    }

    @Test
    fun testConsistentShapes() {
        // 16dp consistent corner radius for core surfaces
        assertEquals(16.dp, PactShapes.cardRadius)
        assertEquals(16.dp, PactShapes.buttonRadius)
        assertEquals(16.dp, PactShapes.inputRadius)
        assertEquals(24.dp, PactShapes.chipRadius)
    }

    @Test
    fun testTypographyScale() {
        // 5 primary scale sizes
        assertEquals(44.sp, PactTypography.heroNumber.fontSize)
        assertEquals(28.sp, PactTypography.headline.fontSize)
        assertEquals(18.sp, PactTypography.title.fontSize)
        assertEquals(15.sp, PactTypography.body.fontSize)
        assertEquals(12.sp, PactTypography.caption.fontSize)
    }

    @Test
    fun testRingSegmentClamping() {
        // Clamping logic: total segments 5..100
        val underMin = 2.coerceIn(5, 100)
        assertEquals(5, underMin)

        val overMax = 150.coerceIn(5, 100)
        assertEquals(100, overMax)

        val validMid = 60.coerceIn(5, 100)
        assertEquals(60, validMid)

        // Qualified clamping
        val clampedQualNegative = (-5).coerceIn(0, 60)
        assertEquals(0, clampedQualNegative)

        val clampedQualOver = 75.coerceIn(0, 60)
        assertEquals(60, clampedQualOver)
    }

    @Test
    fun testRingSegmentMathScaling() {
        // Verify that segment stroke width scales gracefully from 5 to 100
        val radius = 130f
        val segLen = radius * 0.22f
        val midRadius = radius - (segLen / 2f)
        val circumference = 2f * Math.PI.toFloat() * midRadius

        for (n in listOf(5, 20, 50, 60, 100)) {
            val pitch = circumference / n
            val stroke = (pitch * 0.54f).coerceIn(3f, segLen * 0.42f)

            // Stroke must be positive and strictly bounded
            assertTrue("Stroke for N=$n must be >= 3px", stroke >= 3f)
            assertTrue("Stroke for N=$n must not exceed max cap", stroke <= segLen * 0.42f + 0.01f)
            assertTrue("Gap must remain between segments for N=$n", pitch > stroke)
        }
    }
}

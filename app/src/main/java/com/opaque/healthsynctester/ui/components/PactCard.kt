package com.opaque.healthsynctester.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactShapes
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography

/**
 * PACT Standard Card Container.
 *
 * One consistent corner radius (16dp), calm #F5F5F5 surface, subtle #E8E8E8 border,
 * generous padding.
 */
@Composable
fun PactCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    backgroundColor: Color = PactColors.CardBackground,
    borderColor: Color = PactColors.CardBorder,
    borderWidth: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(PactSpacing.md),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else Modifier
            ),
        shape = PactShapes.card,
        color = backgroundColor,
        border = BorderStroke(borderWidth, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

/**
 * PACT Metric Card: for compact dashboard metrics (e.g., 4.8 km Distance, 52 min Active).
 */
@Composable
fun PactMetricCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = PactShapes.card,
        color = PactColors.CardBackground,
        border = BorderStroke(1.dp, PactColors.CardBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = PactSpacing.md, vertical = PactSpacing.sm),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = value,
                style = PactTypography.title.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                color = PactColors.InkText
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = PactTypography.caption,
                color = PactColors.MutedText
            )
        }
    }
}

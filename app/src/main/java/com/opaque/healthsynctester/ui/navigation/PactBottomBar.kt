package com.opaque.healthsynctester.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography

enum class PactTab(
    val label: String,
    val iconOutlined: ImageVector,
    val iconFilled: ImageVector
) {
    TODAY("Today", Icons.Outlined.CheckCircle, Icons.Filled.CheckCircle),
    CHALLENGES("Challenges", Icons.Outlined.Flag, Icons.Filled.Flag),
    COMMUNITY("Community", Icons.Outlined.People, Icons.Filled.People),
    ME("Me", Icons.Outlined.Person, Icons.Filled.Person)
}

@Composable
fun PactBottomBar(
    currentTab: PactTab,
    onTabSelected: (PactTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PactColors.SurfaceWhite)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(color = PactColors.Divider, thickness = 1.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PactTab.values().forEach { tab ->
                val isSelected = tab == currentTab
                val tint = if (isSelected) PactColors.PrimaryBlue else PactColors.MutedText

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onTabSelected(tab) }
                        .padding(vertical = PactSpacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (isSelected) tab.iconFilled else tab.iconOutlined,
                        contentDescription = tab.label,
                        tint = tint,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = tab.label,
                        style = PactTypography.caption.copy(
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = tint
                    )
                }
            }
        }
    }
}

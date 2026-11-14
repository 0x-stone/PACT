package com.opaque.healthsynctester.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opaque.healthsynctester.domain.ProfileScope

@Composable
fun DebugOverlay(
    scope: ProfileScope,
    inFlightRpcCount: Int,
    cooldownUntilMs: Long,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val now = System.currentTimeMillis()
    val cooldownRemainingMs = (cooldownUntilMs - now).coerceAtLeast(0L)
    val isCoolingDown = cooldownRemainingMs > 0L

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xE61E1E1E))
            .padding(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PACT SCOPE DEBUG OVERLAY",
                    color = Color(0xFF4CAF50),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss Debug Overlay",
                    tint = Color.LightGray,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onDismiss() }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            DebugRow("Profile", scope.profile.uppercase())
            DebugRow("Network", scope.network)
            DebugRow("Contract", scope.contractId)
            DebugRow("Account", scope.accountId.ifEmpty { "(none)" })
            DebugRow("ScopeKey", scope.scopeKey)

            Spacer(modifier = Modifier.height(4.dp))

            DebugRow(
                "RPC Gateway",
                if (inFlightRpcCount > 0) "$inFlightRpcCount in-flight" else "Idle",
                valueColor = if (inFlightRpcCount > 0) Color(0xFFFFB74D) else Color(0xFF81C784)
            )

            DebugRow(
                "Cooldown",
                if (isCoolingDown) "${cooldownRemainingMs}ms (Circuit Open)" else "None (Circuit Closed)",
                valueColor = if (isCoolingDown) Color(0xFFE57373) else Color(0xFF81C784)
            )
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            color = Color(0xFFAAAAAA),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}

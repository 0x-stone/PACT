package com.opaque.healthsynctester.ui.backup

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opaque.healthsynctester.ui.components.PrimaryButton
import com.opaque.healthsynctester.ui.components.SecondaryButton
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactShapes
import com.opaque.healthsynctester.ui.theme.PactSpacing
import com.opaque.healthsynctester.ui.theme.PactTypography

/**
 * Non-blocking Key Backup Prompt shown after account creation (Stage C Part 2).
 */
@Composable
fun BackupPromptDialog(
    onBackupNow: () -> Unit,
    onDoLater: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDoLater,
        shape = PactShapes.card,
        containerColor = PactColors.BackgroundCream,
        title = {
            Text(
                text = "Back up your account",
                style = PactTypography.title,
                color = PactColors.InkText
            )
        },
        text = {
            Text(
                text = "Your account is secured by a key stored on this device. Back it up so you can recover access if you lose or replace your phone.",
                style = PactTypography.body,
                color = PactColors.MutedText
            )
        },
        confirmButton = {
            PrimaryButton(
                text = "Back up now",
                onClick = onBackupNow,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            SecondaryButton(
                text = "I'll do this later",
                onClick = onDoLater,
                modifier = Modifier.fillMaxWidth()
            )
        },
        modifier = modifier.padding(PactSpacing.md)
    )
}

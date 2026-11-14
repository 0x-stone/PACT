package com.opaque.healthsynctester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class RationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Health Connect Permissions Rationale",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Health Sync Tester requires read access to your health records (Steps, Heart Rate, Sleep Sessions, Exercise Sessions, Weight, and Blood Pressure) in order to test reading and synchronizing Android Health Connect data to your custom backend API.\n\n" +
                                    "• Data is read only when you explicitly tap 'Read Health Data'.\n" +
                                    "• Data is uploaded only when you explicitly tap 'Upload to Backend'.\n" +
                                    "• No data is logged, sold, or transmitted continuously in the background.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { finish() }) {
                            Text("Return to App")
                        }
                    }
                }
            }
        }
    }
}

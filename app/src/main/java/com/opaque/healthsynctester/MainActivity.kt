package com.opaque.healthsynctester

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import com.opaque.healthsynctester.model.SyncPayload
import com.opaque.healthsynctester.model.SyncResponseResult
import com.opaque.healthsynctester.near.NearIdentity
import com.opaque.healthsynctester.ui.auth.AuthViewModel
import com.opaque.healthsynctester.ui.auth.StageBScreen
import com.opaque.healthsynctester.ui.gallery.GalleryScreen
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.theme.PactTheme
import kotlinx.coroutines.launch

enum class AppView {
    STAGE_B_AUTH,
    GALLERY,
    LEGACY_TESTER
}

class MainActivity : ComponentActivity() {

    private val healthConnectManager by lazy { HealthConnectManager(applicationContext) }
    private val syncRepository by lazy { SyncRepository() }
    private val nearIdentity by lazy { NearIdentity(applicationContext) }
    private val authViewModel by lazy {
        AuthViewModel(
            nearIdentity = nearIdentity,
            backendBaseUrlProvider = { "https://quickness-shining-latticed.ngrok-free.dev" }
        )
    }

    private val todayViewModel by lazy {
        com.opaque.healthsynctester.ui.today.TodayViewModel(
            healthConnectManager = healthConnectManager,
            syncRepository = syncRepository,
            nearIdentity = nearIdentity,
            backendBaseUrlProvider = { "https://quickness-shining-latticed.ngrok-free.dev" }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PactTheme {
                var currentView by remember { mutableStateOf(AppView.STAGE_B_AUTH) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PactColors.BackgroundCream
                ) {
                    when (currentView) {
                        AppView.STAGE_B_AUTH -> {
                            StageBScreen(
                                viewModel = authViewModel,
                                todayViewModel = todayViewModel,
                                nearIdentity = nearIdentity,
                                onOpenGallery = { currentView = AppView.GALLERY },
                                onOpenLegacyTester = { currentView = AppView.LEGACY_TESTER }
                            )
                        }

                        AppView.GALLERY -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = PactColors.CardBackground,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, PactColors.CardBorder)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .statusBarsPadding()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Component Gallery",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = PactColors.MutedText
                                        )
                                        TextButton(onClick = { currentView = AppView.STAGE_B_AUTH }) {
                                            Text("Return to Sign-In", fontSize = 12.sp, color = PactColors.PrimaryBlue)
                                        }
                                    }
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    GalleryScreen(
                                        onSwitchToLegacyTester = { currentView = AppView.LEGACY_TESTER }
                                    )
                                }
                            }
                        }

                        AppView.LEGACY_TESTER -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = PactColors.CardBackground,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, PactColors.CardBorder)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .statusBarsPadding()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Legacy HealthSync Tester",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = PactColors.MutedText
                                        )
                                        TextButton(onClick = { currentView = AppView.STAGE_B_AUTH }) {
                                            Text("Return to Sign-In", fontSize = 12.sp, color = PactColors.PrimaryBlue)
                                        }
                                    }
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    HealthSyncScreen(
                                        healthConnectManager = healthConnectManager,
                                        syncRepository = syncRepository,
                                        nearIdentity = nearIdentity
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthSyncScreen(
    healthConnectManager: HealthConnectManager,
    syncRepository: SyncRepository,
    nearIdentity: NearIdentity
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Health Connect status & permissions
    var availability by remember { mutableStateOf<HealthConnectAvailability>(HealthConnectAvailability.NotSupported) }
    var grantedPermissions by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Backend configuration
    var backendUrl by remember { mutableStateOf("https://quickness-shining-latticed.ngrok-free.dev") }
    var apiKey by remember { mutableStateOf("test-api-key") }

    // NEAR identity & session state
    var nearIdentityInfo by remember { mutableStateOf(nearIdentity.identity()) }
    var nearSession by remember { mutableStateOf(nearIdentity.session()) }
    var desiredAccountId by remember { mutableStateOf(NearIdentity.suggestedAccountId()) }
    var isCreatingAccount by remember { mutableStateOf(false) }
    var isSigningIn by remember { mutableStateOf(false) }
    var nearError by remember { mutableStateOf<String?>(null) }
    var nearStatus by remember { mutableStateOf<String?>(null) }

    // Data reading state
    var isReadingData by remember { mutableStateOf(false) }
    var currentPayload by remember { mutableStateOf<SyncPayload?>(null) }
    var rawJsonPreview by remember { mutableStateOf("") }
    var readErrorMessage by remember { mutableStateOf<String?>(null) }
    var showRawJson by remember { mutableStateOf(false) }

    // Upload state
    var isUploading by remember { mutableStateOf(false) }
    var syncResponseResult by remember { mutableStateOf<SyncResponseResult?>(null) }
    var allowForceDuplicate by remember { mutableStateOf(false) }

    // Refresh permissions and status helper
    fun refreshStatus() {
        availability = healthConnectManager.checkAvailability()
        coroutineScope.launch {
            grantedPermissions = healthConnectManager.getGrantedPermissions()
        }
    }

    // Permission launcher using official Health Connect Contract
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        grantedPermissions = granted
        val total = healthConnectManager.requiredPermissions.size
        Toast.makeText(
            context,
            "Granted ${granted.size} of $total permissions",
            Toast.LENGTH_SHORT
        ).show()
    }

    LaunchedEffect(Unit) {
        refreshStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Health Sync Tester",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = { refreshStatus() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Status")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // SECTION 1: Health Connect Availability & Permissions
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "1. Health Connect Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Availability:", style = MaterialTheme.typography.bodyMedium)
                        when (availability) {
                            HealthConnectAvailability.Available -> {
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text("Available", color = Color(0xFF1B5E20)) },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = Color(0xFFC8E6C9)
                                    )
                                )
                            }
                            HealthConnectAvailability.UpdateRequired -> {
                                SuggestionChip(
                                    onClick = {
                                        val intent = Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                                        )
                                        context.startActivity(intent)
                                    },
                                    label = { Text("Update Required (Tap)", color = Color(0xFFE65100)) },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = Color(0xFFFFE0B2)
                                    )
                                )
                            }
                            HealthConnectAvailability.NotSupported -> {
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text("Not Supported", color = Color(0xFFB71C1C)) },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = Color(0xFFFFCDD2)
                                    )
                                )
                            }
                        }
                    }

                    // Permissions count
                    val totalReq = healthConnectManager.requiredPermissions.size
                    val grantedCount = grantedPermissions.intersect(healthConnectManager.requiredPermissions).size
                    Text(
                        text = "Permissions Granted: $grantedCount / $totalReq",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (grantedCount == totalReq) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Connect / Grant Permissions Button
                    Button(
                        onClick = {
                            if (availability == HealthConnectAvailability.Available) {
                                permissionLauncher.launch(healthConnectManager.requiredPermissions)
                            } else if (availability == HealthConnectAvailability.UpdateRequired) {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                                )
                                context.startActivity(intent)
                            } else {
                                Toast.makeText(
                                    context,
                                    "Health Connect is not supported on this device/OS",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (grantedCount == totalReq) "Re-check / Manage Permissions" else "Connect & Grant Permissions")
                    }
                }
            }

            // SECTION 2: Backend Configuration
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "2. Backend Configuration",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    OutlinedTextField(
                        value = backendUrl,
                        onValueChange = { backendUrl = it },
                        label = { Text("Backend URL") },
                        placeholder = { Text("http://192.168.1.X:8000 or https://api.yourdomain.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key (Optional Header: X-API-Key)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "Note: Target endpoint is {BACKEND_URL}/api/health/sync. Use your PC's LAN IP for physical device testing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // SECTION 3: NEAR Identity (Sign In)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "3. NEAR Identity (Sign In)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (nearIdentityInfo == null) {
                        OutlinedTextField(
                            value = desiredAccountId,
                            onValueChange = { desiredAccountId = it },
                            label = { Text("Desired testnet account id") },
                            placeholder = { Text("e.g. alice.testnet") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = "Creates a funded NEAR testnet account via helper.testnet.near.org, with an Ed25519 key generated on this device " +
                                "(seed wrapped at rest by AndroidKeyStore). A bare name gets '.testnet' appended.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )

                        Button(
                            onClick = {
                                if (isCreatingAccount) return@Button
                                isCreatingAccount = true
                                nearError = null
                                nearStatus = null
                                coroutineScope.launch {
                                    val result = nearIdentity.createTestnetAccount(desiredAccountId)
                                    if (result.isSuccess) {
                                        nearIdentityInfo = nearIdentity.identity()
                                        nearStatus = result.message
                                    } else {
                                        nearError = result.message
                                        nearStatus = result.detail.take(300)
                                    }
                                    isCreatingAccount = false
                                }
                            },
                            enabled = !isCreatingAccount,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isCreatingAccount) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Creating testnet account...")
                            } else {
                                Icon(Icons.Default.PersonAdd, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Create Testnet Account")
                            }
                        }
                    } else {
                        val identityInfo = nearIdentityInfo
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Account:", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = identityInfo?.accountId ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "Full Access Key: ${identityInfo?.publicKey ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline
                        )

                        if (nearSession == null) {
                            Button(
                                onClick = {
                                    if (isSigningIn) return@Button
                                    isSigningIn = true
                                    nearError = null
                                    nearStatus = null
                                    coroutineScope.launch {
                                        val result = nearIdentity.signIn(backendUrl)
                                        if (result.isSuccess) {
                                            nearSession = nearIdentity.session()
                                            nearStatus = result.message
                                        } else {
                                            nearError = result.message
                                            nearStatus = result.detail.take(300)
                                        }
                                        isSigningIn = false
                                    }
                                },
                                enabled = !isSigningIn && backendUrl.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isSigningIn) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Signing in (NEP-413)...")
                                } else {
                                    Icon(Icons.Default.Login, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Sign In with NEAR Account")
                                }
                            }
                            if (backendUrl.isBlank()) {
                                Text(
                                    text = "Set the Backend URL above first: sign-in signs a challenge issued by your backend.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        } else {
                            val session = nearSession
                            Text(
                                text = "Signed in as ${session?.accountId} — session expires ${session?.expiresAt}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1B5E20)
                            )
                            TextButton(onClick = {
                                nearIdentity.signOut()
                                nearSession = null
                                nearStatus = "Signed out (identity stays on this device)"
                            }) {
                                Icon(Icons.Default.Logout, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Sign Out")
                            }
                        }
                    }

                    if (nearError != null) {
                        Text(
                            text = "NEAR error: $nearError",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (nearStatus != null && nearError == null) {
                        Text(
                            text = nearStatus ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }
            }

            // SECTION 4: Read Health Data (Last 7 Days)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "4. Read Health Data (Last 7 Days)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Button(
                        onClick = {
                            isReadingData = true
                            readErrorMessage = null
                            allowForceDuplicate = false
                            coroutineScope.launch {
                                try {
                                    val payload = healthConnectManager.fetchHealthDataLast7Days()
                                    currentPayload = payload
                                    rawJsonPreview = syncRepository.toJson(payload)
                                } catch (e: Exception) {
                                    readErrorMessage = e.localizedMessage ?: e.javaClass.simpleName
                                    currentPayload = null
                                    rawJsonPreview = ""
                                } finally {
                                    isReadingData = false
                                }
                            }
                        },
                        enabled = !isReadingData && availability == HealthConnectAvailability.Available,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isReadingData) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Reading from Health Connect...")
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Read Health Data (Last 7 Days)")
                        }
                    }

                    if (readErrorMessage != null) {
                        Text(
                            text = "Error reading data: $readErrorMessage",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // Display Record Count Grouped by Type
                    val payload = currentPayload
                    if (payload != null) {
                        Text(
                            text = "Retrieved Records (${payload.records.size} total):",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surface,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp)
                        ) {
                            healthConnectManager.supportedRecordTypes.forEach { type ->
                                val count = payload.summary[type] ?: 0
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = type,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = if (count > 0) "$count records" else "0 records (empty)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (count > 0) Color(0xFF1B5E20) else Color.Gray,
                                        fontWeight = if (count > 0) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // SECTION 4: JSON Payload Preview
            if (rawJsonPreview.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "5. JSON Preview",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { showRawJson = !showRawJson }) {
                                    Text(if (showRawJson) "Hide JSON" else "Show JSON")
                                }
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("HealthConnectSyncPayload", rawJsonPreview)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "JSON copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy JSON")
                                }
                            }
                        }

                        if (showRawJson) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .background(Color(0xFF212121), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                                    .verticalScroll(rememberScrollState())
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = rawJsonPreview,
                                    color = Color(0xFFE0E0E0),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        } else {
                            Text(
                                text = "JSON payload prepared (${rawJsonPreview.length} characters). Tap 'Show JSON' or 'Copy' to inspect.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            // SECTION 5: Upload to Backend
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "6. Backend Synchronization",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Button(
                        onClick = {
                            val payload = currentPayload ?: return@Button
                            if (backendUrl.isBlank()) {
                                Toast.makeText(context, "Please enter backend URL", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isUploading = true
                            coroutineScope.launch {
                                val result = syncRepository.uploadHealthData(
                                    baseUrl = backendUrl,
                                    apiKey = apiKey,
                                    payload = payload,
                                    authToken = nearSession?.token,
                                    forceDuplicate = allowForceDuplicate
                                )
                                syncResponseResult = result
                                if (result.statusCode == 401) {
                                    nearError = "Session rejected (HTTP 401) — sign in again in Section 3"
                                    nearSession = nearIdentity.session()
                                }
                                if (result.statusCode == 409) {
                                    allowForceDuplicate = true
                                }
                                isUploading = false
                            }
                        },
                        enabled = !isUploading && currentPayload != null && backendUrl.isNotBlank() && nearSession != null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (isUploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Uploading to Backend...")
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (allowForceDuplicate) "Force Re-Upload Payload" else "Upload to Backend")
                        }
                    }

                    if (nearSession == null) {
                        Text(
                            text = "Sign in (Section 3) first: uploads are attributed to your NEAR account and require a session (Authorization: Bearer).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    // Display Last Synchronization Status & Server Response
                    val result = syncResponseResult
                    if (result != null) {
                        Divider()
                        Text(
                            text = "Last Sync Status: ${result.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (result.isSuccess) Color(0xFF1B5E20) else MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Timestamp: ${result.timestamp}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )

                        Text(
                            text = "Server Response Body:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .background(
                                    if (result.isSuccess) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = result.responseBody,
                                color = if (result.isSuccess) Color(0xFF2E7D32) else Color(0xFFC62828),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

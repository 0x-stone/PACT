package com.opaque.healthsynctester.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.opaque.healthsynctester.near.NearIdentity
import com.opaque.healthsynctester.ui.backup.BackupPromptDialog
import com.opaque.healthsynctester.ui.backup.KeyBackupScreen
import com.opaque.healthsynctester.ui.challenge.ChallengeResultSheet
import com.opaque.healthsynctester.ui.challenge.JoinChallengeSheet
import com.opaque.healthsynctester.ui.challenges.ChallengesScreen
import com.opaque.healthsynctester.ui.community.CommunityScreen
import com.opaque.healthsynctester.ui.components.LoadingState
import com.opaque.healthsynctester.ui.debug.OriginAuditScreen
import com.opaque.healthsynctester.ui.health.HealthPermissionScreen
import com.opaque.healthsynctester.ui.me.DiagnosticsSheet
import com.opaque.healthsynctester.ui.me.MeScreen
import com.opaque.healthsynctester.ui.navigation.PactBottomBar
import com.opaque.healthsynctester.ui.navigation.PactTab
import com.opaque.healthsynctester.ui.theme.PactColors
import com.opaque.healthsynctester.ui.today.TodayScreen
import com.opaque.healthsynctester.ui.today.TodayUiState
import com.opaque.healthsynctester.ui.today.TodayViewModel

/**
 * PACT Root Flow Coordinator (Stage B & C).
 *
 * Coordinates:
 * - Auth flow (Splash, Welcome, ChooseName, Creating, WelcomeBack, Import)
 * - Stage C destination: Today screen with real Health Connect steps & dynamic challenge
 * - Health permission onboarding
 * - Origin audit debug screen
 * - Device key backup and export
 */
@Composable
fun StageBScreen(
    viewModel: AuthViewModel,
    todayViewModel: TodayViewModel,
    nearIdentity: NearIdentity,
    onOpenGallery: () -> Unit,
    onOpenLegacyTester: () -> Unit,
    modifier: Modifier = Modifier
) {
    // If Key Backup Screen is requested, show it over the app with FLAG_SECURE
    if (viewModel.showBackupScreen) {
        KeyBackupScreen(
            credentials = nearIdentity.getBackupCredentials(),
            onBack = { viewModel.showBackupScreen = false },
            modifier = modifier
        )
        return
    }

    var showDiagnosticsSheet by remember { mutableStateOf(false) }

    when (val state = viewModel.state) {
        is AuthScreenState.Initializing -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(PactColors.BackgroundCream),
                contentAlignment = Alignment.Center
            ) {
                LoadingState(message = "Loading PACT...")
            }
        }

        is AuthScreenState.Welcome -> {
            WelcomeScreen(
                onGetStarted = viewModel::onGetStartedClicked,
                onImportAccount = viewModel::onImportAccountOptionClicked,
                modifier = modifier
            )
        }

        is AuthScreenState.ChooseName -> {
            ChooseNameScreen(
                inputName = state.inputName,
                onNameChanged = viewModel::onUsernameChanged,
                isChecking = state.isCheckingAvailability,
                availability = state.availability,
                onCreateAccount = viewModel::onCreateAccountSubmit,
                onBack = viewModel::onBackToWelcome,
                modifier = modifier
            )
        }

        is AuthScreenState.Creating -> {
            CreatingAccountScreen(
                accountId = state.accountId,
                currentStep = state.currentStep,
                errorMessage = state.errorMessage,
                onRetry = { viewModel.onCreateAccountSubmit(state.accountId) },
                onBackToName = viewModel::onGetStartedClicked,
                modifier = modifier
            )
        }

        is AuthScreenState.WelcomeBack -> {
            WelcomeBackScreen(
                accountId = state.accountId,
                isSigningIn = state.isSigningIn,
                errorMessage = state.errorMessage,
                onSignIn = viewModel::onWelcomeBackSignIn,
                onUseAnotherAccount = viewModel::onGetStartedClicked,
                onDeleteAccount = viewModel::onDeleteLocalAccount,
                modifier = modifier
            )
        }

        is AuthScreenState.ImportAccount -> {
            ImportAccountScreen(
                onImportSubmit = viewModel::onImportAccountSubmit,
                onBack = viewModel::onBackToWelcome,
                isImporting = state.isImporting,
                errorMessage = state.errorMessage,
                modifier = modifier
            )
        }

        is AuthScreenState.Authenticated -> {
            // Check health permissions on authenticated landing & re-check on ON_RESUME
            LaunchedEffect(state.accountId) {
                todayViewModel.checkPermissionsAndLoad()
            }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        todayViewModel.onResume()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            if (todayViewModel.showOriginAudit) {
                OriginAuditScreen(
                    auditItems = todayViewModel.auditItems,
                    onBack = { todayViewModel.showOriginAudit = false },
                    modifier = modifier
                )
            } else if (!todayViewModel.hasPermission) {
                if (!todayViewModel.isPermissionCheckDone) {
                    Box(
                        modifier = modifier
                            .fillMaxSize()
                            .background(PactColors.BackgroundCream),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingState(message = "Checking Health Connect permissions...")
                    }
                } else {
                    HealthPermissionScreen(
                        healthAvailability = todayViewModel.healthAvailability,
                        onPermissionGranted = { todayViewModel.onPermissionResult(true) },
                        onSkipLater = { todayViewModel.onPermissionResult(false) },
                        modifier = modifier
                    )
                }
            } else {
                var currentTab by remember { mutableStateOf(PactTab.TODAY) }

                Scaffold(
                    bottomBar = {
                        PactBottomBar(
                            currentTab = currentTab,
                            onTabSelected = { currentTab = it }
                        )
                    },
                    containerColor = PactColors.BackgroundCream,
                    modifier = modifier.fillMaxSize()
                ) { innerPadding ->
                    val content = todayViewModel.uiState as? TodayUiState.Content
                    val contentModifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                    val inFlightCount by androidx.compose.runtime.produceState(initialValue = 0) {
                        todayViewModel.inFlightRpcCount.collect { value = it }
                    }
                    val cooldownUntil by androidx.compose.runtime.produceState(initialValue = 0L) {
                        todayViewModel.cooldownUntilMs.collect { value = it }
                    }

                    when (currentTab) {
                        PactTab.TODAY -> {
                            TodayScreen(
                                uiState = todayViewModel.uiState,
                                accountId = state.accountId,
                                onRefresh = { todayViewModel.refresh(isUserRefresh = true) },
                                onOpenJoin = { todayViewModel.showJoinSheet = true },
                                onOpenResult = { todayViewModel.showResultSheet = true },
                                onOpenProfile = { viewModel.showProfileSheet = true },
                                onClaimReward = todayViewModel::claimReward,
                                onCreateSandboxChallenge = { todayViewModel.createSandboxChallenge() },
                                isCreatingSandbox = todayViewModel.isCreatingSandboxChallenge,
                                sandboxStatusMessage = todayViewModel.sandboxCreationMessage,
                                showDebugOverlay = todayViewModel.showDebugOverlay,
                                onToggleDebugOverlay = { todayViewModel.showDebugOverlay = !todayViewModel.showDebugOverlay },
                                debugScope = todayViewModel.currentScope,
                                inFlightRpcCount = inFlightCount,
                                cooldownUntilMs = cooldownUntil,
                                modifier = contentModifier
                            )
                        }

                        PactTab.CHALLENGES -> {
                            ChallengesScreen(
                                challenges = content?.allChallenges ?: emptyList(),
                                enrolledChallengeIds = todayViewModel.enrolledChallengeIds,
                                challengeMemberStatuses = todayViewModel.challengeMemberStatuses,
                                isCached = content?.isOffline ?: false,
                                nowChainNs = todayViewModel.nowChainNs,
                                quorumPct = content?.quorumPct ?: 70,
                                stakeNear = content?.stakeNear ?: "0.5",
                                minMembers = content?.minMembers ?: 5L,
                                activeProfile = todayViewModel.activeProfile,
                                onJoinChallenge = { ch ->
                                    todayViewModel.openJoinForChallenge(ch)
                                },
                                onOpenResult = { ch ->
                                    todayViewModel.openResultForChallenge(ch)
                                },
                                modifier = contentModifier
                            )
                        }

                        PactTab.COMMUNITY -> {
                            CommunityScreen(
                                challenge = content?.contractChallenge,
                                communitySummary = content?.communitySummary,
                                daoTreasuryNearDisplay = "500,000",
                                quorumPct = content?.quorumPct ?: 70,
                                mintPact = 100,
                                burnPact = 50,
                                daoName = when (todayViewModel.activeProfile) {
                                    "sandbox" -> "Self-Service Relayer (pact-sandbox-relayer.testnet)"
                                    "demo" -> "Demo DAO (pact-demo-dao.testnet)"
                                    else -> "Sputnik DAO (pactdao.testnet)"
                                },
                                activeProfile = todayViewModel.activeProfile,
                                modifier = contentModifier
                            )
                        }

                        PactTab.ME -> {
                            MeScreen(
                                accountId = state.accountId,
                                walletBalances = content?.walletBalances,
                                isDemoMode = todayViewModel.isDemoMode,
                                activeProfile = todayViewModel.activeProfile,
                                txHistory = todayViewModel.getTxHistory(),
                                onToggleDemoMode = { todayViewModel.toggleDemoMode() },
                                onOpenProfileSheet = { viewModel.showProfileSheet = true },
                                onOpenOriginAudit = { todayViewModel.showOriginAudit = true },
                                onOpenKeyBackup = { viewModel.showBackupScreen = true },
                                onOpenDiagnostics = { showDiagnosticsSheet = true },
                                onSignOut = viewModel::onSignOut,
                                modifier = contentModifier
                            )
                        }
                    }
                }
            }

            // Join Challenge bottom sheet (Stage D)
            val currentContent = todayViewModel.uiState as? TodayUiState.Content
            val challengeToJoin = todayViewModel.selectedChallengeForJoin ?: currentContent?.challenge
            if (todayViewModel.showJoinSheet && challengeToJoin != null) {
                val isJoined = todayViewModel.enrolledChallengeIds.contains(challengeToJoin.challengeId) ||
                        currentContent?.memberStatus?.isEnrolled == true
                JoinChallengeSheet(
                    challenge = challengeToJoin,
                    nearBalanceYocto = todayViewModel.nearBalanceYocto,
                    isEnrolled = isJoined,
                    isJoining = todayViewModel.isJoining,
                    joinStepText = todayViewModel.joinStepText,
                    joinError = todayViewModel.joinError,
                    isSandbox = todayViewModel.isSandboxMode,
                    onJoin = todayViewModel::joinActiveChallenge,
                    onDismiss = {
                        todayViewModel.showJoinSheet = false
                        todayViewModel.selectedChallengeForJoin = null
                    }
                )
            }

            // Challenge Result & Settlement bottom sheet (Stage E)
            val resultChallenge = todayViewModel.selectedChallengeForResult ?: currentContent?.contractChallenge
            val resultMemberStatus = resultChallenge?.let { todayViewModel.challengeMemberStatuses[it.challengeId] } ?: currentContent?.memberStatus
            if (todayViewModel.showResultSheet && resultChallenge != null && resultMemberStatus != null) {
                ChallengeResultSheet(
                    challenge = resultChallenge,
                    memberStatus = resultMemberStatus,
                    memberVerdict = if (resultChallenge.challengeId == currentContent?.contractChallenge?.challengeId) currentContent.memberVerdict else null,
                    isSubmittingAttestation = todayViewModel.isSubmittingAttestation,
                    isSettling = todayViewModel.isSettling,
                    isClaimingReward = todayViewModel.isClaimingReward,
                    isWithdrawingStake = todayViewModel.isWithdrawingStake,
                    lastTxHash = todayViewModel.lastTxHash,
                    actionError = todayViewModel.actionError,
                    onSubmitAttestation = todayViewModel::submitAttestation,
                    onSettleWeek = todayViewModel::settleWeek,
                    onClaimReward = todayViewModel::claimReward,
                    onWithdrawStake = todayViewModel::withdrawStake,
                    quorumPct = currentContent?.quorumPct ?: todayViewModel.nearIdentity.activeProfileObject().defaultQuorumPct,
                    stakeNear = currentContent?.stakeNear ?: todayViewModel.nearIdentity.activeProfileObject().defaultStakeNear,
                    minMembers = currentContent?.minMembers ?: todayViewModel.nearIdentity.activeProfileObject().defaultMinMembers,
                    onDismiss = {
                        todayViewModel.showResultSheet = false
                        todayViewModel.selectedChallengeForResult = null
                    }
                )
            }

            // Post-creation non-blocking backup prompt
            if (viewModel.showPostCreationBackupPrompt) {
                BackupPromptDialog(
                    onBackupNow = {
                        viewModel.showPostCreationBackupPrompt = false
                        viewModel.showBackupScreen = true
                    },
                    onDoLater = {
                        viewModel.showPostCreationBackupPrompt = false
                    }
                )
            }

            // Profile & Settings bottom sheet
            if (viewModel.showProfileSheet) {
                ProfileSheet(
                    accountId = state.accountId,
                    publicKey = state.publicKey,
                    session = state.session,
                    isDemoMode = todayViewModel.isDemoMode,
                    activeProfile = todayViewModel.activeProfile,
                    onSelectProfile = { prof -> todayViewModel.setProfile(prof) },
                    onToggleDemoMode = { todayViewModel.toggleDemoMode() },
                    onSignOut = viewModel::onSignOut,
                    onDeleteAccount = viewModel::onDeleteLocalAccount,
                    onOpenBackup = {
                        viewModel.showProfileSheet = false
                        viewModel.showBackupScreen = true
                    },
                    onDismiss = { viewModel.showProfileSheet = false }
                )
            }

            // System Diagnostics bottom sheet
            if (showDiagnosticsSheet) {
                DiagnosticsSheet(
                    activeProfileId = todayViewModel.activeProfile,
                    contractClient = todayViewModel.contractClient,
                    backendUrl = todayViewModel.backendBaseUrl,
                    onDismiss = { showDiagnosticsSheet = false }
                )
            }
        }
    }
}

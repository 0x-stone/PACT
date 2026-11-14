package com.opaque.healthsynctester.ui.today

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opaque.healthsynctester.HealthConnectAvailability
import com.opaque.healthsynctester.HealthConnectManager
import com.opaque.healthsynctester.OriginAuditItem
import com.opaque.healthsynctester.SyncRepository
import com.opaque.healthsynctester.near.NearContractClient
import com.opaque.healthsynctester.near.NearContractClient.ChallengeOutcome
import com.opaque.healthsynctester.near.NearContractClient.ContractChallenge
import com.opaque.healthsynctester.near.NearContractClient.MemberContractStatus
import com.opaque.healthsynctester.near.NearIdentity
import com.opaque.healthsynctester.pact.*
import com.opaque.healthsynctester.domain.ProfileScope
import com.opaque.healthsynctester.gateway.RpcGateway
import com.opaque.healthsynctester.profiles.PactProfiles
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.math.BigInteger
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

sealed class TodayUiState {
    object Loading : TodayUiState()
    data class Content(
        val todaySteps: Long,
        val challenge: ChallengeInfo?,
        val contractChallenge: ContractChallenge?,
        val upcomingChallenge: ContractChallenge? = null,
        val allChallenges: List<ContractChallenge> = emptyList(),
        val phase: ChallengePhase = ChallengePhase.UPCOMING,
        val freshness: FreshnessState = FreshnessState.LIVE,
        val memberStatus: MemberContractStatus?,
        val memberVerdict: MemberVerdictDto?,
        val communitySummary: CommunitySummaryDto?,
        val walletBalances: WalletBalances? = null,
        val timeRemainingSecs: Long = 0L,
        val autoSubmitStatusText: String? = null,
        val lastSyncText: String,
        val isOffline: Boolean = false,
        val isSyncing: Boolean = false,
        val syncMessage: String? = null,
        val isDemoMode: Boolean = false,
        val isSandboxMode: Boolean = false,
        val isCreatingSandbox: Boolean = false,
        val sandboxStatusText: String? = null,
        val activeProfile: String = "real",
        val challengeMemberStatuses: Map<String, MemberContractStatus> = emptyMap(),
        val submissionGraceSecs: Long = 3600L,
        val minMembers: Long = 5L,
        val quorumPct: Int = 70,
        val stakeNear: String = "0.5"
    ) : TodayUiState()
    data class Error(val message: String, val canRetry: Boolean = true) : TodayUiState()
}

class TodayViewModel(
    private val healthConnectManager: HealthConnectManager,
    private val syncRepository: SyncRepository,
    val nearIdentity: NearIdentity,
    val challengeRepository: ChallengeRepository = ChallengeRepository(),
    val contractClient: NearContractClient = NearContractClient(nearIdentity),
    val stateStore: PactStateStore = PactStateStore(nearIdentity.context),
    val backendBaseUrlProvider: () -> String
) : ViewModel() {

    val backendBaseUrl: String get() = backendBaseUrlProvider()
    var uiState by mutableStateOf<TodayUiState>(TodayUiState.Loading)
        private set

    var hasPermission by mutableStateOf(false)
        private set

    var isPermissionCheckDone by mutableStateOf(false)
        private set

    var healthAvailability by mutableStateOf<HealthConnectAvailability>(HealthConnectAvailability.Available)
        private set

    var lastSyncInstant by mutableStateOf<Instant?>(null)
        private set

    var auditItems by mutableStateOf<List<OriginAuditItem>>(emptyList())
        private set

    var showOriginAudit by mutableStateOf(false)

    // Join Sheet state
    var nearBalanceYocto by mutableStateOf<BigInteger>(BigInteger.ZERO)
        private set
    var showJoinSheet by mutableStateOf(false)
    var selectedChallengeForJoin by mutableStateOf<ChallengeInfo?>(null)
    var enrolledChallengeIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var challengeMemberStatuses by mutableStateOf<Map<String, MemberContractStatus>>(emptyMap())
        private set
    var isJoining by mutableStateOf(false)
    var joinStepText by mutableStateOf<String?>(null)
    var joinError by mutableStateOf<String?>(null)

    val nowChainNs: Long get() = contractClient.currentChainTimeNs()

    fun openJoinForChallenge(ch: ContractChallenge) {
        selectedChallengeForJoin = ChallengeInfo(
            challengeId = ch.challengeId,
            stepsPerDay = ch.stepsPerDay.toInt(),
            daysRequired = ch.daysRequired,
            startNs = ch.startNs.toString(),
            endNs = ch.endNs.toString(),
            enrolledCount = ch.enrolledCount,
            qualifierCount = ch.qualifierCount
        )
        showJoinSheet = true
    }

    // Result & Settlement Sheet state
    var showResultSheet by mutableStateOf(false)
    var selectedChallengeForResult by mutableStateOf<ContractChallenge?>(null)

    fun openResultForChallenge(ch: ContractChallenge) {
        selectedChallengeForResult = ch
        showResultSheet = true
    }

    var isSubmittingAttestation by mutableStateOf(false)
    var isSettling by mutableStateOf(false)
    var isClaimingReward by mutableStateOf(false)
    var isWithdrawingStake by mutableStateOf(false)
    var lastTxHash by mutableStateOf<String?>(null)
    var actionError by mutableStateOf<String?>(null)

    val memberZoneId: ZoneId = ZoneId.systemDefault()

    var activeProfile by mutableStateOf(nearIdentity.activeProfileName())
        private set

    val isSandboxMode: Boolean get() = activeProfile == NearIdentity.PROFILE_SANDBOX

    var isDemoMode by mutableStateOf(nearIdentity.isDemoMode())
        private set

    var currentScope: ProfileScope = ProfileScope.fromProfile(
        nearIdentity.activeProfileObject(),
        nearIdentity.identity()?.accountId ?: ""
    )
        private set

    val rpcGateway: RpcGateway = RpcGateway.getInstance()
    val inFlightRpcCount: StateFlow<Int> = rpcGateway.inFlightCount
    val cooldownUntilMs: StateFlow<Long> = rpcGateway.cooldownUntilMs
    var showDebugOverlay by mutableStateOf(false)

    var isCreatingSandboxChallenge by mutableStateOf(false)
    var sandboxCreationMessage by mutableStateOf<String?>(null)

    private var refreshJob: Job? = null
    private var coordinatorJob: Job? = null
    private var isAutoSubmitting = false
    private var isSettlingSingleFlight = false
    private val inFlightActions = mutableSetOf<String>()
    private var autoSubmitRetryCount = 0

    init {
        contractClient.contractId = nearIdentity.activeContractId()
        val initialProfile = nearIdentity.activeProfileName()
        val initialContractId = nearIdentity.activeContractId()
        val initialAccountId = nearIdentity.identity()?.accountId ?: ""
        currentScope = ProfileScope.fromProfile(nearIdentity.activeProfileObject(), initialAccountId)

        // Load cached state immediately so screen NEVER blanks on startup
        val cached = stateStore.loadState(initialProfile, initialContractId, initialAccountId)
        if (cached != null) {
            val challengeInfo = cached.challenge?.let {
                ChallengeInfo(
                    challengeId = it.challengeId,
                    stepsPerDay = it.stepsPerDay.toInt(),
                    daysRequired = it.daysRequired,
                    startNs = it.startNs.toString(),
                    endNs = it.endNs.toString(),
                    enrolledCount = it.enrolledCount,
                    qualifierCount = it.qualifierCount
                )
            }
            challengeMemberStatuses = cached.challengeMemberStatuses ?: emptyMap()
            enrolledChallengeIds = cached.challengeMemberStatuses?.keys ?: emptySet()
            val targetProfile = nearIdentity.activeProfileObject()
            uiState = TodayUiState.Content(
                todaySteps = 0L,
                challenge = challengeInfo,
                contractChallenge = cached.challenge,
                allChallenges = cached.allChallenges ?: emptyList(),
                phase = ChallengePhase.UPCOMING,
                freshness = FreshnessState.UPDATING,
                memberStatus = cached.memberStatus,
                memberVerdict = cached.memberVerdict,
                communitySummary = null,
                walletBalances = cached.walletBalances,
                lastSyncText = "Cached",
                isDemoMode = nearIdentity.isDemoMode(),
                isSandboxMode = nearIdentity.isSandboxMode(),
                activeProfile = targetProfile.id,
                challengeMemberStatuses = cached.challengeMemberStatuses ?: emptyMap(),
                submissionGraceSecs = targetProfile.defaultSubmissionGraceSecs,
                minMembers = targetProfile.defaultMinMembers,
                quorumPct = targetProfile.defaultQuorumPct,
                stakeNear = targetProfile.defaultStakeNear
            )
        }
        startForegroundCoordinator()
    }

    fun onResume() {
        viewModelScope.launch {
            healthAvailability = healthConnectManager.checkAvailability()
            if (healthAvailability != HealthConnectAvailability.Available) {
                hasPermission = false
                isPermissionCheckDone = true
                return@launch
            }
            val hasStep = healthConnectManager.hasStepPermission()
            hasPermission = hasStep
            isPermissionCheckDone = true
            if (hasStep) {
                loadTodayData(isUserRefresh = false)
            }
        }
    }

    fun setProfile(profile: String) {
        val target = PactProfiles.get(profile)
        // Cancel active coroutines from previous profile immediately
        refreshJob?.cancel()
        coordinatorJob?.cancel()
        contractClient.clearCaches()

        nearIdentity.setProfile(target.id)
        activeProfile = target.id
        isDemoMode = target.id == PactProfiles.DEMO.id
        contractClient.contractId = target.contractId

        val accountId = nearIdentity.identity()?.accountId ?: ""
        currentScope = ProfileScope.fromProfile(target, accountId)

        // Clear in-memory active transient states
        actionError = null
        lastTxHash = null
        selectedChallengeForResult = null
        selectedChallengeForJoin = null
        showResultSheet = false
        showJoinSheet = false
        enrolledChallengeIds = emptySet()
        challengeMemberStatuses = emptyMap()

        // Load persisted state for this specific profile and contract immediately
        val cached = stateStore.loadState(target.id, target.contractId, accountId)
        if (cached != null) {
            val nowChainNs = contractClient.currentChainTimeNs()
            val graceSecs = target.defaultSubmissionGraceSecs
            val phase = if (cached.challenge != null) {
                PactLifecycleManager.resolvePhase(
                    cached.challenge,
                    cached.memberStatus,
                    nowChainNs,
                    graceSecs,
                    cached.challenge.enrolledCount.coerceAtLeast(target.defaultMinMembers)
                )
            } else ChallengePhase.UPCOMING

            uiState = TodayUiState.Content(
                todaySteps = 0L,
                challenge = null,
                contractChallenge = cached.challenge,
                upcomingChallenge = null,
                allChallenges = cached.allChallenges ?: emptyList(),
                phase = phase,
                freshness = FreshnessState.UPDATING,
                memberStatus = cached.memberStatus,
                memberVerdict = cached.memberVerdict,
                communitySummary = null,
                walletBalances = cached.walletBalances,
                timeRemainingSecs = 0L,
                autoSubmitStatusText = null,
                lastSyncText = "Cached",
                isOffline = false,
                isSyncing = true,
                syncMessage = "Switching to ${target.label}...",
                isDemoMode = target.id == PactProfiles.DEMO.id,
                isSandboxMode = target.id == PactProfiles.SANDBOX.id,
                isCreatingSandbox = false,
                sandboxStatusText = null,
                activeProfile = target.id,
                challengeMemberStatuses = cached.challengeMemberStatuses ?: emptyMap(),
                submissionGraceSecs = target.defaultSubmissionGraceSecs,
                minMembers = target.defaultMinMembers,
                quorumPct = target.defaultQuorumPct,
                stakeNear = target.defaultStakeNear
            )
        } else {
            uiState = TodayUiState.Loading
        }

        startForegroundCoordinator()
        refresh(isUserRefresh = true)
    }

    fun toggleDemoMode() {
        val newMode = !nearIdentity.isDemoMode()
        setProfile(if (newMode) NearIdentity.PROFILE_DEMO else NearIdentity.PROFILE_REAL)
    }

    fun createSandboxChallenge(steps: Int = 10, durationSeconds: Int = 300, countdownSeconds: Int = 120) {
        viewModelScope.launch {
            val session = nearIdentity.session()
            if (session == null) {
                actionError = "Wallet session required to create sandbox challenge. Please sign in."
                return@launch
            }
            isCreatingSandboxChallenge = true
            actionError = null
            val prof = PactProfiles.get(activeProfile)
            sandboxCreationMessage = "Requesting relayer to create challenge on ${prof.contractId}..."
            val idempKey = "sb-$activeProfile-${session.accountId}-${System.currentTimeMillis() / 15_000}"
            val res = challengeRepository.createSandboxChallenge(
                baseUrl = backendBaseUrlProvider(),
                sessionToken = session.token,
                stepsPerDay = steps,
                durationSeconds = durationSeconds,
                countdownSeconds = countdownSeconds,
                idempotencyKey = idempKey
            )
            isCreatingSandboxChallenge = false
            res.fold(
                onSuccess = { created ->
                    sandboxCreationMessage = if (created.alreadyActive) {
                        "Challenge ${created.challengeId} is already in progress (${created.phase ?: "LIVE"})!"
                    } else {
                        "Challenge ${created.challengeId} created! Enrollment open."
                    }
                    lastTxHash = created.transactionId

                    // Immediately display in UI before contract view confirmation
                    val startNsLong = created.startNs?.toLongOrNull() ?: (System.currentTimeMillis() * 1_000_000L + (countdownSeconds * 1_000_000_000L))
                    val endNsLong = created.endNs?.toLongOrNull() ?: (startNsLong + (durationSeconds * 1_000_000_000L))
                    val tempCh = ContractChallenge(
                        challengeId = created.challengeId,
                        stepsPerDay = steps.toLong(),
                        daysRequired = 1,
                        startNs = startNsLong,
                        endNs = endNsLong,
                        enrolledCount = created.enrolledCount,
                        submittedCount = 0L,
                        qualifierCount = 0L,
                        outcome = ChallengeOutcome.Pending
                    )
                    val curr = uiState as? TodayUiState.Content
                    if (curr != null) {
                        uiState = curr.copy(
                            contractChallenge = tempCh,
                            phase = ChallengePhase.UPCOMING,
                            syncMessage = "Creating... confirming on NEAR"
                        )
                    }

                    refresh(isUserRefresh = true)
                },
                onFailure = { err ->
                    actionError = err.message ?: "Failed to create sandbox challenge"
                    sandboxCreationMessage = null
                }
            )
        }
    }

    fun checkPermissionsAndLoad() {
        viewModelScope.launch {
            healthAvailability = healthConnectManager.checkAvailability()
            if (healthAvailability != HealthConnectAvailability.Available) {
                hasPermission = false
                isPermissionCheckDone = true
                return@launch
            }

            val hasStep = healthConnectManager.hasStepPermission()
            hasPermission = hasStep
            isPermissionCheckDone = true
            if (hasPermission) {
                loadTodayData(isUserRefresh = false)
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            val hasStep = healthConnectManager.hasStepPermission()
            hasPermission = hasStep || granted
            isPermissionCheckDone = true
            if (hasPermission) {
                loadTodayData(isUserRefresh = true)
            }
        }
    }

    fun refresh(isUserRefresh: Boolean = true) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            loadTodayData(isUserRefresh = isUserRefresh)
        }
    }

    /**
     * Foreground live update coordinator:
     * - Ticks countdown every 1s using chain-time corrected clock.
     * - Detects lifecycle phase transitions and triggers auto-submit/refresh.
     * - Periodically polls contract views every 10s around active transitions.
     * - Syncs Health Connect every 60s while LIVE.
     */
    private fun startForegroundCoordinator() {
        coordinatorJob?.cancel()
        coordinatorJob = viewModelScope.launch {
            var tickCount = 0L
            while (isActive) {
                delay(1000L)
                tickCount++

                val content = uiState as? TodayUiState.Content ?: continue
                val ch = content.contractChallenge ?: continue
                val nowChainNs = contractClient.currentChainTimeNs()
                val graceSecs = nearIdentity.activeGraceSecs()
                val minMembers = content.minMembers

                // 1. Evaluate current phase
                val currentPhase = PactLifecycleManager.resolvePhase(ch, content.memberStatus, nowChainNs, graceSecs, minMembers)

                // 2. Compute target countdown
                val graceEndNs = ch.endNs + graceSecs * 1_000_000_000L
                val targetEndNs = when {
                    currentPhase == ChallengePhase.VOID_EARLY -> graceEndNs
                    nowChainNs < ch.startNs -> ch.startNs
                    nowChainNs < ch.endNs -> ch.endNs
                    else -> graceEndNs
                }
                val remainingSecs = ((targetEndNs - nowChainNs) / 1_000_000_000L).coerceAtLeast(0L)

                // Phase transition detected or periodic poll
                val phaseChanged = currentPhase != content.phase
                if (phaseChanged) {
                    uiState = content.copy(phase = currentPhase, timeRemainingSecs = remainingSecs)
                    refresh(isUserRefresh = false)
                } else {
                    uiState = content.copy(timeRemainingSecs = remainingSecs)
                }

                // 3. Foreground Auto-Submit during SUBMITTING
                if (currentPhase == ChallengePhase.SUBMITTING && !isAutoSubmitting) {
                    triggerForegroundAutoSubmit(ch, content.memberStatus, graceSecs)
                }

                // 4. Foreground Auto-Settle during SETTLING
                if (currentPhase == ChallengePhase.SETTLING && !isSettlingSingleFlight) {
                    val settleDecision = PactLifecycleManager.evaluateSettle(ch, nowChainNs, graceSecs, isDemoMode)
                    if (settleDecision is SettleDecision.ShouldSettle && settleDecision.isAppRelayer) {
                        triggerForegroundAutoSettle(ch)
                    }
                }

                // 5. Background sync: every 60s for health sync while LIVE
                if (tickCount % 60 == 0L && currentPhase == ChallengePhase.LIVE) {
                    refresh(isUserRefresh = false)
                }
            }
        }
    }

    private suspend fun loadTodayData(isUserRefresh: Boolean) {
        if (!hasPermission) {
            hasPermission = healthConnectManager.hasStepPermission()
            if (!hasPermission) return
        }

        val scopeAtStart = currentScope
        val baseUrl = backendBaseUrlProvider()
        val profile = scopeAtStart.profile
        contractClient.contractId = scopeAtStart.contractId
        val currentContent = uiState as? TodayUiState.Content

        if (currentContent == null) {
            uiState = TodayUiState.Loading
        } else {
            uiState = currentContent.copy(isSyncing = true, syncMessage = null, freshness = FreshnessState.UPDATING)
        }

        // 1. Sync chain time offset
        try {
            contractClient.fetchChainTimeOffsetNs()
        } catch (_: Exception) {}

        if (scopeAtStart.scopeKey != currentScope.scopeKey) return

        // 2. Query on-chain challenges and merge with cached history
        val identityInfo = nearIdentity.identity()
        val cachedChallenges = currentContent?.allChallenges ?: emptyList()
        var allChallenges: List<ContractChallenge> = cachedChallenges
        var contractChallenge: ContractChallenge? = null
        var upcomingChallenge: ContractChallenge? = null
        var memberStatus: MemberContractStatus? = null
        var memberVerdict: MemberVerdictDto? = null
        var isNetworkError = false

        val enrolledIds = mutableSetOf<String>()
        val statuses = mutableMapOf<String, MemberContractStatus>()

        try {
            val fetched = contractClient.getChallenges(0, 50)
            val filteredFetched = fetched.filter { ch ->
                PactStateStore.isChallengeValidForContract(ch.challengeId, contractClient.contractId)
            }
            allChallenges = PactStateStore.mergeChallenges(cachedChallenges, filteredFetched)
            val nowChainNs = contractClient.currentChainTimeNs()

            if (scopeAtStart.scopeKey != currentScope.scopeKey) return

            if (identityInfo != null) {
                val graceSecs = nearIdentity.activeGraceSecs()
                val graceWindowNs = graceSecs * 1_000_000_000L
                val relevantChallenges = allChallenges.filter { ch ->
                    ch.outcome is ChallengeOutcome.Pending ||
                    enrolledChallengeIds.contains(ch.challengeId) ||
                    (ch.endNs + graceWindowNs) >= nowChainNs
                }

                for (ch in relevantChallenges) {
                    if (scopeAtStart.scopeKey != currentScope.scopeKey) return
                    try {
                        val st = contractClient.getMemberStatus(ch.challengeId, identityInfo.accountId)
                        val isTermSub = stateStore.isTerminalState(profile, ch.challengeId, identityInfo.accountId, "submitted")
                        val isTermClaim = stateStore.isTerminalState(profile, ch.challengeId, identityInfo.accountId, "claimed")
                        val isTermWd = stateStore.isTerminalState(profile, ch.challengeId, identityInfo.accountId, "withdrawn")
                        val reconciledStatus = st.copy(
                            hasSubmitted = st.hasSubmitted || isTermSub,
                            hasClaimedReward = st.hasClaimedReward || isTermClaim,
                            hasWithdrawnStake = st.hasWithdrawnStake || isTermWd
                        )
                        if (reconciledStatus.isEnrolled) {
                            enrolledIds.add(ch.challengeId)
                            statuses[ch.challengeId] = reconciledStatus
                        }
                    } catch (_: Exception) {}
                }
                if (scopeAtStart.scopeKey != currentScope.scopeKey) return
                enrolledChallengeIds = enrolledIds
                challengeMemberStatuses = statuses
            }

            // Rule D.1: Source of truth is get_challenges from the selected contract.
            // Pick primary challenge by rule:
            // (a) one I'm enrolled in that isn't fully complete, else
            // (b) the upcoming joinable one, else
            // (c) none
            contractChallenge = PactLifecycleManager.choosePrimaryChallenge(
                allChallenges = allChallenges,
                memberStatuses = statuses,
                myAccountId = identityInfo?.accountId,
                nowChainNs = nowChainNs
            )

            // Find next upcoming
            upcomingChallenge = allChallenges.filter { it.startNs > nowChainNs }.minByOrNull { it.startNs }

            if (contractChallenge != null && identityInfo != null) {
                memberStatus = statuses[contractChallenge.challengeId]
                    ?: contractClient.getMemberStatus(contractChallenge.challengeId, identityInfo.accountId)
                nearBalanceYocto = memberStatus.nearBalanceYocto
            }
        } catch (e: Exception) {
            isNetworkError = true
        }

        // 3. Fallback to cached state if network failed (NEVER blank or flip to "No active challenge")
        if (allChallenges.isEmpty() && currentContent?.allChallenges?.isNotEmpty() == true) {
            allChallenges = currentContent.allChallenges
        }
        if (contractChallenge == null && currentContent?.contractChallenge != null) {
            contractChallenge = currentContent.contractChallenge
            memberStatus = currentContent.memberStatus
        }

        // 4. Fetch member verdict & community summary from backend
        if (identityInfo != null && contractChallenge != null) {
            val sessionToken = nearIdentity.session()?.token
            if (sessionToken != null) {
                try {
                    memberVerdict = challengeRepository.fetchMemberVerdict(baseUrl, sessionToken, profile).getOrNull()
                } catch (_: Exception) {}
            }
        }
        val commSummary = try {
            challengeRepository.fetchCommunitySummary(baseUrl, profile).getOrNull()
        } catch (_: Exception) { null }

        // 5. Query full real Wallet balances & reconcile terminal/pending state (with last-known-good fallback)
        var walletBalances: WalletBalances? = currentContent?.walletBalances
        if (identityInfo != null) {
            try {
                // Reconcile pending confirmations
                val pendingList = stateStore.getPendingConfirmations(profile)
                for (pending in pendingList) {
                    try {
                        when (pending.action) {
                            "ENROLL" -> {
                                if (contractClient.isEnrolled(pending.challengeId, identityInfo.accountId)) {
                                    stateStore.removePendingConfirmation(profile, pending.challengeId, pending.action)
                                }
                            }
                            "SUBMIT_COMPLETION" -> {
                                if (contractClient.hasSubmitted(pending.challengeId, identityInfo.accountId)) {
                                    stateStore.removePendingConfirmation(profile, pending.challengeId, pending.action)
                                    stateStore.setTerminalState(profile, pending.challengeId, identityInfo.accountId, "submitted")
                                }
                            }
                            "CLAIM_REWARD" -> {
                                if (contractClient.hasClaimedReward(pending.challengeId, identityInfo.accountId)) {
                                    stateStore.removePendingConfirmation(profile, pending.challengeId, pending.action)
                                    stateStore.setTerminalState(profile, pending.challengeId, identityInfo.accountId, "claimed")
                                }
                            }
                            "WITHDRAW_STAKE" -> {
                                if (contractClient.hasWithdrawnStake(pending.challengeId, identityInfo.accountId)) {
                                    stateStore.removePendingConfirmation(profile, pending.challengeId, pending.action)
                                    stateStore.setTerminalState(profile, pending.challengeId, identityInfo.accountId, "withdrawn")
                                }
                            }
                            "SETTLE_WEEK" -> {
                                val ch = contractClient.getChallenge(pending.challengeId)
                                if (ch != null && ch.outcome !is ChallengeOutcome.Pending) {
                                    stateStore.removePendingConfirmation(profile, pending.challengeId, pending.action)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                val balDetails = contractClient.getAccountBalanceDetails(identityInfo.accountId)
                nearBalanceYocto = balDetails.amountYocto

                // Staked: reuse already computed statuses to avoid redundant queries
                var enrolledUnwithdrawnCount = 0
                for (ch in allChallenges) {
                    val st = statuses[ch.challengeId]
                    if (st?.isEnrolled == true && !st.hasWithdrawnStake) {
                        enrolledUnwithdrawnCount++
                    }
                }

                val stakePerChallenge = if (isSandboxMode) NearContractClient.SANDBOX_STAKE_DEPOSIT_YOCTO else NearContractClient.STAKE_DEPOSIT_YOCTO
                val stakedYocto = BigInteger.valueOf(enrolledUnwithdrawnCount.toLong())
                    .multiply(stakePerChallenge)

                // PACT balance: ft_balance_of
                val pactBalYocto = contractClient.getPactBalance(identityInfo.accountId)

                // Claimable PACT: sum of reward_per_qualifier for settled Success challenges where is_qualifier && !has_claimed_reward
                var claimableYocto = BigInteger.ZERO
                for (ch in allChallenges) {
                    if (ch.outcome is ChallengeOutcome.Success) {
                        try {
                            val isQual = statuses[ch.challengeId]?.isQualifier ?: contractClient.isQualifier(ch.challengeId, identityInfo.accountId)
                            val hasClaimed = statuses[ch.challengeId]?.hasClaimedReward ?: contractClient.hasClaimedReward(ch.challengeId, identityInfo.accountId)
                            if (isQual && !hasClaimed) {
                                claimableYocto = claimableYocto.add(ch.outcome.rewardPerQualifierYocto)
                            }
                        } catch (_: Exception) {}
                    }
                }

                val minRequiredForJoin = if (isSandboxMode) {
                    stakePerChallenge.add(BigInteger("10000000000000000000000")) // 0.01 + 0.01 = 0.02 NEAR
                } else {
                    stakePerChallenge.add(BigInteger("50000000000000000000000")) // 0.5 + 0.05 = 0.55 NEAR
                }
                val isAffordable = balDetails.availableNearYocto >= minRequiredForJoin

                // Last-known-good balance preservation
                val finalAvailable = if (balDetails.amountYocto > BigInteger.ZERO) balDetails.availableNearYocto else (walletBalances?.availableNearYocto ?: BigInteger.ZERO)
                val finalStorage = if (balDetails.amountYocto > BigInteger.ZERO) balDetails.lockedStorageYocto else (walletBalances?.lockedStorageNearYocto ?: BigInteger.ZERO)
                val finalPact = if (pactBalYocto > BigInteger.ZERO) pactBalYocto else (walletBalances?.pactBalanceYocto ?: BigInteger.ZERO)

                walletBalances = WalletBalances(
                    accountId = identityInfo.accountId,
                    availableNearYocto = finalAvailable,
                    lockedStorageNearYocto = finalStorage,
                    stakedNearYocto = stakedYocto,
                    pactBalanceYocto = finalPact,
                    claimablePactYocto = claimableYocto,
                    isJoinAffordable = isAffordable
                )
            } catch (_: Exception) {}
        }

        // 6. Fetch raw steps from Health Connect (Never infer permission loss from zero records or query errors)
        val now = Instant.now()
        val weekAgo = now.minus(7, ChronoUnit.DAYS)
        val rawRecords = try {
            healthConnectManager.readRawSteps(weekAgo, now)
        } catch (_: Exception) {
            emptyList()
        }

        // 7. Compute Today's preview steps using deterministic short-window or calendar rule
        val dailyMap = HealthConnectManager.aggregateStepRecordsByDay(rawRecords, memberZoneId)
        val todayDate = LocalDate.now(memberZoneId)
        val isShort = contractChallenge != null && (contractChallenge.endNs - contractChallenge.startNs) < 86_400 * 1_000_000_000L
        val todaySteps = if (contractChallenge != null) {
            if (isShort) {
                val startInst = Instant.ofEpochMilli(contractChallenge.startNs / 1_000_000L)
                val endInst = Instant.ofEpochMilli(contractChallenge.endNs / 1_000_000L)
                HealthConnectManager.aggregateStepRecordsForWindow(rawRecords, startInst, endInst)
            } else {
                dailyMap[todayDate] ?: 0L
            }
        } else {
            0L
        }

        auditItems = healthConnectManager.auditOrigins(rawRecords)

        // 8. Sync steps to backend
        var isOffline = isNetworkError
        var syncMessage: String? = null

        if (identityInfo != null) {
            val sessionToken = nearIdentity.session()?.token ?: run {
                val signResult = nearIdentity.signIn(baseUrl)
                if (signResult.isSuccess) nearIdentity.session()?.token else null
            }

            if (sessionToken != null) {
                val payload = healthConnectManager.buildStepsSyncPayload(rawRecords, memberZoneId)
                val syncResult = syncRepository.uploadHealthData(
                    baseUrl = baseUrl,
                    apiKey = null,
                    payload = payload,
                    authToken = sessionToken,
                    forceDuplicate = isUserRefresh,
                    profile = profile
                )

                if (syncResult.isSuccess) {
                    lastSyncInstant = Instant.now()
                    syncMessage = "Synced to PACT"
                } else if (syncResult.statusCode == 409) {
                    lastSyncInstant = Instant.now()
                    syncMessage = "Up to date"
                } else {
                    isOffline = true
                    syncMessage = "Offline · Step data stored on device"
                }
            }
        }

        val nowChainNs = contractClient.currentChainTimeNs()
        val graceSecs = nearIdentity.activeGraceSecs()
        val minMembers = try { contractClient.getMinMembers() } catch (_: Exception) {
            when (profile) {
                NearIdentity.PROFILE_SANDBOX -> 1L
                NearIdentity.PROFILE_DEMO -> 3L
                else -> 5L
            }
        }
        val quorumPct = try { contractClient.getQuorumPct() } catch (_: Exception) {
            when (profile) {
                NearIdentity.PROFILE_SANDBOX -> 100
                else -> 70
            }
        }
        val stakeNear = when (profile) {
            NearIdentity.PROFILE_SANDBOX -> "0.01"
            else -> "0.5"
        }
        val phase = if (contractChallenge != null) {
            PactLifecycleManager.resolvePhase(contractChallenge, memberStatus, nowChainNs, graceSecs, minMembers)
        } else {
            ChallengePhase.UPCOMING
        }

        val freshness = when {
            isOffline -> FreshnessState.OFFLINE
            else -> FreshnessState.LIVE
        }

        val challengeInfo = contractChallenge?.let {
            ChallengeInfo(
                challengeId = it.challengeId,
                stepsPerDay = it.stepsPerDay.toInt(),
                daysRequired = it.daysRequired,
                startNs = it.startNs.toString(),
                endNs = it.endNs.toString(),
                enrolledCount = it.enrolledCount,
                qualifierCount = it.qualifierCount
            )
        }

        if (scopeAtStart.scopeKey != currentScope.scopeKey) return

        // Persist to local store so cache remains fresh
        if (allChallenges.isNotEmpty() || walletBalances != null || contractChallenge != null) {
            stateStore.saveState(
                profile,
                contractClient.contractId,
                identityInfo?.accountId ?: "",
                PersistedPactState(
                    profile = profile,
                    contractId = contractClient.contractId,
                    accountId = identityInfo?.accountId ?: "",
                    challenge = contractChallenge,
                    memberStatus = memberStatus,
                    memberVerdict = memberVerdict,
                    walletBalances = walletBalances,
                    allChallenges = allChallenges,
                    lastSyncTimestampMs = System.currentTimeMillis(),
                    challengeMemberStatuses = challengeMemberStatuses
                )
            )
        }

        if (scopeAtStart.scopeKey != currentScope.scopeKey) return

        val graceEndNs = (contractChallenge?.endNs ?: 0L) + graceSecs * 1_000_000_000L
        val targetEndNs = if (contractChallenge != null) {
            when {
                phase == ChallengePhase.VOID_EARLY -> graceEndNs
                nowChainNs < contractChallenge.startNs -> contractChallenge.startNs
                nowChainNs < contractChallenge.endNs -> contractChallenge.endNs
                else -> graceEndNs
            }
        } else 0L
        val remainingSecs = ((targetEndNs - nowChainNs) / 1_000_000_000L).coerceAtLeast(0L)

        uiState = TodayUiState.Content(
            todaySteps = todaySteps,
            challenge = challengeInfo,
            contractChallenge = contractChallenge,
            upcomingChallenge = upcomingChallenge,
            allChallenges = allChallenges,
            phase = phase,
            freshness = freshness,
            memberStatus = memberStatus,
            memberVerdict = memberVerdict,
            communitySummary = commSummary,
            walletBalances = walletBalances,
            timeRemainingSecs = remainingSecs,
            autoSubmitStatusText = currentContent?.autoSubmitStatusText,
            lastSyncText = formatLastSync(lastSyncInstant),
            isOffline = isOffline,
            isSyncing = false,
            syncMessage = syncMessage,
            isDemoMode = nearIdentity.isDemoMode(),
            isSandboxMode = nearIdentity.isSandboxMode(),
            isCreatingSandbox = isCreatingSandboxChallenge,
            sandboxStatusText = sandboxCreationMessage,
            activeProfile = nearIdentity.activeProfileName(),
            challengeMemberStatuses = challengeMemberStatuses,
            submissionGraceSecs = graceSecs,
            minMembers = minMembers,
            quorumPct = quorumPct,
            stakeNear = stakeNear
        )
    }

    // -----------------------------------------------------------------------
    // Foreground Auto-Submit & Auto-Settle Coordinators
    // -----------------------------------------------------------------------

    fun triggerForegroundAutoSubmit(
        challenge: ContractChallenge,
        memberStatus: MemberContractStatus?,
        graceSecs: Long
    ) {
        val identity = nearIdentity.identity() ?: return
        val sessionToken = nearIdentity.session()?.token ?: return
        val baseUrl = backendBaseUrlProvider()
        val profile = nearIdentity.activeProfileName()

        val isAlreadySubmittedTerminal = stateStore.isTerminalState(profile, challenge.challengeId, identity.accountId, "submitted") ||
                memberStatus?.hasSubmitted == true
        if (isAlreadySubmittedTerminal) {
            updateAutoSubmitStatus("Submitted ✓")
            return
        }

        val actionKey = "$profile:${challenge.challengeId}:auto_submit"
        if (isAutoSubmitting || inFlightActions.contains(actionKey)) return
        isAutoSubmitting = true
        inFlightActions.add(actionKey)

        viewModelScope.launch {
            updateAutoSubmitStatus("Verifying your steps...")

            try {
                // 1. Force final health sync with 1 spaced retry
                val now = Instant.now()
                val weekAgo = now.minus(7, ChronoUnit.DAYS)
                val rawRecords = try { healthConnectManager.readRawSteps(weekAgo, now) } catch (_: Exception) { emptyList() }
                if (rawRecords.isNotEmpty()) {
                    val payload = healthConnectManager.buildStepsSyncPayload(rawRecords, memberZoneId)
                    val syncRes = syncRepository.uploadHealthData(baseUrl, null, payload, sessionToken, true, profile)
                    if (!syncRes.isSuccess && syncRes.statusCode != 409) {
                        delay(2000L)
                        syncRepository.uploadHealthData(baseUrl, null, payload, sessionToken, true, profile)
                    }
                }

                // 2. Fetch attestation from backend
                val attestationRes = challengeRepository.fetchAttestation(baseUrl, challenge.challengeId, sessionToken, profile)
                val attestation = attestationRes.getOrNull()
                val attestationErr = attestationRes.exceptionOrNull()

                // 3. Evaluate auto submit decision
                val nowChainNs = contractClient.currentChainTimeNs()
                val minMembers = (uiState as? TodayUiState.Content)?.minMembers ?: 3L
                when (val decision = PactLifecycleManager.evaluateAutoSubmit(challenge, memberStatus, attestation, nowChainNs, graceSecs, attestationErr, minMembers)) {
                    is AutoSubmitDecision.ShouldSubmit -> {
                        updateAutoSubmitStatus("Submitting completion proof...")
                        val submitRes = contractClient.submitCompletion(
                            challengeId = challenge.challengeId,
                            accountId = identity.accountId,
                            passed = decision.attestation.passed,
                            expirySecs = decision.attestation.expiry,
                            signatureBase64 = decision.attestation.signature
                        )
                        if (submitRes.isSuccess) {
                            lastTxHash = submitRes.txHash
                            stateStore.saveTx(AppTxRecord(submitRes.txHash ?: "", "SUBMIT_COMPLETION", challenge.challengeId, System.currentTimeMillis(), profile))
                            if (submitRes.message.contains("pending confirmation", ignoreCase = true)) {
                                stateStore.savePendingConfirmation(PendingTxConfirmation(profile, challenge.challengeId, "SUBMIT_COMPLETION", submitRes.txHash ?: "", System.currentTimeMillis()))
                                updateAutoSubmitStatus("Submission pending verification...")
                            } else {
                                stateStore.setTerminalState(profile, challenge.challengeId, identity.accountId, "submitted")
                                stateStore.removePendingConfirmation(profile, challenge.challengeId, "SUBMIT_COMPLETION")
                                updateAutoSubmitStatus("Submitted ✓")
                            }
                            autoSubmitRetryCount = 0
                            loadTodayData(isUserRefresh = true)
                        } else {
                            if (submitRes.message?.contains("already submitted", ignoreCase = true) == true) {
                                stateStore.setTerminalState(profile, challenge.challengeId, identity.accountId, "submitted")
                                stateStore.removePendingConfirmation(profile, challenge.challengeId, "SUBMIT_COMPLETION")
                                updateAutoSubmitStatus("Submitted ✓")
                                autoSubmitRetryCount = 0
                                loadTodayData(isUserRefresh = true)
                            } else if (submitRes.message?.contains("balance", ignoreCase = true) == true || submitRes.message?.contains("gas", ignoreCase = true) == true) {
                                updateAutoSubmitStatus("Submission failed: Low NEAR balance or gas", autoDismiss = true)
                            } else {
                                updateAutoSubmitStatus("Submission failed: ${submitRes.message}", autoDismiss = true)
                            }
                        }
                    }
                    is AutoSubmitDecision.NotQualified -> {
                        updateAutoSubmitStatus("Not qualified · Step target not reached")
                    }
                    is AutoSubmitDecision.GraceClosed -> {
                        updateAutoSubmitStatus("The submission window closed before your attestation was sent. Your stake is still withdrawable.")
                    }
                    is AutoSubmitDecision.AlreadySubmitted -> {
                        stateStore.setTerminalState(profile, challenge.challengeId, identity.accountId, "submitted")
                        updateAutoSubmitStatus("Submitted ✓")
                    }
                    is AutoSubmitDecision.PermanentError -> {
                        updateAutoSubmitStatus(decision.message)
                    }
                    is AutoSubmitDecision.NeedsHealthSync -> {
                        updateAutoSubmitStatus("Step records not found · Final sync required")
                    }
                    is AutoSubmitDecision.RetryableError -> {
                        autoSubmitRetryCount++
                        if (autoSubmitRetryCount > 4) {
                            updateAutoSubmitStatus("Submission paused: ${decision.message}")
                        } else {
                            updateAutoSubmitStatus("Retrying verification (${decision.message})...")
                        }
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                updateAutoSubmitStatus("Auto-submit note: ${e.localizedMessage}", autoDismiss = true)
            } finally {
                isAutoSubmitting = false
                inFlightActions.remove(actionKey)
            }
        }
    }

    private var autoSubmitDismissJob: Job? = null
    private fun updateAutoSubmitStatus(status: String, autoDismiss: Boolean = false) {
        autoSubmitDismissJob?.cancel()
        val currentContent = uiState as? TodayUiState.Content ?: return
        uiState = currentContent.copy(autoSubmitStatusText = status)
        if (autoDismiss) {
            autoSubmitDismissJob = viewModelScope.launch {
                delay(4000L)
                val c = uiState as? TodayUiState.Content
                if (c?.autoSubmitStatusText == status) {
                    uiState = c.copy(autoSubmitStatusText = null)
                }
            }
        }
    }

    private fun triggerForegroundAutoSettle(challenge: ContractChallenge) {
        val profile = nearIdentity.activeProfileName()
        val actionKey = "$profile:${challenge.challengeId}:auto_settle"
        if (isSettlingSingleFlight || inFlightActions.contains(actionKey)) return
        isSettlingSingleFlight = true
        inFlightActions.add(actionKey)

        viewModelScope.launch {
            try {
                val settleRes = contractClient.settleWeek(challenge.challengeId)
                if (settleRes.isSuccess) {
                    lastTxHash = settleRes.txHash
                    stateStore.saveTx(AppTxRecord(settleRes.txHash ?: "", "SETTLE_WEEK", challenge.challengeId, System.currentTimeMillis(), profile))
                    loadTodayData(isUserRefresh = true)
                } else if (settleRes.message?.contains("already settled", ignoreCase = true) == true) {
                    loadTodayData(isUserRefresh = true)
                }
            } catch (_: Exception) {
            } finally {
                isSettlingSingleFlight = false
                inFlightActions.remove(actionKey)
            }
        }
    }

    // -----------------------------------------------------------------------
    // User Actions: Join, Settle, Claim, Withdraw
    // -----------------------------------------------------------------------

    private var joinErrorDismissJob: Job? = null
    fun setTransientJoinError(msg: String?) {
        joinErrorDismissJob?.cancel()
        joinError = msg
        if (msg != null) {
            joinErrorDismissJob = viewModelScope.launch {
                delay(4000L)
                if (joinError == msg) {
                    joinError = null
                }
            }
        }
    }

    private var actionErrorDismissJob: Job? = null
    fun setTransientActionError(msg: String?) {
        actionErrorDismissJob?.cancel()
        actionError = msg
        if (msg != null) {
            actionErrorDismissJob = viewModelScope.launch {
                delay(4000L)
                if (actionError == msg) {
                    actionError = null
                }
            }
        }
    }

    fun joinActiveChallenge() {
        val currentContent = uiState as? TodayUiState.Content ?: return
        val challenge = selectedChallengeForJoin ?: currentContent.challenge ?: return
        val identity = nearIdentity.identity() ?: return
        val profile = nearIdentity.activeProfileName()
        val actionKey = "$profile:${challenge.challengeId}:join"
        if (isJoining || inFlightActions.contains(actionKey)) return

        val nowChainNs = contractClient.currentChainTimeNs()
        val startNs = challenge.startNs?.toLongOrNull() ?: 0L
        if (startNs > 0 && nowChainNs >= startNs) {
            setTransientJoinError("Enrollment is closed because this challenge has already started.")
            return
        }

        viewModelScope.launch {
            isJoining = true
            inFlightActions.add(actionKey)
            setTransientJoinError(null)
            joinStepText = "Checking storage deposit..."

            try {
                val isRegistered = currentContent.memberStatus?.isStorageRegistered == true
                if (!isRegistered) {
                    joinStepText = "Registering token storage (0.00125 NEAR)..."
                    val storageRes = contractClient.storageDeposit(identity.accountId)
                    if (!storageRes.isSuccess) {
                        setTransientJoinError(storageRes.message)
                        isJoining = false
                        inFlightActions.remove(actionKey)
                        return@launch
                    }
                    stateStore.saveTx(AppTxRecord(storageRes.txHash ?: "", "STORAGE_DEPOSIT", challenge.challengeId, System.currentTimeMillis(), profile))
                }

                val stakeDisplay = if (nearIdentity.isSandboxMode()) "0.01" else "0.5"
                joinStepText = "Enrolling and depositing $stakeDisplay NEAR stake..."
                val enrollRes = contractClient.enroll(challenge.challengeId)
                if (!enrollRes.isSuccess) {
                    setTransientJoinError(enrollRes.message)
                    isJoining = false
                    inFlightActions.remove(actionKey)
                    return@launch
                }

                joinStepText = "Verifying on-chain enrollment..."
                lastTxHash = enrollRes.txHash
                stateStore.saveTx(AppTxRecord(enrollRes.txHash ?: "", "ENROLL", challenge.challengeId, System.currentTimeMillis(), profile))
                if (enrollRes.message.contains("pending confirmation", ignoreCase = true)) {
                    stateStore.savePendingConfirmation(PendingTxConfirmation(profile, challenge.challengeId, "ENROLL", enrollRes.txHash ?: "", System.currentTimeMillis()))
                } else {
                    stateStore.removePendingConfirmation(profile, challenge.challengeId, "ENROLL")
                }
                loadTodayData(isUserRefresh = true)
                showJoinSheet = false
                selectedChallengeForJoin = null
            } catch (e: Exception) {
                setTransientJoinError("Enrollment failed: ${e.localizedMessage}")
            } finally {
                isJoining = false
                joinStepText = null
                inFlightActions.remove(actionKey)
            }
        }
    }

    fun submitAttestation() {
        val currentContent = uiState as? TodayUiState.Content ?: return
        val challenge = selectedChallengeForResult ?: currentContent.contractChallenge ?: return
        val status = selectedChallengeForResult?.let { challengeMemberStatuses[it.challengeId] } ?: currentContent.memberStatus
        triggerForegroundAutoSubmit(challenge, status, nearIdentity.activeGraceSecs())
    }

    fun settleWeek() {
        val currentContent = uiState as? TodayUiState.Content ?: return
        val targetChallenge = selectedChallengeForResult ?: currentContent.contractChallenge ?: return
        val challengeId = targetChallenge.challengeId
        val profile = nearIdentity.activeProfileName()
        val actionKey = "$profile:$challengeId:settle"
        if (isSettling || inFlightActions.contains(actionKey)) return

        viewModelScope.launch {
            isSettling = true
            inFlightActions.add(actionKey)
            setTransientActionError(null)
            try {
                val settleRes = contractClient.settleWeek(challengeId)
                if (!settleRes.isSuccess) {
                    if (settleRes.message?.contains("already settled", ignoreCase = true) == true) {
                        loadTodayData(isUserRefresh = true)
                    } else {
                        setTransientActionError(settleRes.message)
                    }
                } else {
                    lastTxHash = settleRes.txHash
                    stateStore.saveTx(AppTxRecord(settleRes.txHash ?: "", "SETTLE_WEEK", challengeId, System.currentTimeMillis(), profile))
                    loadTodayData(isUserRefresh = true)
                }
            } catch (e: Exception) {
                setTransientActionError("Settlement failed: ${e.localizedMessage}")
            } finally {
                isSettling = false
                inFlightActions.remove(actionKey)
            }
        }
    }

    fun claimReward() {
        val currentContent = uiState as? TodayUiState.Content ?: return
        val targetChallenge = selectedChallengeForResult ?: currentContent.contractChallenge ?: return
        val challengeId = targetChallenge.challengeId
        val identity = nearIdentity.identity() ?: return
        val profile = nearIdentity.activeProfileName()
        val actionKey = "$profile:$challengeId:claim"
        if (isClaimingReward || inFlightActions.contains(actionKey)) return

        viewModelScope.launch {
            isClaimingReward = true
            inFlightActions.add(actionKey)
            setTransientActionError(null)
            try {
                val claimRes = contractClient.claimReward(challengeId)
                if (!claimRes.isSuccess) {
                    setTransientActionError(claimRes.message)
                } else {
                    lastTxHash = claimRes.txHash
                    stateStore.saveTx(AppTxRecord(claimRes.txHash ?: "", "CLAIM_REWARD", challengeId, System.currentTimeMillis(), profile))
                    if (claimRes.message.contains("pending confirmation", ignoreCase = true)) {
                        stateStore.savePendingConfirmation(PendingTxConfirmation(profile, challengeId, "CLAIM_REWARD", claimRes.txHash ?: "", System.currentTimeMillis()))
                    } else {
                        stateStore.setTerminalState(profile, challengeId, identity.accountId, "claimed")
                        stateStore.removePendingConfirmation(profile, challengeId, "CLAIM_REWARD")
                    }
                    loadTodayData(isUserRefresh = true)
                }
            } catch (e: Exception) {
                setTransientActionError("Claim failed: ${e.localizedMessage}")
            } finally {
                isClaimingReward = false
                inFlightActions.remove(actionKey)
            }
        }
    }

    fun withdrawStake() {
        val currentContent = uiState as? TodayUiState.Content ?: return
        val targetChallenge = selectedChallengeForResult ?: currentContent.contractChallenge ?: return
        val challengeId = targetChallenge.challengeId
        val identity = nearIdentity.identity() ?: return
        val profile = nearIdentity.activeProfileName()
        val actionKey = "$profile:$challengeId:withdraw"
        if (isWithdrawingStake || inFlightActions.contains(actionKey)) return

        viewModelScope.launch {
            isWithdrawingStake = true
            inFlightActions.add(actionKey)
            setTransientActionError(null)
            try {
                val withdrawRes = contractClient.withdrawStake(challengeId)
                if (!withdrawRes.isSuccess) {
                    setTransientActionError(withdrawRes.message)
                } else {
                    lastTxHash = withdrawRes.txHash
                    stateStore.saveTx(AppTxRecord(withdrawRes.txHash ?: "", "WITHDRAW_STAKE", challengeId, System.currentTimeMillis(), profile))
                    if (withdrawRes.message.contains("pending confirmation", ignoreCase = true)) {
                        stateStore.savePendingConfirmation(PendingTxConfirmation(profile, challengeId, "WITHDRAW_STAKE", withdrawRes.txHash ?: "", System.currentTimeMillis()))
                    } else {
                        stateStore.setTerminalState(profile, challengeId, identity.accountId, "withdrawn")
                        stateStore.removePendingConfirmation(profile, challengeId, "WITHDRAW_STAKE")
                    }
                    loadTodayData(isUserRefresh = true)
                }
            } catch (e: Exception) {
                setTransientActionError("Withdrawal failed: ${e.localizedMessage}")
            } finally {
                isWithdrawingStake = false
                inFlightActions.remove(actionKey)
            }
        }
    }

    fun getTxHistory(): List<AppTxRecord> {
        val profile = nearIdentity.activeProfileName()
        return stateStore.getTxsForProfile(profile)
    }

    private fun formatLastSync(instant: Instant?): String {
        if (instant == null) return "Never synced"
        val seconds = ChronoUnit.SECONDS.between(instant, Instant.now())
        return when {
            seconds < 10 -> "Synced just now"
            seconds < 60 -> "Synced ${seconds}s ago"
            seconds < 3600 -> "Synced ${seconds / 60}m ago"
            else -> "Synced ${seconds / 3600}h ago"
        }
    }
}

package com.opaque.healthsynctester.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opaque.healthsynctester.near.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class AuthScreenState {
    object Initializing : AuthScreenState()
    object Welcome : AuthScreenState()
    data class ChooseName(
        val inputName: String = "",
        val isCheckingAvailability: Boolean = false,
        val availability: AvailabilityResult? = null
    ) : AuthScreenState()
    data class Creating(
        val accountId: String,
        val currentStep: CreationStep = CreationStep.CREATING_ACCOUNT,
        val errorMessage: String? = null
    ) : AuthScreenState()
    data class WelcomeBack(
        val accountId: String,
        val isSigningIn: Boolean = false,
        val errorMessage: String? = null
    ) : AuthScreenState()
    data class Authenticated(
        val accountId: String,
        val publicKey: String,
        val session: NearSession
    ) : AuthScreenState()
    data class ImportAccount(
        val accountIdInput: String = "",
        val privateKeyInput: String = "",
        val isImporting: Boolean = false,
        val errorMessage: String? = null
    ) : AuthScreenState()
}

class AuthViewModel(
    private val nearIdentity: NearIdentity,
    private val backendBaseUrlProvider: () -> String = { "https://quickness-shining-latticed.ngrok-free.dev" }
) : ViewModel() {

    var state by mutableStateOf<AuthScreenState>(AuthScreenState.Initializing)
        private set

    var showProfileSheet by mutableStateOf(false)
    var showPostCreationBackupPrompt by mutableStateOf(false)
    var showBackupScreen by mutableStateOf(false)

    private var availabilityDebounceJob: Job? = null

    init {
        initializeAuthState()
    }

    fun initializeAuthState() {
        viewModelScope.launch {
            state = AuthScreenState.Initializing
            val identity = nearIdentity.identity()
            val seed = nearIdentity.seed()

            if (identity == null || seed == null) {
                // No account on device -> Welcome
                state = AuthScreenState.Welcome
                return@launch
            }

            val currentSession = nearIdentity.session()
            if (currentSession != null) {
                // Account and session both present -> Authenticated
                state = AuthScreenState.Authenticated(
                    accountId = identity.accountId,
                    publicKey = identity.publicKey,
                    session = currentSession
                )
            } else {
                // Account on device but signed out or session dropped -> Silent re-auth or WelcomeBack
                silentSignInOrWelcomeBack(identity)
            }
        }
    }

    private suspend fun silentSignInOrWelcomeBack(identity: NearIdentityInfo) {
        val result = nearIdentity.signIn(backendBaseUrlProvider())
        if (result.isSuccess) {
            val session = nearIdentity.session()
            if (session != null) {
                state = AuthScreenState.Authenticated(
                    accountId = identity.accountId,
                    publicKey = identity.publicKey,
                    session = session
                )
                return
            }
        }
        // If silent sign-in fails (e.g. backend offline or signed out), show calm WelcomeBack screen
        state = AuthScreenState.WelcomeBack(
            accountId = identity.accountId,
            errorMessage = if (!result.isSuccess && result.message.contains("Connection")) result.message else null
        )
    }

    fun onGetStartedClicked() {
        state = AuthScreenState.ChooseName(
            inputName = "",
            isCheckingAvailability = false,
            availability = null
        )
    }

    fun onImportAccountOptionClicked() {
        state = AuthScreenState.ImportAccount()
    }

    fun onBackToWelcome() {
        state = AuthScreenState.Welcome
    }

    fun onUsernameChanged(newName: String) {
        val currentState = state as? AuthScreenState.ChooseName ?: return
        val trimmed = newName.trim().lowercase()

        // Fast client validation
        val normalized = NearIdentity.normalizeAccountId(trimmed)
        val validation = NearIdentity.validateAccountId(normalized)

        if (trimmed.isEmpty()) {
            availabilityDebounceJob?.cancel()
            state = currentState.copy(inputName = trimmed, isCheckingAvailability = false, availability = null)
            return
        }

        if (validation != null) {
            availabilityDebounceJob?.cancel()
            state = currentState.copy(
                inputName = trimmed,
                isCheckingAvailability = false,
                availability = AvailabilityResult.Invalid(validation)
            )
            return
        }

        state = currentState.copy(
            inputName = trimmed,
            isCheckingAvailability = true,
            availability = null
        )

        // Debounce live network check
        availabilityDebounceJob?.cancel()
        availabilityDebounceJob = viewModelScope.launch {
            delay(400) // 400ms debounce
            val result = nearIdentity.checkUsernameAvailability(trimmed)
            state = (state as? AuthScreenState.ChooseName)?.copy(
                isCheckingAvailability = false,
                availability = result
            ) ?: state
        }
    }

    fun onCreateAccountSubmit(desiredName: String) {
        val accountId = NearIdentity.normalizeAccountId(desiredName)
        state = AuthScreenState.Creating(accountId = accountId, currentStep = CreationStep.CREATING_ACCOUNT)

        viewModelScope.launch {
            val result = nearIdentity.createAndSignInAccount(
                desiredAccountId = desiredName,
                backendBaseUrl = backendBaseUrlProvider(),
                onStepChanged = { step ->
                    state = (state as? AuthScreenState.Creating)?.copy(currentStep = step) ?: state
                }
            )

            if (result.isSuccess) {
                val identity = nearIdentity.identity()
                val session = nearIdentity.session()
                if (identity != null && session != null) {
                    showPostCreationBackupPrompt = true
                    state = AuthScreenState.Authenticated(
                        accountId = identity.accountId,
                        publicKey = identity.publicKey,
                        session = session
                    )
                } else {
                    state = AuthScreenState.Creating(
                        accountId = accountId,
                        errorMessage = "Account created, but failed to load session."
                    )
                }
            } else {
                state = AuthScreenState.Creating(
                    accountId = accountId,
                    errorMessage = result.message
                )
            }
        }
    }

    fun onWelcomeBackSignIn() {
        val current = state as? AuthScreenState.WelcomeBack ?: return
        state = current.copy(isSigningIn = true, errorMessage = null)

        viewModelScope.launch {
            val result = nearIdentity.signIn(backendBaseUrlProvider())
            if (result.isSuccess) {
                val identity = nearIdentity.identity()
                val session = nearIdentity.session()
                if (identity != null && session != null) {
                    state = AuthScreenState.Authenticated(
                        accountId = identity.accountId,
                        publicKey = identity.publicKey,
                        session = session
                    )
                    return@launch
                }
            }
            state = current.copy(
                isSigningIn = false,
                errorMessage = result.message
            )
        }
    }

    fun onImportAccountSubmit(accountIdInput: String, privateKeyInput: String) {
        state = AuthScreenState.ImportAccount(
            accountIdInput = accountIdInput,
            privateKeyInput = privateKeyInput,
            isImporting = true,
            errorMessage = null
        )

        viewModelScope.launch {
            val result = nearIdentity.importAccount(
                desiredAccountId = accountIdInput,
                privateKeyOrSeedInput = privateKeyInput,
                backendBaseUrl = backendBaseUrlProvider()
            )

            if (result.isSuccess) {
                val identity = nearIdentity.identity()
                val session = nearIdentity.session()
                if (identity != null && session != null) {
                    state = AuthScreenState.Authenticated(
                        accountId = identity.accountId,
                        publicKey = identity.publicKey,
                        session = session
                    )
                    return@launch
                }
            }

            state = AuthScreenState.ImportAccount(
                accountIdInput = accountIdInput,
                privateKeyInput = privateKeyInput,
                isImporting = false,
                errorMessage = result.message
            )
        }
    }

    fun onSignOut() {
        nearIdentity.signOut()
        showProfileSheet = false
        val identity = nearIdentity.identity()
        if (identity != null) {
            state = AuthScreenState.WelcomeBack(accountId = identity.accountId)
        } else {
            state = AuthScreenState.Welcome
        }
    }

    fun onDeleteLocalAccount() {
        nearIdentity.deleteLocalAccount()
        showProfileSheet = false
        state = AuthScreenState.Welcome
    }
}

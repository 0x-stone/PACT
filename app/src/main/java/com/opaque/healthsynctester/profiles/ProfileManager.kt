package com.opaque.healthsynctester.profiles

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ProfileManager holds the selected profile id, persists it to SharedPreferences,
 * and loads it synchronously on cold start BEFORE any network call is initiated.
 */
class ProfileManager(context: Context) {

    companion object {
        const val PREFS_NAME = "pact_profile_prefs"
        const val KEY_ACTIVE_PROFILE = "key_active_profile_id"

        @Volatile
        private var instance: ProfileManager? = null

        fun getInstance(context: Context): ProfileManager {
            return instance ?: synchronized(this) {
                instance ?: ProfileManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _activeProfileFlow: MutableStateFlow<PactProfile>
    val activeProfileFlow: StateFlow<PactProfile>

    init {
        // Synchronously load on cold start (default to DEMO)
        val storedId = prefs.getString(KEY_ACTIVE_PROFILE, PactProfiles.DEMO.id)
        val initial = PactProfiles.get(storedId)
        _activeProfileFlow = MutableStateFlow(initial)
        activeProfileFlow = _activeProfileFlow.asStateFlow()
    }

    val currentProfile: PactProfile
        get() = _activeProfileFlow.value

    val currentProfileId: String
        get() = currentProfile.id

    val currentContractId: String
        get() = currentProfile.contractId

    fun setProfile(profileId: String): PactProfile {
        val target = PactProfiles.get(profileId)
        prefs.edit().putString(KEY_ACTIVE_PROFILE, target.id).commit() // commit synchronously
        _activeProfileFlow.value = target
        return target
    }

    fun isDemo(): Boolean = currentProfile.id == PactProfiles.DEMO.id
    fun isSandbox(): Boolean = currentProfile.id == PactProfiles.SANDBOX.id
}

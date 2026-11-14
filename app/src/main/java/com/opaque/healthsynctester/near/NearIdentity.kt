package com.opaque.healthsynctester.near

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import com.opaque.healthsynctester.profiles.PactProfiles
import com.opaque.healthsynctester.profiles.ProfileManager

/** Simple ok/failure result. */
data class NearResult(
    val isSuccess: Boolean,
    val message: String,
    val detail: String = ""
)

/** A backend session issued by `POST /api/auth/verify` (NEP-413 sign-in). */
data class NearSession(
    val token: String,
    val accountId: String,
    val expiresAt: String
)

/** The device's NEAR identity: an account and its Full Access key. */
data class NearIdentityInfo(
    val accountId: String,
    /** "ed25519:<base58>" — the Full Access Key of [accountId]. */
    val publicKey: String
)

/** Formatted credentials for backup and recovery (Stage C). */
data class BackupCredentials(
    val network: String = "testnet",
    val accountId: String,
    val publicKey: String,
    val privateKey: String,
    val warning: String = "SENSITIVE CREDENTIALS. Anyone who has this private key controls this NEAR account. Do not share or upload."
) {
    fun toJsonString(): String {
        val obj = com.google.gson.JsonObject().apply {
            addProperty("_warning", warning)
            addProperty("network", network)
            addProperty("account_id", accountId)
            addProperty("public_key", publicKey)
            addProperty("private_key", privateKey)
        }
        return com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj)
    }
}

/** 3-step account creation progress as specified in Stage B design. */
enum class CreationStep(val label: String) {
    CREATING_ACCOUNT("Creating your account"),
    SECURING_KEY("Securing your key"),
    SIGNING_IN("Signing you in")
}

/** Username availability result for live debounced checks. */
sealed class AvailabilityResult {
    object Available : AvailabilityResult()
    data class Taken(val message: String) : AvailabilityResult()
    data class Invalid(val reason: String) : AvailabilityResult()
    data class Error(val message: String) : AvailabilityResult()
}

/**
 * Mobile NEAR identity manager (Stage B: Sign-up & Sign-in).
 *
 * Implements:
 * - Ed25519 keypair generation on device.
 * - Hardware AES-256-GCM seed wrapping via [KeystoreCrypto].
 * - Encrypted session token storage.
 * - Live username availability check via NEAR RPC.
 * - Account registration via public testnet helper with failure detection.
 * - NEP-413 challenge sign-in with backend.
 * - Silent re-authentication for returning users.
 * - Secure account import and clean local account deletion.
 */
class NearIdentity(val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var lastNetworkException: Exception? = null

    // -------------------------------------------------------------------
    // Persisted Identity & Encrypted Session State
    // -------------------------------------------------------------------

    /** Unwraps the 32-byte Ed25519 seed from AndroidKeyStore AES-GCM. */
    fun seed(): ByteArray? =
        prefs.getString(KEY_WRAPPED_SEED, null)?.let {
            try { KeystoreCrypto.unwrap(it) } catch (e: Exception) { null }
        }

    /** The device identity info, or null if no account exists on this device. */
    fun identity(): NearIdentityInfo? {
        val accountId = prefs.getString(KEY_ACCOUNT_ID, null) ?: return null
        val publicKey = prefs.getString(KEY_PUBLIC_KEY, null) ?: return null
        return NearIdentityInfo(accountId, publicKey)
    }

    /** Returns true if an account is registered and its key is accessible locally. */
    fun hasLocalAccount(): Boolean = identity() != null && seed() != null

    val profileManager by lazy { ProfileManager.getInstance(context) }

    /** Profile management: Real, Demo, or Sandbox. Default: Real. */
    fun activeProfile(): String = profileManager.currentProfileId

    fun setProfile(profile: String) {
        profileManager.setProfile(profile)
    }

    fun isDemoMode(): Boolean = profileManager.isDemo()
    fun isSandboxMode(): Boolean = profileManager.isSandbox()

    fun setDemoMode(isDemo: Boolean) {
        setProfile(if (isDemo) PROFILE_DEMO else PROFILE_REAL)
    }

    fun activeContractId(): String = profileManager.currentContractId

    fun activeProfileName(): String = profileManager.currentProfileId

    fun activeGraceSecs(): Long = profileManager.currentProfile.defaultSubmissionGraceSecs

    fun activeProfileObject(): com.opaque.healthsynctester.profiles.PactProfile = profileManager.currentProfile

    /**
     * Stored session token (decrypted from AES-GCM wrap).
     * Returns null if signed out or token absent.
     */
    fun session(): NearSession? {
        val wrappedToken = prefs.getString(KEY_SESSION_TOKEN, null) ?: return null
        val token = try {
            KeystoreCrypto.unwrap(wrappedToken).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            // Fallback if token was stored plaintext previously
            wrappedToken
        }
        val accountId = prefs.getString(KEY_SESSION_ACCOUNT_ID, null) ?: return null
        val expiresAt = prefs.getString(KEY_SESSION_EXPIRES_AT, null) ?: return null
        return NearSession(token, accountId, expiresAt)
    }

    /** Drops the session token only. The local encrypted key and account remain. */
    fun signOut() {
        prefs.edit()
            .remove(KEY_SESSION_TOKEN)
            .remove(KEY_SESSION_ACCOUNT_ID)
            .remove(KEY_SESSION_EXPIRES_AT)
            .apply()
    }

    /** Completely removes the encrypted key, account ID, and session from this device. */
    fun deleteLocalAccount() {
        prefs.edit().clear().apply()
    }

    // -------------------------------------------------------------------
    // Live Username Availability Check
    // -------------------------------------------------------------------

    suspend fun checkUsernameAvailability(desiredName: String): AvailabilityResult =
        withContext(Dispatchers.IO) {
            val accountId = normalizeAccountId(desiredName)
            val validation = validateAccountId(accountId)
            if (validation != null) {
                return@withContext AvailabilityResult.Invalid(validation)
            }

            val queryBody = JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", "availability")
                addProperty("method", "query")
                val params = JsonObject().apply {
                    addProperty("request_type", "view_account")
                    addProperty("finality", "final")
                    addProperty("account_id", accountId)
                }
                add("params", params)
            }

            val response = postJson(DEFAULT_RPC_URL, queryBody)
                ?: return@withContext AvailabilityResult.Error(
                    "Cannot reach NEAR network to check availability"
                )

            val json = response.first
            if (json.has("result")) {
                return@withContext AvailabilityResult.Taken(
                    "'$accountId' is already taken on NEAR testnet"
                )
            }

            if (json.has("error")) {
                val errObj = json.get("error").asJsonObject
                val cause = errObj.get("cause")?.asJsonObject
                val name = cause?.get("name")?.asString
                val fullErr = errObj.toString()
                if (name == "UNKNOWN_ACCOUNT" || fullErr.contains("does not exist")) {
                    return@withContext AvailabilityResult.Available
                }
                return@withContext AvailabilityResult.Error(
                    errObj.get("message")?.asString ?: "Network check failed"
                )
            }

            AvailabilityResult.Available
        }

    // -------------------------------------------------------------------
    // Stepwise Account Creation & Sign-in (Stage B Flow)
    // -------------------------------------------------------------------

    /** Backward-compatible creation method for legacy tester harness. */
    suspend fun createTestnetAccount(desiredAccountId: String): NearResult =
        createAndSignInAccount(desiredAccountId, "http://97.107.130.182:8000") {}

    suspend fun createAndSignInAccount(
        desiredAccountId: String,
        backendBaseUrl: String,
        onStepChanged: (CreationStep) -> Unit
    ): NearResult = withContext(Dispatchers.IO) {
        val accountId = normalizeAccountId(desiredAccountId)
        val validation = validateAccountId(accountId)
        if (validation != null) {
            return@withContext NearResult(false, "Invalid username format", validation)
        }

        // STEP 1: Creating your account on testnet
        onStepChanged(CreationStep.CREATING_ACCOUNT)
        val seedBytes = ByteArray(SEED_BYTES).also { SecureRandom().nextBytes(it) }
        val publicKeyWire = "ed25519:" + Base58.encode(publicKeyBytes(seedBytes))

        val createBody = JsonObject().apply {
            addProperty("newAccountId", accountId)
            addProperty("newAccountPublicKey", publicKeyWire)
        }

        val request = Request.Builder()
            .url("$TESTNET_HELPER_URL/account")
            .post(createBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("User-Agent", "PACT/1.0.0 (Android)")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                val json = try { gson.fromJson(text, JsonObject::class.java) } catch (e: Exception) { null }

                // Check for on-chain failure receipts even if HTTP code was 200
                if (json != null && json.has("status")) {
                    val statusObj = json.getAsJsonObject("status")
                    if (statusObj.has("Failure")) {
                        val failStr = statusObj.toString()
                        val msg = if (failStr.contains("AccountAlreadyExists")) {
                            "The username '$accountId' was just registered by someone else. Please choose another."
                        } else {
                            "Account creation failed on testnet: $failStr"
                        }
                        return@withContext NearResult(false, msg, failStr)
                    }
                }

                if (!response.isSuccessful) {
                    return@withContext NearResult(
                        false,
                        "Testnet helper error (HTTP ${response.code})",
                        text
                    )
                }
            }
        } catch (e: Exception) {
            return@withContext networkFailure(e, TESTNET_HELPER_URL)
        }

        // STEP 2: Securing your key in AndroidKeyStore
        onStepChanged(CreationStep.SECURING_KEY)
        try {
            val wrapped = KeystoreCrypto.wrap(seedBytes)
            prefs.edit()
                .putString(KEY_WRAPPED_SEED, wrapped)
                .putString(KEY_ACCOUNT_ID, accountId)
                .putString(KEY_PUBLIC_KEY, publicKeyWire)
                .apply()
        } catch (e: Exception) {
            return@withContext NearResult(
                false,
                "Failed to secure key in Android Keystore: ${e.localizedMessage}",
                e.toString()
            )
        }

        // STEP 3: Signing you in to backend
        onStepChanged(CreationStep.SIGNING_IN)
        val signInResult = signIn(backendBaseUrl)
        if (!signInResult.isSuccess) {
            // Account is created and key is saved, but backend session failed
            return@withContext NearResult(
                false,
                "Account created on testnet, but backend sign-in failed: ${signInResult.message}",
                signInResult.detail
            )
        }

        NearResult(true, "Welcome to PACT, $accountId!", signInResult.detail)
    }

    // -------------------------------------------------------------------
    // Sign-in (NEP-413 Challenge -> Backend Session Token)
    // -------------------------------------------------------------------

    suspend fun signIn(backendBaseUrl: String): NearResult = withContext(Dispatchers.IO) {
        val identityInfo = identity()
        val seedBytes = seed()
        if (identityInfo == null || seedBytes == null) {
            return@withContext NearResult(
                isSuccess = false,
                message = "No local account found on this device",
                detail = "Create a new account or import an existing one."
            )
        }
        val baseUrl = backendBaseUrl.trim().removeSuffix("/")

        // 1. Request single-use challenge nonce
        val nonceBody = JsonObject().apply {
            addProperty("accountId", identityInfo.accountId)
        }
        val nonceResponse = postJson("$baseUrl/api/auth/nonce", nonceBody)
            ?: return@withContext networkFailure(lastNetworkException, baseUrl)

        if (!nonceResponse.second.isSuccessful) {
            return@withContext httpFailure(nonceResponse.first, nonceResponse.second.code, "/api/auth/nonce")
        }
        val nonceJson = nonceResponse.first
        val nonceString = nonceJson["nonce"]?.asString
            ?: return@withContext NearResult(false, "Backend nonce response is missing 'nonce'")
        val message = nonceJson["message"]?.asString
            ?: return@withContext NearResult(false, "Backend nonce response is missing 'message'")
        val recipient = nonceJson["recipient"]?.asString
            ?: return@withContext NearResult(false, "Backend nonce response is missing 'recipient'")

        val nonceBytes = try {
            Base64.decode(nonceString, Base64.NO_WRAP)
        } catch (e: Exception) {
            return@withContext NearResult(false, "Invalid nonce encoding from backend")
        }
        if (nonceBytes.size != SEED_BYTES) {
            return@withContext NearResult(false, "Nonce length invalid (${nonceBytes.size} bytes)")
        }

        // 2. Sign the NEP-413 payload (seed plaintext in memory strictly here)
        val digest = Nep413.hash(message, nonceBytes, recipient)
        val signature = sign(digest, seedBytes)
        if (signature.size != SIGNATURE_BYTES) {
            return@withContext NearResult(false, "Signing failure (signature length invalid)")
        }

        // 3. Verify signature with backend and receive session token
        val verifyBody = JsonObject().apply {
            addProperty("accountId", identityInfo.accountId)
            addProperty("publicKey", identityInfo.publicKey)
            addProperty("signature", Base64.encodeToString(signature, Base64.NO_WRAP))
            addProperty("nonce", nonceString)
        }
        val verifyResponse = postJson("$baseUrl/api/auth/verify", verifyBody)
            ?: return@withContext networkFailure(lastNetworkException, baseUrl)

        if (!verifyResponse.second.isSuccessful) {
            return@withContext httpFailure(verifyResponse.first, verifyResponse.second.code, "/api/auth/verify")
        }
        val sessionJson = verifyResponse.first
        val token = sessionJson["token"]?.asString
            ?: return@withContext NearResult(false, "Backend did not return a session token")
        val sessionAccount = sessionJson["accountId"]?.asString ?: identityInfo.accountId

        // Store session token encrypted at rest
        val wrappedToken = try {
            KeystoreCrypto.wrap(token.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            token
        }

        prefs.edit()
            .putString(KEY_SESSION_TOKEN, wrappedToken)
            .putString(KEY_SESSION_ACCOUNT_ID, sessionAccount)
            .putString(KEY_SESSION_EXPIRES_AT, sessionJson["expiresAt"]?.asString ?: "(unknown)")
            .apply()

        NearResult(true, "Signed in as $sessionAccount", sessionJson.toString())
    }

    // -------------------------------------------------------------------
    // Account Import ("I already have an account")
    // -------------------------------------------------------------------

    suspend fun importAccount(
        desiredAccountId: String,
        privateKeyOrSeedInput: String,
        backendBaseUrl: String
    ): NearResult = withContext(Dispatchers.IO) {
        val accountId = normalizeAccountId(desiredAccountId)
        val validation = validateAccountId(accountId)
        if (validation != null) {
            return@withContext NearResult(false, "Invalid username", validation)
        }

        val rawInput = privateKeyOrSeedInput.trim()
        val extractedKey = if (rawInput.startsWith("{")) {
            try {
                val json = gson.fromJson(rawInput, JsonObject::class.java)
                json.get("private_key")?.asString ?: rawInput
            } catch (e: Exception) {
                rawInput
            }
        } else {
            rawInput
        }

        val cleanKey = extractedKey.trim().removePrefix("ed25519:")
        val rawBytes = try {
            Base58.decode(cleanKey)
        } catch (e: Exception) {
            return@withContext NearResult(
                false,
                "Invalid private key format",
                "Key must be valid base58 (32-byte seed or 64-byte keypair)"
            )
        }

        val seedBytes = when (rawBytes.size) {
            32 -> rawBytes
            64 -> rawBytes.copyOfRange(0, 32)
            else -> return@withContext NearResult(
                false,
                "Unsupported key length (${rawBytes.size} bytes)",
                "Expected 32 or 64 bytes"
            )
        }

        val publicKeyWire = "ed25519:" + Base58.encode(publicKeyBytes(seedBytes))

        // Verify key exists on chain for this account
        val keyListBody = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", "import-check")
            addProperty("method", "query")
            val params = JsonObject().apply {
                addProperty("request_type", "view_access_key_list")
                addProperty("finality", "final")
                addProperty("account_id", accountId)
            }
            add("params", params)
        }

        val rpcResponse = postJson(DEFAULT_RPC_URL, keyListBody)
            ?: return@withContext networkFailure(lastNetworkException, DEFAULT_RPC_URL)

        val rpcJson = rpcResponse.first
        if (rpcJson.has("error")) {
            return@withContext NearResult(
                false,
                "Account '$accountId' was not found on NEAR testnet",
                rpcJson.get("error").toString()
            )
        }

        val keysArr = rpcJson.getAsJsonObject("result")?.getAsJsonArray("keys")
        val isFullAccess = keysArr?.any {
            val keyObj = it.asJsonObject
            val pub = keyObj.get("public_key")?.asString
            val perm = keyObj.getAsJsonObject("access_key")?.get("permission")?.toString() ?: ""
            pub == publicKeyWire && perm.contains("FullAccess")
        } ?: false

        if (!isFullAccess) {
            return@withContext NearResult(
                false,
                "Key does not match '$accountId'",
                "The provided key is not a Full Access key for this account on testnet."
            )
        }

        // Key verified on chain; secure key in AndroidKeyStore
        try {
            val wrapped = KeystoreCrypto.wrap(seedBytes)
            prefs.edit()
                .putString(KEY_WRAPPED_SEED, wrapped)
                .putString(KEY_ACCOUNT_ID, accountId)
                .putString(KEY_PUBLIC_KEY, publicKeyWire)
                .apply()
        } catch (e: Exception) {
            return@withContext NearResult(
                false,
                "Failed to secure key on device",
                e.localizedMessage ?: ""
            )
        }

        // Authenticate with backend
        val signResult = signIn(backendBaseUrl)
        if (!signResult.isSuccess) {
            return@withContext NearResult(
                true,
                "Imported '$accountId', but sign-in failed: ${signResult.message}",
                signResult.detail
            )
        }

        NearResult(true, "Successfully imported '$accountId'", signResult.detail)
    }

    /**
     * Prepares full credentials for the backup and export screen (Stage C).
     * Returns null if no account exists on the device.
     */
    fun getBackupCredentials(): BackupCredentials? {
        val identityInfo = identity() ?: return null
        val seedBytes = seed() ?: return null
        val fullKeyPairBytes = seedBytes + publicKeyBytes(seedBytes)
        val privateKeyWire = "ed25519:" + Base58.encode(fullKeyPairBytes)
        return BackupCredentials(
            network = "testnet",
            accountId = identityInfo.accountId,
            publicKey = identityInfo.publicKey,
            privateKey = privateKeyWire
        )
    }

    // -------------------------------------------------------------------
    // Internal HTTP & Cryptographic Helpers
    // -------------------------------------------------------------------

    private fun postJson(url: String, body: JsonObject): Pair<JsonObject, okhttp3.Response>? {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("User-Agent", "PACT/1.0.0 (Android)")
            .header("ngrok-skip-browser-warning", "true")
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                val json: JsonObject = try {
                    gson.fromJson(text, JsonObject::class.java)
                } catch (e: Exception) {
                    null
                } ?: JsonObject().apply { addProperty("raw", text) }
                json to response
            }
        } catch (e: Exception) {
            lastNetworkException = e
            null
        }
    }

    private fun httpFailure(body: JsonObject, code: Int, endpoint: String): NearResult =
        NearResult(
            isSuccess = false,
            message = "$endpoint returned HTTP $code",
            detail = body["detail"]?.asString ?: body.toString()
        )

    private fun networkFailure(e: Exception?, where: String): NearResult {
        val message = when (e) {
            is java.net.ConnectException ->
                "Unable to connect to $where. Please check your network connection."
            is java.net.SocketTimeoutException ->
                "Connection to $where timed out."
            is java.net.UnknownHostException ->
                "Could not resolve host '$where'."
            else -> "Network request to $where failed."
        }
        return NearResult(
            isSuccess = false,
            message = message,
            detail = e?.localizedMessage ?: e?.javaClass?.simpleName ?: ""
        )
    }

    private fun sign(digest: ByteArray, seedBytes: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seedBytes, 0))
        signer.update(digest, 0, digest.size)
        return signer.generateSignature()
    }

    private fun publicKeyBytes(seedBytes: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(seedBytes, 0).generatePublicKey().encoded

    companion object {
        const val PROFILE_REAL = "real"
        const val PROFILE_DEMO = "demo"
        const val PROFILE_SANDBOX = "sandbox"

        private const val PREFS_NAME = "near_identity"
        private const val KEY_ACTIVE_PROFILE = "active_profile_name"
        private const val KEY_WRAPPED_SEED = "wrapped_seed"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_PUBLIC_KEY = "public_key_wire"
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_SESSION_ACCOUNT_ID = "session_account_id"
        private const val KEY_SESSION_EXPIRES_AT = "session_expires_at"
        private const val KEY_DEMO_MODE = "demo_mode_active"
        private const val SEED_BYTES = 32
        private const val SIGNATURE_BYTES = 64

        const val TESTNET_HELPER_URL = "https://helper.testnet.near.org"
        const val DEFAULT_RPC_URL = "https://test.rpc.fastnear.com"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val ACCOUNT_ID_RE = Regex("^[a-z0-9._-]{2,64}$")
        private const val SEPARATORS = "._-"

        fun suggestedAccountId(): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
            val suffix = (1..6).map { alphabet[SecureRandom().nextInt(alphabet.length)] }.joinToString("")
            return "opaque-$suffix.testnet"
        }

        fun normalizeAccountId(desired: String): String {
            val trimmed = desired.trim().lowercase()
            return if (trimmed.contains('.')) trimmed else "$trimmed.testnet"
        }

        fun validateAccountId(accountId: String): String? {
            if (!ACCOUNT_ID_RE.matches(accountId)) {
                return "Account name must be 2–64 characters using lowercase letters, numbers, '.', '_' or '-'"
            }
            if (accountId.first() in SEPARATORS || accountId.last() in SEPARATORS) {
                return "Account name cannot begin or end with a separator ('.', '_' or '-')"
            }
            accountId.zipWithNext().forEach { (a, b) ->
                if (a in SEPARATORS && b in SEPARATORS) {
                    return "Account name cannot contain consecutive separators"
                }
            }
            return null
        }
    }
}

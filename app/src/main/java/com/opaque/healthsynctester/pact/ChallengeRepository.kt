package com.opaque.healthsynctester.pact

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.opaque.healthsynctester.profiles.RequestLogEntry
import com.opaque.healthsynctester.profiles.RequestLogTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ChallengeInfo(
    @SerializedName("challenge_id") val challengeId: String = "",
    @SerializedName("steps_per_day") val stepsPerDay: Int = 0,
    @SerializedName("days_required") val daysRequired: Int = 0,
    @SerializedName("start_ns") val startNs: String? = null,
    @SerializedName("end_ns") val endNs: String? = null,
    @SerializedName("enrolled_count") val enrolledCount: Long = 0,
    @SerializedName("qualifier_count") val qualifierCount: Long = 0,
    @SerializedName("outcome") val outcome: String? = null,
    @SerializedName("start_iso") val startIso: String? = null,
    @SerializedName("end_iso") val endIso: String? = null
)

data class PactAttestationDto(
    @SerializedName("schemaTag") val schemaTag: String = "",
    @SerializedName("contractId") val contractId: String = "",
    @SerializedName("challengeId") val challengeId: String = "",
    @SerializedName("accountId") val accountId: String = "",
    @SerializedName("passed") val passed: Boolean = false,
    @SerializedName("expiry") val expiry: Long = 0L,
    @SerializedName("expiresAt") val expiresAt: String = "",
    @SerializedName("signature") val signature: String = "",
    @SerializedName("publicKey") val publicKey: String = ""
)

class AttestationException(val statusCode: Int, message: String) : Exception(message)

data class MemberVerdictDto(
    val passed: Boolean,
    val daysCompleted: Int,
    val message: String
)

data class CommunitySummaryDto(
    @SerializedName("enrolled") val enrolled: Int = 0,
    @SerializedName("onTrackPct") val onTrackPct: Int = 0,
    @SerializedName("daysLeft") val daysLeft: Int = 0
)

class ChallengeRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val req = chain.request()
            val reqProfile = req.header("X-Pact-Profile")
            val response = chain.proceed(req)

            // Record in thread-safe Diagnostics tracker
            RequestLogTracker.add(
                RequestLogEntry(
                    timestampMs = System.currentTimeMillis(),
                    method = req.method,
                    path = req.url.encodedPath,
                    profileHeader = reqProfile,
                    statusCode = response.code
                )
            )

            // Validate profile echo if present on both sides
            val echoProfile = response.header("X-Pact-Profile")
            if (reqProfile != null && echoProfile != null && !reqProfile.equals(echoProfile, ignoreCase = true)) {
                throw IllegalStateException("Profile echo mismatch: requested $reqProfile but server returned $echoProfile")
            }

            response
        }
        .build()
) {
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchActiveChallenge(baseUrl: String, profile: String = "real"): Result<ChallengeInfo?> = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trim().removeSuffix("/") + "/api/pact/challenge"
        val request = Request.Builder()
            .url(cleanUrl)
            .header("Content-Type", "application/json")
            .header("User-Agent", "PACT/1.0.0 (Android)")
            .header("ngrok-skip-browser-warning", "true")
            .header("X-Pact-Profile", profile)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("HTTP ${response.code} from /api/pact/challenge: ${response.message}")
                    )
                }
                val body = response.body?.string()?.trim() ?: ""
                if (body.isEmpty() || body == "null") {
                    return@withContext Result.success(null)
                }
                val info = gson.fromJson(body, ChallengeInfo::class.java)
                Result.success(info)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchAttestation(
        baseUrl: String,
        challengeRef: String,
        sessionToken: String,
        profile: String = "real"
    ): Result<PactAttestationDto> = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trim().removeSuffix("/") + "/api/pact/attestation/$challengeRef"
        val request = Request.Builder()
            .url(cleanUrl)
            .header("Authorization", "Bearer $sessionToken")
            .header("Content-Type", "application/json")
            .header("User-Agent", "PACT/1.0.0 (Android)")
            .header("ngrok-skip-browser-warning", "true")
            .header("X-Pact-Profile", profile)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()?.trim() ?: ""
                if (!response.isSuccessful) {
                    val detail = try {
                        val obj = gson.fromJson(body, com.google.gson.JsonObject::class.java)
                        obj.get("detail")?.asString ?: body
                    } catch (e: Exception) {
                        body
                    }
                    return@withContext Result.failure(AttestationException(response.code, detail))
                }
                val attestation = gson.fromJson(body, PactAttestationDto::class.java)
                Result.success(attestation)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchMemberVerdict(
        baseUrl: String,
        sessionToken: String,
        profile: String = "real"
    ): Result<MemberVerdictDto> = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trim().removeSuffix("/") + "/api/pact/eligibility"
        val emptyPayload = "{\"records\": []}".toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(cleanUrl)
            .header("Authorization", "Bearer $sessionToken")
            .header("Content-Type", "application/json")
            .header("User-Agent", "PACT/1.0.0 (Android)")
            .header("ngrok-skip-browser-warning", "true")
            .header("X-Pact-Profile", profile)
            .post(emptyPayload)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()?.trim() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code} from eligibility: $body"))
                }
                val json = gson.fromJson(body, com.google.gson.JsonObject::class.java)
                val eval = json.getAsJsonObject("evaluation")
                val passed = eval?.get("passed")?.asBoolean ?: false
                val daysCompleted = eval?.get("days_completed")?.asInt ?: 0
                Result.success(MemberVerdictDto(passed, daysCompleted, "Evaluated by PACT backend pipeline"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchCommunitySummary(
        baseUrl: String,
        profile: String = "real"
    ): Result<CommunitySummaryDto> = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trim().removeSuffix("/") + "/api/pact/community"
        val request = Request.Builder()
            .url(cleanUrl)
            .header("Content-Type", "application/json")
            .header("User-Agent", "PACT/1.0.0 (Android)")
            .header("ngrok-skip-browser-warning", "true")
            .header("X-Pact-Profile", profile)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()?.trim() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code} from community: $body"))
                }
                val summary = gson.fromJson(body, CommunitySummaryDto::class.java)
                Result.success(summary)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createSandboxChallenge(
        baseUrl: String,
        sessionToken: String,
        stepsPerDay: Int = 10,
        durationSeconds: Int = 300,
        countdownSeconds: Int = 120,
        idempotencyKey: String? = null
    ): Result<CreateSandboxChallengeResponse> = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trim().removeSuffix("/") + "/api/sandbox/create-challenge"
        val bodyObj = com.google.gson.JsonObject().apply {
            addProperty("steps_per_day", stepsPerDay)
            addProperty("duration_seconds", durationSeconds)
            addProperty("countdown_seconds", countdownSeconds)
            if (idempotencyKey != null) {
                addProperty("idempotency_key", idempotencyKey)
            }
        }
        val reqBody = bodyObj.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(cleanUrl)
            .header("Authorization", "Bearer $sessionToken")
            .header("Content-Type", "application/json")
            .header("User-Agent", "PACT/1.0.0 (Android)")
            .header("ngrok-skip-browser-warning", "true")
            .header("X-Pact-Profile", "sandbox")
            .post(reqBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()?.trim() ?: ""
                if (!response.isSuccessful) {
                    val detail = try {
                        val obj = gson.fromJson(body, com.google.gson.JsonObject::class.java)
                        obj.get("detail")?.asString ?: body
                    } catch (e: Exception) {
                        body
                    }
                    return@withContext Result.failure(Exception(detail))
                }
                val res = gson.fromJson(body, CreateSandboxChallengeResponse::class.java)
                Result.success(res)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchSandboxStatus(
        baseUrl: String,
        sessionToken: String? = null
    ): Result<SandboxStatusDto> = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trim().removeSuffix("/") + "/api/sandbox/status"
        val reqBuilder = Request.Builder()
            .url(cleanUrl)
            .header("Content-Type", "application/json")
            .header("User-Agent", "PACT/1.0.0 (Android)")
            .header("ngrok-skip-browser-warning", "true")
            .get()
        if (!sessionToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $sessionToken")
        }

        try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                val body = response.body?.string()?.trim() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code} from sandbox status: $body"))
                }
                val status = gson.fromJson(body, SandboxStatusDto::class.java)
                Result.success(status)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

data class SandboxStatusDto(
    @SerializedName("contract_id") val contractId: String = "",
    @SerializedName("relayer_id") val relayerId: String = "",
    @SerializedName("stake_yocto") val stakeYocto: String = "",
    @SerializedName("stake_near") val stakeNear: String = "",
    @SerializedName("submission_grace_secs") val submissionGraceSecs: Long = 180L,
    @SerializedName("can_create_challenge") val canCreateChallenge: Boolean = true,
    @SerializedName("active_challenge") val activeChallenge: SandboxActiveChallengeDto? = null
)

data class SandboxActiveChallengeDto(
    @SerializedName("challenge_id") val challengeId: String = "",
    @SerializedName("steps_per_day") val stepsPerDay: Int = 10,
    @SerializedName("days_required") val daysRequired: Int = 1,
    @SerializedName("start_ns") val startNs: String = "",
    @SerializedName("end_ns") val endNs: String = "",
    @SerializedName("start_iso") val startIso: String = "",
    @SerializedName("end_iso") val endIso: String = "",
    @SerializedName("outcome") val outcome: String = "Pending",
    @SerializedName("enrolled_count") val enrolledCount: Long = 0L,
    @SerializedName("submitted_count") val submittedCount: Long = 0L,
    @SerializedName("qualifier_count") val qualifierCount: Long = 0L,
    @SerializedName("phase") val phase: String = "",
    @SerializedName("seconds_remaining") val secondsRemaining: Long = 0L,
    @SerializedName("submission_grace_secs") val submissionGraceSecs: Long = 180L,
    @SerializedName("member_status") val memberStatus: SandboxMemberStatusDto? = null
)

data class SandboxMemberStatusDto(
    @SerializedName("account_id") val accountId: String = "",
    @SerializedName("is_enrolled") val isEnrolled: Boolean = false,
    @SerializedName("has_submitted") val hasSubmitted: Boolean = false,
    @SerializedName("is_qualifier") val isQualifier: Boolean = false,
    @SerializedName("has_claimed_reward") val hasClaimedReward: Boolean = false,
    @SerializedName("has_withdrawn_stake") val hasWithdrawnStake: Boolean = false
)

data class CreateSandboxChallengeResponse(
    val success: Boolean,
    @SerializedName("challenge_id") val challengeId: String,
    @SerializedName("transaction_id") val transactionId: String?,
    @SerializedName("start_iso") val startIso: String?,
    @SerializedName("end_iso") val endIso: String?,
    @SerializedName("start_ns") val startNs: String? = null,
    @SerializedName("end_ns") val endNs: String? = null,
    @SerializedName("already_active") val alreadyActive: Boolean = false,
    @SerializedName("existing") val existing: Boolean = false,
    @SerializedName("phase") val phase: String? = null,
    @SerializedName("enrolled_count") val enrolledCount: Long = 0L
)

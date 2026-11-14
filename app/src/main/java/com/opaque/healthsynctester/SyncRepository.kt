package com.opaque.healthsynctester

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.opaque.healthsynctester.model.SyncPayload
import com.opaque.healthsynctester.model.SyncResponseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class SyncRepository {

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Cache fingerprint of the last successfully uploaded payload to prevent unintentional duplicate uploads
    private var lastUploadedPayloadHash: String? = null
    var lastSuccessfulUploadTime: String? = null
        private set

    fun toJson(payload: SyncPayload): String {
        return gson.toJson(payload)
    }

    private fun computeSha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun isDuplicateUpload(jsonPayload: String): Boolean {
        val currentHash = computeSha256(jsonPayload)
        return currentHash == lastUploadedPayloadHash
    }

    /**
     * Uploads the health data payload to {baseUrl}/api/health/sync
     * Never silently discards failed uploads; returns a detailed SyncResponseResult.
     *
     * [authToken] is the NEAR session token from the backend's /api/auth/verify;
     * the sync endpoint requires it (uploads are attributed to the signed-in
     * NEAR account) and rejects a missing/expired one with HTTP 401.
     */
    suspend fun uploadHealthData(
        baseUrl: String,
        apiKey: String?,
        payload: SyncPayload,
        authToken: String? = null,
        forceDuplicate: Boolean = false,
        profile: String = "real"
    ): SyncResponseResult = withContext(Dispatchers.IO) {
        val cleanBaseUrl = baseUrl.trim().removeSuffix("/")
        val syncEndpoint = if (cleanBaseUrl.endsWith("/api/health/sync")) {
            cleanBaseUrl
        } else {
            "$cleanBaseUrl/api/health/sync"
        }

        val jsonPayload = toJson(payload)
        val currentHash = computeSha256(jsonPayload)

        if (!forceDuplicate && currentHash == lastUploadedPayloadHash) {
            return@withContext SyncResponseResult(
                isSuccess = false,
                statusCode = 409,
                message = "Duplicate Upload Prevented: This exact data batch was already uploaded at $lastSuccessfulUploadTime. Click again if you intend to re-upload.",
                responseBody = "Warning: Duplicate upload detected. Toggle force re-upload to proceed."
            )
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonPayload.toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .url(syncEndpoint)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("User-Agent", "HealthSyncTester/1.0.0 (Android)")
            .header("ngrok-skip-browser-warning", "true")
            .header("X-Pact-Profile", profile)

        if (!apiKey.isNullOrBlank()) {
            requestBuilder.header("X-API-Key", apiKey.trim())
        }
        if (!authToken.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer ${authToken.trim()}")
        }

        val request = requestBuilder.build()

        try {
            client.newCall(request).execute().use { response ->
                val responseBodyText = response.body?.string() ?: "(Empty response body)"
                val code = response.code
                val isSuccess = response.isSuccessful

                if (isSuccess) {
                    lastUploadedPayloadHash = currentHash
                    lastSuccessfulUploadTime = java.time.Instant.now().toString()
                    SyncResponseResult(
                        isSuccess = true,
                        statusCode = code,
                        message = "Upload Successful (HTTP $code)",
                        responseBody = responseBodyText
                    )
                } else {
                    SyncResponseResult(
                        isSuccess = false,
                        statusCode = code,
                        message = "Server returned HTTP $code (${response.message.ifBlank { "Error" }})",
                        responseBody = responseBodyText
                    )
                }
            }
        } catch (e: java.net.ConnectException) {
            SyncResponseResult(
                isSuccess = false,
                statusCode = 0,
                message = "Connection Failed: Unable to reach $syncEndpoint. Verify your backend server is running and device is on the same network.",
                responseBody = "Exception: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        } catch (e: java.net.SocketTimeoutException) {
            SyncResponseResult(
                isSuccess = false,
                statusCode = 0,
                message = "Connection Timed Out: The backend server took too long to respond.",
                responseBody = "Exception: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        } catch (e: java.net.UnknownHostException) {
            SyncResponseResult(
                isSuccess = false,
                statusCode = 0,
                message = "Unknown Host: Cannot resolve backend host in '$baseUrl'. Check URL spelling.",
                responseBody = "Exception: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        } catch (e: Exception) {
            SyncResponseResult(
                isSuccess = false,
                statusCode = 0,
                message = "Network Error: ${e.localizedMessage ?: e.javaClass.simpleName}",
                responseBody = "Exception: ${e.stackTraceToString().take(500)}"
            )
        }
    }
}

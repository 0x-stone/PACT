package com.opaque.healthsynctester.model

/**
 * Encapsulates the overall sync payload sent to the backend endpoint.
 */
data class SyncPayload(
    val deviceId: String,
    val syncTimestamp: String,
    val timezone: String? = null,
    val summary: Map<String, Int>,
    val records: List<SyncRecord>
)

/**
 * Standardized record payload sent in JSON.
 */
data class SyncRecord(
    val recordType: String,
    val startTime: String,
    val endTime: String? = null,
    val recordData: Map<String, Any?>,
    val metadata: Map<String, Any?>? = null
)

/**
 * Backend sync response representation.
 */
data class SyncResponseResult(
    val isSuccess: Boolean,
    val statusCode: Int,
    val message: String,
    val responseBody: String,
    val timestamp: String = java.time.Instant.now().toString()
)

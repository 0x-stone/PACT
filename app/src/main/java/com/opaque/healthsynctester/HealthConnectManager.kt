package com.opaque.healthsynctester

import android.content.Context
import android.provider.Settings
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.opaque.healthsynctester.model.SyncPayload
import com.opaque.healthsynctester.model.SyncRecord
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

sealed class HealthConnectAvailability {
    object Available : HealthConnectAvailability()
    object UpdateRequired : HealthConnectAvailability()
    object NotSupported : HealthConnectAvailability()
}

class HealthConnectManager(private val context: Context) {

    val healthConnectClient: HealthConnectClient? by lazy {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
    }

    val supportedRecordTypes = listOf(
        "StepsRecord",
        "HeartRateRecord",
        "SleepSessionRecord",
        "ExerciseSessionRecord",
        "WeightRecord",
        "BloodPressureRecord"
    )

    val stepPermission: String = HealthPermission.getReadPermission(StepsRecord::class)

    val requiredPermissions: Set<String> = setOf(
        stepPermission
    )

    fun checkAvailability(): HealthConnectAvailability {
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.UpdateRequired
            else -> HealthConnectAvailability.NotSupported
        }
    }

    suspend fun getGrantedPermissions(): Set<String> {
        val client = healthConnectClient ?: return emptySet()
        return try {
            client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            emptySet()
        }
    }

    suspend fun hasStepPermission(): Boolean {
        return getGrantedPermissions().contains(stepPermission)
    }

    suspend fun hasAllPermissions(): Boolean {
        val granted = getGrantedPermissions()
        return granted.containsAll(requiredPermissions)
    }

    fun getDeviceId(): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: UUID.randomUUID().toString()
        } catch (e: Exception) {
            UUID.randomUUID().toString()
        }
    }

    /**
     * Reads records from the last 7 days with pagination.
     */
    suspend fun fetchHealthDataLast7Days(): SyncPayload {
        val client = healthConnectClient
            ?: throw IllegalStateException("Health Connect is not available on this device.")

        val endTime = Instant.now()
        val startTime = endTime.minus(7, ChronoUnit.DAYS)
        val timeRangeFilter = TimeRangeFilter.between(startTime, endTime)

        val granted = getGrantedPermissions()
        val records = mutableListOf<SyncRecord>()
        val summaryCounts = mutableMapOf<String, Int>()

        // 1. Steps
        if (granted.contains(HealthPermission.getReadPermission(StepsRecord::class))) {
            val stepRecords = readAllRecords<StepsRecord>(client, timeRangeFilter)
            summaryCounts["StepsRecord"] = stepRecords.size
            for (record in stepRecords) {
                records.add(
                    SyncRecord(
                        recordType = "StepsRecord",
                        startTime = record.startTime.toString(),
                        endTime = record.endTime.toString(),
                        recordData = mapOf(
                            "count" to record.count,
                            "unit" to "count"
                        ),
                        metadata = extractMetadata(record.metadata)
                    )
                )
            }
        } else {
            summaryCounts["StepsRecord"] = 0
        }

        // 2. Heart Rate
        if (granted.contains(HealthPermission.getReadPermission(HeartRateRecord::class))) {
            val hrRecords = readAllRecords<HeartRateRecord>(client, timeRangeFilter)
            summaryCounts["HeartRateRecord"] = hrRecords.size
            for (record in hrRecords) {
                val sampleList = record.samples.map { sample ->
                    mapOf(
                        "beatsPerMinute" to sample.beatsPerMinute,
                        "time" to sample.time.toString()
                    )
                }
                records.add(
                    SyncRecord(
                        recordType = "HeartRateRecord",
                        startTime = record.startTime.toString(),
                        endTime = record.endTime.toString(),
                        recordData = mapOf(
                            "samples" to sampleList,
                            "sampleCount" to sampleList.size,
                            "unit" to "beats_per_minute"
                        ),
                        metadata = extractMetadata(record.metadata)
                    )
                )
            }
        } else {
            summaryCounts["HeartRateRecord"] = 0
        }

        // 3. Sleep Session
        if (granted.contains(HealthPermission.getReadPermission(SleepSessionRecord::class))) {
            val sleepRecords = readAllRecords<SleepSessionRecord>(client, timeRangeFilter)
            summaryCounts["SleepSessionRecord"] = sleepRecords.size
            for (record in sleepRecords) {
                val stageList = record.stages.map { stage ->
                    mapOf(
                        "stage" to stage.stage,
                        "startTime" to stage.startTime.toString(),
                        "endTime" to stage.endTime.toString()
                    )
                }
                val durationMinutes = Duration.between(record.startTime, record.endTime).toMinutes()
                records.add(
                    SyncRecord(
                        recordType = "SleepSessionRecord",
                        startTime = record.startTime.toString(),
                        endTime = record.endTime.toString(),
                        recordData = mapOf(
                            "title" to record.title,
                            "notes" to record.notes,
                            "durationMinutes" to durationMinutes,
                            "stages" to stageList,
                            "unit" to "stages_and_duration_minutes"
                        ),
                        metadata = extractMetadata(record.metadata)
                    )
                )
            }
        } else {
            summaryCounts["SleepSessionRecord"] = 0
        }

        // 4. Exercise Session
        if (granted.contains(HealthPermission.getReadPermission(ExerciseSessionRecord::class))) {
            val exerciseRecords = readAllRecords<ExerciseSessionRecord>(client, timeRangeFilter)
            summaryCounts["ExerciseSessionRecord"] = exerciseRecords.size
            for (record in exerciseRecords) {
                val durationMinutes = Duration.between(record.startTime, record.endTime).toMinutes()
                records.add(
                    SyncRecord(
                        recordType = "ExerciseSessionRecord",
                        startTime = record.startTime.toString(),
                        endTime = record.endTime.toString(),
                        recordData = mapOf(
                            "exerciseType" to record.exerciseType,
                            "title" to record.title,
                            "notes" to record.notes,
                            "durationMinutes" to durationMinutes,
                            "unit" to "duration_minutes"
                        ),
                        metadata = extractMetadata(record.metadata)
                    )
                )
            }
        } else {
            summaryCounts["ExerciseSessionRecord"] = 0
        }

        // 5. Weight
        if (granted.contains(HealthPermission.getReadPermission(WeightRecord::class))) {
            val weightRecords = readAllRecords<WeightRecord>(client, timeRangeFilter)
            summaryCounts["WeightRecord"] = weightRecords.size
            for (record in weightRecords) {
                records.add(
                    SyncRecord(
                        recordType = "WeightRecord",
                        startTime = record.time.toString(),
                        endTime = record.time.toString(),
                        recordData = mapOf(
                            "weightKg" to record.weight.inKilograms,
                            "unit" to "kilograms"
                        ),
                        metadata = extractMetadata(record.metadata)
                    )
                )
            }
        } else {
            summaryCounts["WeightRecord"] = 0
        }

        // 6. Blood Pressure
        if (granted.contains(HealthPermission.getReadPermission(BloodPressureRecord::class))) {
            val bpRecords = readAllRecords<BloodPressureRecord>(client, timeRangeFilter)
            summaryCounts["BloodPressureRecord"] = bpRecords.size
            for (record in bpRecords) {
                records.add(
                    SyncRecord(
                        recordType = "BloodPressureRecord",
                        startTime = record.time.toString(),
                        endTime = record.time.toString(),
                        recordData = mapOf(
                            "systolicMmHg" to record.systolic.inMillimetersOfMercury,
                            "diastolicMmHg" to record.diastolic.inMillimetersOfMercury,
                            "bodyPosition" to record.bodyPosition,
                            "measurementLocation" to record.measurementLocation,
                            "unit" to "mmHg"
                        ),
                        metadata = extractMetadata(record.metadata)
                    )
                )
            }
        } else {
            summaryCounts["BloodPressureRecord"] = 0
        }

        return SyncPayload(
            deviceId = getDeviceId(),
            syncTimestamp = Instant.now().toString(),
            summary = summaryCounts,
            records = records
        )
    }

    private suspend inline fun <reified T : Record> readAllRecords(
        client: HealthConnectClient,
        timeRangeFilter: TimeRangeFilter
    ): List<T> {
        val result = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = T::class,
                    timeRangeFilter = timeRangeFilter,
                    pageToken = pageToken,
                    pageSize = 1000
                )
            )
            result.addAll(response.records)
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return result
    }

    suspend fun readRawSteps(startTime: Instant, endTime: Instant): List<StepsRecord> {
        val client = healthConnectClient ?: return emptyList()
        val filter = TimeRangeFilter.between(startTime, endTime)
        return readAllRecords<StepsRecord>(client, filter)
    }

    fun buildStepsSyncPayload(records: List<StepsRecord>, zoneId: java.time.ZoneId): SyncPayload {
        val syncRecords = records.map { rec ->
            SyncRecord(
                recordType = "StepsRecord",
                startTime = rec.startTime.toString(),
                endTime = rec.endTime.toString(),
                recordData = mapOf(
                    "count" to rec.count,
                    "unit" to "count"
                ),
                metadata = extractMetadata(rec.metadata)
            )
        }
        return SyncPayload(
            deviceId = getDeviceId(),
            syncTimestamp = Instant.now().toString(),
            timezone = zoneId.id,
            summary = mapOf("StepsRecord" to records.size),
            records = syncRecords
        )
    }

    fun auditOrigins(records: List<StepsRecord>): List<OriginAuditItem> {
        val grouped = records.groupBy { rec ->
            rec.metadata.dataOrigin.packageName to rec.metadata.recordingMethod
        }
        return grouped.map { (key, group) ->
            val (origin, method) = key
            val totalSteps = group.sumOf { it.count }
            val (passes, reason) = doesRecordPassFilters(origin, method)
            OriginAuditItem(
                dataOrigin = origin,
                recordingMethod = method,
                recordCount = group.size,
                totalSteps = totalSteps,
                passesAllowlist = passes,
                reason = reason
            )
        }.sortedByDescending { it.recordCount }
    }

    private fun extractMetadata(metadata: androidx.health.connect.client.records.metadata.Metadata): Map<String, Any?> {
        return mapOf(
            "id" to metadata.id,
            "clientRecordId" to metadata.clientRecordId,
            "clientRecordVersion" to metadata.clientRecordVersion,
            "dataOrigin" to metadata.dataOrigin.packageName,
            "recordingMethod" to metadata.recordingMethod,
            "lastModifiedTime" to metadata.lastModifiedTime.toString()
        )
    }

    companion object {
        const val SPN_PREFIX = "com.android.healthconnect.phone."
        val TRUSTED_ORIGINS = setOf(
            "com.google.android.gms.fitness",
            "com.google.android.apps.fitness",
            "com.sec.android.app.shealth",
            "com.xiaomi.hm.health"
        )

        fun isOriginTrusted(origin: String): Boolean {
            if (origin.isBlank()) return false
            if (TRUSTED_ORIGINS.contains(origin)) return true
            return origin.startsWith(SPN_PREFIX) && origin.length > SPN_PREFIX.length
        }

        fun doesRecordPassFilters(origin: String, recordingMethod: Int): Pair<Boolean, String> {
            val trusted = isOriginTrusted(origin)
            if (!trusted) return false to "Untrusted origin: '$origin'"
            val validMethod = recordingMethod == 1 || recordingMethod == 2
            if (!validMethod) {
                val methodStr = when (recordingMethod) {
                    0 -> "0:UNKNOWN"
                    3 -> "3:MANUAL_ENTRY"
                    else -> "$recordingMethod"
                }
                return false to "Rejected method: $methodStr (must be 1:Active or 2:Auto)"
            }
            return true to "Passes filters"
        }

        /**
         * Aggregates step records by calendar day using the exact backend deduplication:
         * 1. Exact dedupe by id or fingerprint
         * 2. Per-origin summation
         * 3. Day total = max across origins
         */
        fun aggregateStepRecordsByDay(
            records: List<StepsRecord>,
            zoneId: java.time.ZoneId
        ): Map<java.time.LocalDate, Long> {
            val seen = mutableSetOf<String>()
            val unique = mutableListOf<StepsRecord>()
            for (rec in records) {
                val key = rec.metadata.id.ifBlank {
                    "${rec.metadata.dataOrigin.packageName}_${rec.startTime}_${rec.endTime}_${rec.count}"
                }
                if (seen.add(key)) {
                    unique.add(rec)
                }
            }

            // Group by day and origin
            val byDayOrigin = mutableMapOf<java.time.LocalDate, MutableMap<String, Long>>()
            for (rec in unique) {
                val day = rec.startTime.atZone(zoneId).toLocalDate()
                val origin = rec.metadata.dataOrigin.packageName
                val map = byDayOrigin.getOrPut(day) { mutableMapOf() }
                map[origin] = (map[origin] ?: 0L) + rec.count
            }

            // Max across origins
            return byDayOrigin.mapValues { (_, origins) ->
                origins.values.maxOrNull() ?: 0L
            }
        }

        /**
         * SHORT-WINDOW ELIGIBILITY AGGREGATION:
         * For challenges shorter than 24h:
         * A record counts if its START time falls inside [startInstant, endInstant] (no clipping).
         * Records before startInstant or after endInstant do NOT count.
         * Steps are summed per origin, and the window total is the MAX across eligible origins.
         */
        fun aggregateStepRecordsForWindow(
            records: List<StepsRecord>,
            startInstant: java.time.Instant,
            endInstant: java.time.Instant
        ): Long {
            val seen = mutableSetOf<String>()
            val unique = mutableListOf<StepsRecord>()
            for (rec in records) {
                // Window check: START time must fall inside [startInstant, endInstant]
                if (rec.startTime.isBefore(startInstant) || rec.startTime.isAfter(endInstant)) {
                    continue
                }
                val key = rec.metadata.id.ifBlank {
                    "${rec.metadata.dataOrigin.packageName}_${rec.startTime}_${rec.endTime}_${rec.count}"
                }
                if (seen.add(key)) {
                    unique.add(rec)
                }
            }

            val byOrigin = mutableMapOf<String, Long>()
            for (rec in unique) {
                val origin = rec.metadata.dataOrigin.packageName
                if (isOriginTrusted(origin)) {
                    byOrigin[origin] = (byOrigin[origin] ?: 0L) + rec.count
                }
            }

            return byOrigin.values.maxOrNull() ?: 0L
        }
    }
}

data class OriginAuditItem(
    val dataOrigin: String,
    val recordingMethod: Int,
    val recordCount: Int,
    val totalSteps: Long,
    val passesAllowlist: Boolean,
    val reason: String
)

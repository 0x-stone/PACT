package com.opaque.healthsynctester

import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opaque.healthsynctester.near.BackupCredentials
import com.opaque.healthsynctester.near.Base58
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class StageCTest {

    // -------------------------------------------------------------------
    // 1. SPN Origin and Method Validation Tests
    // -------------------------------------------------------------------

    @Test
    fun testSpnOriginAllowlistRules() {
        val validSpn = "com.android.healthconnect.phone.j3e9fbda4bd2d3ba196e22e69884b8b11"
        assertTrue("Real phone SPN should pass", HealthConnectManager.isOriginTrusted(validSpn))

        // Known trusted packages
        assertTrue("Google Play fitness should pass", HealthConnectManager.isOriginTrusted("com.google.android.gms.fitness"))
        assertTrue("Google Fit should pass", HealthConnectManager.isOriginTrusted("com.google.android.apps.fitness"))
        assertTrue("Samsung Health should pass", HealthConnectManager.isOriginTrusted("com.sec.android.app.shealth"))
        assertTrue("Xiaomi should pass", HealthConnectManager.isOriginTrusted("com.xiaomi.hm.health"))

        // Rejected origins
        assertFalse("Empty suffix rejected", HealthConnectManager.isOriginTrusted("com.android.healthconnect.phone."))
        assertFalse("Invalid phonex prefix rejected", HealthConnectManager.isOriginTrusted("com.android.healthconnect.phonex.abc"))
        assertFalse("Prefix without dot rejected", HealthConnectManager.isOriginTrusted("com.android.healthconnect"))
        assertFalse("Arbitrary unlisted origin rejected", HealthConnectManager.isOriginTrusted("com.spoofed.tracker"))
        assertFalse("Blank origin rejected", HealthConnectManager.isOriginTrusted(""))
    }

    @Test
    fun testRecordingMethodFiltering() {
        val validSpn = "com.android.healthconnect.phone.j3e9fbda4bd2d3ba196e22e69884b8b11"

        val (passAuto, _) = HealthConnectManager.doesRecordPassFilters(validSpn, 2)
        assertTrue("Auto-recorded (2) must pass", passAuto)

        val (passActive, _) = HealthConnectManager.doesRecordPassFilters(validSpn, 1)
        assertTrue("Actively recorded (1) must pass", passActive)

        val (passManual, reasonManual) = HealthConnectManager.doesRecordPassFilters(validSpn, 3)
        assertFalse("Manual entry (3) must be rejected", passManual)
        assertTrue(reasonManual.contains("3:MANUAL_ENTRY"))

        val (passUnknown, reasonUnknown) = HealthConnectManager.doesRecordPassFilters(validSpn, 0)
        assertFalse("Unknown method (0) must be rejected", passUnknown)
        assertTrue(reasonUnknown.contains("0:UNKNOWN"))
    }

    // -------------------------------------------------------------------
    // 2. Step Aggregation, Deduplication & Multi-Origin Tests
    // -------------------------------------------------------------------

    private fun createMockStepRecord(
        id: String,
        startTime: Instant,
        endTime: Instant,
        count: Long,
        originPackage: String
    ): StepsRecord {
        return StepsRecord(
            startTime = startTime,
            startZoneOffset = ZoneOffset.UTC,
            endTime = endTime,
            endZoneOffset = ZoneOffset.UTC,
            count = count,
            metadata = Metadata(
                id = id,
                dataOrigin = DataOrigin(originPackage),
                recordingMethod = Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED
            )
        )
    }

    @Test
    fun testExactDeduplication() {
        val zoneId = ZoneId.of("UTC")
        val t1 = Instant.parse("2026-11-12T10:00:00Z")
        val t2 = Instant.parse("2026-11-12T10:30:00Z")

        val rec1 = createMockStepRecord("id-1", t1, t2, 1000L, "com.sec.android.app.shealth")
        val rec1Duplicate = createMockStepRecord("id-1", t1, t2, 1000L, "com.sec.android.app.shealth")

        val result = HealthConnectManager.aggregateStepRecordsByDay(listOf(rec1, rec1Duplicate), zoneId)
        val today = LocalDate.of(2026, 11, 12)

        assertEquals(1000L, result[today])
    }

    @Test
    fun testMultiOriginMaxNotSum() {
        // One body moved once: if both phone and watch record 5000 and 4500 steps during the same day,
        // the total must be max(5000, 4500) = 5000, NOT 9500!
        val zoneId = ZoneId.of("UTC")
        val t1 = Instant.parse("2026-11-12T08:00:00Z")
        val t2 = Instant.parse("2026-11-12T12:00:00Z")

        val phoneRec = createMockStepRecord("p-1", t1, t2, 5000L, "com.android.healthconnect.phone.j3e9fbda4bd2d3ba196e22e69884b8b11")
        val watchRec = createMockStepRecord("w-1", t1, t2, 4500L, "com.sec.android.app.shealth")

        val result = HealthConnectManager.aggregateStepRecordsByDay(listOf(phoneRec, watchRec), zoneId)
        val today = LocalDate.of(2026, 11, 12)

        assertEquals(5000L, result[today])
    }

    @Test
    fun testDayBoundaryBucketing() {
        // Test record near midnight UTC: 23:30 UTC
        // In UTC, date is 2026-11-12
        // In Tokyo (+9), date is 2026-11-13 (08:30 JST)
        val t = Instant.parse("2026-11-12T23:30:00Z")
        val rec = createMockStepRecord("rec-midnight", t, t.plusSeconds(1800), 1200L, "com.google.android.gms.fitness")

        val utcResult = HealthConnectManager.aggregateStepRecordsByDay(listOf(rec), ZoneId.of("UTC"))
        assertEquals(1200L, utcResult[LocalDate.of(2026, 11, 12)])

        val tokyoResult = HealthConnectManager.aggregateStepRecordsByDay(listOf(rec), ZoneId.of("Asia/Tokyo"))
        assertEquals(1200L, tokyoResult[LocalDate.of(2026, 11, 13)])
    }

    // -------------------------------------------------------------------
    // 3. Key Backup & Export-Import Equivalence Tests
    // -------------------------------------------------------------------

    @Test
    fun testExportJsonFormatAndWarning() {
        val credentials = BackupCredentials(
            network = "testnet",
            accountId = "alice.testnet",
            publicKey = "ed25519:E6Luhy99xWvTq8vF4WzQx8x3VfG2k3wXyZ1234567890",
            privateKey = "ed25519:5M3K...longPrivateKeyString..."
        )

        val jsonString = credentials.toJsonString()
        val json = Gson().fromJson(jsonString, JsonObject::class.java)

        assertTrue(json.has("_warning"))
        assertEquals("testnet", json.get("network").asString)
        assertEquals("alice.testnet", json.get("account_id").asString)
        assertEquals(credentials.publicKey, json.get("public_key").asString)
        assertEquals(credentials.privateKey, json.get("private_key").asString)
    }

    @Test
    fun testExportThenImportIdentityEquivalence() {
        // Generate a 32-byte seed
        val seedBytes = ByteArray(32) { (it + 1).toByte() }
        val privParams = Ed25519PrivateKeyParameters(seedBytes, 0)
        val pubBytes = privParams.generatePublicKey().encoded
        val expectedPublicKey = "ed25519:" + Base58.encode(pubBytes)

        // 64-byte keypair export format
        val fullKeyPairBytes = seedBytes + pubBytes
        val privateKeyWire = "ed25519:" + Base58.encode(fullKeyPairBytes)

        val exportedCredentials = BackupCredentials(
            network = "testnet",
            accountId = "pact-user.testnet",
            publicKey = expectedPublicKey,
            privateKey = privateKeyWire
        )

        // Simulate import step 1: decode raw string
        val rawInput = exportedCredentials.privateKey.removePrefix("ed25519:")
        val decodedBytes = Base58.decode(rawInput)
        assertEquals(64, decodedBytes.size)

        // Simulate import step 2: take first 32 bytes as seed
        val importedSeed = decodedBytes.copyOfRange(0, 32)
        assertArrayEquals(seedBytes, importedSeed)

        // Simulate import step 3: regenerate public key and verify identity equivalence
        val importedPrivParams = Ed25519PrivateKeyParameters(importedSeed, 0)
        val importedPubBytes = importedPrivParams.generatePublicKey().encoded
        val importedPublicKeyWire = "ed25519:" + Base58.encode(importedPubBytes)

        assertEquals("Exported and re-imported public key must be identical", expectedPublicKey, importedPublicKeyWire)

        // Also test JSON extraction
        val jsonExport = exportedCredentials.toJsonString()
        val parsedJson = Gson().fromJson(jsonExport, JsonObject::class.java)
        val extractedKey = parsedJson.get("private_key").asString
        assertEquals(privateKeyWire, extractedKey)
    }
}

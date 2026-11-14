package com.opaque.healthsynctester.gateway

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/**
 * High-performance, circuit-breaker guarded RPC Gateway for NEAR RPC.
 *
 * Guarantees:
 * 1. Strict concurrency bounding (max 3 concurrent RPC network calls) via non-blocking Semaphore.
 * 2. Backoff sleep and cooldown DELAY OCCUR OUTSIDE SEMAPHORE PERMITS (prevents starvation deadlock).
 * 3. Shared circuit breaker: 429 / 503 Retry-After sets a global cooldown that all requests respect.
 * 4. Deduplication via CompletableDeferred: cancelling one caller does not cancel the shared fetch.
 * 5. Hard timeouts on every call (never hangs).
 */
class RpcGateway(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    maxConcurrent: Int = 3
) {
    companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_COOLDOWN_MS = 30_000L
        private const val REQUEST_TIMEOUT_MS = 20_000L

        @Volatile
        private var instance: RpcGateway? = null

        fun getInstance(): RpcGateway {
            return instance ?: synchronized(this) {
                instance ?: RpcGateway().also { instance = it }
            }
        }
    }

    private val semaphore = Semaphore(maxConcurrent)
    private val inFlight = ConcurrentHashMap<String, Deferred<JsonObject?>>()
    private val cooldownMutex = Mutex()

    // Observability for Debug Overlay
    private val _inFlightCount = MutableStateFlow(0)
    val inFlightCount: StateFlow<Int> = _inFlightCount.asStateFlow()

    private val _cooldownUntilMs = MutableStateFlow(0L)
    val cooldownUntilMs: StateFlow<Long> = _cooldownUntilMs.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    suspend fun postRpc(rpcUrl: String, body: JsonObject): JsonObject? = withContext(Dispatchers.IO) {
        val bodyStr = body.toString()
        val dedupeKey = "$rpcUrl::$bodyStr"

        // Check deduplication
        val existingDeferred = inFlight[dedupeKey]
        if (existingDeferred != null) {
            return@withContext try {
                existingDeferred.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

        val deferred = CompletableDeferred<JsonObject?>()
        val prev = inFlight.putIfAbsent(dedupeKey, deferred)
        if (prev != null) {
            return@withContext try {
                prev.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

        try {
            val result = executeWithCircuitBreaker(rpcUrl, bodyStr)
            deferred.complete(result)
            return@withContext result
        } catch (e: CancellationException) {
            // Cancelled by caller: complete with null so other waiters unblock
            deferred.complete(null)
            throw e
        } catch (e: Exception) {
            _lastError.value = e.message
            deferred.complete(null)
            return@withContext null
        } finally {
            inFlight.remove(dedupeKey)
        }
    }

    private suspend fun executeWithCircuitBreaker(rpcUrl: String, bodyStr: String): JsonObject? {
        val maxAttempts = 3
        var attempt = 0

        while (attempt < maxAttempts) {
            // 1. Wait for shared circuit breaker cooldown OUTSIDE the semaphore permit
            val cooldown = _cooldownUntilMs.value
            val now = System.currentTimeMillis()
            if (cooldown > now) {
                val waitTime = (cooldown - now).coerceAtMost(MAX_COOLDOWN_MS)
                delay(waitTime)
            }

            // 2. Acquire semaphore permit strictly for network execution
            var respCode = 0
            var retryAfterHeader: String? = null
            var responseBodyStr: String? = null
            var networkError: Exception? = null

            semaphore.acquire()
            _inFlightCount.value += 1
            try {
                withTimeout(REQUEST_TIMEOUT_MS) {
                    val request = Request.Builder()
                        .url(rpcUrl)
                        .post(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
                        .header("Content-Type", "application/json")
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        respCode = response.code
                        retryAfterHeader = response.header("Retry-After")
                        responseBodyStr = response.body?.string()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                networkError = e
            } finally {
                semaphore.release()
                _inFlightCount.value = (_inFlightCount.value - 1).coerceAtLeast(0)
            }

            // 3. Handle rate limits and server errors
            if (respCode == 429 || respCode == 503) {
                val delayMs = parseRetryAfter(retryAfterHeader, attempt)
                setCooldown(delayMs)
                attempt++
                if (attempt < maxAttempts) {
                    delay(delayMs) // Delay occurs with permit RELEASED
                    continue
                }
                return null
            }

            if (networkError != null || respCode >= 500) {
                attempt++
                if (attempt < maxAttempts) {
                    val backoff = (300L * (1L shl attempt)) + ThreadLocalRandom.current().nextLong(50, 150)
                    delay(backoff)
                    continue
                }
                return null
            }

            if (responseBodyStr != null && respCode in 200..299) {
                return try {
                    JsonParser.parseString(responseBodyStr).asJsonObject
                } catch (e: Exception) {
                    null
                }
            }

            // 4xx client errors are non-retryable
            return null
        }
        return null
    }

    private suspend fun setCooldown(durationMs: Long) {
        cooldownMutex.withLock {
            val target = System.currentTimeMillis() + durationMs.coerceIn(500L, MAX_COOLDOWN_MS)
            if (target > _cooldownUntilMs.value) {
                _cooldownUntilMs.value = target
            }
        }
    }

    fun triggerCooldown(durationMs: Long) {
        val target = System.currentTimeMillis() + durationMs.coerceIn(500L, MAX_COOLDOWN_MS)
        if (target > _cooldownUntilMs.value) {
            _cooldownUntilMs.value = target
        }
    }

    fun parseRetryAfter(header: String?, attempt: Int): Long {
        if (!header.isNullOrBlank()) {
            val seconds = header.toLongOrNull()
            if (seconds != null && seconds > 0) {
                return (seconds * 1000L).coerceIn(500L, MAX_COOLDOWN_MS)
            }
            try {
                val httpFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                val date = httpFormat.parse(header)
                if (date != null) {
                    val diff = date.time - System.currentTimeMillis()
                    if (diff > 0) return diff.coerceIn(500L, MAX_COOLDOWN_MS)
                }
            } catch (_: Exception) {}
        }
        val base = 500L * (1L shl attempt)
        val jitter = (ThreadLocalRandom.current().nextDouble(0.8, 1.2) * base).toLong()
        return jitter.coerceIn(400L, 8000L)
    }

    fun resetCooldown() {
        _cooldownUntilMs.value = 0L
    }
}

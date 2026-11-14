package com.opaque.healthsynctester.profiles

data class RequestLogEntry(
    val timestampMs: Long,
    val method: String,
    val path: String,
    val profileHeader: String?,
    val statusCode: Int
)

/**
 * Thread-safe tracker keeping the last 20 network requests for the Diagnostics screen.
 * Tokens, private keys, and secrets are strictly redacted/omitted.
 */
object RequestLogTracker {
    private val _logs = mutableListOf<RequestLogEntry>()

    val logs: List<RequestLogEntry>
        @Synchronized get() = _logs.toList()

    @Synchronized
    fun add(entry: RequestLogEntry) {
        _logs.add(0, entry)
        while (_logs.size > 20) {
            _logs.removeAt(_logs.size - 1)
        }
    }

    @Synchronized
    fun clear() {
        _logs.clear()
    }
}

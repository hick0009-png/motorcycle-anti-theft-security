package com.example.motorcycleantitheftsensor.security

import java.security.SecureRandom

data class PairingCode(val value: String, val expiresAtMs: Long)

enum class PairingResult {
    Accepted,
    Expired,
    Rejected,
    AlreadyPaired
}

class PairingCodePolicy(
    private val secureRandom: SecureRandom = SecureRandom(),
    private val validityMs: Long = VALIDITY_MS
) {
    fun generate(nowMs: Long): PairingCode {
        val value = secureRandom.nextInt(CODE_RANGE).toString().padStart(CODE_LENGTH, '0')
        return PairingCode(value, nowMs + validityMs)
    }

    fun isExpired(stored: PairingCode, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs >= stored.expiresAtMs

    fun validate(submitted: String, stored: PairingCode?, nowMs: Long): PairingResult = when {
        stored == null -> PairingResult.Rejected
        isExpired(stored, nowMs) -> PairingResult.Expired
        submitted.trim() != stored.value -> PairingResult.Rejected
        else -> PairingResult.Accepted
    }

    private companion object {
        const val CODE_LENGTH = 6
        const val CODE_RANGE = 1_000_000
        const val VALIDITY_MS = 10 * 60 * 1000L
    }
}

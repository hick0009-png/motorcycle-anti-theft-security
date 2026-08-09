package com.example.motorcycleantitheftsensor.security

import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * SEC-05: TotpAuthenticator
 * Implements TOTP RFC 6238 algorithm (HMAC-SHA1 6-digit 30s period).
 * Compatible with Google Authenticator, Authy, and Microsoft Authenticator.
 * Includes rate-limiting against brute-force attacks (max 3 failed attempts per 5 minutes).
 */
class TotpAuthenticator internal constructor(
    private val readSeed: () -> String?,
    private val saveSeed: (String) -> Unit,
    private val nowMs: () -> Long,
    private val fillRandomBytes: (ByteArray) -> Unit,
) {

    constructor(prefsManager: EncryptedPrefsManager) : this(
        readSeed = prefsManager::getTotpSeed,
        saveSeed = prefsManager::saveTotpSeed,
        nowMs = System::currentTimeMillis,
        fillRandomBytes = SecureRandom()::nextBytes,
    )

    companion object {
        private const val TIME_STEP_SECONDS = 30L
        private const val DIGITS = 6
        private const val MAX_FAILED_ATTEMPTS = 3
        private const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L // 5 minutes
    }

    enum class VerificationResult {
        SUCCESS,
        INVALID_CODE,
        LOCKED_OUT,
        NO_SEED_CONFIGURED
    }

    internal class SetupCandidate(
        val secret: String,
        val uri: String,
    )

    private var failedAttempts = 0
    private var lockoutTimeMs = 0L

    /** Generates a candidate without changing the configured seed. */
    internal fun createSetupCandidate(accountName: String = "VehicleOwner"): SetupCandidate {
        val randomBytes = ByteArray(20) // 160-bit secret
        fillRandomBytes(randomBytes)
        val base32Seed = base32Encode(randomBytes)
        return SetupCandidate(
            secret = base32Seed,
            uri = "otpauth://totp/MotorcycleGuard:$accountName?secret=$base32Seed&issuer=MotorcycleGuard&algorithm=SHA1&digits=6&period=30",
        )
    }

    /** Verifies a candidate without changing the configured seed. */
    internal fun verifySetupCode(candidate: SetupCandidate, inputCode: String): VerificationResult =
        verifyCodeAgainstSeed(candidate.secret, inputCode)

    /** Makes a successfully verified candidate authoritative. */
    internal fun activateSetup(candidate: SetupCandidate) {
        saveSeed(candidate.secret)
    }

    /**
     * Verifies the 6-digit code submitted by the user.
     */
    fun verifyCode(inputCode: String): VerificationResult {
        val now = nowMs()
        if (now < lockoutTimeMs) return VerificationResult.LOCKED_OUT
        val base32Seed = readSeed() ?: return VerificationResult.NO_SEED_CONFIGURED
        return verifyCodeAgainstSeed(base32Seed, inputCode, now)
    }

    private fun verifyCodeAgainstSeed(
        base32Seed: String,
        inputCode: String,
        now: Long = nowMs(),
    ): VerificationResult {
        if (now < lockoutTimeMs) {
            return VerificationResult.LOCKED_OUT
        }

        val currentCounter = now / 1000L / TIME_STEP_SECONDS
        // Check current time step, previous (-1), and next (+1) to allow minor clock skew
        for (i in -1..1) {
            val expectedCode = generateTotpCode(base32Seed, currentCounter + i)
            if (inputCode.trim() == expectedCode) {
                failedAttempts = 0
                return VerificationResult.SUCCESS
            }
        }

        failedAttempts++
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            lockoutTimeMs = now + LOCKOUT_DURATION_MS
            return VerificationResult.LOCKED_OUT
        }

        return VerificationResult.INVALID_CODE
    }

    /**
     * Generates TOTP 6-digit code for a given timestamp.
     */
    fun getCurrentCode(): String? {
        val seed = readSeed() ?: return null
        val timeIndex = (nowMs() / 1000L) / TIME_STEP_SECONDS
        return generateTotpCode(seed, timeIndex)
    }

    private fun generateTotpCode(base32Secret: String, timeIndex: Long): String {
        val secretBytes = base32Decode(base32Secret)
        val buffer = ByteBuffer.allocate(8).putLong(timeIndex).array()

        val mac = Mac.getInstance("HmacSHA1")
        val key = SecretKeySpec(secretBytes, "HmacSHA1")
        mac.init(key)
        val hmac = mac.doFinal(buffer)

        val offset = hmac[hmac.size - 1].toInt() and 0x0F
        val binary = ((hmac[offset].toInt() and 0x7F) shl 24) or
                ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
                ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
                (hmac[offset + 3].toInt() and 0xFF)

        val otp = binary % (10.0.pow(DIGITS.toDouble())).toInt()
        return String.format("%0${DIGITS}d", otp)
    }

    // --- Base32 Utility Functions ---
    private val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun base32Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var current = 0
        var valCount = 0
        for (b in data) {
            current = (current shl 8) or (b.toInt() and 0xFF)
            valCount += 8
            while (valCount >= 5) {
                val index = (current shr (valCount - 5)) and 0x1F
                sb.append(BASE32_ALPHABET[index])
                valCount -= 5
            }
        }
        if (valCount > 0) {
            val index = (current shl (5 - valCount)) and 0x1F
            sb.append(BASE32_ALPHABET[index])
        }
        return sb.toString()
    }

    private fun base32Decode(base32: String): ByteArray {
        val cleanInput = base32.uppercase().replace("=", "").trim()
        val out = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0

        for (ch in cleanInput) {
            val charValue = BASE32_ALPHABET.indexOf(ch)
            if (charValue < 0) continue
            buffer = (buffer shl 5) or charValue
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }
}

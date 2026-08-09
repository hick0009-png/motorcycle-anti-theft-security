package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.security.PairingCode
import com.example.motorcycleantitheftsensor.security.PairingCodePolicy
import com.example.motorcycleantitheftsensor.security.TotpAuthenticator
import com.example.motorcycleantitheftsensor.telegram.TelegramBotClient
import com.example.motorcycleantitheftsensor.telegram.normalizeTelegramBotToken
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidProtectionSettingsGateway internal constructor(
    private val operations: AndroidProtectionSettingsOperations,
    private val pairingCodePolicy: PairingCodePolicy,
) : ProtectionSettingsGateway {
    private val authenticatorLock = Any()
    private var authenticatorSetupVersion = 0L
    private var pendingAuthenticatorSetup: TotpAuthenticator.SetupCandidate? = null

    constructor(
        preferences: EncryptedPrefsManager,
        telegram: TelegramBotClient,
        pairingCodePolicy: PairingCodePolicy,
        totpAuthenticator: TotpAuthenticator,
        refreshControlService: () -> Unit,
    ) : this(
        operations = EncryptedAndroidProtectionSettingsOperations(
            preferences = preferences,
            telegram = telegram,
            totpAuthenticator = totpAuthenticator,
            refreshService = refreshControlService,
        ),
        pairingCodePolicy = pairingCodePolicy,
    )

    override suspend fun read(missingPermissions: Set<String>): ProtectionSettingsSummary {
        val allowedChatIds = operations.getAllowedChatIds()
        val pairingCode = if (allowedChatIds.isEmpty()) {
            val stored = operations.getPairingCode()
            if (stored == null || pairingCodePolicy.isExpired(stored)) {
                operations.createPairingCode(pairingCodePolicy).value
            } else {
                stored.value
            }
        } else {
            null
        }
        return ProtectionSettingsSummary(
            tokenConfigured = !operations.getBotToken().isNullOrBlank(),
            pairedOwnerCount = allowedChatIds.size,
            pairingCode = pairingCode,
            authenticatorConfigured = !operations.getTotpSeed().isNullOrBlank(),
            sensitivity = operations.getSensitivity(),
            smsFallbackConfigured = !operations.getSmsDestination().isNullOrBlank() &&
                !operations.getSmsAesKey().isNullOrBlank(),
            missingPermissions = missingPermissions,
        )
    }

    override fun saveSensitivity(level: Int) {
        operations.setSensitivity(level)
    }

    override suspend fun replaceBotToken(token: String): SettingsOperationResult {
        val candidate = normalizeTelegramBotToken(token)
        if (candidate.isBlank()) {
            return SettingsOperationResult(applied = false, message = "Bot token is required")
        }
        if (!verifyBotToken(candidate)) {
            return SettingsOperationResult(applied = false, message = "Bot token could not be verified")
        }
        operations.saveBotToken(candidate)
        operations.refreshControlService()
        return SettingsOperationResult(applied = true, message = "Bot token updated")
    }

    override suspend fun resetPairing(): SettingsOperationResult {
        operations.saveAllowedChatIds(emptySet())
        operations.createPairingCode(pairingCodePolicy)
        operations.refreshControlService()
        return SettingsOperationResult(
            applied = true,
            message = "Pairing reset; use the new pairing code",
        )
    }

    override fun saveSmsFallback(destination: String, aesKey: String): SettingsOperationResult {
        val cleanDestination = destination.trim()
        val cleanKey = aesKey.trim()
        if (cleanDestination.isBlank() || cleanKey.isBlank()) {
            return SettingsOperationResult(
                applied = false,
                message = "SMS destination and encryption key are required",
            )
        }
        operations.saveSmsDestination(cleanDestination)
        operations.saveSmsAesKey(cleanKey)
        return SettingsOperationResult(applied = true, message = "SMS fallback updated")
    }

    override suspend fun beginAuthenticatorSetup(): AuthenticatorSetupDetails? {
        val requestVersion = synchronized(authenticatorLock) {
            authenticatorSetupVersion += 1
            pendingAuthenticatorSetup = null
            authenticatorSetupVersion
        }
        val candidate = operations.createAuthenticatorSetup()
        val accepted = synchronized(authenticatorLock) {
            if (authenticatorSetupVersion == requestVersion) {
                pendingAuthenticatorSetup = candidate
                true
            } else {
                false
            }
        }
        return if (accepted) {
            AuthenticatorSetupDetails(secret = candidate.secret, uri = candidate.uri)
        } else {
            null
        }
    }

    override fun cancelAuthenticatorSetup() {
        synchronized(authenticatorLock) {
            authenticatorSetupVersion += 1
            pendingAuthenticatorSetup = null
        }
    }

    override suspend fun verifyAuthenticator(code: String): Boolean {
        val pending = synchronized(authenticatorLock) {
            val candidate = pendingAuthenticatorSetup ?: return false
            PendingAuthenticatorSetup(authenticatorSetupVersion, candidate)
        }
        val result = operations.verifyAuthenticatorSetup(pending.candidate, code)
        if (result != TotpAuthenticator.VerificationResult.SUCCESS) return false

        return synchronized(authenticatorLock) {
            if (
                authenticatorSetupVersion != pending.version ||
                pendingAuthenticatorSetup !== pending.candidate
            ) {
                false
            } else {
                operations.activateAuthenticatorSetup(pending.candidate)
                pendingAuthenticatorSetup = null
                true
            }
        }
    }

    private suspend fun verifyBotToken(candidate: String): Boolean = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        continuation.invokeOnCancellation { completed.compareAndSet(false, true) }
        operations.verifyBotToken(candidate) { isValid ->
            if (completed.compareAndSet(false, true) && continuation.isActive) {
                continuation.resume(isValid)
            }
        }
    }
}

internal interface AndroidProtectionSettingsOperations {
    fun getAllowedChatIds(): Set<String>
    fun saveAllowedChatIds(chatIds: Set<String>)
    fun getPairingCode(): PairingCode?
    fun createPairingCode(policy: PairingCodePolicy): PairingCode
    fun getBotToken(): String?
    fun getTotpSeed(): String?
    fun getSensitivity(): Int
    fun getSmsDestination(): String?
    fun getSmsAesKey(): String?
    fun setSensitivity(level: Int)
    fun saveBotToken(token: String)
    fun saveSmsDestination(destination: String)
    fun saveSmsAesKey(aesKey: String)
    fun verifyBotToken(token: String, onResult: (Boolean) -> Unit)
    fun refreshControlService()
    fun createAuthenticatorSetup(): TotpAuthenticator.SetupCandidate
    fun verifyAuthenticatorSetup(
        candidate: TotpAuthenticator.SetupCandidate,
        code: String,
    ): TotpAuthenticator.VerificationResult
    fun activateAuthenticatorSetup(candidate: TotpAuthenticator.SetupCandidate)
}

private class EncryptedAndroidProtectionSettingsOperations(
    private val preferences: EncryptedPrefsManager,
    private val telegram: TelegramBotClient,
    private val totpAuthenticator: TotpAuthenticator,
    private val refreshService: () -> Unit,
) : AndroidProtectionSettingsOperations {
    override fun getAllowedChatIds(): Set<String> = preferences.getAllowedChatIds()
    override fun saveAllowedChatIds(chatIds: Set<String>) = preferences.saveAllowedChatIds(chatIds)
    override fun getPairingCode(): PairingCode? = preferences.getPairingCode()
    override fun createPairingCode(policy: PairingCodePolicy): PairingCode = preferences.createPairingCode(policy)
    override fun getBotToken(): String? = preferences.getBotToken()
    override fun getTotpSeed(): String? = preferences.getTotpSeed()
    override fun getSensitivity(): Int = preferences.getSensitivity()
    override fun getSmsDestination(): String? = preferences.getSmsDestination()
    override fun getSmsAesKey(): String? = preferences.getSmsAesKey()
    override fun setSensitivity(level: Int) = preferences.setSensitivity(level)
    override fun saveBotToken(token: String) = preferences.saveBotToken(token)
    override fun saveSmsDestination(destination: String) = preferences.saveSmsDestination(destination)
    override fun saveSmsAesKey(aesKey: String) = preferences.saveSmsAesKey(aesKey)
    override fun verifyBotToken(token: String, onResult: (Boolean) -> Unit) {
        telegram.verifyBotToken(token) { isValid, _, _ -> onResult(isValid) }
    }
    override fun refreshControlService() = refreshService()
    override fun createAuthenticatorSetup(): TotpAuthenticator.SetupCandidate =
        totpAuthenticator.createSetupCandidate()

    override fun verifyAuthenticatorSetup(
        candidate: TotpAuthenticator.SetupCandidate,
        code: String,
    ): TotpAuthenticator.VerificationResult = totpAuthenticator.verifySetupCode(candidate, code)

    override fun activateAuthenticatorSetup(candidate: TotpAuthenticator.SetupCandidate) =
        totpAuthenticator.activateSetup(candidate)
}

private data class PendingAuthenticatorSetup(
    val version: Long,
    val candidate: TotpAuthenticator.SetupCandidate,
)

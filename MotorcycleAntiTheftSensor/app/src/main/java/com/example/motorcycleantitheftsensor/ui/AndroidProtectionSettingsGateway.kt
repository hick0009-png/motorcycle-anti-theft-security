package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.security.PairingCode
import com.example.motorcycleantitheftsensor.security.PairingCodePolicy
import com.example.motorcycleantitheftsensor.telegram.TelegramBotClient
import com.example.motorcycleantitheftsensor.telegram.TelegramBotVerificationResult
import com.example.motorcycleantitheftsensor.telegram.normalizeTelegramBotToken
import kotlinx.coroutines.withTimeoutOrNull

class AndroidProtectionSettingsGateway internal constructor(
    private val operations: AndroidProtectionSettingsOperations,
    private val pairingCodePolicy: PairingCodePolicy,
    private val verificationTimeoutMs: Long = 12_000L,
) : ProtectionSettingsGateway {

    constructor(
        preferences: EncryptedPrefsManager,
        telegram: TelegramBotClient,
        pairingCodePolicy: PairingCodePolicy,
        refreshControlService: () -> Unit,
        verificationTimeoutMs: Long = 12_000L,
    ) : this(
        operations = EncryptedAndroidProtectionSettingsOperations(
            preferences = preferences,
            telegram = telegram,
            refreshService = refreshControlService,
        ),
        pairingCodePolicy = pairingCodePolicy,
        verificationTimeoutMs = verificationTimeoutMs,
    )

    override suspend fun read(missingPermissions: Set<String>): ProtectionSettingsSummary {
        val allowedChatIds = operations.getAllowedChatIds()
        val pairingCode = run {
            val stored = operations.getPairingCode()
            if (stored == null || pairingCodePolicy.isExpired(stored)) {
                operations.createPairingCode(pairingCodePolicy).value
            } else {
                stored.value
            }
        }
        return ProtectionSettingsSummary(
            tokenConfigured = !operations.getBotToken().isNullOrBlank(),
            pairedOwnerCount = allowedChatIds.size,
            pairingCode = pairingCode,
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
        val result = verifyBotToken(candidate)
        return when (result) {
            is TelegramBotVerificationResult.Verified -> {
                operations.saveBotToken(candidate)
                operations.refreshControlService()
                SettingsOperationResult(applied = true, message = "Bot @${result.username} verified and token updated")
            }
            TelegramBotVerificationResult.Rejected -> {
                SettingsOperationResult(applied = false, message = "Bot token could not be verified")
            }
            TelegramBotVerificationResult.ConnectionFailure -> {
                SettingsOperationResult(applied = false, message = "Telegram connection could not be established")
            }
        }
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

    private suspend fun verifyBotToken(candidate: String): TelegramBotVerificationResult =
        withTimeoutOrNull(verificationTimeoutMs) {
            operations.verifyBotToken(candidate)
        } ?: TelegramBotVerificationResult.ConnectionFailure
}

internal interface AndroidProtectionSettingsOperations {
    fun getAllowedChatIds(): Set<String>
    fun saveAllowedChatIds(chatIds: Set<String>)
    fun getPairingCode(): PairingCode?
    fun createPairingCode(policy: PairingCodePolicy): PairingCode
    fun getBotToken(): String?
    fun getSensitivity(): Int
    fun getSmsDestination(): String?
    fun getSmsAesKey(): String?
    fun setSensitivity(level: Int)
    fun saveBotToken(token: String)
    fun saveSmsDestination(destination: String)
    fun saveSmsAesKey(aesKey: String)
    suspend fun verifyBotToken(token: String): TelegramBotVerificationResult
    fun refreshControlService()
}

private class EncryptedAndroidProtectionSettingsOperations(
    private val preferences: EncryptedPrefsManager,
    private val telegram: TelegramBotClient,
    private val refreshService: () -> Unit,
) : AndroidProtectionSettingsOperations {
    override fun getAllowedChatIds(): Set<String> = preferences.getAllowedChatIds()
    override fun saveAllowedChatIds(chatIds: Set<String>) = preferences.saveAllowedChatIds(chatIds)
    override fun getPairingCode(): PairingCode? = preferences.getPairingCode()
    override fun createPairingCode(policy: PairingCodePolicy): PairingCode = preferences.createPairingCode(policy)
    override fun getBotToken(): String? = preferences.getBotToken()
    override fun getSensitivity(): Int = preferences.getSensitivity()
    override fun getSmsDestination(): String? = preferences.getSmsDestination()
    override fun getSmsAesKey(): String? = preferences.getSmsAesKey()
    override fun setSensitivity(level: Int) = preferences.setSensitivity(level)
    override fun saveBotToken(token: String) = preferences.saveBotToken(token)
    override fun saveSmsDestination(destination: String) = preferences.saveSmsDestination(destination)
    override fun saveSmsAesKey(aesKey: String) = preferences.saveSmsAesKey(aesKey)
    override suspend fun verifyBotToken(token: String): TelegramBotVerificationResult {
        return telegram.verifyBotTokenResult(token)
    }
    override fun refreshControlService() = refreshService()
}

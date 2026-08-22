package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.protection.SensorCapability
import com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicy
import com.example.motorcycleantitheftsensor.protection.SensorConfigurationRepository
import com.example.motorcycleantitheftsensor.protection.SensorFusionConfiguration
import com.example.motorcycleantitheftsensor.protection.SensorPreset
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
        sensorConfigRepository: SensorConfigurationRepository? = null,
    ) : this(
        operations = EncryptedAndroidProtectionSettingsOperations(
            preferences = preferences,
            telegram = telegram,
            refreshService = refreshControlService,
            sensorConfigRepository = sensorConfigRepository,
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
        val sensorConfig = operations.getSensorConfiguration()
        val displayPreset = sensorConfig?.let {
            SensorConfigurationPolicy().displayPreset(it)
        }
        return ProtectionSettingsSummary(
            tokenConfigured = !operations.getBotToken().isNullOrBlank(),
            pairedOwnerCount = allowedChatIds.size,
            pairingCode = pairingCode,
            sensitivity = operations.getSensitivity(),
            smsFallbackConfigured = !operations.getSmsDestination().isNullOrBlank() &&
                !operations.getSmsAesKey().isNullOrBlank(),
            missingPermissions = missingPermissions,
            sensorConfiguration = sensorConfig,
            sensorDisplayPreset = displayPreset,
        )
    }

    override fun saveSensitivity(level: Int) {
        operations.setSensitivity(level)
    }

    override fun saveSensorConfiguration(config: SensorFusionConfiguration): SettingsOperationResult {
        val success = operations.saveSensorConfiguration(config)
        return if (success) {
            SettingsOperationResult(applied = true, message = "Sensor configuration updated")
        } else {
            SettingsOperationResult(applied = false, message = "Failed to save sensor configuration")
        }
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
    fun getSensorConfiguration(): SensorFusionConfiguration? = null
    fun saveSensorConfiguration(config: SensorFusionConfiguration): Boolean = true
}

private class EncryptedAndroidProtectionSettingsOperations(
    private val preferences: EncryptedPrefsManager,
    private val telegram: TelegramBotClient,
    private val refreshService: () -> Unit,
    private val sensorConfigRepository: SensorConfigurationRepository? = null,
) : AndroidProtectionSettingsOperations {
    private var inMemoryConfig: SensorFusionConfiguration? = null

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

    override fun getSensorConfiguration(): SensorFusionConfiguration {
        val repo = sensorConfigRepository
        if (repo != null) {
            return repo.loadConfiguration()
        }
        if (inMemoryConfig == null) {
            val legacySensitivity = preferences.getSensitivity()
            val policy = SensorConfigurationPolicy()
            val balanced = policy.forPreset(SensorPreset.BALANCED)
            inMemoryConfig = policy.withGroupSensitivity(
                config = policy.withGroupSensitivity(balanced, SensorCapability.MOVEMENT, legacySensitivity),
                capability = SensorCapability.LIGHT,
                sensitivity = legacySensitivity,
            )
        }
        return inMemoryConfig!!
    }

    override fun saveSensorConfiguration(config: SensorFusionConfiguration): Boolean {
        val repo = sensorConfigRepository
        if (repo != null) {
            val saveResult = repo.saveConfiguration(config)
            if (saveResult.isSuccess) {
                inMemoryConfig = config
                preferences.setSensitivity(config.capability(SensorCapability.MOVEMENT).sensitivity)
                return true
            }
            return false
        }
        inMemoryConfig = config
        preferences.setSensitivity(config.capability(SensorCapability.MOVEMENT).sensitivity)
        return true
    }
}

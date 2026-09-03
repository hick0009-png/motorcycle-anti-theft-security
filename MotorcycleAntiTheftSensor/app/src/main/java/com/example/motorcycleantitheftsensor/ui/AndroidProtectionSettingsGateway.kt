package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.protection.ProfileSensorSettingsStore
import com.example.motorcycleantitheftsensor.protection.ProtectionProfileRepository
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
        profileRepository: ProtectionProfileRepository? = null,
    ) : this(
        operations = EncryptedAndroidProtectionSettingsOperations(
            preferences = preferences,
            telegram = telegram,
            refreshService = refreshControlService,
            sensorConfigRepository = sensorConfigRepository,
            profileRepository = profileRepository,
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
            smsFallbackConfigured = !operations.getSmsDestination().isNullOrBlank(),
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

    /**
     * The owner supplies a destination and nothing else. The encryption key is generated on
     * the device and stays there — asking a human to invent a secret that only this phone
     * ever uses bought no security and cost a brute-forceable passphrase.
     */
    override fun saveSmsFallback(destination: String): SettingsOperationResult {
        val cleanDestination = destination.trim()
        if (cleanDestination.isBlank()) {
            return SettingsOperationResult(
                applied = false,
                message = "SMS destination is required",
            )
        }
        operations.ensureSmsAesKey()
        operations.saveSmsDestination(cleanDestination)
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
    fun setSensitivity(level: Int)
    fun saveBotToken(token: String)
    fun saveSmsDestination(destination: String)
    fun ensureSmsAesKey()
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
    /**
     * Where a use's own detection settings live, and the only store an armed session reads.
     *
     * Without it this screen edits the central configuration that nothing arms from: the
     * owner moves a slider, the screen saves, and the next Arm resolves the use's own
     * configuration and overwrites every one of those decisions. The screen was honest about
     * roles and locks and dishonest about whether any of it took effect.
     */
    private val profileRepository: ProtectionProfileRepository? = null,
) : AndroidProtectionSettingsOperations {
    private var inMemoryConfig: SensorFusionConfiguration? = null
    private val profileSettings = profileRepository?.let { ProfileSensorSettingsStore(it) }

    override fun getAllowedChatIds(): Set<String> = preferences.getAllowedChatIds()
    override fun saveAllowedChatIds(chatIds: Set<String>) = preferences.saveAllowedChatIds(chatIds)
    override fun getPairingCode(): PairingCode? = preferences.getPairingCode()
    override fun createPairingCode(policy: PairingCodePolicy): PairingCode = preferences.createPairingCode(policy)
    override fun getBotToken(): String? = preferences.getBotToken()
    override fun getSensitivity(): Int = preferences.getSensitivity()
    override fun getSmsDestination(): String? = preferences.getSmsDestination()
    override fun setSensitivity(level: Int) = preferences.setSensitivity(level)
    override fun saveBotToken(token: String) = preferences.saveBotToken(token)
    override fun saveSmsDestination(destination: String) = preferences.saveSmsDestination(destination)
    override fun ensureSmsAesKey() {
        preferences.getOrCreateSmsAesKey()
    }
    override suspend fun verifyBotToken(token: String): TelegramBotVerificationResult {
        return telegram.verifyBotTokenResult(token)
    }
    override fun refreshControlService() = refreshService()

    override fun getSensorConfiguration(): SensorFusionConfiguration {
        // What the selected use would actually arm with: its recommendation with this owner's
        // decisions applied, and its locked sources already forced off.
        selectedProfileConfiguration()?.let { return it }
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
        val savedToProfile = saveToSelectedProfile(config)
        val repo = sensorConfigRepository
        if (repo != null) {
            val saveResult = repo.saveConfiguration(config)
            if (saveResult.isSuccess) {
                inMemoryConfig = config
                preferences.setSensitivity(config.capability(SensorCapability.MOVEMENT).sensitivity)
                return true
            }
            // The central copy is kept in step for the parts of the app still reading it, but
            // the use's own store is what an Arm reads: a write that landed there succeeded.
            return savedToProfile
        }
        inMemoryConfig = config
        preferences.setSensitivity(config.capability(SensorCapability.MOVEMENT).sensitivity)
        return true
    }

    private fun selectedProfileConfiguration(): SensorFusionConfiguration? = profileSettings?.read()

    private fun saveToSelectedProfile(config: SensorFusionConfiguration): Boolean =
        profileSettings?.write(config) ?: false
}

package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.security.PairingCodePolicy
import com.example.motorcycleantitheftsensor.telegram.TelegramBotClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidProtectionSettingsGateway(
    private val preferences: EncryptedPrefsManager,
    private val telegram: TelegramBotClient,
    private val pairingCodePolicy: PairingCodePolicy,
    private val startControlService: () -> Unit,
) : ProtectionSettingsGateway {
    override fun read(missingPermissions: Set<String>): ProtectionSettingsSummary {
        val allowedChatIds = preferences.getAllowedChatIds()
        val pairingCode = if (allowedChatIds.isEmpty()) {
            val stored = preferences.getPairingCode()
            if (stored == null || pairingCodePolicy.isExpired(stored)) {
                preferences.createPairingCode(pairingCodePolicy).value
            } else {
                stored.value
            }
        } else {
            null
        }
        return ProtectionSettingsSummary(
            tokenConfigured = !preferences.getBotToken().isNullOrBlank(),
            pairedOwnerCount = allowedChatIds.size,
            pairingCode = pairingCode,
            authenticatorConfigured = !preferences.getTotpSeed().isNullOrBlank(),
            sensitivity = preferences.getSensitivity(),
            smsFallbackConfigured = !preferences.getSmsDestination().isNullOrBlank() &&
                !preferences.getSmsAesKey().isNullOrBlank(),
            missingPermissions = missingPermissions,
        )
    }

    override fun saveSensitivity(level: Int) {
        preferences.setSensitivity(level)
    }

    override suspend fun replaceBotToken(token: String): SettingsOperationResult {
        val candidate = token.trim()
        if (candidate.isBlank()) {
            return SettingsOperationResult(applied = false, message = "Bot token is required")
        }
        if (!verifyBotToken(candidate)) {
            return SettingsOperationResult(applied = false, message = "Bot token could not be verified")
        }
        preferences.saveBotToken(candidate)
        startControlService()
        return SettingsOperationResult(applied = true, message = "Bot token updated")
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
        preferences.saveSmsDestination(cleanDestination)
        preferences.saveSmsAesKey(cleanKey)
        return SettingsOperationResult(applied = true, message = "SMS fallback updated")
    }

    private suspend fun verifyBotToken(candidate: String): Boolean = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        continuation.invokeOnCancellation { completed.compareAndSet(false, true) }
        telegram.verifyBotToken(candidate) { isValid, _, _ ->
            if (completed.compareAndSet(false, true) && continuation.isActive) {
                continuation.resume(isValid)
            }
        }
    }
}

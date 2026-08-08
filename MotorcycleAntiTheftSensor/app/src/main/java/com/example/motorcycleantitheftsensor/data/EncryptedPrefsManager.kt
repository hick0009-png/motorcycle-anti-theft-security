package com.example.motorcycleantitheftsensor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import com.example.motorcycleantitheftsensor.security.PairingCode
import com.example.motorcycleantitheftsensor.security.PairingCodePolicy
import com.example.motorcycleantitheftsensor.security.PairingResult
import com.example.motorcycleantitheftsensor.security.SecureKeyManager
import java.util.UUID

/**
 * SEC-02: EncryptedPrefsManager
 * Wrapper around EncryptedSharedPreferences (AES-256-SIV key + AES-256-GCM value encryption).
 * Stores Telegram Bot Token, TOTP Seed, Chat ID Whitelist, and Device UUID securely at rest.
 */
class EncryptedPrefsManager(context: Context) {

    companion object {
        private const val PREF_FILE_NAME = "motorcycle_anti_theft_encrypted_prefs"
        
        private const val KEY_BOT_TOKEN = "enc_telegram_bot_token"
        private const val KEY_ALLOWED_CHAT_IDS = "enc_allowed_chat_ids"
        private const val KEY_TOTP_SEED = "enc_totp_seed"
        private const val KEY_DEVICE_UUID = "enc_device_uuid"
        private const val KEY_SMS_AES_KEY = "enc_sms_aes_key"
        private const val KEY_SMS_DESTINATION = "enc_sms_destination"
        private const val KEY_SYSTEM_ARMED = "system_armed_state"
        private const val KEY_SENSITIVITY = "sensor_sensitivity_level"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_PAIRING_EXPIRES_AT_MS = "pairing_expires_at_ms"
    }

    private val secureKeyManager = SecureKeyManager(context)

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREF_FILE_NAME,
            secureKeyManager.masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // --- Telegram Bot Token ---
    fun saveBotToken(token: String) {
        prefs.edit().putString(KEY_BOT_TOKEN, token).apply()
    }

    fun getBotToken(): String? {
        return prefs.getString(KEY_BOT_TOKEN, null)
    }

    // --- Telegram Chat ID Whitelist ---
    fun saveAllowedChatIds(chatIds: Set<String>) {
        prefs.edit().putStringSet(KEY_ALLOWED_CHAT_IDS, chatIds).apply()
    }

    fun getAllowedChatIds(): Set<String> {
        return prefs.getStringSet(KEY_ALLOWED_CHAT_IDS, emptySet()) ?: emptySet()
    }

    fun isChatIdAllowed(chatId: String): Boolean {
        return getAllowedChatIds().contains(chatId)
    }

    fun createPairingCode(policy: PairingCodePolicy, nowMs: Long = System.currentTimeMillis()): PairingCode {
        val pairingCode = policy.generate(nowMs)
        prefs.edit()
            .putString(KEY_PAIRING_CODE, pairingCode.value)
            .putLong(KEY_PAIRING_EXPIRES_AT_MS, pairingCode.expiresAtMs)
            .apply()
        return pairingCode
    }

    fun getPairingCode(): PairingCode? {
        val value = prefs.getString(KEY_PAIRING_CODE, null) ?: return null
        val expiresAtMs = prefs.getLong(KEY_PAIRING_EXPIRES_AT_MS, 0L)
        return PairingCode(value, expiresAtMs)
    }

    fun claimPairingCode(
        chatId: String,
        submittedCode: String,
        policy: PairingCodePolicy,
        nowMs: Long = System.currentTimeMillis()
    ): PairingResult {
        if (getAllowedChatIds().isNotEmpty()) return PairingResult.AlreadyPaired
        val result = policy.validate(submittedCode, getPairingCode(), nowMs)
        if (result != PairingResult.Accepted) return result

        val committed = prefs.edit()
            .putStringSet(KEY_ALLOWED_CHAT_IDS, setOf(chatId))
            .remove(KEY_PAIRING_CODE)
            .remove(KEY_PAIRING_EXPIRES_AT_MS)
            .commit()
        return if (committed) PairingResult.Accepted else PairingResult.Rejected
    }

    // --- TOTP Seed ---
    fun saveTotpSeed(seedBase32: String) {
        prefs.edit().putString(KEY_TOTP_SEED, seedBase32).apply()
    }

    fun getTotpSeed(): String? {
        return prefs.getString(KEY_TOTP_SEED, null)
    }

    // --- Device UUID ---
    fun getOrCreateDeviceUuid(): String {
        val existing = prefs.getString(KEY_DEVICE_UUID, null)
        if (!existing.isNullOrEmpty()) {
            return existing
        }
        val newUuid = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_UUID, newUuid).apply()
        return newUuid
    }

    // --- SMS Pre-Shared Key ---
    fun saveSmsAesKey(keyBase64: String) {
        prefs.edit().putString(KEY_SMS_AES_KEY, keyBase64).apply()
    }

    fun getSmsAesKey(): String? {
        return prefs.getString(KEY_SMS_AES_KEY, null)
    }

    fun saveSmsDestination(phoneNumber: String) {
        prefs.edit().putString(KEY_SMS_DESTINATION, phoneNumber.trim()).apply()
    }

    fun getSmsDestination(): String? = prefs.getString(KEY_SMS_DESTINATION, null)

    // --- Arm / Disarm State ---
    fun setSystemArmed(armed: Boolean) {
        prefs.edit().putBoolean(KEY_SYSTEM_ARMED, armed).apply()
    }

    fun isSystemArmed(): Boolean {
        return prefs.getBoolean(KEY_SYSTEM_ARMED, false)
    }

    // --- Sensor Sensitivity (1-10) ---
    fun setSensitivity(level: Int) {
        val clamped = level.coerceIn(1, 10)
        prefs.edit().putInt(KEY_SENSITIVITY, clamped).apply()
    }

    fun getSensitivity(): Int {
        return prefs.getInt(KEY_SENSITIVITY, 5)
    }
}

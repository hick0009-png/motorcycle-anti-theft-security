package com.example.motorcycleantitheftsensor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import com.example.motorcycleantitheftsensor.security.PairingCode
import com.example.motorcycleantitheftsensor.security.PairingCodePolicy
import com.example.motorcycleantitheftsensor.security.PairingResult
import com.example.motorcycleantitheftsensor.security.SecureKeyManager
import com.example.motorcycleantitheftsensor.telephony.EncryptedSmsCodec
import java.util.UUID

/**
 * SEC-02: EncryptedPrefsManager
 * Wrapper around EncryptedSharedPreferences (AES-256-SIV key + AES-256-GCM value encryption).
 * Stores Telegram Bot Token, TOTP Seed, Chat ID Whitelist, and Device UUID securely at rest.
 */
class EncryptedPrefsManager(private val context: Context) {

    companion object {
        private const val PREF_FILE_NAME = "motorcycle_anti_theft_encrypted_prefs"
        
        private const val KEY_BOT_TOKEN = "enc_telegram_bot_token"
        private const val KEY_ALLOWED_CHAT_IDS = "enc_allowed_chat_ids"
        private const val LEGACY_AUTHENTICATOR_SEED_KEY = "enc_totp_seed"
        private const val KEY_DEVICE_UUID = "enc_device_uuid"
        private const val KEY_SMS_AES_KEY = "enc_sms_aes_key"
        private const val KEY_SMS_AES_KEY_V3 = "enc_sms_aes_key_v3"
        private const val KEY_SMS_DESTINATION = "enc_sms_destination"
        private const val KEY_SYSTEM_ARMED = "system_armed_state"
        private const val KEY_AUTO_RECOVERY_AFTER_BOOT = "auto_recovery_after_boot"
        private const val KEY_SENSITIVITY = "sensor_sensitivity_level"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_PAIRING_EXPIRES_AT_MS = "pairing_expires_at_ms"
        private const val KEY_LAST_TELEGRAM_UPDATE_ID = "last_telegram_update_id"
        private const val KEY_PARKING_ANCHOR = "enc_parking_anchor_json"
        private const val KEY_LIVE_PURSUIT_SESSION = "enc_live_pursuit_session_json"
        private const val KEY_MOVEMENT_TRACKING_STATE = "enc_movement_tracking_state_json"
    }

    private val secureKeyManager = SecureKeyManager(context)

    private val prefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            android.util.Log.w("EncryptedPrefsManager", "EncryptedSharedPreferences corrupted, recovering...", e)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    context.deleteSharedPreferences(PREF_FILE_NAME)
                } else {
                    context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
                }
            } catch (_: Exception) {}
            createEncryptedPrefs()
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            PREF_FILE_NAME,
            secureKeyManager.masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // --- Telegram Bot Token ---
    fun saveBotToken(token: String) {
        val previous = prefs.getString(KEY_BOT_TOKEN, null)
        val editor = prefs.edit().putString(KEY_BOT_TOKEN, token)
        if (previous != token) editor.remove(KEY_LAST_TELEGRAM_UPDATE_ID)
        editor.apply()
    }

    fun getBotToken(): String? {
        return prefs.getString(KEY_BOT_TOKEN, null)
    }

    // --- Telegram Chat ID Whitelist ---
    fun saveAllowedChatIds(chatIds: Set<String>) {
        prefs.edit().putStringSet(KEY_ALLOWED_CHAT_IDS, HashSet(chatIds)).apply()
    }

    fun getAllowedChatIds(): Set<String> {
        return prefs.getStringSet(KEY_ALLOWED_CHAT_IDS, emptySet())?.toSet() ?: emptySet()
    }

    fun isChatIdAllowed(chatId: String): Boolean {
        return getAllowedChatIds().contains(chatId)
    }

    fun getLastTelegramUpdateId(): Long = prefs.getLong(KEY_LAST_TELEGRAM_UPDATE_ID, 0L)

    fun commitLastTelegramUpdateId(updateId: Long): Boolean = prefs.edit()
        .putLong(KEY_LAST_TELEGRAM_UPDATE_ID, updateId)
        .commit()

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

    // --- Legacy Authenticator Seed Cleanup ---
    fun containsLegacyAuthenticatorSeed(): Boolean =
        prefs.contains(LEGACY_AUTHENTICATOR_SEED_KEY)

    fun removeLegacyAuthenticatorSeed(): Boolean =
        prefs.edit().remove(LEGACY_AUTHENTICATOR_SEED_KEY).commit()

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

    // --- SMS Device Key ---
    /**
     * The key the SMS fallback seals alerts under, created on first use and never shown
     * to anyone. Both ends of the round trip are this device — it encrypts the alert and
     * it decrypts what the owner forwards back through `/decode` — so there is nothing to
     * type, and nothing weak enough to brute force from an intercepted message.
     *
     * Written with commit() rather than apply(): a key lost to a crash between generating
     * and persisting would leave alerts on the wire that nothing can open.
     */
    fun getOrCreateSmsAesKey(): String {
        prefs.getString(KEY_SMS_AES_KEY_V3, null)
            ?.takeIf { EncryptedSmsCodec.isValidKeyBase64(it) }
            ?.let { return it }
        val generated = EncryptedSmsCodec.generateKeyBase64()
        prefs.edit().putString(KEY_SMS_AES_KEY_V3, generated).commit()
        return generated
    }

    /**
     * The passphrase older builds asked the owner to type. Read-only, and used for nothing
     * but opening alerts sent before the key was randomised.
     */
    fun getLegacySmsAesKey(): String? {
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

    fun commitSystemArmed(armed: Boolean): Boolean =
        prefs.edit().putBoolean(KEY_SYSTEM_ARMED, armed).commit()

    fun isSystemArmed(): Boolean {
        return prefs.getBoolean(KEY_SYSTEM_ARMED, false)
    }

    fun isAutoRecoveryAfterBootEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_RECOVERY_AFTER_BOOT, true)
    }

    // --- Sensor Sensitivity (1-10) ---
    fun setSensitivity(level: Int) {
        val clamped = level.coerceIn(1, 10)
        prefs.edit().putInt(KEY_SENSITIVITY, clamped).apply()
    }

    fun getSensitivity(): Int {
        return prefs.getInt(KEY_SENSITIVITY, 5)
    }

    // --- Movement Tracking ---
    fun saveMovementTrackingState(json: String?): Boolean {
        return if (json == null) {
            prefs.edit().remove(KEY_MOVEMENT_TRACKING_STATE).commit()
        } else {
            prefs.edit().putString(KEY_MOVEMENT_TRACKING_STATE, json).commit()
        }
    }

    fun getMovementTrackingState(): String? = prefs.getString(KEY_MOVEMENT_TRACKING_STATE, null)

    fun clearLegacyMovementTrackingKeys(): Boolean {
        return prefs.edit()
            .remove(KEY_PARKING_ANCHOR)
            .remove(KEY_LIVE_PURSUIT_SESSION)
            .commit()
    }

    fun saveParkingAnchor(json: String?): Boolean {
        return if (json == null) {
            prefs.edit().remove(KEY_PARKING_ANCHOR).commit()
        } else {
            prefs.edit().putString(KEY_PARKING_ANCHOR, json).commit()
        }
    }

    fun getParkingAnchor(): String? = prefs.getString(KEY_PARKING_ANCHOR, null)

    fun saveLivePursuitSession(json: String?): Boolean {
        return if (json == null) {
            prefs.edit().remove(KEY_LIVE_PURSUIT_SESSION).commit()
        } else {
            prefs.edit().putString(KEY_LIVE_PURSUIT_SESSION, json).commit()
        }
    }

    fun getLivePursuitSession(): String? = prefs.getString(KEY_LIVE_PURSUIT_SESSION, null)
}

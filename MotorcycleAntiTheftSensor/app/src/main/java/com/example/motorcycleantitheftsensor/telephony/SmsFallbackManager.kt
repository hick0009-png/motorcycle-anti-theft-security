package com.example.motorcycleantitheftsensor.telephony

import android.content.Context
import android.telephony.SmsManager
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager

/**
 * COM-03: SmsFallbackManager
 * Sends AES-128 encrypted SMS alert messages containing GPS coordinates when cellular internet connectivity
 * is unavailable.
 */
class SmsFallbackManager(
    private val context: Context,
    private val prefsManager: EncryptedPrefsManager
) {

    /**
     * Encrypts alarm text using AES-128 and sends via SmsManager.
     */
    fun sendEncryptedSmsAlert(destinationNumber: String, alertType: String, gpsLocation: String): Boolean {
        val secretPass = prefsManager.getSmsAesKey() ?: return false // Refuse to send if no key configured
        if (destinationNumber.isBlank()) return false
        val payload = "ALERT:$alertType|LOC:$gpsLocation|TIME:${System.currentTimeMillis()}"

        val encryptedBody = EncryptedSmsCodec.encryptSmsPayload(payload, secretPass)

        val smsManager = SmsManager.getDefault()
        val parts = smsManager.divideMessage(encryptedBody)
        smsManager.sendMultipartTextMessage(destinationNumber, null, parts, null, null)
        return true
    }
}

package com.example.motorcycleantitheftsensor.telephony

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager

/**
 * COM-03: SmsFallbackManager
 * Sends AES-128 encrypted SMS alert messages containing GPS coordinates and places emergency phone call alerts
 * when cellular internet connectivity is unavailable.
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

    /**
     * Triggers an emergency direct phone call (Missed Call Alert) to the vehicle owner's primary mobile number.
     */
    @SuppressLint("MissingPermission")
    fun triggerEmergencyDirectCall(phoneNumber: String) {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}

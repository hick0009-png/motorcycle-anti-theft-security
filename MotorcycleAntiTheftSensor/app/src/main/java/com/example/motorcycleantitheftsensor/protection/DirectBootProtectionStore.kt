package com.example.motorcycleantitheftsensor.protection

import android.content.Context

/**
 * Minimal recovery hint that is available before the user unlocks Android.
 * It deliberately contains no credentials, Telegram configuration, identifiers, or incidents.
 */
data class DirectBootProtectionMarker(
    val armed: Boolean,
    val autoRecoveryAfterBoot: Boolean,
)

class DirectBootProtectionStore(context: Context) {
    private val deviceProtectedContext = context.applicationContext.createDeviceProtectedStorageContext()
    private val preferences = deviceProtectedContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(marker: DirectBootProtectionMarker): Boolean = preferences.edit()
        .putBoolean(KEY_ARMED, marker.armed)
        .putBoolean(KEY_AUTO_RECOVERY_AFTER_BOOT, marker.autoRecoveryAfterBoot)
        .commit()

    fun load(): DirectBootProtectionMarker = DirectBootProtectionMarker(
        armed = preferences.getBoolean(KEY_ARMED, false),
        autoRecoveryAfterBoot = preferences.getBoolean(KEY_AUTO_RECOVERY_AFTER_BOOT, false),
    )

    private companion object {
        const val PREFERENCES_NAME = "direct_boot_protection_marker"
        const val KEY_ARMED = "armed"
        const val KEY_AUTO_RECOVERY_AFTER_BOOT = "auto_recovery_after_boot"
    }
}

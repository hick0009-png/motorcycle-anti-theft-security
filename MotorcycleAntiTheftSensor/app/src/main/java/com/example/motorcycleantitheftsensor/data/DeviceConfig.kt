package com.example.motorcycleantitheftsensor.data

/**
 * UI-02: DeviceConfig
 * Data class representing system configuration model.
 */
data class DeviceConfig(
    val deviceUuid: String,
    val isArmed: Boolean,
    val sensitivityLevel: Int,
    val hasBotToken: Boolean,
    val hasTotpSeed: Boolean,
    val allowedChatCount: Int
)

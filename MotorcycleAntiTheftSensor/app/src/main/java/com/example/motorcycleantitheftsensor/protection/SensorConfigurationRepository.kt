package com.example.motorcycleantitheftsensor.protection

import android.content.SharedPreferences
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager

interface SensorConfigurationRepository {
    fun loadConfiguration(): SensorFusionConfiguration
    fun saveConfiguration(config: SensorFusionConfiguration): Result<Unit>
    fun hasPersistedConfiguration(): Boolean
}

class EncryptedPrefsSensorConfigurationRepository(
    private val prefs: SharedPreferences,
    private val codec: SensorConfigurationCodec = SensorConfigurationCodec(),
    private val policy: SensorConfigurationPolicy = SensorConfigurationPolicy(),
) : SensorConfigurationRepository {

    constructor(
        context: android.content.Context,
        codec: SensorConfigurationCodec = SensorConfigurationCodec(),
        policy: SensorConfigurationPolicy = SensorConfigurationPolicy(),
    ) : this(
        prefs = context.getSharedPreferences("motorcycle_anti_theft_sensor_config", android.content.Context.MODE_PRIVATE),
        codec = codec,
        policy = policy,
    )

    companion object {
        const val KEY_SENSOR_FUSION_CONFIG = "sensor_fusion_configuration_json"
        const val KEY_LEGACY_SENSITIVITY = "sensor_sensitivity_level"
    }

    override fun hasPersistedConfiguration(): Boolean {
        return prefs.contains(KEY_SENSOR_FUSION_CONFIG)
    }

    override fun loadConfiguration(): SensorFusionConfiguration {
        val storedJson = prefs.getString(KEY_SENSOR_FUSION_CONFIG, null)
        if (!storedJson.isNullOrBlank()) {
            return try {
                val decoded = codec.decode(storedJson)
                if (policy.validateForSave(decoded) is SensorConfigurationValidation.Valid) {
                    decoded
                } else {
                    policy.forPreset(SensorPreset.BALANCED)
                }
            } catch (_: Exception) {
                policy.forPreset(SensorPreset.BALANCED)
            }
        }

        // Migration from legacy sensitivity
        val legacySensitivity = prefs.getInt(KEY_LEGACY_SENSITIVITY, 5).coerceIn(1, 10)
        val migratedBalanced = policy.forPreset(SensorPreset.BALANCED)
        val migrated = policy.withGroupSensitivity(
            config = policy.withGroupSensitivity(migratedBalanced, SensorCapability.MOVEMENT, legacySensitivity),
            capability = SensorCapability.LIGHT,
            sensitivity = legacySensitivity
        )

        // Save migrated configuration atomically
        saveConfiguration(migrated)
        return migrated
    }

    override fun saveConfiguration(config: SensorFusionConfiguration): Result<Unit> {
        val validation = policy.validateForSave(config)
        if (validation !is SensorConfigurationValidation.Valid) {
            return Result.failure(IllegalArgumentException("Invalid sensor configuration: $validation"))
        }

        return try {
            val json = codec.encode(config)
            val committed = prefs.edit()
                .putString(KEY_SENSOR_FUSION_CONFIG, json)
                .commit()
            if (committed) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Failed to commit sensor configuration to encrypted preferences"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

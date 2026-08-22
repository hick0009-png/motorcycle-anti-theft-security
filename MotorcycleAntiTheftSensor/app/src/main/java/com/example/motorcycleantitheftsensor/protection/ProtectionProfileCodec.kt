package com.example.motorcycleantitheftsensor.protection

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned codec for the whole [ProtectionProfileStoreState] aggregate.
 *
 * Encoding rules (Task 2 of the three-protection-profiles foundation):
 * - One JSON document holds every profile plus the optional selection and legacy configuration.
 * - Profiles are always written in fixed canonical enum order.
 * - Nested sensor configurations reuse [SensorConfigurationCodec].
 * - Decoding rejects unknown future schema versions, missing profile entries,
 *   invalid enum values, and invalid profile-specific ranges instead of guessing.
 */
class ProtectionProfileCodec(
    private val sensorCodec: SensorConfigurationCodec = SensorConfigurationCodec(),
) {

    fun encode(state: ProtectionProfileStoreState): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        if (state.selectedProfile != null) {
            root.put("selectedProfile", state.selectedProfile.name)
        } else {
            root.put("selectedProfile", JSONObject.NULL)
        }

        val profilesArray = JSONArray()
        // Write profiles in canonical enum order.
        for (profile in ProtectionProfile.entries) {
            val stored = state.profiles.getValue(profile)
            val profileObj = JSONObject()
            profileObj.put("profile", stored.profile.name)
            profileObj.put("presetVersion", stored.presetVersion)
            profileObj.put("setupState", stored.setupState.name)
            profileObj.put("sensorOverrides", encodeSensorOverrides(stored.sensorOverrides))
            profileObj.put("specificOverrides", encodeSpecificOverrides(stored.specificOverrides))
            profilesArray.put(profileObj)
        }
        root.put("profiles", profilesArray)

        if (state.legacyConfiguration != null) {
            root.put("legacyConfiguration", JSONObject(sensorCodec.encode(state.legacyConfiguration)))
        } else {
            root.put("legacyConfiguration", JSONObject.NULL)
        }

        val transaction = state.switchTransaction
        if (transaction != null) {
            val txnObj = JSONObject()
            txnObj.put("transactionId", transaction.transactionId)
            if (transaction.oldArmedSessionId != null) {
                txnObj.put("oldArmedSessionId", transaction.oldArmedSessionId)
            } else {
                txnObj.put("oldArmedSessionId", JSONObject.NULL)
            }
            txnObj.put("targetProfile", transaction.targetProfile.name)
            txnObj.put("phase", transaction.phase.name)
            root.put("switchTransaction", txnObj)
        } else {
            root.put("switchTransaction", JSONObject.NULL)
        }
        return root.toString()
    }

    fun decode(jsonString: String): ProtectionProfileStoreState {
        try {
            val root = JSONObject(jsonString)
            val schemaVersion = root.getInt("schemaVersion")
            require(schemaVersion == SCHEMA_VERSION) {
                "Unsupported protection profile schema version: $schemaVersion"
            }

            val selectedProfile = decodeOptionalEnum<ProtectionProfile>(root, "selectedProfile")

            val profilesJson = root.getJSONArray("profiles")
            val profiles = mutableMapOf<ProtectionProfile, StoredProfileConfiguration>()
            for (i in 0 until profilesJson.length()) {
                val profileObj = profilesJson.getJSONObject(i)
                val profile = decodeEnum<ProtectionProfile>(profileObj, "profile")
                require(!profiles.containsKey(profile)) { "Duplicate profile entry: $profile" }
                val presetVersion = profileObj.getInt("presetVersion")
                require(presetVersion >= 1) { "Invalid preset version for $profile: $presetVersion" }
                val setupState = decodeEnum<ProfileSetupState>(profileObj, "setupState")
                val sensorOverrides = decodeSensorOverrides(profileObj.getJSONObject("sensorOverrides"))
                val specificOverrides =
                    decodeSpecificOverrides(profile, profileObj.getJSONObject("specificOverrides"))
                profiles[profile] = StoredProfileConfiguration(
                    profile = profile,
                    presetVersion = presetVersion,
                    sensorOverrides = sensorOverrides,
                    specificOverrides = specificOverrides,
                    setupState = setupState,
                )
            }
            val missing = ProtectionProfile.entries.filterNot { profiles.containsKey(it) }
            require(missing.isEmpty()) { "Missing profile entries: $missing" }

            val legacyConfiguration = if (root.isNull("legacyConfiguration")) {
                null
            } else {
                sensorCodec.decode(root.getJSONObject("legacyConfiguration").toString())
            }

            val switchTransaction = if (root.isNull("switchTransaction")) {
                null
            } else {
                val txnObj = root.getJSONObject("switchTransaction")
                val transactionId = txnObj.getString("transactionId")
                require(transactionId.isNotBlank()) { "Switch transaction id must not be blank" }
                ProfileSwitchTransaction(
                    transactionId = transactionId,
                    oldArmedSessionId =
                        if (txnObj.isNull("oldArmedSessionId")) null else txnObj.getString("oldArmedSessionId"),
                    targetProfile = decodeEnum<ProtectionProfile>(txnObj, "targetProfile"),
                    phase = decodeEnum<ProfileSwitchPhase>(txnObj, "phase"),
                )
            }

            return ProtectionProfileStoreState(
                schemaVersion = SCHEMA_VERSION,
                selectedProfile = selectedProfile,
                profiles = profiles,
                legacyConfiguration = legacyConfiguration,
                switchTransaction = switchTransaction,
            )
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Corrupt protection profile aggregate", e)
        }
    }

    private fun encodeSensorOverrides(overrides: SensorFusionProfileOverrides): JSONObject {
        val obj = JSONObject()
        putOptionalName(obj, "samplingProfile", overrides.samplingProfile?.name)

        val capabilitiesArray = JSONArray()
        for (capability in SensorCapability.entries) {
            val capabilityOverrides = overrides.capabilities[capability] ?: continue
            val capabilityObj = JSONObject()
            capabilityObj.put("capability", capability.name)
            putOptionalInt(capabilityObj, "sensitivity", capabilityOverrides.sensitivity)
            putOptionalLong(capabilityObj, "correlationWindowMs", capabilityOverrides.correlationWindowMs)
            putOptionalLong(
                capabilityObj,
                "confirmationDurationMs",
                capabilityOverrides.confirmationDurationMs,
            )
            capabilitiesArray.put(capabilityObj)
        }
        obj.put("capabilities", capabilitiesArray)

        val sourcesArray = JSONArray()
        for (source in SensorSource.entries) {
            val sourceOverrides = overrides.sources[source] ?: continue
            val sourceObj = JSONObject()
            sourceObj.put("source", source.name)
            putOptionalName(sourceObj, "role", sourceOverrides.role?.name)
            putOptionalDouble(sourceObj, "thresholdOverride", sourceOverrides.thresholdOverride)
            putOptionalLong(sourceObj, "debounceOverrideMs", sourceOverrides.debounceOverrideMs)
            putOptionalName(
                sourceObj,
                "samplingProfileOverride",
                sourceOverrides.samplingProfileOverride?.name,
            )
            sourcesArray.put(sourceObj)
        }
        obj.put("sources", sourcesArray)
        return obj
    }

    private fun decodeSensorOverrides(obj: JSONObject): SensorFusionProfileOverrides {
        val samplingProfile = decodeOptionalEnum<SensorSamplingProfile>(obj, "samplingProfile")

        val capabilities = mutableMapOf<SensorCapability, SensorCapabilityProfileOverrides>()
        val capabilitiesArray = obj.optJSONArray("capabilities") ?: JSONArray()
        for (i in 0 until capabilitiesArray.length()) {
            val capabilityObj = capabilitiesArray.getJSONObject(i)
            val capability = decodeEnum<SensorCapability>(capabilityObj, "capability")
            require(!capabilities.containsKey(capability)) {
                "Duplicate capability override: $capability"
            }
            capabilities[capability] = SensorCapabilityProfileOverrides(
                sensitivity = decodeOptionalInt(capabilityObj, "sensitivity"),
                correlationWindowMs = decodeOptionalLong(capabilityObj, "correlationWindowMs"),
                confirmationDurationMs = decodeOptionalLong(capabilityObj, "confirmationDurationMs"),
            )
        }

        val sources = mutableMapOf<SensorSource, SensorSourceProfileOverrides>()
        val sourcesArray = obj.optJSONArray("sources") ?: JSONArray()
        for (i in 0 until sourcesArray.length()) {
            val sourceObj = sourcesArray.getJSONObject(i)
            val source = decodeEnum<SensorSource>(sourceObj, "source")
            require(!sources.containsKey(source)) { "Duplicate source override: $source" }
            sources[source] = SensorSourceProfileOverrides(
                role = decodeOptionalEnum<SensorRole>(sourceObj, "role"),
                thresholdOverride = decodeOptionalDouble(sourceObj, "thresholdOverride"),
                debounceOverrideMs = decodeOptionalLong(sourceObj, "debounceOverrideMs"),
                samplingProfileOverride =
                    decodeOptionalEnum<SensorSamplingProfile>(sourceObj, "samplingProfileOverride"),
            )
        }

        return SensorFusionProfileOverrides(
            samplingProfile = samplingProfile,
            capabilities = capabilities,
            sources = sources,
        )
    }

    private fun encodeSpecificOverrides(overrides: ProfileSpecificOverrides): JSONObject {
        val obj = JSONObject()
        when (overrides) {
            is VehicleProfileOverrides -> obj.put("kind", ProfileKinds.VEHICLE)
            is EntryProfileOverrides -> {
                obj.put("kind", ProfileKinds.ENTRY)
                putOptionalInt(obj, "angleThresholdDegrees", overrides.angleThresholdDegrees)
                putOptionalLong(obj, "openConfirmationMs", overrides.openConfirmationMs)
            }
            is PowerProfileOverrides -> {
                obj.put("kind", ProfileKinds.POWER)
                putOptionalLong(obj, "lossConfirmationMs", overrides.lossConfirmationMs)
                putOptionalLong(obj, "recoveryConfirmationMs", overrides.recoveryConfirmationMs)
            }
        }
        return obj
    }

    private fun decodeSpecificOverrides(
        profile: ProtectionProfile,
        obj: JSONObject,
    ): ProfileSpecificOverrides {
        val kind = obj.optString("kind")
        return when (profile) {
            ProtectionProfile.VEHICLE -> {
                require(kind == ProfileKinds.VEHICLE) {
                    "Vehicle profile requires vehicle-specific overrides, found: $kind"
                }
                VehicleProfileOverrides
            }
            ProtectionProfile.ENTRY -> {
                require(kind == ProfileKinds.ENTRY) {
                    "Entry profile requires entry-specific overrides, found: $kind"
                }
                val angleThresholdDegrees = decodeOptionalInt(obj, "angleThresholdDegrees")
                require(angleThresholdDegrees == null || angleThresholdDegrees in 5..90) {
                    "Entry angle threshold must be 5-90 degrees"
                }
                val openConfirmationMs = decodeOptionalLong(obj, "openConfirmationMs")
                require(openConfirmationMs == null || openConfirmationMs in 250L..3_000L) {
                    "Entry open confirmation must be 250-3000 ms"
                }
                EntryProfileOverrides(
                    angleThresholdDegrees = angleThresholdDegrees,
                    openConfirmationMs = openConfirmationMs,
                )
            }
            ProtectionProfile.POWER -> {
                require(kind == ProfileKinds.POWER) {
                    "Power profile requires power-specific overrides, found: $kind"
                }
                val lossConfirmationMs = decodeOptionalLong(obj, "lossConfirmationMs")
                require(lossConfirmationMs == null || lossConfirmationMs == 10_000L) {
                    "Power loss confirmation must be 10000 ms"
                }
                val recoveryConfirmationMs = decodeOptionalLong(obj, "recoveryConfirmationMs")
                require(recoveryConfirmationMs == null || recoveryConfirmationMs == 30_000L) {
                    "Power recovery confirmation must be 30000 ms"
                }
                PowerProfileOverrides(
                    lossConfirmationMs = lossConfirmationMs,
                    recoveryConfirmationMs = recoveryConfirmationMs,
                )
            }
        }
    }

    private inline fun <reified T : Enum<T>> decodeEnum(obj: JSONObject, key: String): T {
        val raw = obj.getString(key)
        return try {
            enumValues<T>().first { it.name == raw }
        } catch (_: NoSuchElementException) {
            throw IllegalArgumentException("Invalid ${T::class.java.simpleName} value: $raw")
        }
    }

    private inline fun <reified T : Enum<T>> decodeOptionalEnum(obj: JSONObject, key: String): T? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return decodeEnum<T>(obj, key)
    }

    private fun decodeOptionalInt(obj: JSONObject, key: String): Int? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.getInt(key)

    private fun decodeOptionalLong(obj: JSONObject, key: String): Long? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.getLong(key)

    private fun decodeOptionalDouble(obj: JSONObject, key: String): Double? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.getDouble(key)

    private fun putOptionalInt(obj: JSONObject, key: String, value: Int?) {
        if (value != null) obj.put(key, value) else obj.put(key, JSONObject.NULL)
    }

    private fun putOptionalLong(obj: JSONObject, key: String, value: Long?) {
        if (value != null) obj.put(key, value) else obj.put(key, JSONObject.NULL)
    }

    private fun putOptionalDouble(obj: JSONObject, key: String, value: Double?) {
        if (value != null) obj.put(key, value) else obj.put(key, JSONObject.NULL)
    }

    private fun putOptionalName(obj: JSONObject, key: String, value: String?) {
        if (value != null) obj.put(key, value) else obj.put(key, JSONObject.NULL)
    }

    private object ProfileKinds {
        const val VEHICLE = "VEHICLE"
        const val ENTRY = "ENTRY"
        const val POWER = "POWER"
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

package com.example.motorcycleantitheftsensor.protection

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Stable SHA-256 fingerprint over the canonical codec output of a resolved armed
 * configuration plus its profile-specific settings. Never uses object hashCode() or
 * wall-clock values, so equal canonical configurations always produce equal fingerprints.
 */
object ConfigurationFingerprint {

    fun sha256(
        configuration: SensorFusionConfiguration,
        specificSettings: ProfileSpecificSettings,
    ): String {
        val canonical = buildString {
            append(SCHEMA_TAG)
            append('|')
            append(sensorCodec.encode(configuration))
            append('|')
            append(canonicalSpecificSettings(specificSettings))
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private fun canonicalSpecificSettings(settings: ProfileSpecificSettings): String = when (settings) {
        is VehicleProfileSettings -> "VEHICLE"
        is EntryProfileSettings -> "ENTRY|angle=${settings.angleThresholdDegrees}" +
            "|openMs=${settings.openConfirmationMs}" +
            "|closeAngle=${settings.closeThresholdDegrees}" +
            "|closeMs=${settings.closeConfirmationMs}"
        is PowerProfileSettings -> "POWER|lossMs=${settings.lossConfirmationMs}" +
            "|recoveryMs=${settings.recoveryConfirmationMs}"
    }

    private val sensorCodec = SensorConfigurationCodec()

    private const val SCHEMA_TAG = "armed-profile-config-v1"
}

/**
 * Strict codec for the immutable [ArmedProfileSnapshot]. Rejects future schema versions,
 * invalid enums, blank identifiers/fingerprints, calibration kinds that do not match the
 * armed profile, and Entry/Power calibrations without a model fingerprint.
 */
class ArmedProfileSnapshotCodec(
    private val sensorCodec: SensorConfigurationCodec = SensorConfigurationCodec(),
) {

    fun encode(snapshot: ArmedProfileSnapshot): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("armedSessionId", snapshot.armedSessionId)
        root.put("profile", snapshot.profile.name)
        root.put("resolvedPresetVersion", snapshot.resolvedPresetVersion)
        root.put(
            "effectiveConfiguration",
            JSONObject(sensorCodec.encode(snapshot.effectiveConfiguration)),
        )
        root.put("configurationFingerprint", snapshot.configurationFingerprint)
        if (snapshot.commissionedModelFingerprint != null) {
            root.put("commissionedModelFingerprint", snapshot.commissionedModelFingerprint)
        } else {
            root.put("commissionedModelFingerprint", JSONObject.NULL)
        }
        root.put("armedCalibrationSnapshot", encodeCalibration(snapshot.armedCalibrationSnapshot))
        return root.toString()
    }

    fun decode(jsonString: String): ArmedProfileSnapshot {
        try {
            val root = JSONObject(jsonString)
            val schemaVersion = root.getInt("schemaVersion")
            require(schemaVersion == SCHEMA_VERSION) {
                "Unsupported armed profile snapshot schema version: $schemaVersion"
            }
            val armedSessionId = root.getString("armedSessionId")
            require(armedSessionId.isNotBlank()) { "Armed session id must not be blank" }
            val profile = decodeEnum<ProtectionProfile>(root, "profile")
            val resolvedPresetVersion = root.getInt("resolvedPresetVersion")
            require(resolvedPresetVersion >= 1) {
                "Invalid resolved preset version: $resolvedPresetVersion"
            }
            val effectiveConfiguration =
                sensorCodec.decode(root.getJSONObject("effectiveConfiguration").toString())
            val configurationFingerprint = root.getString("configurationFingerprint")
            require(configurationFingerprint.isNotBlank()) {
                "Configuration fingerprint must not be blank"
            }
            val commissionedModelFingerprint =
                if (root.isNull("commissionedModelFingerprint")) {
                    null
                } else {
                    root.getString("commissionedModelFingerprint")
                }
            val calibration = decodeCalibration(profile, root.getJSONObject("armedCalibrationSnapshot"))
            return ArmedProfileSnapshot(
                armedSessionId = armedSessionId,
                profile = profile,
                resolvedPresetVersion = resolvedPresetVersion,
                effectiveConfiguration = effectiveConfiguration,
                configurationFingerprint = configurationFingerprint,
                commissionedModelFingerprint = commissionedModelFingerprint,
                armedCalibrationSnapshot = calibration,
            )
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Corrupt armed profile snapshot", e)
        }
    }

    private fun encodeCalibration(calibration: ArmedCalibrationSnapshot): JSONObject {
        val obj = JSONObject()
        when (calibration) {
            is VehicleArmedCalibrationSnapshot -> {
                obj.put("kind", CalibrationKinds.VEHICLE)
                obj.put("generation", calibration.generation)
            }
            is EntryArmedCalibrationSnapshot -> {
                obj.put("kind", CalibrationKinds.ENTRY)
                obj.put("generation", calibration.generation)
                obj.put("modelFingerprint", calibration.modelFingerprint)
            }
            is PowerArmedCalibrationSnapshot -> {
                obj.put("kind", CalibrationKinds.POWER)
                obj.put("generation", calibration.generation)
                obj.put("modelFingerprint", calibration.modelFingerprint)
            }
        }
        return obj
    }

    private fun decodeCalibration(
        profile: ProtectionProfile,
        obj: JSONObject,
    ): ArmedCalibrationSnapshot {
        val kind = obj.optString("kind")
        val generation = obj.getLong("generation")
        require(generation >= 0L) { "Invalid calibration generation: $generation" }
        return when (profile) {
            ProtectionProfile.VEHICLE -> {
                require(kind == CalibrationKinds.VEHICLE) {
                    "Vehicle armed session requires vehicle calibration, found: $kind"
                }
                VehicleArmedCalibrationSnapshot(generation = generation)
            }
            ProtectionProfile.ENTRY -> {
                require(kind == CalibrationKinds.ENTRY) {
                    "Entry armed session requires entry calibration, found: $kind"
                }
                val modelFingerprint = obj.getString("modelFingerprint")
                require(modelFingerprint.isNotBlank()) {
                    "Entry calibration requires a model fingerprint"
                }
                EntryArmedCalibrationSnapshot(
                    generation = generation,
                    modelFingerprint = modelFingerprint,
                )
            }
            ProtectionProfile.POWER -> {
                require(kind == CalibrationKinds.POWER) {
                    "Power armed session requires power calibration, found: $kind"
                }
                val modelFingerprint = obj.getString("modelFingerprint")
                require(modelFingerprint.isNotBlank()) {
                    "Power calibration requires a model fingerprint"
                }
                PowerArmedCalibrationSnapshot(
                    generation = generation,
                    modelFingerprint = modelFingerprint,
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

    private object CalibrationKinds {
        const val VEHICLE = "VEHICLE"
        const val ENTRY = "ENTRY"
        const val POWER = "POWER"
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

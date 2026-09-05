package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ArmedProfileSnapshotCodecTest {

    private val codec = ArmedProfileSnapshotCodec()
    private val vehicleConfig =
        SensorConfigurationPolicy().forPreset(SensorPreset.BALANCED, nowMs = 1_000L)

    @Test
    fun snapshotRoundTripKeepsProfileSessionAndFrozenConfiguration() {
        val armed = ArmedProfileSnapshot(
            armedSessionId = "session-1",
            profile = ProtectionProfile.VEHICLE,
            resolvedPresetVersion = 1,
            effectiveConfiguration = vehicleConfig,
            configurationFingerprint =
                ConfigurationFingerprint.sha256(vehicleConfig, VehicleProfileSettings),
            commissionedModelFingerprint = null,
            armedCalibrationSnapshot = VehicleArmedCalibrationSnapshot(generation = 7L),
        )

        assertEquals(armed, codec.decode(codec.encode(armed)))
    }

    @Test
    fun entryWatchLevelSurvivesTheRoundTripAndAbsenceStaysAbsent() {
        // The level decides which sensors detect at all. A session frozen at the
        // sound-and-movement level that decoded as DOOR_ANGLE would have five sensor kinds
        // counted against it after a restart, two of which it never registered.
        val soundAndMovement = ArmedProfileSnapshot(
            armedSessionId = "session-entry-level",
            profile = ProtectionProfile.ENTRY,
            resolvedPresetVersion = 1,
            effectiveConfiguration = vehicleConfig,
            configurationFingerprint =
                ConfigurationFingerprint.sha256(vehicleConfig, EntryProfileSettings()),
            commissionedModelFingerprint = "model-fp-entry",
            armedCalibrationSnapshot = EntryArmedCalibrationSnapshot(
                generation = 3L,
                modelFingerprint = "model-fp-entry",
            ),
            entryLevel = EntryWatchLevel.SOUND_AND_MOVEMENT,
        )
        assertEquals(soundAndMovement, codec.decode(codec.encode(soundAndMovement)))

        val vehicle = ArmedProfileSnapshot(
            armedSessionId = "session-vehicle-level",
            profile = ProtectionProfile.VEHICLE,
            resolvedPresetVersion = 1,
            effectiveConfiguration = vehicleConfig,
            configurationFingerprint =
                ConfigurationFingerprint.sha256(vehicleConfig, VehicleProfileSettings),
            commissionedModelFingerprint = null,
            armedCalibrationSnapshot = VehicleArmedCalibrationSnapshot(generation = 1L),
        )
        assertNull(codec.decode(codec.encode(vehicle)).entryLevel)
    }

    @Test
    fun aSessionFrozenBeforeTheLevelExistedStillDecodes() {
        // Field added inside schema version 1: the stored JSON of every already-armed
        // phone has no "entryLevel" key, and refusing those would strand a live session.
        val armed = ArmedProfileSnapshot(
            armedSessionId = "session-legacy",
            profile = ProtectionProfile.ENTRY,
            resolvedPresetVersion = 1,
            effectiveConfiguration = vehicleConfig,
            configurationFingerprint =
                ConfigurationFingerprint.sha256(vehicleConfig, EntryProfileSettings()),
            commissionedModelFingerprint = "model-fp-entry",
            armedCalibrationSnapshot = EntryArmedCalibrationSnapshot(
                generation = 3L,
                modelFingerprint = "model-fp-entry",
            ),
        )
        val encodedWithoutLevel = codec.encode(armed)
        assertFalse(encodedWithoutLevel.contains("entryLevel"))
        assertNull(codec.decode(encodedWithoutLevel).entryLevel)
    }

    @Test
    fun anEntryLevelOnANonEntrySessionIsRefused() {
        val armed = ArmedProfileSnapshot(
            armedSessionId = "session-mismatch",
            profile = ProtectionProfile.VEHICLE,
            resolvedPresetVersion = 1,
            effectiveConfiguration = vehicleConfig,
            configurationFingerprint =
                ConfigurationFingerprint.sha256(vehicleConfig, VehicleProfileSettings),
            commissionedModelFingerprint = null,
            armedCalibrationSnapshot = VehicleArmedCalibrationSnapshot(generation = 1L),
            entryLevel = EntryWatchLevel.DOOR_ANGLE,
        )
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode(codec.encode(armed))
        }
    }

    @Test
    fun roundTripPreservesEntryAndPowerCalibrationSnapshots() {
        val entryArmed = ArmedProfileSnapshot(
            armedSessionId = "session-entry",
            profile = ProtectionProfile.ENTRY,
            resolvedPresetVersion = 1,
            effectiveConfiguration = vehicleConfig,
            configurationFingerprint =
                ConfigurationFingerprint.sha256(vehicleConfig, EntryProfileSettings()),
            commissionedModelFingerprint = "model-fp-entry",
            armedCalibrationSnapshot = EntryArmedCalibrationSnapshot(
                generation = 3L,
                modelFingerprint = "model-fp-entry",
            ),
        )
        val powerArmed = ArmedProfileSnapshot(
            armedSessionId = "session-power",
            profile = ProtectionProfile.POWER,
            resolvedPresetVersion = 1,
            effectiveConfiguration = vehicleConfig,
            configurationFingerprint =
                ConfigurationFingerprint.sha256(vehicleConfig, PowerProfileSettings()),
            commissionedModelFingerprint = "model-fp-power",
            armedCalibrationSnapshot = PowerArmedCalibrationSnapshot(
                generation = 5L,
                modelFingerprint = "model-fp-power",
                witnessPlacementValidated = true,
            ),
        )

        assertEquals(entryArmed, codec.decode(codec.encode(entryArmed)))
        assertEquals(powerArmed, codec.decode(codec.encode(powerArmed)))
    }

    @Test
    fun legacyPowerSnapshotWithoutChallengeResultFailsSafe() {
        val powerArmed = ArmedProfileSnapshot(
            armedSessionId = "session-power",
            profile = ProtectionProfile.POWER,
            resolvedPresetVersion = 1,
            effectiveConfiguration = vehicleConfig,
            configurationFingerprint =
                ConfigurationFingerprint.sha256(vehicleConfig, PowerProfileSettings()),
            commissionedModelFingerprint = "model-fp-power",
            armedCalibrationSnapshot = PowerArmedCalibrationSnapshot(
                generation = 5L,
                modelFingerprint = "model-fp-power",
                witnessPlacementValidated = true,
            ),
        )
        val legacy = org.json.JSONObject(codec.encode(powerArmed)).apply {
            getJSONObject("armedCalibrationSnapshot").remove("witnessPlacementValidated")
        }.toString()

        val calibration = codec.decode(legacy).armedCalibrationSnapshot as PowerArmedCalibrationSnapshot

        assertFalse(calibration.witnessPlacementValidated)
    }

    @Test
    fun fingerprintIsStableForEqualCanonicalConfiguration() {
        assertEquals(
            ConfigurationFingerprint.sha256(vehicleConfig, VehicleProfileSettings),
            ConfigurationFingerprint.sha256(vehicleConfig.copy(), VehicleProfileSettings),
        )
    }

    @Test
    fun fingerprintChangesWhenConfigurationOrSettingsChange() {
        val hotterConfig = SensorConfigurationPolicy()
            .withGroupSensitivity(vehicleConfig, SensorCapability.MOVEMENT, 9, 1_000L)

        assertNotEquals(
            ConfigurationFingerprint.sha256(vehicleConfig, VehicleProfileSettings),
            ConfigurationFingerprint.sha256(hotterConfig, VehicleProfileSettings),
        )
        assertNotEquals(
            ConfigurationFingerprint.sha256(vehicleConfig, VehicleProfileSettings),
            ConfigurationFingerprint.sha256(vehicleConfig, EntryProfileSettings()),
        )
    }

    @Test
    fun futureSchemaVersionIsRejected() {
        val json = codec.encode(
            ArmedProfileSnapshot(
                armedSessionId = "session-1",
                profile = ProtectionProfile.VEHICLE,
                resolvedPresetVersion = 1,
                effectiveConfiguration = vehicleConfig,
                configurationFingerprint =
                    ConfigurationFingerprint.sha256(vehicleConfig, VehicleProfileSettings),
                commissionedModelFingerprint = null,
                armedCalibrationSnapshot = VehicleArmedCalibrationSnapshot(generation = 7L),
            ),
        )
        val tampered = json.replace("\"schemaVersion\":1", "\"schemaVersion\":99")

        assertThrows(IllegalArgumentException::class.java) { codec.decode(tampered) }
    }

    @Test
    fun corruptJsonIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { codec.decode("{ corrupt") }
    }

    @Test
    fun calibrationKindMustMatchArmedProfile() {
        val json = codec.encode(
            ArmedProfileSnapshot(
                armedSessionId = "session-1",
                profile = ProtectionProfile.VEHICLE,
                resolvedPresetVersion = 1,
                effectiveConfiguration = vehicleConfig,
                configurationFingerprint =
                    ConfigurationFingerprint.sha256(vehicleConfig, VehicleProfileSettings),
                commissionedModelFingerprint = null,
                armedCalibrationSnapshot = VehicleArmedCalibrationSnapshot(generation = 7L),
            ),
        )
        val tampered = json.replace("\"kind\":\"VEHICLE\"", "\"kind\":\"POWER\"")

        assertThrows(IllegalArgumentException::class.java) { codec.decode(tampered) }
    }

    @Test
    fun entryAndPowerCalibrationRequireModelFingerprint() {
        val entryJson = """
            {
              "schemaVersion": 1,
              "armedSessionId": "session-entry",
              "profile": "ENTRY",
              "resolvedPresetVersion": 1,
              "effectiveConfiguration": ${org.json.JSONObject(sensorJson()).toString()},
              "configurationFingerprint": "abc123",
              "commissionedModelFingerprint": null,
              "armedCalibrationSnapshot": {"kind": "ENTRY", "generation": 3}
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) { codec.decode(entryJson) }
    }

    private fun sensorJson(): String =
        SensorConfigurationCodec().encode(vehicleConfig)
}

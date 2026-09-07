package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionProfileCodecTest {

    private val codec = ProtectionProfileCodec()
    private val policy = ProtectionProfilePolicy(nowMs = { 1_000L })

    @Test
    fun roundTripPreservesPerProfileSettingsAndNullSelection() {
        val original = policy.newStoreState()
        assertEquals(original, codec.decode(codec.encode(original)))
    }

    @Test
    fun roundTripPreservesSelectedProfileOverridesAndLegacyConfiguration() {
        val base = policy.newStoreState(
            SensorConfigurationPolicy().forPreset(SensorPreset.MAXIMUM_PROTECTION, 1_000L),
        )
        val entryCustomized = base.profiles.getValue(ProtectionProfile.ENTRY).copy(
            sensorOverrides = SensorFusionProfileOverrides(
                samplingProfile = SensorSamplingProfile.RESPONSIVE,
                capabilities = mapOf(
                    SensorCapability.ROTATION to SensorCapabilityProfileOverrides(sensitivity = 8),
                ),
                sources = mapOf(
                    SensorSource.GYROSCOPE to SensorSourceProfileOverrides(role = SensorRole.PRIMARY),
                ),
            ),
            specificOverrides = EntryProfileOverrides(angleThresholdDegrees = 30),
        )
        val customized = policy.updateProfile(base, entryCustomized).copy(
            selectedProfile = ProtectionProfile.ENTRY,
        )

        assertEquals(customized, codec.decode(codec.encode(customized)))
    }

    @Test
    fun roundTripPreservesPowerSpecificOverrides() {
        val base = policy.newStoreState()
        val powerCustomized = base.profiles.getValue(ProtectionProfile.POWER).copy(
            specificOverrides = PowerProfileOverrides(lossConfirmationMs = 10_000L),
        )
        val customized = policy.updateProfile(base, powerCustomized)

        assertEquals(customized, codec.decode(codec.encode(customized)))
    }

    @Test
    fun roundTripPreservesTenSecondPowerRecoveryOverride() {
        val base = policy.newStoreState()
        val powerCustomized = base.profiles.getValue(ProtectionProfile.POWER).copy(
            specificOverrides = PowerProfileOverrides(recoveryConfirmationMs = 10_000L),
        )
        val customized = policy.updateProfile(base, powerCustomized)

        assertEquals(customized, codec.decode(codec.encode(customized)))
    }

    @Test
    fun legacyThirtySecondPowerRecoveryOverrideMigratesToTenSeconds() {
        val witnessModel = PowerWitnessModel(
            darkMinLux = 2.0,
            darkMaxLux = 4.0,
            litMinLux = 120.0,
            litMaxLux = 123.0,
            guardBandLux = 20.0,
            algorithmVersion = 4,
            sensorIdentity = "light#1",
            hoodSignature = "hood-A",
        )
        val commissioned = policy.commissionPower(policy.newStoreState(), witnessModel).copy(
            selectedProfile = ProtectionProfile.POWER,
        )
        val root = org.json.JSONObject(codec.encode(commissioned))
        val profiles = root.getJSONArray("profiles")
        for (i in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(i)
            if (profile.getString("profile") == "POWER") {
                profile.getJSONObject("specificOverrides")
                    .put("recoveryConfirmationMs", 30_000L)
            }
        }

        val decoded = codec.decode(root.toString())
        val overrides = decoded.profiles.getValue(ProtectionProfile.POWER)
            .specificOverrides as PowerProfileOverrides

        assertEquals(ProtectionProfile.POWER, decoded.selectedProfile)
        assertEquals(witnessModel, decoded.profiles.getValue(ProtectionProfile.POWER).powerWitnessModel)
        assertEquals(10_000L, overrides.recoveryConfirmationMs)
    }

    @Test
    fun futureSchemaVersionIsRejected() {
        val json = codec.encode(policy.newStoreState())
        val tampered = json.replace("\"schemaVersion\":1", "\"schemaVersion\":99")

        assertThrows(IllegalArgumentException::class.java) { codec.decode(tampered) }
    }

    @Test
    fun corruptJsonIsRejected() {
        assertThrows(Exception::class.java) { codec.decode("{ invalid json }") }
    }

    @Test
    fun missingProfileEntryIsRejected() {
        val json = codec.encode(policy.newStoreState())
        val root = org.json.JSONObject(json)
        val profiles = root.getJSONArray("profiles")
        // Remove the POWER entry so one profile is missing from the aggregate.
        for (i in 0 until profiles.length()) {
            if (profiles.getJSONObject(i).getString("profile") == "POWER") {
                profiles.remove(i)
                break
            }
        }

        assertThrows(IllegalArgumentException::class.java) { codec.decode(root.toString()) }
    }

    @Test
    fun invalidEnumValueIsRejected() {
        val json = codec.encode(policy.newStoreState())
        val root = org.json.JSONObject(json)
        root.put("selectedProfile", "GARAGE")

        assertThrows(IllegalArgumentException::class.java) { codec.decode(root.toString()) }
    }

    @Test
    fun invalidProfileSpecificRangeIsRejected() {
        val json = codec.encode(policy.newStoreState())
        val root = org.json.JSONObject(json)
        val profiles = root.getJSONArray("profiles")
        for (i in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(i)
            if (profile.getString("profile") == "ENTRY") {
                val overrides = profile.getJSONObject("specificOverrides")
                overrides.put("angleThresholdDegrees", 120)
            }
        }

        assertThrows(IllegalArgumentException::class.java) { codec.decode(root.toString()) }
    }

    @Test
    fun encodeWritesProfilesInCanonicalEnumOrder() {
        val json = codec.encode(policy.newStoreState())
        val root = org.json.JSONObject(json)
        val profiles = root.getJSONArray("profiles")

        assertEquals(3, profiles.length())
        assertEquals("VEHICLE", profiles.getJSONObject(0).getString("profile"))
        assertEquals("ENTRY", profiles.getJSONObject(1).getString("profile"))
        assertEquals("POWER", profiles.getJSONObject(2).getString("profile"))
        assertTrue(root.isNull("selectedProfile"))
    }

    private val hingeModel = EntryHingeModel(
        axisX = 0.0,
        axisY = 0.0,
        axisZ = 1.0,
        allowedDirection = 1,
        residualToleranceDeg = 7.5,
        algorithmVersion = 1,
        sensorIdentity = "rotation-vector",
        orientationSourcePolicy = "default",
    )

    @Test
    fun roundTripPreservesEntryHingeModelAndReadySetupState() {
        val base = policy.newStoreState()
        val commissioned = policy.commissionEntry(base, hingeModel)

        assertEquals(ProfileSetupState.READY, commissioned.profiles.getValue(ProtectionProfile.ENTRY).setupState)
        assertEquals(commissioned, codec.decode(codec.encode(commissioned)))
    }

    /**
     * A model written by an older build carries a "mountSignature" key. It only ever held one
     * fixed string, so it is gone from the model — but a phone that calibrated under the old
     * build has one on disk, and reading past it is the difference between that owner keeping
     * their calibration and being sent back through the guided cycles for nothing.
     */
    @Test
    fun aModelSavedWithTheOldMountSignatureStillLoads() {
        val commissioned = policy.commissionEntry(policy.newStoreState(), hingeModel)
        val legacy = codec.encode(commissioned)
            .replace("\"sensorIdentity\":", "\"mountSignature\":\"default-mount\",\"sensorIdentity\":")

        val decoded = codec.decode(legacy)

        assertEquals(
            commissioned.profiles.getValue(ProtectionProfile.ENTRY).entryHingeModel,
            decoded.profiles.getValue(ProtectionProfile.ENTRY).entryHingeModel,
        )
    }

    @Test
    fun roundTripPreservesTheCommissionedMountPose() {
        val posed = hingeModel.copy(mountUp = EntryVector3(0.0, 0.7071, 0.7071))
        val commissioned = policy.commissionEntry(policy.newStoreState(), posed)

        val decoded = codec.decode(codec.encode(commissioned))

        assertEquals(
            posed.mountUp,
            decoded.profiles.getValue(ProtectionProfile.ENTRY).entryHingeModel?.mountUp,
        )
    }

    @Test
    fun aStoredModelWithoutAMountPoseStaysValid() {
        // Everything commissioned before poses were recorded. Decoding must leave the model
        // usable rather than decommissioning it for a measurement nobody ever took.
        val commissioned = policy.commissionEntry(policy.newStoreState(), hingeModel)

        val decoded = codec.decode(codec.encode(commissioned))
        val model = decoded.profiles.getValue(ProtectionProfile.ENTRY).entryHingeModel

        assertNotNull(model)
        assertEquals(null, model?.mountUp)
    }

    @Test
    fun legacyPayloadWithoutHingeModelDecodesWithNullModel() {
        // A payload written before the additive field exists must load unchanged
        // with entryHingeModel = null and SETUP_REQUIRED preserved.
        val legacyJson = codec.encode(policy.newStoreState())
        val decoded = codec.decode(legacyJson)

        assertEquals(null, decoded.profiles.getValue(ProtectionProfile.ENTRY).entryHingeModel)
        assertEquals(
            ProfileSetupState.SETUP_REQUIRED,
            decoded.profiles.getValue(ProtectionProfile.ENTRY).setupState,
        )
    }

    @Test
    fun decommissionEntryClearsModelAndReturnsToSetupRequired() {
        val commissioned = policy.commissionEntry(policy.newStoreState(), hingeModel)
        val decommissioned = policy.decommissionEntry(commissioned)

        assertEquals(null, decommissioned.profiles.getValue(ProtectionProfile.ENTRY).entryHingeModel)
        assertEquals(
            ProfileSetupState.SETUP_REQUIRED,
            decommissioned.profiles.getValue(ProtectionProfile.ENTRY).setupState,
        )
        assertEquals(
            commissioned.profiles.getValue(ProtectionProfile.POWER),
            decommissioned.profiles.getValue(ProtectionProfile.POWER),
        )
    }

    @Test
    fun nonUnitHingeAxisIsRejected() {
        val commissioned = policy.commissionEntry(policy.newStoreState(), hingeModel)
        val root = org.json.JSONObject(codec.encode(commissioned))
        val profiles = root.getJSONArray("profiles")
        for (i in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(i)
            if (profile.getString("profile") == "ENTRY") {
                profile.getJSONObject("entryHingeModel").put("axisX", 5.0)
            }
        }

        assertThrows(IllegalArgumentException::class.java) { codec.decode(root.toString()) }
    }
}

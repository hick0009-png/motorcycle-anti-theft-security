package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
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
}

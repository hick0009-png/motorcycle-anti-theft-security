package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorConfigurationCodecTest {

    private val codec = SensorConfigurationCodec()
    private val policy = SensorConfigurationPolicy()

    @Test
    fun roundTripPreservesCanonicalConfiguration() {
        val original = policy.forPreset(SensorPreset.BALANCED, nowMs = 123456789L)
        val modified = policy.withSourceRole(
            config = policy.withGroupSensitivity(original, SensorCapability.ROTATION, 8, 123456789L),
            source = SensorSource.GYROSCOPE,
            role = SensorRole.PRIMARY,
            nowMs = 123456789L
        )

        val json = codec.encode(modified)
        val decoded = codec.decode(json)

        assertEquals(modified.schemaVersion, decoded.schemaVersion)
        assertEquals(modified.basePreset, decoded.basePreset)
        assertEquals(modified.samplingProfile, decoded.samplingProfile)
        assertEquals(modified.updatedAtMs, decoded.updatedAtMs)

        for (cap in SensorCapability.entries) {
            assertEquals(modified.capability(cap).sensitivity, decoded.capability(cap).sensitivity)
            assertEquals(modified.capability(cap).correlationWindowMs, decoded.capability(cap).correlationWindowMs)
            assertEquals(modified.capability(cap).confirmationDurationMs, decoded.capability(cap).confirmationDurationMs)

            for (src in SensorSource.entries.filter { it.capability == cap }) {
                assertEquals(modified.source(src).role, decoded.source(src).role)
                assertEquals(modified.source(src).thresholdOverride, decoded.source(src).thresholdOverride)
                assertEquals(modified.source(src).debounceOverrideMs, decoded.source(src).debounceOverrideMs)
                assertEquals(modified.source(src).samplingProfileOverride, decoded.source(src).samplingProfileOverride)
            }
        }
    }

    @Test
    fun corruptOrEmptyJsonReturnsFallbackBalanced() {
        val fallback = codec.decode("{ invalid json }")
        assertEquals(SensorPreset.BALANCED, fallback.basePreset)
    }

    @Test
    fun futureSchemaVersionReturnsFallback() {
        val futureJson = """{"schemaVersion":99,"basePreset":"BALANCED"}"""
        val decoded = codec.decode(futureJson)
        assertEquals(1, decoded.schemaVersion)
        assertEquals(SensorPreset.BALANCED, decoded.basePreset)
    }
}

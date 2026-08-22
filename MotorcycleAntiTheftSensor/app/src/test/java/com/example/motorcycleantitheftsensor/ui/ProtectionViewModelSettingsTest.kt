package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.protection.SensorCapability
import com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicy
import com.example.motorcycleantitheftsensor.protection.SensorPreset
import com.example.motorcycleantitheftsensor.protection.SensorPresetDisplay
import com.example.motorcycleantitheftsensor.protection.SensorRole
import com.example.motorcycleantitheftsensor.protection.SensorSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionViewModelSettingsTest {

    private val policy = SensorConfigurationPolicy()

    @Test
    fun selectingPresetUpdatesConfigurationToApprovedDefaults() {
        val balanced = policy.forPreset(SensorPreset.BALANCED)
        assertEquals(SensorPresetDisplay.BALANCED, policy.displayPreset(balanced))
        assertEquals(SensorRole.PRIMARY, balanced.source(SensorSource.ACCELEROMETER).role)

        val batterySaver = policy.forPreset(SensorPreset.BATTERY_SAVER)
        assertEquals(SensorPresetDisplay.BATTERY_SAVER, policy.displayPreset(batterySaver))
        assertEquals(SensorRole.OFF, batterySaver.source(SensorSource.GYROSCOPE).role)
    }

    @Test
    fun updatingCapabilitySensitivityRetainsOtherCapabilities() {
        val balanced = policy.forPreset(SensorPreset.BALANCED)
        val updated = policy.withGroupSensitivity(balanced, SensorCapability.ROTATION, 9)

        assertEquals(9, updated.capability(SensorCapability.ROTATION).sensitivity)
        assertEquals(5, updated.capability(SensorCapability.MOVEMENT).sensitivity)
        assertEquals(5, updated.capability(SensorCapability.LIGHT).sensitivity)
    }

    @Test
    fun updatingSingleSourceRoleDerivesCustomDisplayPreset() {
        val balanced = policy.forPreset(SensorPreset.BALANCED)
        val custom = policy.withSourceRole(balanced, SensorSource.GYROSCOPE, SensorRole.PRIMARY)

        assertEquals(SensorPreset.BALANCED, custom.basePreset)
        assertEquals(SensorPresetDisplay.CUSTOM, policy.displayPreset(custom))
    }
}

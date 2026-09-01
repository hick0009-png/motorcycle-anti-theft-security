package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SensorCapability
import com.example.motorcycleantitheftsensor.protection.SensorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advanced sensor screen must never decide for itself which sensors a profile uses.
 * These proofs pin the projection it reads instead, including the case where no profile
 * is selected yet — nothing may be reported as locked there.
 */
class SensorEditabilityUiModelTest {

    @Test
    fun powerProfileLocksEverySourceButTheWitnessLampAndDisablesPresets() {
        val model = SensorEditabilityUiModel.from(ProtectionProfile.POWER)

        assertTrue(model.anyLocked)
        assertTrue(model.isLocked(SensorSource.ACCELEROMETER))
        assertFalse(model.isLocked(SensorSource.AMBIENT_LIGHT))
        assertTrue(model.isLocked(SensorCapability.MOVEMENT))
        assertFalse(model.isLocked(SensorCapability.LIGHT))
        assertFalse(model.presetSelectable)
        assertEquals(listOf(SensorSource.AMBIENT_LIGHT), model.editableSources)
        assertEquals(SensorSource.entries.size - 1, model.lockedSourceList.size)
    }

    @Test
    fun powerProfileCarriesTheOwnerCopyForBannerRowAndPreset() {
        val model = SensorEditabilityUiModel.from(ProtectionProfile.POWER)

        assertNotNull(model.notice)
        assertNotNull(model.rowReason)
        assertNotNull(model.presetNotice)
    }

    @Test
    fun customisableProfilesLockNothingAndCarryNoLockCopy() {
        listOf(ProtectionProfile.VEHICLE, ProtectionProfile.ENTRY).forEach { profile ->
            val model = SensorEditabilityUiModel.from(profile)

            assertFalse("$profile must lock nothing", model.anyLocked)
            assertTrue(model.presetSelectable)
            assertEquals(SensorSource.entries, model.editableSources)
            assertNull(model.notice)
            assertNull(model.rowReason)
        }
    }

    @Test
    fun noSelectedProfileClaimsNothingIsLocked() {
        val model = SensorEditabilityUiModel.from(null)

        assertFalse(model.anyLocked)
        assertTrue(model.presetSelectable)
        assertNull(model.profile)
        assertNull(model.notice)
    }

    @Test
    fun uiStateDerivesEditabilityFromTheSelectedProfile() {
        val state = ProtectionUiState.from(
            snapshot = ProtectionSnapshot.offline(1_000L).copy(state = ProtectionState.DISARMED_ONLINE),
            incidents = emptyList(),
            settings = ProtectionSettingsSummary(
                tokenConfigured = true,
                pairedOwnerCount = 1,
                pairingCode = "A1B2C3",
                sensitivity = 5,
                smsFallbackConfigured = false,
                missingPermissions = emptySet(),
            ),
            nowMs = 2_000L,
            profile = ProtectionProfileUiState(selectedProfile = ProtectionProfile.POWER),
        )

        assertEquals(ProtectionProfile.POWER, state.sensorEditability.profile)
        assertTrue(state.sensorEditability.isLocked(SensorSource.GYROSCOPE))
        assertFalse(state.sensorEditability.presetSelectable)
    }
}

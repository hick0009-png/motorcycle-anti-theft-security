package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionProfilePolicyTest {

    private val policy = ProtectionProfilePolicy(nowMs = { 1_000L })

    @Test
    fun newStoreHasEveryProfileWithoutImplicitCustomerSelection() {
        val state = policy.newStoreState()

        assertNull(state.selectedProfile)
        assertEquals(
            setOf(
                ProtectionProfile.VEHICLE,
                ProtectionProfile.ENTRY,
                ProtectionProfile.POWER,
            ),
            state.profiles.keys,
        )
    }

    @Test
    fun updatingEntryProfileDoesNotChangePowerProfile() {
        val initial = policy.newStoreState()
        val originalPower = initial.profiles.getValue(ProtectionProfile.POWER)
        val entry = initial.profiles.getValue(ProtectionProfile.ENTRY)

        val changed = policy.updateProfile(
            initial,
            entry.copy(
                specificOverrides = EntryProfileOverrides(angleThresholdDegrees = 30),
            ),
        )

        assertEquals(
            EntryProfileSettings(angleThresholdDegrees = 30),
            policy.resolve(changed, ProtectionProfile.ENTRY).specificSettings,
        )
        assertEquals(originalPower, changed.profiles.getValue(ProtectionProfile.POWER))
    }

    @Test
    fun restoringEntryRecommendedValuesDoesNotChangePowerProfile() {
        val initial = policy.newStoreState()
        val originalPower = initial.profiles.getValue(ProtectionProfile.POWER)
        val customized = policy.updateProfile(
            initial,
            initial.profiles.getValue(ProtectionProfile.ENTRY).copy(
                specificOverrides = EntryProfileOverrides(angleThresholdDegrees = 45),
            ),
        )

        val restored = policy.restoreRecommended(customized, ProtectionProfile.ENTRY)

        assertEquals(
            EntryProfileSettings(),
            policy.resolve(restored, ProtectionProfile.ENTRY).specificSettings,
        )
        assertFalse(policy.resolve(restored, ProtectionProfile.ENTRY).customized)
        assertEquals(originalPower, restored.profiles.getValue(ProtectionProfile.POWER))
    }

    @Test
    fun entryRecommendedConfigurationUsesRotationAsPrimaryEvidence() {
        val entry = policy.recommended(ProtectionProfile.ENTRY).sensorConfiguration

        assertEquals(SensorRole.SUPPORTING, entry.source(SensorSource.ACCELEROMETER).role)
        assertEquals(SensorRole.PRIMARY, entry.source(SensorSource.GYROSCOPE).role)
        assertEquals(SensorRole.PRIMARY, entry.source(SensorSource.ROTATION_VECTOR).role)
        assertEquals(SensorRole.PRIMARY, entry.source(SensorSource.GAME_ROTATION_VECTOR).role)
        assertEquals(SensorRole.SUPPORTING, entry.source(SensorSource.AMBIENT_LIGHT).role)
    }

    @Test
    fun powerRecommendedConfigurationUsesOnlyWitnessLightAsPrimaryEvidence() {
        val power = policy.recommended(ProtectionProfile.POWER).sensorConfiguration

        assertEquals(SensorRole.OFF, power.source(SensorSource.ACCELEROMETER).role)
        assertEquals(SensorRole.OFF, power.source(SensorSource.GYROSCOPE).role)
        assertEquals(SensorRole.OFF, power.source(SensorSource.ROTATION_VECTOR).role)
        assertEquals(SensorRole.OFF, power.source(SensorSource.MAGNETIC_FIELD).role)
        assertEquals(SensorRole.PRIMARY, power.source(SensorSource.AMBIENT_LIGHT).role)
        assertEquals(SensorRole.OFF, power.source(SensorSource.PROXIMITY).role)
    }

    @Test
    fun resolverAppliesOnlyExplicitSensorOverrides() {
        val initial = policy.newStoreState()
        val entry = initial.profiles.getValue(ProtectionProfile.ENTRY)
        val changed = policy.updateProfile(
            initial,
            entry.copy(
                sensorOverrides = SensorFusionProfileOverrides(
                    samplingProfile = SensorSamplingProfile.RESPONSIVE,
                    capabilities = mapOf(
                        SensorCapability.ROTATION to SensorCapabilityProfileOverrides(
                            sensitivity = 9,
                        ),
                    ),
                    sources = mapOf(
                        SensorSource.GYROSCOPE to SensorSourceProfileOverrides(
                            role = SensorRole.SUPPORTING,
                        ),
                    ),
                ),
            ),
        )

        val resolvedEntry = policy.resolve(changed, ProtectionProfile.ENTRY)

        assertEquals(SensorSamplingProfile.RESPONSIVE, resolvedEntry.sensorConfiguration.samplingProfile)
        assertEquals(9, resolvedEntry.sensorConfiguration.capability(SensorCapability.ROTATION).sensitivity)
        assertEquals(SensorRole.SUPPORTING, resolvedEntry.sensorConfiguration.source(SensorSource.GYROSCOPE).role)
        assertEquals(SensorRole.PRIMARY, resolvedEntry.sensorConfiguration.source(SensorSource.ROTATION_VECTOR).role)
        assertEquals(
            policy.recommended(ProtectionProfile.POWER),
            policy.resolve(changed, ProtectionProfile.POWER),
        )
    }

    @Test
    fun entryAngleOutsideApprovedRangeIsRejected() {
        val initial = policy.newStoreState()
        val entry = initial.profiles.getValue(ProtectionProfile.ENTRY).copy(
            specificOverrides = EntryProfileOverrides(angleThresholdDegrees = 4),
        )

        assertThrows(IllegalArgumentException::class.java) {
            policy.updateProfile(initial, entry)
        }
    }

    @Test
    fun entryConfirmationOutsideApprovedRangeIsRejected() {
        listOf(249L, 3_001L).forEach { invalidConfirmationMs ->
            val initial = policy.newStoreState()
            val entry = initial.profiles.getValue(ProtectionProfile.ENTRY).copy(
                specificOverrides = EntryProfileOverrides(
                    openConfirmationMs = invalidConfirmationMs,
                ),
            )

            assertThrows(IllegalArgumentException::class.java) {
                policy.updateProfile(initial, entry)
            }
        }
    }

    @Test
    fun powerTimingOutsideApprovedContractIsRejected() {
        listOf(
            PowerProfileOverrides(lossConfirmationMs = 9_999L),
            PowerProfileOverrides(recoveryConfirmationMs = 9_999L),
            PowerProfileOverrides(recoveryConfirmationMs = 30_000L),
        ).forEach { invalidOverrides ->
            val initial = policy.newStoreState()
            val power = initial.profiles.getValue(ProtectionProfile.POWER).copy(
                specificOverrides = invalidOverrides,
            )

            assertThrows(IllegalArgumentException::class.java) {
                policy.updateProfile(initial, power)
            }
        }
    }

    @Test
    fun powerRecoveryTimingAcceptsTenSecondContract() {
        val initial = policy.newStoreState()
        val power = initial.profiles.getValue(ProtectionProfile.POWER).copy(
            specificOverrides = PowerProfileOverrides(recoveryConfirmationMs = 10_000L),
        )

        val updated = policy.updateProfile(initial, power)

        assertEquals(
            PowerProfileSettings(recoveryConfirmationMs = 10_000L),
            policy.resolve(updated, ProtectionProfile.POWER).specificSettings,
        )
    }

    @Test
    fun invalidResolvedSensorConfigurationIsRejected() {
        val initial = policy.newStoreState()
        val vehicle = initial.profiles.getValue(ProtectionProfile.VEHICLE).copy(
            sensorOverrides = SensorFusionProfileOverrides(
                capabilities = mapOf(
                    SensorCapability.MOVEMENT to SensorCapabilityProfileOverrides(
                        sensitivity = 11,
                    ),
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            policy.updateProfile(initial, vehicle)
        }
    }

    /**
     * Fix A: the POWER preset turns every non-light source OFF because Power Guard
     * watches one lamp and one charging signal. A stored per-source override must not
     * be able to put movement sensors back on: a live accelerometer opens rival
     * incidents when the owner physically touches the cable.
     */
    @Test
    fun powerProfileKeepsNonLightSourcesOffDespiteStoredOverrides() {
        val initial = policy.newStoreState()
        val power = initial.profiles.getValue(ProtectionProfile.POWER)
        val tampered = policy.updateProfile(
            initial,
            power.copy(
                sensorOverrides = power.sensorOverrides.copy(
                    sources = mapOf(
                        SensorSource.ACCELEROMETER to SensorSourceProfileOverrides(
                            role = SensorRole.PRIMARY,
                        ),
                        SensorSource.SIGNIFICANT_MOTION to SensorSourceProfileOverrides(
                            role = SensorRole.SUPPORTING,
                        ),
                        SensorSource.GYROSCOPE to SensorSourceProfileOverrides(
                            role = SensorRole.PRIMARY,
                        ),
                    ),
                ),
            ),
        )

        val resolved = policy.resolve(tampered, ProtectionProfile.POWER).sensorConfiguration

        assertEquals(SensorRole.OFF, resolved.source(SensorSource.ACCELEROMETER).role)
        assertEquals(SensorRole.OFF, resolved.source(SensorSource.SIGNIFICANT_MOTION).role)
        assertEquals(SensorRole.OFF, resolved.source(SensorSource.GYROSCOPE).role)
        assertEquals(SensorRole.PRIMARY, resolved.source(SensorSource.AMBIENT_LIGHT).role)
    }

    @Test
    fun nonPowerProfilesStillHonourStoredSourceRoleOverrides() {
        val initial = policy.newStoreState()
        val entry = initial.profiles.getValue(ProtectionProfile.ENTRY)
        val customised = policy.updateProfile(
            initial,
            entry.copy(
                sensorOverrides = entry.sensorOverrides.copy(
                    sources = mapOf(
                        SensorSource.ACCELEROMETER to SensorSourceProfileOverrides(
                            role = SensorRole.PRIMARY,
                        ),
                    ),
                ),
            ),
        )

        val resolved = policy.resolve(customised, ProtectionProfile.ENTRY).sensorConfiguration

        assertEquals(SensorRole.PRIMARY, resolved.source(SensorSource.ACCELEROMETER).role)
    }


    /**
     * Selecting a profile must switch off every sensor it does not use, including the
     * ones the fusion configuration cannot express: the microphone and location are not
     * SensorSources, so a profile has to declare them separately or they keep running.
     */
    @Test
    fun powerProfileUsesNeitherMicrophoneNorLocation() {
        assertEquals(
            setOf(SensorKind.LIGHT, SensorKind.POWER_THERMAL),
            ProtectionProfilePolicy.usedSensorKinds(ProtectionProfile.POWER),
        )
    }

    @Test
    fun vehicleAndEntryProfilesStillUseTheAuxiliarySensors() {
        listOf(ProtectionProfile.VEHICLE, ProtectionProfile.ENTRY).forEach { profile ->
            val used = ProtectionProfilePolicy.usedSensorKinds(profile)
            assertTrue("$profile must keep the microphone", SensorKind.MICROPHONE in used)
            assertTrue("$profile must keep location", SensorKind.LOCATION in used)
            assertTrue("$profile must keep movement", SensorKind.VIBRATION in used)
        }
    }

}

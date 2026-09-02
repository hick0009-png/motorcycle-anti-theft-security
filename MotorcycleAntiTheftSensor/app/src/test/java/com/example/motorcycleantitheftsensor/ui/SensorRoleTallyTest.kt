package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionProfilePolicy
import com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicy
import com.example.motorcycleantitheftsensor.protection.SensorPreset
import com.example.motorcycleantitheftsensor.protection.SensorRole
import com.example.motorcycleantitheftsensor.protection.SensorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The counts behind "หลัก N · ประกอบ N · ปิด N".
 *
 * The line is only worth showing if it describes the configuration the profile will
 * actually run, so these pin that it adds up, that it moves when a role moves, and that
 * a use pinning most of its sources off is not credited with them.
 */
class SensorRoleTallyTest {

    private val configPolicy = SensorConfigurationPolicy()
    private val balanced = configPolicy.forPreset(SensorPreset.BALANCED, nowMs = 1_000L)

    @Test
    fun theThreeCountsAlwaysCoverEverySource() {
        val tally = balanced.roleTally()

        assertEquals(SensorSource.entries.size, tally.primary + tally.supporting + tally.off)
    }

    @Test
    fun theVehicleWatchStartsWithTwoLeadSensors() {
        // The acceptance step the design asks for: an untouched vehicle watch reads
        // "หลัก 2 · ประกอบ 8 · ปิด 0".
        val tally = balanced.roleTally()

        assertEquals(2, tally.primary)
        assertEquals(8, tally.supporting)
        assertEquals(0, tally.off)
        assertTrue(tally.armable)
    }

    @Test
    fun raisingOneSourceMovesTheCountByOneInEachDirection() {
        val tally = configPolicy
            .withSourceRole(balanced, SensorSource.GYROSCOPE, SensorRole.PRIMARY)
            .roleTally()

        assertEquals(3, tally.primary)
        assertEquals(7, tally.supporting)
        assertEquals(0, tally.off)
    }

    @Test
    fun aUseThatPinsMostSourcesOffIsNotCreditedWithThem() {
        // Power Guard runs one lamp. Counting the balanced preset instead would tell its
        // owner they have eight corroborating sensors that are never registered.
        val locked = ProtectionProfilePolicy.lockedSources(ProtectionProfile.POWER)
        val pinned = locked.fold(balanced) { config, source ->
            configPolicy.withSourceRole(config, source, SensorRole.OFF)
        }
        val tally = pinned.roleTally()

        assertEquals(1, tally.primary)
        assertEquals(0, tally.supporting)
        assertEquals(SensorSource.entries.size - 1, tally.off)
        assertTrue(tally.armable)
    }

    @Test
    fun aConfigurationWithNoLeadSensorCannotArm() {
        val tally = SensorSource.entries
            .fold(balanced) { config, source ->
                configPolicy.withSourceRole(config, source, SensorRole.OFF)
            }
            .roleTally()

        assertEquals(0, tally.primary)
        assertFalse(tally.armable)
    }

    @Test
    fun everyProfileRecommendationArmsAsItStands() {
        // A recommendation the owner cannot arm would be a recommendation to be unprotected.
        ProtectionProfile.entries.forEach { profile ->
            val recommended = ProtectionProfilePolicy.recommendedRoles(profile)
                .entries
                .fold(balanced) { config, (source, role) ->
                    configPolicy.withSourceRole(config, source, role)
                }

            assertTrue("$profile recommends a configuration that cannot arm", recommended.roleTally().armable)
        }
    }
}

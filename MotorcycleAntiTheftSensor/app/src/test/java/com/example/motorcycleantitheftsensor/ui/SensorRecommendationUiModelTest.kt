package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionProfilePolicy
import com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicy
import com.example.motorcycleantitheftsensor.protection.SensorPreset
import com.example.motorcycleantitheftsensor.protection.SensorRole
import com.example.motorcycleantitheftsensor.protection.SensorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projection behind the ⭐ on a role button and the "ไม่ตรงกับค่าแนะนำ" line.
 *
 * Compose reads this and never rebuilds the recommendation table; these proofs pin what
 * it may claim, including the case where no profile is selected — an unknown
 * recommendation must not read as a recommendation of OFF.
 */
class SensorRecommendationUiModelTest {

    private val configPolicy = SensorConfigurationPolicy()
    private val balanced = configPolicy.forPreset(SensorPreset.BALANCED, nowMs = 1_000L)

    @Test
    fun everyProfileCarriesTheDomainRecommendationForEverySource() {
        ProtectionProfile.entries.forEach { profile ->
            val model = SensorRecommendationUiModel.from(profile)

            assertTrue("$profile must publish a recommendation", model.known)
            assertEquals(
                ProtectionProfilePolicy.recommendedRoles(profile),
                model.roles,
            )
            SensorSource.entries.forEach { source ->
                assertEquals(
                    ProtectionProfilePolicy.recommendedRoles(profile).getValue(source),
                    model.recommendedRole(source),
                )
            }
        }
    }

    @Test
    fun theStarMovesWhenTheProfileChanges() {
        // The owner-visible proof that the two movement profiles are not the same profile.
        val vehicle = SensorRecommendationUiModel.from(ProtectionProfile.VEHICLE)
        val entry = SensorRecommendationUiModel.from(ProtectionProfile.ENTRY)

        assertEquals(SensorRole.SUPPORTING, vehicle.recommendedRole(SensorSource.GYROSCOPE))
        assertEquals(SensorRole.PRIMARY, entry.recommendedRole(SensorSource.GYROSCOPE))
        assertEquals(SensorRole.PRIMARY, vehicle.recommendedRole(SensorSource.ACCELEROMETER))
        assertEquals(SensorRole.SUPPORTING, entry.recommendedRole(SensorSource.ACCELEROMETER))
    }

    @Test
    fun noSelectedProfileRecommendsNothingRatherThanRecommendingOff() {
        val model = SensorRecommendationUiModel.from(null)

        assertFalse(model.known)
        assertNull(model.profile)
        assertNull(model.recommendedRole(SensorSource.ACCELEROMETER))
        // Every role, including OFF, must read as "not diverging" while nothing is known.
        SensorRole.entries.forEach { role ->
            assertFalse(model.differs(SensorSource.ACCELEROMETER, role))
        }
        assertEquals(
            emptyList<SensorSource>(),
            model.divergingSources(balanced, SensorEditabilityUiModel.from(null)),
        )
    }

    @Test
    fun aRoleOnTheRecommendationDoesNotDiverge() {
        val model = SensorRecommendationUiModel.from(ProtectionProfile.VEHICLE)

        assertFalse(model.differs(SensorSource.ACCELEROMETER, SensorRole.PRIMARY))
        assertTrue(model.differs(SensorSource.ACCELEROMETER, SensorRole.OFF))
        assertTrue(model.differs(SensorSource.ACCELEROMETER, SensorRole.SUPPORTING))
    }

    @Test
    fun theVehicleRecommendationIsTheBalancedPresetSoNothingDivergesAtRest() {
        val model = SensorRecommendationUiModel.from(ProtectionProfile.VEHICLE)
        val editability = SensorEditabilityUiModel.from(ProtectionProfile.VEHICLE)

        assertEquals(
            emptyList<SensorSource>(),
            model.divergingSources(balanced, editability),
        )
    }

    @Test
    fun raisingOneSourceNamesThatSourceAndNoOther() {
        val model = SensorRecommendationUiModel.from(ProtectionProfile.VEHICLE)
        val editability = SensorEditabilityUiModel.from(ProtectionProfile.VEHICLE)
        val edited = configPolicy.withSourceRole(
            balanced,
            SensorSource.GYROSCOPE,
            SensorRole.PRIMARY,
        )

        assertEquals(
            listOf(SensorSource.GYROSCOPE),
            model.divergingSources(edited, editability),
        )
    }

    @Test
    fun lockedSourcesNeverCountAsDivergingBecauseTheOwnerCannotActOnThem() {
        // Power Guard pins its movement sources OFF and recommends OFF, so they agree.
        // Even if a restored older configuration disagreed, a locked row offers no button
        // to press, and naming it would only be a complaint the owner cannot answer.
        val model = SensorRecommendationUiModel.from(ProtectionProfile.POWER)
        val editability = SensorEditabilityUiModel.from(ProtectionProfile.POWER)
        val stale = configPolicy.withSourceRole(
            balanced,
            SensorSource.ACCELEROMETER,
            SensorRole.PRIMARY,
        )

        assertTrue(model.differs(SensorSource.ACCELEROMETER, SensorRole.PRIMARY))
        assertEquals(
            listOf(SensorSource.AMBIENT_LIGHT),
            model.divergingSources(
                configPolicy.withSourceRole(stale, SensorSource.AMBIENT_LIGHT, SensorRole.OFF),
                editability,
            ),
        )
        assertEquals(
            emptyList<SensorSource>(),
            model.divergingSources(stale, editability),
        )
    }

    @Test
    fun restoringEveryRecommendedRoleLeavesNothingDiverging() {
        // What the restore button does, in the domain: fold the published roles onto the
        // configuration the screen shows.
        ProtectionProfile.entries.forEach { profile ->
            val model = SensorRecommendationUiModel.from(profile)
            val editability = SensorEditabilityUiModel.from(profile)
            val restored = model.roles.entries.fold(balanced) { config, (source, role) ->
                configPolicy.withSourceRole(config, source, role)
            }

            assertEquals(
                "$profile still diverges after restoring its own recommendation",
                emptyList<SensorSource>(),
                model.divergingSources(restored, editability),
            )
            model.roles.forEach { (source, role) ->
                assertEquals(role, restored.source(source).role)
            }
        }
    }
}

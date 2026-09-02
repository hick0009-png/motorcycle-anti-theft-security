package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thirty entries of owner-facing copy, one per protection use and hardware source.
 *
 * These proofs cannot check that the wording is good, so they check the ways it could be
 * wrong without anyone noticing: a missing entry, an enum name reaching the screen, the
 * same sentence reused for uses that behave differently, or a promise about a detection
 * the engine does not make.
 */
class SensorContributionCatalogTest {

    private val everyCombination: List<Pair<ProtectionProfile, SensorSource>> =
        ProtectionProfile.entries.flatMap { profile ->
            SensorSource.entries.map { source -> profile to source }
        }

    @Test
    fun everyProfileAndSourceHasCompleteCopy() {
        everyCombination.forEach { (profile, source) ->
            val contribution = PresentationTextCatalog.contribution(profile, source)
            val where = "$profile/$source"

            assertEquals(source, contribution.source)
            listOf(
                "detectsTh" to contribution.detectsTh,
                "asPrimaryTh" to contribution.asPrimaryTh,
                "asSupportingTh" to contribution.asSupportingTh,
                "ifOffTh" to contribution.ifOffTh,
                "costTh" to contribution.costTh,
            ).forEach { (field, text) ->
                assertTrue("$where has a blank $field", text.isNotBlank())
            }
        }
    }

    @Test
    fun noEnumNameEverReachesTheOwner() {
        // The failure this guards is a row that reads "GAME_ROTATION_VECTOR" because a
        // branch was added to the enum and not to the copy.
        val enumNames = SensorSource.entries.map { it.name } +
            SensorRole.entries.map { it.name } +
            SensorCapability.entries.map { it.name } +
            ProtectionProfile.entries.map { it.name }

        everyCombination.forEach { (profile, source) ->
            val contribution = PresentationTextCatalog.contribution(profile, source)
            val allCopy = listOfNotNull(
                contribution.detectsTh,
                contribution.asPrimaryTh,
                contribution.asSupportingTh,
                contribution.ifOffTh,
                contribution.costTh,
                contribution.caveatTh,
            ).joinToString(" ")

            enumNames.forEach { name ->
                assertFalse("$profile/$source leaks the enum name $name", allCopy.contains(name))
            }
        }
    }

    @Test
    fun theRecommendedRoleComesFromTheDomainRatherThanBeingRetypedHere() {
        everyCombination.forEach { (profile, source) ->
            assertEquals(
                "$profile/$source disagrees with the policy about its recommended role",
                ProtectionProfilePolicy.recommendedRoles(profile).getValue(source),
                PresentationTextCatalog.contribution(profile, source).recommendedRole,
            )
        }
    }

    @Test
    fun theSameSensorReadsDifferentlyForUsesThatUseItDifferently() {
        // The light sensor leads the vehicle watch, corroborates the door watch and is the
        // witness lamp for Power Guard. One shared sentence would hide all of that.
        val vehicle = PresentationTextCatalog.contribution(
            ProtectionProfile.VEHICLE,
            SensorSource.AMBIENT_LIGHT,
        )
        val entry = PresentationTextCatalog.contribution(
            ProtectionProfile.ENTRY,
            SensorSource.AMBIENT_LIGHT,
        )
        val power = PresentationTextCatalog.contribution(
            ProtectionProfile.POWER,
            SensorSource.AMBIENT_LIGHT,
        )

        assertNotEqualCopy(vehicle.detectsTh, power.detectsTh)
        assertNotEqualCopy(vehicle.detectsTh, entry.detectsTh)
        assertNotEqualCopy(entry.detectsTh, power.detectsTh)
    }

    @Test
    fun theMovementProfilesDoNotShareOneDescriptionForTheSensorsTheyRankDifferently() {
        // The accelerometer leads one use and corroborates the other; the gyroscope is the
        // other way round. Identical copy would contradict the star T3 puts on the row.
        listOf(SensorSource.ACCELEROMETER, SensorSource.GYROSCOPE).forEach { source ->
            val vehicle = PresentationTextCatalog.contribution(ProtectionProfile.VEHICLE, source)
            val entry = PresentationTextCatalog.contribution(ProtectionProfile.ENTRY, source)

            assertNotEqualCopy(vehicle.detectsTh, entry.detectsTh)
            assertTrue(vehicle.recommendedRole != entry.recommendedRole)
        }
    }

    @Test
    fun powerGuardClaimsNoDetectionFromTheSensorsItPinsOff() {
        SensorSource.entries
            .filter { it != SensorSource.AMBIENT_LIGHT }
            .forEach { source ->
                val contribution = PresentationTextCatalog.contribution(ProtectionProfile.POWER, source)

                assertTrue("$source must read as unused by Power Guard", contribution.unusedByProfile)
                assertEquals(SensorRole.OFF, contribution.recommendedRole)
            }
        assertFalse(
            PresentationTextCatalog.contribution(
                ProtectionProfile.POWER,
                SensorSource.AMBIENT_LIGHT,
            ).unusedByProfile,
        )
    }

    @Test
    fun theTwoPlacesWhereTheRoleDoesNotGovernDetectionSayItOutLoud() {
        // The door watch reads the rotation sensors directly and Power Guard reads the light
        // sensor directly, so in both places the role on the settings screen governs only the
        // fusion running alongside. Copy that stayed silent about this would be the second
        // lie the design set out to remove.
        listOf(
            SensorSource.GYROSCOPE,
            SensorSource.ROTATION_VECTOR,
            SensorSource.GAME_ROTATION_VECTOR,
        ).forEach { source ->
            assertNotNull(
                "the door watch must admit it reads $source directly",
                PresentationTextCatalog.contribution(ProtectionProfile.ENTRY, source).caveatTh,
            )
        }
        assertNotNull(
            PresentationTextCatalog.contribution(
                ProtectionProfile.POWER,
                SensorSource.AMBIENT_LIGHT,
            ).caveatTh,
        )
    }

    @Test
    fun aUseThatReallyDoesActOnTheRoleCarriesNoCaveat() {
        // A caveat everywhere would train the owner to ignore it.
        SensorSource.entries.forEach { source ->
            assertNull(
                "the vehicle watch acts on every role it is given, including $source",
                PresentationTextCatalog.contribution(ProtectionProfile.VEHICLE, source).caveatTh,
            )
        }
    }

    @Test
    fun noCopyPromisesThatALightReadingOpensAnIncidentByItself() {
        // IncidentEngine records a light reading as a precursor and returns, whatever role it
        // carries. "แสงเปลี่ยน = แจ้งเตือนทันที" would be false in every profile.
        ProtectionProfile.entries.forEach { profile ->
            val light = PresentationTextCatalog.contribution(profile, SensorSource.AMBIENT_LIGHT)

            assertFalse(
                "$profile claims a light reading opens an incident on its own",
                light.asPrimaryTh.contains("เปิดเหตุการณ์ได้เอง"),
            )
        }
    }

    private fun assertNotEqualCopy(first: String, second: String) {
        assertFalse("two uses share one sentence: $first", first == second)
    }
}

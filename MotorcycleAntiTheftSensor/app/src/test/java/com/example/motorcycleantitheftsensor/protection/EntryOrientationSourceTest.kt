package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which orientation sensor a phone ends up on, and what the fingerprint then says about it.
 *
 * The armed watch used to accept two fallbacks and the drift recorder three, while the
 * commissioning fingerprint named one of them unconditionally. Every proof here exists to
 * stop those three drifting apart again.
 */
class EntryOrientationSourceTest {

    @Test
    fun aPhoneWithEverythingTakesTheGameVector() {
        assertEquals(
            EntryOrientationSource.GAME_ROTATION_VECTOR,
            EntryOrientationSourcePolicy.choose(EntryOrientationSource.entries),
        )
    }

    @Test
    fun aPhoneWithoutAGameVectorFallsBackInOrder() {
        assertEquals(
            EntryOrientationSource.ROTATION_VECTOR,
            EntryOrientationSourcePolicy.choose(
                setOf(
                    EntryOrientationSource.ROTATION_VECTOR,
                    EntryOrientationSource.GEOMAGNETIC_ROTATION_VECTOR,
                ),
            ),
        )
        assertEquals(
            EntryOrientationSource.GEOMAGNETIC_ROTATION_VECTOR,
            EntryOrientationSourcePolicy.choose(setOf(EntryOrientationSource.GEOMAGNETIC_ROTATION_VECTOR)),
        )
    }

    @Test
    fun aPhoneWithNoneOfThemChoosesNothing() {
        // The door watch cannot measure an angle here. Saying so is the whole point:
        // registering nothing and arming anyway is what this replaces.
        assertNull(EntryOrientationSourcePolicy.choose(emptySet()))
    }

    @Test
    fun theGameVectorKeepsTheFingerprintItAlreadyHad() {
        // Every hinge model commissioned before the fingerprint could tell the sources apart
        // was stamped with this exact string. Changing it would decommission all of them.
        assertEquals(
            "game-rotation-vector/HUAWEI/INE-LX2",
            EntryOrientationSourcePolicy.identity(
                EntryOrientationSource.GAME_ROTATION_VECTOR,
                manufacturer = "HUAWEI",
                model = "INE-LX2",
            ),
        )
        assertEquals(
            "game-rotation-vector-primary-v1",
            EntryOrientationSourcePolicy.sourcePolicy(EntryOrientationSource.GAME_ROTATION_VECTOR),
        )
    }

    @Test
    fun aFallbackIsNamedAsItselfSoTheModelIsRecommissioned() {
        // The bug this closes: a phone that fell back went on claiming the game vector, so a
        // model measured against one sensor was armed against another with nothing to notice.
        val identities = EntryOrientationSource.entries.map {
            EntryOrientationSourcePolicy.identity(it, "HUAWEI", "INE-LX2")
        }
        assertEquals(identities.size, identities.toSet().size)

        val policies = EntryOrientationSource.entries.map { EntryOrientationSourcePolicy.sourcePolicy(it) }
        assertEquals(policies.size, policies.toSet().size)
    }

    @Test
    fun onlyTheGameVectorIsFreeOfTheCompass() {
        assertFalse(EntryOrientationSource.GAME_ROTATION_VECTOR.compassPinned)
        assertTrue(EntryOrientationSource.ROTATION_VECTOR.compassPinned)
        assertTrue(EntryOrientationSource.GEOMAGNETIC_ROTATION_VECTOR.compassPinned)
    }
}

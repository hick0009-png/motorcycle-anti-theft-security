package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sensor screen and the armed session must be reading the same store.
 *
 * They were not. The screen read and wrote a central configuration kept from before uses
 * existed, and an Arm built the selected use's recommendation plus that use's own overrides
 * without ever looking at the central one. The owner set a role, the screen confirmed it,
 * and the phone armed with something else — a screen that lies is worse than a screen that
 * is missing, because nobody checks a promise that was made confidently.
 */
class ProfileSensorSettingsStoreTest {

    // Fixed clock: a recommendation carries the moment it was built, and two calls a
    // millisecond apart are not the same object even when every setting in them matches.
    private val policy = ProtectionProfilePolicy(nowMs = { 1_000L })

    private fun repositoryWith(selected: ProtectionProfile?): InMemoryProfileRepository =
        InMemoryProfileRepository(
            policy.newStoreState(legacyConfiguration = null).copy(selectedProfile = selected),
        )

    @Test
    fun readingReturnsWhatTheSelectedUseWouldArmWith() {
        val repository = repositoryWith(ProtectionProfile.VEHICLE)
        val store = ProfileSensorSettingsStore(repository, policy)

        val read = store.read()

        assertEquals(
            policy.resolve(repository.load(), ProtectionProfile.VEHICLE).sensorConfiguration,
            read,
        )
    }

    @Test
    fun withNoUseSelectedThereIsNothingToRead() {
        // The central configuration still answers for this case; saying null is how the
        // gateway knows to fall back rather than inventing a use the owner never picked.
        assertNull(ProfileSensorSettingsStore(repositoryWith(null), policy).read())
        assertFalse(ProfileSensorSettingsStore(repositoryWith(null), policy).write(anyConfiguration()))
    }

    @Test
    fun anEditIsStoredAgainstTheUseAndComesBackFromIt() {
        val repository = repositoryWith(ProtectionProfile.VEHICLE)
        val store = ProfileSensorSettingsStore(repository, policy)
        val before = store.read()!!
        val edited = withRole(before, SensorSource.MAGNETIC_FIELD, SensorRole.PRIMARY)

        assertTrue(store.write(edited))

        assertEquals(SensorRole.PRIMARY, store.read()!!.source(SensorSource.MAGNETIC_FIELD).role)
        assertEquals(
            SensorRole.PRIMARY,
            policy.resolve(repository.load(), ProtectionProfile.VEHICLE)
                .sensorConfiguration.source(SensorSource.MAGNETIC_FIELD).role,
        )
    }

    @Test
    fun onlyTheDifferencesAreStored() {
        // A use whose recommendation improves later must carry the owner's decisions forward
        // and nothing else. Storing the whole configuration would freeze today's defaults into
        // their profile for ever, invisibly.
        val repository = repositoryWith(ProtectionProfile.VEHICLE)
        val store = ProfileSensorSettingsStore(repository, policy)
        val edited = withRole(store.read()!!, SensorSource.MAGNETIC_FIELD, SensorRole.PRIMARY)

        store.write(edited)

        val overrides = repository.load().profiles.getValue(ProtectionProfile.VEHICLE).sensorOverrides
        assertEquals(setOf(SensorSource.MAGNETIC_FIELD), overrides.sources.keys)
        assertEquals(SensorRole.PRIMARY, overrides.sources.getValue(SensorSource.MAGNETIC_FIELD).role)
    }

    @Test
    fun savingWhatWasReadChangesNothing() {
        // Opening the screen and pressing save must not customise the use. The screen reads a
        // resolved configuration, so every value in it equals the recommendation.
        val repository = repositoryWith(ProtectionProfile.POWER)
        val store = ProfileSensorSettingsStore(repository, policy)

        store.write(store.read()!!)

        val stored = repository.load().profiles.getValue(ProtectionProfile.POWER)
        assertEquals(SensorFusionProfileOverrides(), stored.sensorOverrides)
        assertFalse(policy.resolve(repository.load(), ProtectionProfile.POWER).customized)
    }

    @Test
    fun aLockedSourceIsNeverRecordedAsTheOwnersDecision() {
        // Power Guard forces every source but the lamp off. Those zeros are the use's rule,
        // not a choice the owner made, and writing them down would outlive the rule.
        val repository = repositoryWith(ProtectionProfile.POWER)
        val store = ProfileSensorSettingsStore(repository, policy)
        val edited = withRole(store.read()!!, SensorSource.ACCELEROMETER, SensorRole.PRIMARY)

        store.write(edited)

        val overrides = repository.load().profiles.getValue(ProtectionProfile.POWER).sensorOverrides
        assertFalse(SensorSource.ACCELEROMETER in overrides.sources)
        assertEquals(
            SensorRole.OFF,
            policy.resolve(repository.load(), ProtectionProfile.POWER)
                .sensorConfiguration.source(SensorSource.ACCELEROMETER).role,
        )
    }

    @Test
    fun eachUseKeepsItsOwnDecisions() {
        val repository = repositoryWith(ProtectionProfile.VEHICLE)
        val store = ProfileSensorSettingsStore(repository, policy)
        store.write(withRole(store.read()!!, SensorSource.MAGNETIC_FIELD, SensorRole.PRIMARY))

        val entryOverrides = repository.load().profiles.getValue(ProtectionProfile.ENTRY).sensorOverrides
        assertEquals(SensorFusionProfileOverrides(), entryOverrides)
    }

    @Test
    fun sensitivityIsStoredPerUseToo() {
        val repository = repositoryWith(ProtectionProfile.VEHICLE)
        val store = ProfileSensorSettingsStore(repository, policy)
        val before = store.read()!!
        val movement = before.capability(SensorCapability.MOVEMENT)
        val edited = before.copy(
            capabilities = before.capabilities + (
                SensorCapability.MOVEMENT to movement.copy(sensitivity = movement.sensitivity + 2)
                ),
        )

        store.write(edited)

        assertEquals(
            movement.sensitivity + 2,
            store.read()!!.capability(SensorCapability.MOVEMENT).sensitivity,
        )
    }

    private fun withRole(
        config: SensorFusionConfiguration,
        source: SensorSource,
        role: SensorRole,
    ): SensorFusionConfiguration {
        val capability = config.capability(source.capability)
        val updated = capability.copy(
            sources = capability.sources + (source to capability.source(source).copy(role = role)),
        )
        return config.copy(capabilities = config.capabilities + (source.capability to updated))
    }

    private fun anyConfiguration(): SensorFusionConfiguration =
        SensorConfigurationPolicy().forPreset(SensorPreset.BALANCED)

    private class InMemoryProfileRepository(
        initial: ProtectionProfileStoreState,
    ) : ProtectionProfileRepository {
        private var state = initial
        override fun load(): ProtectionProfileStoreState = state
        override fun save(state: ProtectionProfileStoreState): Result<Unit> {
            this.state = state
            return Result.success(Unit)
        }

        override fun update(
            transform: (ProtectionProfileStoreState) -> ProtectionProfileStoreState,
        ): Result<ProtectionProfileStoreState> {
            state = transform(state)
            return Result.success(state)
        }
    }
}

package com.example.motorcycleantitheftsensor.protection

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ProtectionProfileRepositoryTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        var failNextCommit = false

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val tempMap = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) tempMap[key] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removed.add(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                map.clear()
                return this
            }
            override fun commit(): Boolean {
                if (failNextCommit) {
                    failNextCommit = false
                    return false
                }
                removed.forEach { map.remove(it) }
                map.putAll(tempMap)
                return true
            }
            override fun apply() {
                commit()
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var legacyRepository: SensorConfigurationRepository
    private lateinit var repository: SharedPreferencesProtectionProfileRepository
    private val policy = ProtectionProfilePolicy(nowMs = { 1_000L })

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        legacyRepository = mock()
        repository = SharedPreferencesProtectionProfileRepository(
            preferences = fakePrefs,
            legacyRepository = legacyRepository,
        )
    }

    @Test
    fun firstLoadPreservesLegacyConfigurationWithoutSelectingProfile() {
        val legacy = SensorConfigurationPolicy().forPreset(SensorPreset.MAXIMUM_PROTECTION, 1_000L)
        whenever(legacyRepository.loadConfiguration()).thenReturn(legacy)

        val loaded = repository.load()

        assertNull(loaded.selectedProfile)
        assertEquals(legacy, loaded.legacyConfiguration)
        // First load must not write or select a profile until the owner acts.
        assertFalse(fakePrefs.contains(SharedPreferencesProtectionProfileRepository.KEY_PROFILE_STATE))
    }

    @Test
    fun saveThenLoadRoundTripsTheWholeAggregate() {
        val original = policy.newStoreState()

        val result = repository.save(original)

        assertTrue(result.isSuccess)
        assertEquals(original, repository.load())
    }

    @Test
    fun failedCommitReturnsFailureAndKeepsPreviousAggregate() {
        val originalState = policy.newStoreState()
        assertTrue(repository.save(originalState).isSuccess)

        val changedState = policy.updateProfile(
            originalState,
            originalState.profiles.getValue(ProtectionProfile.ENTRY).copy(
                specificOverrides = EntryProfileOverrides(angleThresholdDegrees = 30),
            ),
        )
        fakePrefs.failNextCommit = true
        val result = repository.save(changedState)

        assertTrue(result.isFailure)
        assertEquals(originalState, repository.load())
    }

    @Test
    fun updateAppliesTransformAndPersistsAtomically() {
        val original = policy.newStoreState()
        repository.save(original)

        val result = repository.update { state ->
            state.copy(selectedProfile = ProtectionProfile.VEHICLE)
        }

        assertTrue(result.isSuccess)
        assertEquals(ProtectionProfile.VEHICLE, result.getOrNull()?.selectedProfile)
        assertEquals(ProtectionProfile.VEHICLE, repository.load().selectedProfile)
    }

    @Test
    fun corruptStoredAggregateFallsBackToLegacyPreservingState() {
        val legacy = SensorConfigurationPolicy().forPreset(SensorPreset.BATTERY_SAVER, 1_000L)
        whenever(legacyRepository.loadConfiguration()).thenReturn(legacy)
        fakePrefs.edit()
            .putString(SharedPreferencesProtectionProfileRepository.KEY_PROFILE_STATE, "{ corrupt")
            .commit()

        val loaded = repository.load()

        assertNull(loaded.selectedProfile)
        assertEquals(legacy, loaded.legacyConfiguration)
    }
}

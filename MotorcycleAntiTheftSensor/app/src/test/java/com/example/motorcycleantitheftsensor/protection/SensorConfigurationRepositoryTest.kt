package com.example.motorcycleantitheftsensor.protection

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class SensorConfigurationRepositoryTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

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
    private lateinit var repository: EncryptedPrefsSensorConfigurationRepository

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        repository = EncryptedPrefsSensorConfigurationRepository(fakePrefs)
    }

    @Test
    fun uninitializedPrefsMigratesLegacySensitivity() {
        fakePrefs.edit().putInt("sensor_sensitivity_level", 8).commit()

        val config = repository.loadConfiguration()

        assertEquals(8, config.capability(SensorCapability.MOVEMENT).sensitivity)
        assertEquals(8, config.capability(SensorCapability.LIGHT).sensitivity)
        assertEquals(5, config.capability(SensorCapability.ROTATION).sensitivity)
        assertTrue(repository.hasPersistedConfiguration())
    }

    @Test
    fun saveAndLoadConfigurationPreservesChanges() {
        val policy = SensorConfigurationPolicy()
        val customConfig = policy.withSourceRole(
            config = policy.forPreset(SensorPreset.MAXIMUM_PROTECTION, 1000L),
            source = SensorSource.PROXIMITY,
            role = SensorRole.OFF,
            nowMs = 1000L
        )

        val saveResult = repository.saveConfiguration(customConfig)
        assertTrue(saveResult.isSuccess)

        val loaded = repository.loadConfiguration()
        assertEquals(SensorRole.OFF, loaded.source(SensorSource.PROXIMITY).role)
        assertEquals(SensorPreset.MAXIMUM_PROTECTION, loaded.basePreset)
    }
}

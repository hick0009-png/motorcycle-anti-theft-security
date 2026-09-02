package com.example.motorcycleantitheftsensor.protection

import android.content.SharedPreferences

/**
 * Remembers what this phone measured about its own drift.
 *
 * Small and separate on purpose. It is not a secret, it is not part of the commissioned
 * hinge model — a model must not be invalidated because a measurement was retaken — and it
 * has to be readable at the moment a use is offered, which is before any session exists.
 */
interface EntryDriftMeasurementStore {
    fun load(): EntryDriftMeasurement?
    fun save(measurement: EntryDriftMeasurement)
    fun clear()
}

class SharedPreferencesEntryDriftMeasurementStore(
    private val preferences: SharedPreferences,
) : EntryDriftMeasurementStore {

    override fun load(): EntryDriftMeasurement? {
        val source = preferences.getString(KEY_SOURCE, null) ?: return null
        val measuredMs = preferences.getLong(KEY_MEASURED_MS, 0L)
        if (measuredMs <= 0L) return null
        return EntryDriftMeasurement(
            sourceLabel = source,
            degPerHour = preferences.getFloat(KEY_DEG_PER_HOUR, 0f).toDouble(),
            measuredMs = measuredMs,
            measuredAtWallMs = preferences.getLong(KEY_MEASURED_AT, 0L),
        )
    }

    /**
     * Keeps the longer of the two measurements rather than the newer one.
     *
     * A rate is only as good as the hours behind it, and a short recording started by
     * accident — or stopped a minute after starting — would otherwise overwrite the overnight
     * one that actually answered the question.
     */
    override fun save(measurement: EntryDriftMeasurement) {
        val existing = load()
        if (existing != null &&
            existing.sourceLabel == measurement.sourceLabel &&
            existing.measuredMs > measurement.measuredMs
        ) {
            return
        }
        preferences.edit()
            .putString(KEY_SOURCE, measurement.sourceLabel)
            .putFloat(KEY_DEG_PER_HOUR, measurement.degPerHour.toFloat())
            .putLong(KEY_MEASURED_MS, measurement.measuredMs)
            .putLong(KEY_MEASURED_AT, measurement.measuredAtWallMs)
            .apply()
    }

    override fun clear() {
        preferences.edit()
            .remove(KEY_SOURCE)
            .remove(KEY_DEG_PER_HOUR)
            .remove(KEY_MEASURED_MS)
            .remove(KEY_MEASURED_AT)
            .apply()
    }

    private companion object {
        const val KEY_SOURCE = "entry_drift_source"
        const val KEY_DEG_PER_HOUR = "entry_drift_deg_per_hour"
        const val KEY_MEASURED_MS = "entry_drift_measured_ms"
        const val KEY_MEASURED_AT = "entry_drift_measured_at"
    }
}

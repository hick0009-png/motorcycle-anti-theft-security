package com.example.motorcycleantitheftsensor.protection

import android.content.Context
import android.content.SharedPreferences

interface ProtectionSnapshotPreferences {
    fun put(values: Map<String, Any?>)

    fun getString(key: String): String?

    fun getLong(key: String): Long?

    fun getBoolean(key: String): Boolean?
}

data class ProtectionRecoveryHints(
    val persistedState: ProtectionState,
    val lastTransitionAtMs: Long?,
    val lastServiceHeartbeatAtMs: Long?,
    val lastTelegramContactAtMs: Long?,
    val demoModeEnabled: Boolean,
    val lastIncidentId: String?,
)

data class ProtectionRecoveryState(
    val liveSnapshot: ProtectionSnapshot,
    val hints: ProtectionRecoveryHints,
)

class ProtectionRecoveryGate(
    val capturedState: ProtectionRecoveryState,
) {
    @Volatile
    private var recoveryComplete = false

    fun shouldPersistSnapshot(): Boolean = recoveryComplete

    fun markRecoveryComplete() {
        recoveryComplete = true
    }
}

class ProtectionSnapshotStore(
    private val preferences: ProtectionSnapshotPreferences,
    private val clock: ProtectionClock,
) {
    constructor(
        context: Context,
        clock: ProtectionClock,
    ) : this(
        preferences = SharedPreferencesSnapshotPreferences(
            context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ),
        ),
        clock = clock,
    )

    fun save(
        snapshot: ProtectionSnapshot,
        lastServiceHeartbeatAtMs: Long?,
    ) {
        preferences.put(
            mapOf(
                KEY_STATE to snapshot.state.name,
                KEY_TRANSITION_AT_MS to snapshot.lastTransitionAtMs,
                KEY_SERVICE_HEARTBEAT_AT_MS to lastServiceHeartbeatAtMs,
                KEY_TELEGRAM_CONTACT_AT_MS to snapshot.lastTelegramContactAtMs,
                KEY_DEMO_ENABLED to snapshot.demoModeEnabled,
                KEY_LAST_INCIDENT_ID to snapshot.lastIncident?.id,
            ),
        )
    }

    fun loadForRecovery(): ProtectionRecoveryState {
        val persistedState = preferences.getString(KEY_STATE)
            ?.let { name -> runCatching { ProtectionState.valueOf(name) }.getOrNull() }
            ?: ProtectionState.DISARMED_ONLINE
        return ProtectionRecoveryState(
            liveSnapshot = ProtectionSnapshot.offline(clock.nowMs()),
            hints = ProtectionRecoveryHints(
                persistedState = persistedState,
                lastTransitionAtMs = preferences.getLong(KEY_TRANSITION_AT_MS),
                lastServiceHeartbeatAtMs = preferences.getLong(KEY_SERVICE_HEARTBEAT_AT_MS),
                lastTelegramContactAtMs = preferences.getLong(KEY_TELEGRAM_CONTACT_AT_MS),
                demoModeEnabled = preferences.getBoolean(KEY_DEMO_ENABLED) ?: false,
                lastIncidentId = preferences.getString(KEY_LAST_INCIDENT_ID),
            ),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "protection_runtime_state"
        const val KEY_STATE = "state"
        const val KEY_TRANSITION_AT_MS = "transition_at_ms"
        const val KEY_SERVICE_HEARTBEAT_AT_MS = "service_heartbeat_at_ms"
        const val KEY_TELEGRAM_CONTACT_AT_MS = "telegram_contact_at_ms"
        const val KEY_DEMO_ENABLED = "demo_enabled"
        const val KEY_LAST_INCIDENT_ID = "last_incident_id"
    }
}

private class SharedPreferencesSnapshotPreferences(
    private val preferences: SharedPreferences,
) : ProtectionSnapshotPreferences {
    override fun put(values: Map<String, Any?>) {
        val editor = preferences.edit()
        values.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Boolean -> editor.putBoolean(key, value)
                else -> error("Unsupported snapshot value for $key")
            }
        }
        check(editor.commit()) { "Unable to persist protection recovery snapshot" }
    }

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun getLong(key: String): Long? = if (preferences.contains(key)) {
        preferences.getLong(key, 0L)
    } else {
        null
    }

    override fun getBoolean(key: String): Boolean? = if (preferences.contains(key)) {
        preferences.getBoolean(key, false)
    } else {
        null
    }
}

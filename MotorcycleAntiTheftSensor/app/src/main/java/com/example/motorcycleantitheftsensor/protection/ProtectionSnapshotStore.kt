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
    val lastIncidentId: String?,
    val revision: Long = 0L,
)

data class ProtectionRecoveryState(
    val liveSnapshot: ProtectionSnapshot,
    val hints: ProtectionRecoveryHints,
    val continuityIntent: ProtectionContinuityIntent = ProtectionContinuityIntent(
        desiredService = DesiredService.RUNNING,
        desiredProtection = DesiredProtection.DISARMED,
        autoRecoveryAfterBoot = false,
    ),
    val continuityValid: Boolean = false,
)

class ProtectionRecoveryGate(
    val capturedState: ProtectionRecoveryState,
) {
    @Volatile
    private var recoveryComplete = false
    @Volatile
    private var recoverySuperseded = false

    fun shouldPersistSnapshot(): Boolean = recoveryComplete

    fun markRecoveryComplete() {
        recoveryComplete = true
    }

    fun supersedeRecovery() {
        recoverySuperseded = true
        recoveryComplete = true
    }

    fun shouldApplyRecovery(): Boolean = !recoverySuperseded
}

class ProtectionSnapshotStore(
    private val preferences: ProtectionSnapshotPreferences,
    private val clock: ProtectionClock,
    private val armedProfileSnapshotCodec: ArmedProfileSnapshotCodec = ArmedProfileSnapshotCodec(),
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
    ) = save(
        snapshot = snapshot,
        lastServiceHeartbeatAtMs = lastServiceHeartbeatAtMs,
        continuityIntent = ProtectionContinuityIntent(
            desiredService = DesiredService.RUNNING,
            desiredProtection = DesiredProtection.DISARMED,
            autoRecoveryAfterBoot = false,
        ),
    )

    fun save(
        snapshot: ProtectionSnapshot,
        lastServiceHeartbeatAtMs: Long?,
        continuityIntent: ProtectionContinuityIntent,
    ) {
        preferences.put(
            mapOf(
                KEY_STATE to snapshot.state.name,
                KEY_TRANSITION_AT_MS to snapshot.lastTransitionAtMs,
                KEY_SERVICE_HEARTBEAT_AT_MS to lastServiceHeartbeatAtMs,
                KEY_TELEGRAM_CONTACT_AT_MS to snapshot.lastTelegramContactAtMs,
                LEGACY_REMOVED_FLAG_KEY to null,
                KEY_LAST_INCIDENT_ID to snapshot.lastIncident?.id,
                KEY_REVISION to snapshot.revision,
                KEY_CONTINUITY_DESIRED_SERVICE to continuityIntent.desiredService.name,
                KEY_CONTINUITY_DESIRED_PROTECTION to continuityIntent.desiredProtection.name,
                KEY_CONTINUITY_AUTO_RECOVERY_AFTER_BOOT to continuityIntent.autoRecoveryAfterBoot,
                KEY_CONTINUITY_ARMED_SESSION_ID to continuityIntent.armedSessionId,
                // Encoded inside the same atomic put/commit as every other field.
                KEY_ARMED_PROFILE_SNAPSHOT_JSON to snapshot.armedProfileSnapshot?.let { armedProfileSnapshotCodec.encode(it) },
            ),
        )
    }

    fun loadForRecovery(): ProtectionRecoveryState {
        val persistedState = preferences.getString(KEY_STATE)
            ?.let { name -> runCatching { ProtectionState.valueOf(name) }.getOrNull() }
            ?: ProtectionState.DISARMED_ONLINE
        val desiredService = preferences.getString(KEY_CONTINUITY_DESIRED_SERVICE)
            ?.let { name -> runCatching { DesiredService.valueOf(name) }.getOrNull() }
        val desiredProtection = preferences.getString(KEY_CONTINUITY_DESIRED_PROTECTION)
            ?.let { name -> runCatching { DesiredProtection.valueOf(name) }.getOrNull() }
        val autoRecoveryAfterBoot = preferences.getBoolean(KEY_CONTINUITY_AUTO_RECOVERY_AFTER_BOOT)
        val armedSessionId = preferences.getString(KEY_CONTINUITY_ARMED_SESSION_ID)
        val continuityValid = desiredService != null &&
            desiredProtection != null &&
            autoRecoveryAfterBoot != null &&
            !(desiredProtection == DesiredProtection.ARMED && armedSessionId.isNullOrBlank()) &&
            !(desiredService == DesiredService.STOPPED_BY_OWNER && desiredProtection != DesiredProtection.DISARMED)
        val continuityIntent = if (continuityValid) {
            ProtectionContinuityIntent(
                desiredService = checkNotNull(desiredService),
                desiredProtection = checkNotNull(desiredProtection),
                autoRecoveryAfterBoot = checkNotNull(autoRecoveryAfterBoot),
                armedSessionId = armedSessionId,
            )
        } else {
            ProtectionContinuityIntent(
                desiredService = DesiredService.RUNNING,
                desiredProtection = DesiredProtection.DISARMED,
                autoRecoveryAfterBoot = false,
            )
        }
        val armedProfileSnapshot = preferences.getString(KEY_ARMED_PROFILE_SNAPSHOT_JSON)
            ?.let { json ->
                try {
                    armedProfileSnapshotCodec.decode(json)
                } catch (_: Exception) {
                    // A corrupt armed snapshot fails safe to null; continuity intent above
                    // still decides whether recovery may rearm.
                    null
                }
            }
        return ProtectionRecoveryState(
            liveSnapshot = ProtectionSnapshot.offline(clock.nowMs()).copy(
                armedProfileSnapshot = armedProfileSnapshot,
            ),
            hints = ProtectionRecoveryHints(
                persistedState = persistedState,
                lastTransitionAtMs = preferences.getLong(KEY_TRANSITION_AT_MS),
                lastServiceHeartbeatAtMs = preferences.getLong(KEY_SERVICE_HEARTBEAT_AT_MS),
                lastTelegramContactAtMs = preferences.getLong(KEY_TELEGRAM_CONTACT_AT_MS),
                lastIncidentId = preferences.getString(KEY_LAST_INCIDENT_ID),
                revision = preferences.getLong(KEY_REVISION) ?: 0L,
            ),
            continuityIntent = continuityIntent,
            continuityValid = continuityValid,
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "protection_runtime_state"
        const val KEY_STATE = "state"
        const val KEY_TRANSITION_AT_MS = "transition_at_ms"
        const val KEY_SERVICE_HEARTBEAT_AT_MS = "service_heartbeat_at_ms"
        const val KEY_TELEGRAM_CONTACT_AT_MS = "telegram_contact_at_ms"
        const val LEGACY_REMOVED_FLAG_KEY = "demo_enabled"
        const val KEY_LAST_INCIDENT_ID = "last_incident_id"
        const val KEY_REVISION = "revision"
        const val KEY_CONTINUITY_DESIRED_SERVICE = "continuity_desired_service"
        const val KEY_CONTINUITY_DESIRED_PROTECTION = "continuity_desired_protection"
        const val KEY_CONTINUITY_AUTO_RECOVERY_AFTER_BOOT = "continuity_auto_recovery_after_boot"
        const val KEY_CONTINUITY_ARMED_SESSION_ID = "continuity_armed_session_id"
        const val KEY_ARMED_PROFILE_SNAPSHOT_JSON = "armed_profile_snapshot_json"
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

package com.example.motorcycleantitheftsensor.protection

import android.content.SharedPreferences

/**
 * Single writer for the persisted protection-profile aggregate (Task 2).
 *
 * All later tasks must go through this repository to mutate the profile store.
 */
interface ProtectionProfileRepository {
    fun load(): ProtectionProfileStoreState
    fun save(state: ProtectionProfileStoreState): Result<Unit>
    fun update(
        transform: (ProtectionProfileStoreState) -> ProtectionProfileStoreState,
    ): Result<ProtectionProfileStoreState>
}

class SharedPreferencesProtectionProfileRepository(
    private val preferences: SharedPreferences,
    private val legacyRepository: SensorConfigurationRepository,
    private val codec: ProtectionProfileCodec = ProtectionProfileCodec(),
    private val policy: ProtectionProfilePolicy = ProtectionProfilePolicy(),
) : ProtectionProfileRepository {

    override fun load(): ProtectionProfileStoreState {
        val storedJson = preferences.getString(KEY_PROFILE_STATE, null)
        if (!storedJson.isNullOrBlank()) {
            try {
                return codec.decode(storedJson)
            } catch (_: Exception) {
                // Corrupt or future-schema aggregate falls through to the
                // legacy-preserving initial state below; never guess.
            }
        }
        // First load with no aggregate: preserve the customer's existing sensor
        // configuration without selecting a profile and without writing anything.
        return policy.newStoreState(legacyRepository.loadConfiguration())
    }

    override fun save(state: ProtectionProfileStoreState): Result<Unit> = try {
        val json = codec.encode(state)
        // One commit boundary for the whole aggregate.
        val committed = preferences.edit()
            .putString(KEY_PROFILE_STATE, json)
            .commit()
        if (committed) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Failed to commit protection profile aggregate"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    override fun update(
        transform: (ProtectionProfileStoreState) -> ProtectionProfileStoreState,
    ): Result<ProtectionProfileStoreState> {
        val next = try {
            transform(load())
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return save(next).map { next }
    }

    companion object {
        const val KEY_PROFILE_STATE = "protection_profile_state_json"
    }
}

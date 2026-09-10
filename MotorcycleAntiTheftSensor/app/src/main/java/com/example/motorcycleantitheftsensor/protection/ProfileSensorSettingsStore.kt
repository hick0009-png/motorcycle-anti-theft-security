package com.example.motorcycleantitheftsensor.protection

/**
 * The sensor screen's window onto the store an armed session actually reads.
 *
 * Two configurations existed side by side. The screen read and wrote a central one, kept
 * from before uses existed; an Arm resolved the selected use's own recommendation plus that
 * use's overrides, and never looked at the central one. So an owner could set a role,
 * watch the screen confirm it, and arm into something else entirely — which is worse than
 * the setting not existing, because the screen said it had taken.
 *
 * Reads come back resolved: the use's recommendation, the owner's decisions on top, and the
 * sources this use locks off already forced off, which is exactly what an Arm would build.
 * Writes are stored as differences, so a later change to what a use recommends carries the
 * owner's decisions forward instead of freezing yesterday's defaults into their profile.
 */
class ProfileSensorSettingsStore(
    private val repository: ProtectionProfileRepository,
    private val policy: ProtectionProfilePolicy = ProtectionProfilePolicy(),
) {

    /** What the selected use would arm with, or null when no use is selected yet. */
    fun read(): SensorFusionConfiguration? = runCatching {
        val state = repository.load()
        val profile = state.selectedProfile ?: return null
        policy.resolve(state, profile).sensorConfiguration
    }.getOrNull()

    /**
     * @return whether the edit was stored against a use. False means there was no use to
     *   store it against — never that the edit was lost, since the caller still keeps the
     *   central copy for everything that has not moved over yet.
     */
    fun write(config: SensorFusionConfiguration): Boolean = runCatching {
        val state = repository.load()
        val profile = state.selectedProfile ?: return false
        val stored = state.profiles.getValue(profile)
        repository.save(
            state.copy(
                profiles = state.profiles + (
                    profile to stored.copy(sensorOverrides = policy.overridesFrom(profile, config))
                    ),
            ),
        ).isSuccess
    }.getOrDefault(false)
}

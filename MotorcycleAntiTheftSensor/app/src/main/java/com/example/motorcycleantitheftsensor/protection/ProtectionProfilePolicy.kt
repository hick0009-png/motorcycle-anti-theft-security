package com.example.motorcycleantitheftsensor.protection

class ProtectionProfilePolicy(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sensorPolicy: SensorConfigurationPolicy = SensorConfigurationPolicy(),
) {
    fun newStoreState(
        legacyConfiguration: SensorFusionConfiguration? = null,
    ): ProtectionProfileStoreState = ProtectionProfileStoreState(
        selectedProfile = null,
        profiles = ProtectionProfile.entries.associateWith(::initialProfile),
        legacyConfiguration = legacyConfiguration,
    )

    fun updateProfile(
        state: ProtectionProfileStoreState,
        profile: StoredProfileConfiguration,
    ): ProtectionProfileStoreState {
        validateSpecificOverrides(profile)
        val updated = state.copy(
            profiles = state.profiles + (profile.profile to profile),
        )
        val validation = sensorPolicy.validateForSave(
            resolve(updated, profile.profile).sensorConfiguration,
        )
        require(validation is SensorConfigurationValidation.Valid) {
            "Resolved sensor configuration is invalid: $validation"
        }
        return updated
    }

    fun recommended(profile: ProtectionProfile): ResolvedProfileConfiguration =
        ResolvedProfileConfiguration(
            profile = profile,
            presetVersion = 1,
            sensorConfiguration = recommendedSensorConfiguration(profile),
            specificSettings = when (profile) {
                ProtectionProfile.VEHICLE -> VehicleProfileSettings
                ProtectionProfile.ENTRY -> EntryProfileSettings()
                ProtectionProfile.POWER -> PowerProfileSettings()
            },
            setupState = initialSetupState(profile),
            customized = false,
        )

    fun resolve(
        state: ProtectionProfileStoreState,
        profile: ProtectionProfile,
    ): ResolvedProfileConfiguration {
        val stored = state.profiles.getValue(profile)
        val recommended = recommended(profile)
        return recommended.copy(
            sensorConfiguration = resolveSensorConfiguration(
                recommended.sensorConfiguration,
                stored.sensorOverrides,
                lockedOffSources = lockedOffSources(profile, recommended.sensorConfiguration),
            ),
            specificSettings = resolveSpecificSettings(
                recommended.specificSettings,
                stored.specificOverrides,
            ),
            setupState = stored.setupState,
            customized = stored.sensorOverrides != SensorFusionProfileOverrides() ||
                stored.specificOverrides != emptySpecificOverrides(profile),
        )
    }

    fun restoreRecommended(
        state: ProtectionProfileStoreState,
        profile: ProtectionProfile,
    ): ProtectionProfileStoreState {
        val stored = state.profiles.getValue(profile)
        return updateProfile(
            state,
            stored.copy(
                sensorOverrides = SensorFusionProfileOverrides(),
                specificOverrides = emptySpecificOverrides(profile),
            ),
        )
    }

    /** Explicit Entry commissioning success: stores the hinge model and flips to READY. */
    fun commissionEntry(
        state: ProtectionProfileStoreState,
        model: EntryHingeModel,
    ): ProtectionProfileStoreState {
        val entry = state.profiles.getValue(ProtectionProfile.ENTRY)
        return updateProfile(
            state,
            entry.copy(
                entryHingeModel = model,
                setupState = ProfileSetupState.READY,
            ),
        )
    }

    /** Fingerprint invalidation or owner-initiated recommissioning requirement. */
    fun decommissionEntry(state: ProtectionProfileStoreState): ProtectionProfileStoreState {
        val entry = state.profiles.getValue(ProtectionProfile.ENTRY)
        return updateProfile(
            state,
            entry.copy(
                entryHingeModel = null,
                setupState = ProfileSetupState.SETUP_REQUIRED,
            ),
        )
    }

    /** Explicit Power commissioning success: stores the witness model and flips to READY. */
    fun commissionPower(
        state: ProtectionProfileStoreState,
        model: PowerWitnessModel,
    ): ProtectionProfileStoreState {
        val power = state.profiles.getValue(ProtectionProfile.POWER)
        return updateProfile(
            state,
            power.copy(
                powerWitnessModel = model,
                setupState = ProfileSetupState.READY,
            ),
        )
    }

    /** Fingerprint invalidation or owner-initiated recommissioning requirement. */
    fun decommissionPower(state: ProtectionProfileStoreState): ProtectionProfileStoreState {
        val power = state.profiles.getValue(ProtectionProfile.POWER)
        return updateProfile(
            state,
            power.copy(
                powerWitnessModel = null,
                setupState = ProfileSetupState.SETUP_REQUIRED,
            ),
        )
    }

    private fun initialProfile(profile: ProtectionProfile): StoredProfileConfiguration =
        StoredProfileConfiguration(
            profile = profile,
            presetVersion = 1,
            sensorOverrides = SensorFusionProfileOverrides(),
            specificOverrides = emptySpecificOverrides(profile),
            setupState = initialSetupState(profile),
        )

    private fun validateSpecificOverrides(profile: StoredProfileConfiguration) {
        when (profile.profile) {
            ProtectionProfile.VEHICLE -> require(profile.specificOverrides is VehicleProfileOverrides)
            ProtectionProfile.ENTRY -> {
                val overrides = profile.specificOverrides as? EntryProfileOverrides
                    ?: throw IllegalArgumentException("Entry profile requires EntryProfileOverrides")
                require(overrides.angleThresholdDegrees == null || overrides.angleThresholdDegrees in 5..90) {
                    "Entry angle threshold must be 5-90 degrees"
                }
                require(overrides.openConfirmationMs == null || overrides.openConfirmationMs in 250L..3_000L) {
                    "Entry open confirmation must be 250-3000 ms"
                }
            }
            ProtectionProfile.POWER -> {
                val overrides = profile.specificOverrides as? PowerProfileOverrides
                    ?: throw IllegalArgumentException("Power profile requires PowerProfileOverrides")
                require(overrides.lossConfirmationMs == null || overrides.lossConfirmationMs == 10_000L) {
                    "Power loss confirmation must be 10000 ms"
                }
                require(
                    overrides.recoveryConfirmationMs == null ||
                        overrides.recoveryConfirmationMs == 10_000L
                ) {
                    "Power recovery confirmation must be 10000 ms"
                }
            }
        }
    }

    private fun initialSetupState(profile: ProtectionProfile): ProfileSetupState = when (profile) {
        ProtectionProfile.VEHICLE -> ProfileSetupState.READY
        ProtectionProfile.ENTRY,
        ProtectionProfile.POWER,
        -> ProfileSetupState.SETUP_REQUIRED
    }

    private fun emptySpecificOverrides(profile: ProtectionProfile): ProfileSpecificOverrides = when (profile) {
        ProtectionProfile.VEHICLE -> VehicleProfileOverrides
        ProtectionProfile.ENTRY -> EntryProfileOverrides()
        ProtectionProfile.POWER -> PowerProfileOverrides()
    }

    private fun resolveSpecificSettings(
        recommended: ProfileSpecificSettings,
        overrides: ProfileSpecificOverrides,
    ): ProfileSpecificSettings = when {
        recommended is VehicleProfileSettings && overrides is VehicleProfileOverrides -> recommended
        recommended is EntryProfileSettings && overrides is EntryProfileOverrides -> recommended.copy(
            angleThresholdDegrees = overrides.angleThresholdDegrees ?: recommended.angleThresholdDegrees,
            openConfirmationMs = overrides.openConfirmationMs ?: recommended.openConfirmationMs,
        )
        recommended is PowerProfileSettings && overrides is PowerProfileOverrides -> recommended.copy(
            lossConfirmationMs = overrides.lossConfirmationMs ?: recommended.lossConfirmationMs,
            recoveryConfirmationMs = overrides.recoveryConfirmationMs ?: recommended.recoveryConfirmationMs,
        )
        else -> throw IllegalArgumentException("Profile-specific override type does not match profile")
    }

    /**
     * Sources a profile pins OFF as part of its detection contract, which a stored
     * override may never raise again.
     *
     * Power Guard watches exactly one lamp and one charging signal; the movement
     * sources are not merely unused there, they are harmful. Reaching for the charging
     * cable is movement, so a live accelerometer opens a rival incident that competes
     * with the power episode for the engine's active-incident slot. Other profiles stay
     * fully customisable.
     */
    private fun lockedOffSources(
        profile: ProtectionProfile,
        recommended: SensorFusionConfiguration,
    ): Set<SensorSource> = when (profile) {
        ProtectionProfile.POWER -> SensorSource.entries
            .filter { source -> recommended.source(source).role == SensorRole.OFF }
            .toSet()
        ProtectionProfile.VEHICLE, ProtectionProfile.ENTRY -> emptySet()
    }

    private fun resolveSensorConfiguration(
        recommended: SensorFusionConfiguration,
        overrides: SensorFusionProfileOverrides,
        lockedOffSources: Set<SensorSource> = emptySet(),
    ): SensorFusionConfiguration {
        val capabilities = recommended.capabilities.toMutableMap()
        overrides.capabilities.forEach { (capability, capabilityOverrides) ->
            val current = capabilities.getValue(capability)
            capabilities[capability] = current.copy(
                sensitivity = capabilityOverrides.sensitivity ?: current.sensitivity,
                correlationWindowMs = capabilityOverrides.correlationWindowMs ?: current.correlationWindowMs,
                confirmationDurationMs = capabilityOverrides.confirmationDurationMs
                    ?: current.confirmationDurationMs,
            )
        }
        overrides.sources.forEach { (source, sourceOverrides) ->
            val capability = source.capability
            val currentCapability = capabilities.getValue(capability)
            val currentSource = currentCapability.source(source)
            capabilities[capability] = currentCapability.copy(
                sources = currentCapability.sources + (
                    source to currentSource.copy(
                        role = if (source in lockedOffSources) {
                            SensorRole.OFF
                        } else {
                            sourceOverrides.role ?: currentSource.role
                        },
                        thresholdOverride = sourceOverrides.thresholdOverride
                            ?: currentSource.thresholdOverride,
                        debounceOverrideMs = sourceOverrides.debounceOverrideMs
                            ?: currentSource.debounceOverrideMs,
                        samplingProfileOverride = sourceOverrides.samplingProfileOverride
                            ?: currentSource.samplingProfileOverride,
                    )
                ),
            )
        }
        return recommended.copy(
            samplingProfile = overrides.samplingProfile ?: recommended.samplingProfile,
            capabilities = capabilities,
        )
    }

    private fun recommendedSensorConfiguration(
        profile: ProtectionProfile,
    ): SensorFusionConfiguration {
        val balanced = sensorPolicy.forPreset(SensorPreset.BALANCED, nowMs())
        val recommendedRoles = when (profile) {
            ProtectionProfile.VEHICLE -> return balanced
            ProtectionProfile.ENTRY -> mapOf(
                SensorSource.SIGNIFICANT_MOTION to SensorRole.SUPPORTING,
                SensorSource.ACCELEROMETER to SensorRole.SUPPORTING,
                SensorSource.LINEAR_ACCELERATION to SensorRole.SUPPORTING,
                SensorSource.GYROSCOPE to SensorRole.PRIMARY,
                SensorSource.ROTATION_VECTOR to SensorRole.PRIMARY,
                SensorSource.GAME_ROTATION_VECTOR to SensorRole.PRIMARY,
                SensorSource.MAGNETIC_FIELD to SensorRole.SUPPORTING,
                SensorSource.GEOMAGNETIC_ROTATION_VECTOR to SensorRole.SUPPORTING,
                SensorSource.AMBIENT_LIGHT to SensorRole.SUPPORTING,
                SensorSource.PROXIMITY to SensorRole.SUPPORTING,
            )
            ProtectionProfile.POWER -> SensorSource.entries.associateWith { source ->
                if (source == SensorSource.AMBIENT_LIGHT) SensorRole.PRIMARY else SensorRole.OFF
            }
        }
        return balanced.copy(
            capabilities = balanced.capabilities.mapValues { (_, capability) ->
                capability.copy(
                    sources = capability.sources.mapValues { (source, sourceConfiguration) ->
                        sourceConfiguration.copy(role = recommendedRoles.getValue(source))
                    },
                )
            },
        )
    }

    companion object {
        /**
         * Sensor kinds a profile actually detects with.
         *
         * The fusion configuration can only express motion, orientation, magnetic,
         * light and proximity sources, so the microphone and location would keep
         * running for every profile. Power Guard watches one lamp and one charging
         * signal; recording audio and taking location fixes for it costs battery and
         * privacy, and their absence must not read as a degraded system either.
         */
        fun usedSensorKinds(profile: ProtectionProfile): Set<SensorKind> = when (profile) {
            ProtectionProfile.POWER -> setOf(SensorKind.LIGHT, SensorKind.POWER_THERMAL)
            ProtectionProfile.VEHICLE, ProtectionProfile.ENTRY -> setOf(
                SensorKind.VIBRATION,
                SensorKind.LIGHT,
                SensorKind.MICROPHONE,
                SensorKind.LOCATION,
                SensorKind.POWER_THERMAL,
            )
        }
    }

}

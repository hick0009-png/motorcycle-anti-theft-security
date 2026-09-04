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
        val specificSettings = resolveSpecificSettings(
            recommended.specificSettings,
            stored.specificOverrides,
            commissioned = stored.entryHingeModel != null,
        )
        return recommended.copy(
            sensorConfiguration = resolveSensorConfiguration(
                recommended.sensorConfiguration,
                stored.sensorOverrides,
                lockedOffSources = lockedOffSources(profile),
            ),
            specificSettings = specificSettings,
            // The sound-and-movement level has nothing left to set up: it is ready the moment
            // the use is chosen, and holding it at SETUP_REQUIRED would refuse an arm for a
            // calibration it does not use.
            setupState = if (
                profile == ProtectionProfile.ENTRY &&
                (specificSettings as? EntryProfileSettings)?.level == EntryWatchLevel.SOUND_AND_MOVEMENT
            ) {
                ProfileSetupState.READY
            } else {
                stored.setupState
            },
            customized = stored.sensorOverrides != SensorFusionProfileOverrides() ||
                stored.specificOverrides != emptySpecificOverrides(profile),
        )
    }

    /**
     * What the owner changed, expressed as the difference from this use's recommendation.
     *
     * The settings screen edits a whole configuration, because that is what a screen of
     * sliders and roles is. The store keeps differences, because a use that is later
     * re-recommended — a better default, a device that turned out to lack a sensor — must
     * carry the owner's decisions forward and nothing else. This converts one into the other.
     *
     * Sources this use locks off are never written: [resolve] forces them off anyway, and
     * recording an override that agrees with a lock would turn a temporary rule into a
     * decision the owner never made.
     */
    fun overridesFrom(
        profile: ProtectionProfile,
        edited: SensorFusionConfiguration,
    ): SensorFusionProfileOverrides {
        val recommended = recommended(profile).sensorConfiguration
        val locked = lockedOffSources(profile)

        val capabilities = mutableMapOf<SensorCapability, SensorCapabilityProfileOverrides>()
        val sources = mutableMapOf<SensorSource, SensorSourceProfileOverrides>()

        SensorCapability.entries.forEach { capability ->
            val base = recommended.capability(capability)
            val now = edited.capability(capability)
            val capabilityOverride = SensorCapabilityProfileOverrides(
                sensitivity = now.sensitivity.takeIf { it != base.sensitivity },
                correlationWindowMs = now.correlationWindowMs.takeIf { it != base.correlationWindowMs },
                confirmationDurationMs = now.confirmationDurationMs
                    .takeIf { it != base.confirmationDurationMs },
            )
            if (capabilityOverride != SensorCapabilityProfileOverrides()) {
                capabilities[capability] = capabilityOverride
            }
        }

        SensorSource.entries.forEach { source ->
            if (source in locked) return@forEach
            val base = recommended.source(source)
            val now = edited.source(source)
            val sourceOverride = SensorSourceProfileOverrides(
                role = now.role.takeIf { it != base.role },
                thresholdOverride = now.thresholdOverride.takeIf { it != base.thresholdOverride },
                debounceOverrideMs = now.debounceOverrideMs.takeIf { it != base.debounceOverrideMs },
                samplingProfileOverride = now.samplingProfileOverride
                    .takeIf { it != base.samplingProfileOverride },
            )
            if (sourceOverride != SensorSourceProfileOverrides()) {
                sources[source] = sourceOverride
            }
        }

        return SensorFusionProfileOverrides(
            samplingProfile = edited.samplingProfile.takeIf { it != recommended.samplingProfile },
            capabilities = capabilities,
            sources = sources,
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

    /**
     * @param commissioned whether a hinge model has been captured for this use.
     *
     * An owner who has never chosen a level gets the one their setup supports: a phone with a
     * commissioned model is already watching angles and must go on doing so, and one without
     * gets the level it can actually arm. Choosing by hand overrides both.
     */
    private fun resolveSpecificSettings(
        recommended: ProfileSpecificSettings,
        overrides: ProfileSpecificOverrides,
        commissioned: Boolean = false,
    ): ProfileSpecificSettings = when {
        recommended is VehicleProfileSettings && overrides is VehicleProfileOverrides -> recommended
        recommended is EntryProfileSettings && overrides is EntryProfileOverrides -> recommended.copy(
            angleThresholdDegrees = overrides.angleThresholdDegrees ?: recommended.angleThresholdDegrees,
            openConfirmationMs = overrides.openConfirmationMs ?: recommended.openConfirmationMs,
            level = overrides.level
                ?: if (commissioned) EntryWatchLevel.DOOR_ANGLE else EntryWatchLevel.SOUND_AND_MOVEMENT,
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
    private fun lockedOffSources(profile: ProtectionProfile): Set<SensorSource> =
        lockedSources(profile)

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
        val recommendedRoles = recommendedRoles(profile)
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
         * The role every source is recommended to carry under a profile.
         *
         * This is where the recommendation lives; [recommendedSensorConfiguration] paints
         * it onto the balanced preset and the settings screen reads the same map to mark
         * the recommended button. A screen that rebuilt the table for itself would drift
         * from what the runtime actually receives, and the star would point at a role the
         * profile no longer asks for.
         */
        fun recommendedRoles(profile: ProtectionProfile): Map<SensorSource, SensorRole> =
            when (profile) {
                // The vehicle watch is the balanced preset itself: movement leads, the
                // rest corroborates.
                ProtectionProfile.VEHICLE -> SensorConfigurationPolicy.DEFAULT_ROLES_BALANCED
                // A door swings before it shakes, so orientation leads here and the
                // accelerometer steps back to corroboration.
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

        /**
         * Sources a profile pins OFF as part of its detection contract, exposed so the
         * settings screen can state the same fact instead of inferring its own.
         *
         * This is the single definition; `resolve()` enforces it and the UI only reads
         * it. `ProtectionProfilePolicyTest` pins it against the recommended roles, so a
         * later change to the recommendation table cannot drift the two apart silently.
         */
        fun lockedSources(profile: ProtectionProfile): Set<SensorSource> = when (profile) {
            ProtectionProfile.POWER -> SensorSource.entries.toSet() - SensorSource.AMBIENT_LIGHT
            ProtectionProfile.VEHICLE, ProtectionProfile.ENTRY -> emptySet()
        }

        /** Capabilities whose every source is locked, so the group slider is meaningless too. */
        fun lockedCapabilities(profile: ProtectionProfile): Set<SensorCapability> {
            val locked = lockedSources(profile)
            if (locked.isEmpty()) return emptySet()
            return SensorCapability.entries
                .filter { capability ->
                    SensorSource.entries
                        .filter { it.capability == capability }
                        .all { it in locked }
                }
                .toSet()
        }

        /**
         * Presets describe a whole-device balance. A profile that pins roles itself can
         * never match one, so offering the three preset buttons would only ever produce
         * a configuration the profile immediately overrides.
         */
        fun presetSelectable(profile: ProtectionProfile): Boolean =
            lockedSources(profile).isEmpty()

        /**
         * Sensor kinds a profile actually detects with.
         *
         * The fusion configuration can only express motion, orientation, magnetic,
         * light and proximity sources, so the microphone and location would keep
         * running for every profile. Power Guard watches one lamp and one charging
         * signal; recording audio and taking location fixes for it costs battery and
         * privacy, and their absence must not read as a degraded system either.
         */
        fun usedSensorKinds(
            profile: ProtectionProfile,
            entryLevel: EntryWatchLevel = EntryWatchLevel.DOOR_ANGLE,
        ): Set<SensorKind> =
            signalRoles(profile, entryLevel).filterValues { it != SensorRole.OFF }.keys

        /**
         * Which signals a use lets open an incident on its own, for the signals the fusion
         * configuration cannot describe.
         *
         * The ten hardware sources carry the role the owner set, but the microphone, the
         * location fix and the charging line are not `SensorSource`s and reached the engine
         * with no role at all. `isPrimaryRole` read a missing role as primary, so all three
         * hosted every use by accident — the door watch had four hosts nobody chose, all
         * competing for the one active-incident slot.
         *
         * A use names its hosts once, here, and everything else corroborates:
         *
         * - The vehicle watch keeps the charging line and the location fix as hosts. A cut
         *   cable or a bike that has moved is theft on its own evidence, and demoting either
         *   would silence an alert that works today.
         * - The door watch hosts on orientation alone. Sound and vibration matter greatly as
         *   corroboration — a loud road is not a door opening — so they may join an incident
         *   but never start one.
         * - Power Guard hosts on the witness lamp and the charging line together, and runs
         *   nothing else at all.
         */
        fun signalRoles(
            profile: ProtectionProfile,
            entryLevel: EntryWatchLevel = EntryWatchLevel.DOOR_ANGLE,
        ): Map<SensorKind, SensorRole> = when (profile) {
            ProtectionProfile.VEHICLE -> mapOf(
                SensorKind.VIBRATION to SensorRole.PRIMARY,
                SensorKind.LIGHT to SensorRole.PRIMARY,
                SensorKind.MICROPHONE to SensorRole.SUPPORTING,
                SensorKind.LOCATION to SensorRole.PRIMARY,
                SensorKind.POWER_THERMAL to SensorRole.PRIMARY,
            )
            ProtectionProfile.ENTRY -> when (entryLevel) {
                // Nothing measures an angle at this level, so the pair that can open a door
                // event has to host it. Neither alone: a lorry reaches the microphone and a
                // slammed gate next door reaches the accelerometer, and only their coincidence
                // is about this door.
                EntryWatchLevel.SOUND_AND_MOVEMENT -> mapOf(
                    SensorKind.VIBRATION to SensorRole.PRIMARY,
                    SensorKind.MICROPHONE to SensorRole.PRIMARY,
                    SensorKind.LIGHT to SensorRole.SUPPORTING,
                    SensorKind.LOCATION to SensorRole.SUPPORTING,
                    SensorKind.POWER_THERMAL to SensorRole.SUPPORTING,
                )
                // The orientation verdict carries PRIMARY of its own; a raw vibration sample
                // arriving here is corroboration, which is what the recommendation already
                // sets for the door watch's movement sources.
                EntryWatchLevel.DOOR_ANGLE -> mapOf(
                    SensorKind.VIBRATION to SensorRole.SUPPORTING,
                    SensorKind.LIGHT to SensorRole.SUPPORTING,
                    SensorKind.MICROPHONE to SensorRole.SUPPORTING,
                    SensorKind.LOCATION to SensorRole.SUPPORTING,
                    SensorKind.POWER_THERMAL to SensorRole.SUPPORTING,
                )
            }
            ProtectionProfile.POWER -> mapOf(
                SensorKind.LIGHT to SensorRole.PRIMARY,
                SensorKind.POWER_THERMAL to SensorRole.PRIMARY,
                SensorKind.VIBRATION to SensorRole.OFF,
                SensorKind.MICROPHONE to SensorRole.OFF,
                SensorKind.LOCATION to SensorRole.OFF,
            )
        }

        /** The hosts of a use, in the order the screen should name them. */
        fun hostKinds(
            profile: ProtectionProfile,
            entryLevel: EntryWatchLevel = EntryWatchLevel.DOOR_ANGLE,
        ): List<SensorKind> =
            SensorKind.entries.filter { signalRoles(profile, entryLevel)[it] == SensorRole.PRIMARY }

        /**
         * What opens an incident under a use, as the owner would name it.
         *
         * Mostly this is [hostKinds], but the door watch is the exception the table cannot
         * express: its host is the orientation verdict, which arrives as a vibration-kind
         * observation carrying its own primary role, so the table has to leave raw
         * vibration corroborating while the verdict still hosts. `ProtectionProfilePolicyTest`
         * pins the two against each other so they cannot drift.
         */
        fun hosts(
            profile: ProtectionProfile,
            entryLevel: EntryWatchLevel = EntryWatchLevel.DOOR_ANGLE,
        ): List<ProtectionHost> = when {
            profile == ProtectionProfile.ENTRY && entryLevel == EntryWatchLevel.DOOR_ANGLE ->
                listOf(ProtectionHost.ORIENTATION)
            else -> hostKinds(profile, entryLevel).mapNotNull { kind ->
                when (kind) {
                    SensorKind.VIBRATION -> ProtectionHost.MOVEMENT
                    SensorKind.LIGHT -> ProtectionHost.LIGHT
                    SensorKind.LOCATION -> ProtectionHost.LOCATION
                    SensorKind.POWER_THERMAL -> ProtectionHost.CHARGING
                    SensorKind.MICROPHONE -> ProtectionHost.SOUND
                }
            }
        }
    }

}

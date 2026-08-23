package com.example.motorcycleantitheftsensor.protection

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.telegram.TelegramBotClient
import com.example.motorcycleantitheftsensor.telephony.SmsFallbackManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.example.motorcycleantitheftsensor.location.EncryptedMovementTrackingStore
import com.example.motorcycleantitheftsensor.location.MovementDisplacementPolicy
import com.example.motorcycleantitheftsensor.sensor.LocationObservationProvider
import com.example.motorcycleantitheftsensor.sensor.audio.AudioThreatCandidateBuffer

object ProtectionRuntimeGraph {
    @Volatile
    private var instance: Graph? = null

    fun from(context: Context): Graph = instance ?: synchronized(this) {
        instance ?: buildGraph(context.applicationContext).also { graph -> instance = graph }
    }

    data class Graph(
        val coordinator: ProtectionCoordinator,
        val incidents: IncidentRepository,
        val delivery: IncidentDeliveryCoordinator,
        val runtime: ProtectionRuntime,
        val snapshotStore: ProtectionSnapshotStore,
        val statePersistence: ProtectionStatePersistenceArbiter,
        val scope: CoroutineScope,
        val livePursuitCoordinator: LivePursuitCoordinator,
        val sensorRepository: SensorConfigurationRepository? = null,
        val profileRepository: ProtectionProfileRepository? = null,
    )

    private fun buildGraph(context: Context): Graph {
        lateinit var coordinator: ProtectionCoordinator
        val wallClock = ProtectionClock(System::currentTimeMillis)
        val elapsedClock = ProtectionClock(SystemClock::elapsedRealtime)
        val snapshotStore = ProtectionSnapshotStore(context, wallClock)
        val directBootStore = DirectBootProtectionStore(context)
        val secureKeyManager = com.example.motorcycleantitheftsensor.security.SecureKeyManager(context)
        val repository = FileIncidentRepository(
            file = File(context.filesDir, "protection_incidents.bin"),
            maxRecords = 200,
            secureKeyManager = secureKeyManager,
        )
        val preferences = EncryptedPrefsManager(context)
        val statePersistence = ProtectionStatePersistenceArbiter(
            writeCompatibilityArmed = { armed ->
                withContext(Dispatchers.IO) {
                    check(preferences.commitSystemArmed(armed)) {
                        "Unable to persist armed compatibility state"
                    }
                }
            },
            writeSnapshot = { snapshot, heartbeat ->
                withContext(Dispatchers.IO) {
                    val armed = snapshot.state in setOf(
                        ProtectionState.ARMING,
                        ProtectionState.ARMED_HEALTHY,
                        ProtectionState.ARMED_DEGRADED,
                        ProtectionState.ALERT_ACTIVE,
                    )
                    snapshotStore.save(
                        snapshot = snapshot,
                        lastServiceHeartbeatAtMs = heartbeat,
                        continuityIntent = ProtectionContinuityIntent(
                            desiredService = if (snapshot.state == ProtectionState.OFFLINE) {
                                DesiredService.STOPPED_BY_OWNER
                            } else {
                                DesiredService.RUNNING
                            },
                            desiredProtection = if (armed) DesiredProtection.ARMED else DesiredProtection.DISARMED,
                            autoRecoveryAfterBoot = preferences.isAutoRecoveryAfterBootEnabled(),
                            armedSessionId = if (armed) coordinator.currentArmedSessionId() else null,
                        ),
                    )
                    val markerSaved = directBootStore.save(
                        DirectBootProtectionMarker(
                            armed = armed,
                            autoRecoveryAfterBoot = preferences.isAutoRecoveryAfterBootEnabled(),
                        ),
                    )
                    if (!markerSaved) {
                        Log.w(TAG, "Unable to persist direct-boot recovery marker")
                    }
                }
            },
        )
        val telegram = TelegramBotClient(
            prefsManager = preferences,
            onTelegramContact = { atMs -> coordinator.recordTelegramContact(atMs) },
        )
        val progressTelegram = com.example.motorcycleantitheftsensor.telegram.TelegramIncidentProgressTransport(
            httpClient = com.example.motorcycleantitheftsensor.network.TlsPinningClient.client,
            prefs = preferences,
            onTelegramContact = { atMs -> coordinator.recordTelegramContact(atMs) },
        )
        val sms = SmsFallbackManager(context, preferences)
        val labelResolver = com.example.motorcycleantitheftsensor.location.AndroidLocationLabelResolver(context)
        val locationProvider = LocationObservationProvider(context)
        val delivery = IncidentDeliveryCoordinator(
            repository = repository,
            formatter = IncidentMessageFormatter { coordinator.snapshot.value },
            telegram = IncidentTransport(telegram::sendTelegramAlert),
            sms = IncidentTransport { _ ->
                val destination = preferences.getSmsDestination()
                if (destination.isNullOrBlank() || preferences.getSmsAesKey().isNullOrBlank()) {
                    false
                } else {
                    sms.sendEncryptedSmsAlert(destination, "SECURITY_INCIDENT")
                }
            },
            labelResolver = labelResolver,
            progressTelegram = progressTelegram,
        )
        val processor = SensorObservationProcessor(
            staleAfterMs = 5_000L,
            debounceSamples = mapOf(
                SensorKind.VIBRATION to 3,
                SensorKind.LIGHT to 1,
                SensorKind.POWER_THERMAL to 1,
            ),
            thresholdDeltas = mapOf(
                SensorKind.VIBRATION to 2.0,
                SensorKind.LIGHT to 10.0,
                SensorKind.POWER_THERMAL to 5.0,
            ),
            absoluteMinimumsByDiagnostic = mapOf(
                "temperature_celsius" to 45.0,
            ),
        )
        val incidentEngine = IncidentEngine(
            idGenerator = IncidentIdGenerator { UUID.randomUUID().toString() },
            correlationWindowMs = 15_000L,
        )
        val deliveryPolicy = IncidentUpdateDeliveryPolicy()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val incidentMutex = Mutex()
        suspend fun process(update: IncidentUpdate) {
            val incident = update.incidentOrNull() ?: return
            when (deliveryPolicy.action(update)) {
                DeliveryAction.NONE -> Unit
                DeliveryAction.PERSIST_ONLY -> {
                    withContext(Dispatchers.IO) { repository.upsert(incident) }
                    coordinator.recordPersistenceRecovered(PersistenceSource.INCIDENT_HISTORY)
                    coordinator.recordIncident(incident)
                }

                DeliveryAction.PERSIST_AND_EDIT -> {
                    withContext(Dispatchers.IO) { repository.upsert(incident) }
                    coordinator.recordPersistenceRecovered(PersistenceSource.INCIDENT_HISTORY)
                    coordinator.recordIncident(incident)
                    delivery.updateProgress(incident)
                }

                DeliveryAction.SEND_CONTINUATION -> {
                    withContext(Dispatchers.IO) { repository.upsert(incident) }
                    coordinator.recordPersistenceRecovered(PersistenceSource.INCIDENT_HISTORY)
                    coordinator.recordIncident(incident)
                    delivery.updateProgress(incident)
                    delivery.sendContinuation(incident)
                }

                DeliveryAction.SEND,
                DeliveryAction.SEND_CLOSE_SUMMARY,
                -> {
                    coordinator.recordIncident(incident)
                    val delivered = withContext(Dispatchers.IO) {
                        delivery.deliver(
                            update = update,
                            configuration = DeliveryConfiguration(
                                smsConfigured = !preferences.getSmsDestination().isNullOrBlank() &&
                                    !preferences.getSmsAesKey().isNullOrBlank(),
                            ),
                        )
                    }
                    coordinator.recordIncident(delivered)
                    val localAttempt = delivered.deliveryAttempts.lastOrNull { attempt ->
                        attempt.channel == DeliveryChannel.LOCAL_STORAGE
                    }
                    if (localAttempt?.state == DeliveryState.FAILED) {
                        coordinator.recordPersistenceFailure(PersistenceSource.INCIDENT_HISTORY)
                    } else if (localAttempt != null) {
                        coordinator.recordPersistenceRecovered(PersistenceSource.INCIDENT_HISTORY)
                    }
                }
            }
        }
        val incidentCloseDispatcher = IncidentCloseDispatcher(
            scope = scope,
            persistLocal = { incident ->
                withContext(Dispatchers.IO) { repository.upsert(incident) }
                coordinator.recordPersistenceRecovered(PersistenceSource.INCIDENT_HISTORY)
                coordinator.recordIncident(incident)
            },
            deliverExternal = ::process,
            onPersistenceFailure = {
                coordinator.recordPersistenceFailure(PersistenceSource.INCIDENT_HISTORY)
            },
            onExternalFailure = { Log.w(TAG, "Incident close delivery failed") },
        )
        val candidateBuffer = AudioThreatCandidateBuffer()
        val onAudioCandidatesReset: () -> Unit = {
            scope.launch {
                incidentMutex.withLock {
                    incidentEngine.clearPendingAudio()
                }
            }
        }
        var quietWatchdogJob: Job? = null
        val watchdogLock = Any()

        fun scheduleQuietWatchdog() {
            synchronized(watchdogLock) {
                quietWatchdogJob?.cancel()
                quietWatchdogJob = scope.launch {
                    while (isActive) {
                        delay(INCIDENT_QUIET_WINDOW_MS)
                        val closed = incidentMutex.withLock {
                            if (!incidentEngine.hasActiveIncident) {
                                return@withLock null
                            }
                            incidentEngine.closeIfQuiet(
                                nowElapsedMs = elapsedClock.nowMs(),
                                quietWindowMs = INCIDENT_QUIET_WINDOW_MS,
                            )
                        }
                        if (closed != null) {
                            process(closed)
                            break
                        } else {
                            val stillActive = incidentMutex.withLock { incidentEngine.hasActiveIncident }
                            if (!stillActive) {
                                break
                            }
                        }
                    }
                }
            }
        }
        val incidentChannel = PriorityChannel<Pair<Long, IncidentObservationBatch>>(
            capacity = 1000,
            priorityOf = { (_, batch) ->
                var score = 0
                if (batch.primary.audioThreat != null) score += 10
                if (batch.location != null) score += 5
                if (batch.supplementalEvidence.any { it.audioThreat != null }) score += 10
                score
            }
        )

        scope.launch {
            while (isActive) {
                val (sessionEpoch, batch) = incidentChannel.receive()
                try {
                    val updateToProcess = incidentMutex.withLock {
                        val primaryUpdate = if (coordinator.acceptsIncident(sessionEpoch)) {
                            incidentEngine.accept(
                                observation = batch.primary,
                                protectionState = coordinator.snapshot.value.state,
                                location = batch.location,
                            )
                        } else {
                            IncidentUpdate.Ignored
                        }
                        val enrichedUpdate = if (primaryUpdate == IncidentUpdate.Ignored) {
                            primaryUpdate
                        } else {
                            batch.supplementalEvidence.fold(primaryUpdate) { update, evidence ->
                                val evidenceUpdate = incidentEngine.accept(
                                    observation = evidence,
                                    protectionState = coordinator.snapshot.value.state,
                                    location = batch.location,
                                )
                                evidenceUpdate.incidentOrNull()?.let { latest -> update.withIncident(latest) } ?: update
                            }
                        }
                        val incident = enrichedUpdate.incidentOrNull()
                        val currentArmedSession = coordinator.currentArmedSessionId()
                        if (incident != null && currentArmedSession != null) {
                            incident.evidence.forEach { ev ->
                                val threat = ev.audioThreat
                                if (threat != null) {
                                    candidateBuffer.consume(threat.category, currentArmedSession)
                                }
                            }
                        }
                        enrichedUpdate
                    }
                    if (updateToProcess != IncidentUpdate.Ignored) {
                        process(updateToProcess)
                        scheduleQuietWatchdog()
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    coordinator.recordPersistenceFailure(PersistenceSource.INCIDENT_HISTORY)
                    Log.e(TAG, "Incident processing failed", error)
                }
            }
        }

        val consumeIncidentBatch: (IncidentObservationBatch) -> Unit = { batch ->
            val sessionEpoch = coordinator.currentIncidentEpoch()
            incidentChannel.trySend(sessionEpoch to batch)
        }

        val movementTrackingStore = EncryptedMovementTrackingStore(preferences)
        val displacementPolicy = MovementDisplacementPolicy()
        val liveLocationTransport = com.example.motorcycleantitheftsensor.telegram.TelegramLiveLocationTransportImpl(
            httpClient = com.example.motorcycleantitheftsensor.network.TlsPinningClient.client,
            prefs = preferences
        )
        val expiryScheduler = CoroutinePursuitExpiryScheduler(scope)
        val livePursuitCoordinator = DefaultLivePursuitCoordinator(
            locationTracking = locationProvider,
            store = movementTrackingStore,
            displacementPolicy = displacementPolicy,
            transport = liveLocationTransport,
            labelResolver = labelResolver,
            expiryScheduler = expiryScheduler,
            scope = scope,
            coordinatorProvider = { coordinator },
            onMovementConfirmed = { fix ->
                val sessionEpoch = coordinator.currentIncidentEpoch()
                val armedSessionId = coordinator.currentArmedSessionId()
                scope.launch {
                    try {
                        val updateToProcess = incidentMutex.withLock {
                            if (!coordinator.acceptsIncident(sessionEpoch) || armedSessionId.isNullOrBlank()) return@withLock null
                            val obs = SensorObservation(
                                kind = SensorKind.LOCATION,
                                eventElapsedMs = fix.elapsedRealtimeMs,
                                wallClockMs = fix.wallClockMs,
                                normalizedValue = 1.0,
                                baselineDelta = 0.0,
                                valid = true,
                                diagnostic = "confirmed_movement",
                            )
                            val update = incidentEngine.onConfirmedMovement(
                                observation = obs,
                                protectionState = coordinator.snapshot.value.state,
                                location = IncidentLocation(
                                    latitude = fix.latitude,
                                    longitude = fix.longitude,
                                    accuracyMeters = fix.accuracyMeters,
                                    capturedAtWallClockMs = fix.wallClockMs,
                                ),
                            )
                            val incident = update.incidentOrNull()
                            if (incident != null) {
                                incident.evidence.forEach { ev ->
                                    val threat = ev.audioThreat
                                    if (threat != null) {
                                        candidateBuffer.consume(threat.category, armedSessionId)
                                    }
                                }
                                update
                            } else {
                                null
                            }
                        }
                        if (updateToProcess != null) {
                            process(updateToProcess)
                            scheduleQuietWatchdog()
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        coordinator.recordPersistenceFailure(PersistenceSource.INCIDENT_HISTORY)
                        Log.e(TAG, "Confirmed movement processing failed", error)
                    }
                }
            },
        )

        val sensorHandlerOwner = com.example.motorcycleantitheftsensor.sensor.SensorHandlerOwner("SensorThread")
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val sensorCatalog = com.example.motorcycleantitheftsensor.sensor.AndroidSensorCatalog(sensorManager)
        val sensorNormalizer = com.example.motorcycleantitheftsensor.sensor.SensorObservationNormalizer()
        val sensorCalibrationManager = com.example.motorcycleantitheftsensor.sensor.SensorCalibrationManager()
        val sensorPolicy = SensorConfigurationPolicy()
        val sensorController = com.example.motorcycleantitheftsensor.sensor.DefaultSensorCapabilityController(
            sensorManager = sensorManager,
            catalog = sensorCatalog,
            calibrationManager = sensorCalibrationManager,
            normalizer = sensorNormalizer,
            policy = sensorPolicy,
            handlerOwner = sensorHandlerOwner,
        )
        val sharedPrefs = try {
            androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                "motorcycle_anti_theft_encrypted_prefs",
                com.example.motorcycleantitheftsensor.security.SecureKeyManager(context).masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.getSharedPreferences("motorcycle_anti_theft_encrypted_prefs", Context.MODE_PRIVATE)
        }
        val sensorRepository = EncryptedPrefsSensorConfigurationRepository(sharedPrefs)

        // One process-shared profile aggregate writer used by both the UI layer and
        // SensorService recovery; credential-protected storage only.
        val profileRepository = SharedPreferencesProtectionProfileRepository(
            preferences = sharedPrefs,
            legacyRepository = sensorRepository,
        )

        val runtime = AndroidProtectionRuntime(
            readinessProvider = AndroidRuntimeReadiness(context) {
                RemoteControlReadiness(
                    botTokenConfigured = !preferences.getBotToken().isNullOrBlank(),
                    ownerPaired = preferences.getAllowedChatIds().isNotEmpty(),
                )
            }::report,
            detectorFactory = { callback ->
                PlatformAndroidDetectorSet(
                    context = context,
                    location = locationProvider,
                    onObservation = callback,
                    audioCandidateBuffer = candidateBuffer,
                    onAudioCandidatesReset = onAudioCandidatesReset,
                    handlerOwner = sensorHandlerOwner,
                    controller = sensorController,
                )
            },
            observationProcessor = processor,
            elapsedClock = elapsedClock,
            stateProvider = { coordinator.snapshot.value.state },
            sensorSampleRecorder = { kind, atMs, detail, normalizedValue ->
                coordinator.recordSensorSample(kind, atMs, detail, normalizedValue)
            },
            incidentConsumer = consumeIncidentBatch,
        )

        val initialConfig = sensorRepository.loadConfiguration()
        runtime.applySensorConfiguration(initialConfig)

        val configuredSensitivity = initialConfig.capability(SensorCapability.MOVEMENT).sensitivity
        preferences.setSensitivity(configuredSensitivity)
        runtime.applySensitivity(configuredSensitivity)
        coordinator = ProtectionCoordinator(
            initialSnapshot = ProtectionSnapshot.offline(wallClock.nowMs()).copy(
                sensitivityLevel = configuredSensitivity,
                sensorFusionConfiguration = initialConfig,
            ),
            runtime = runtime,
            armingDelay = ArmingDelay { delay(10_000L) },
            clock = wallClock,
            incidentCloser = { reason ->
                synchronized(watchdogLock) {
                    quietWatchdogJob?.cancel()
                    quietWatchdogJob = null
                }
                incidentMutex.lock()
                val closed = try {
                    incidentEngine.close(
                        nowMs = wallClock.nowMs(),
                        reason = reason,
                    )
                } finally {
                    incidentMutex.unlock()
                }
                closed?.let { incidentCloseDispatcher.persistAndDispatch(it) } ?: true
            },
            durableSnapshotWriter = { snapshot ->
                statePersistence.persist(
                    ProtectionPersistenceRequest(snapshot, wallClock.nowMs()),
                )
            },
            sensorRepository = sensorRepository,
            profileRepository = profileRepository,
            entryCommissioningContextProvider = {
                EntryCommissioningEnvironment.currentContext(entryUseContinuous = true)
            },
        )
        runtime.applySensitivity(configuredSensitivity)
        coordinator.recordSensorHealthSnapshot(runtime.currentSensorHealth())
        scope.launch {
            runtime.sensorHealth.collect { healthMap ->
                val healthWithLocation = healthMap.toMutableMap()
                healthWithLocation[SensorKind.LOCATION] = locationProvider.trackingHealth.value.toSensorHealth()
                coordinator.recordSensorHealthSnapshot(healthWithLocation)
            }
        }
        coordinator.recordSensorHealth(SensorKind.LOCATION, locationProvider.trackingHealth.value.toSensorHealth())
        scope.launch {
            locationProvider.trackingHealth.collect { health ->
                coordinator.recordSensorHealth(SensorKind.LOCATION, health.toSensorHealth())
            }
        }
        var previousNotifiedState: ProtectionState? = null
        val stateNotifier = ProtectionStateTelegramNotifier()
        scope.launch {
            coordinator.snapshot.collect { snapshot ->
                livePursuitCoordinator.onProtectionStateChanged(snapshot.state, coordinator.currentArmedSessionId())
                val current = snapshot.state
                val prev = previousNotifiedState
                previousNotifiedState = current

                val messages = stateNotifier.messagesFor(prev, current, snapshot.degradationReasons)
                if (messages.isNotEmpty()) {
                    scope.launch(Dispatchers.IO) {
                        for (msg in messages) {
                            try {
                                telegram.sendTelegramAlert(msg)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
        return Graph(
            coordinator = coordinator,
            incidents = repository,
            delivery = delivery,
            runtime = runtime,
            snapshotStore = snapshotStore,
            statePersistence = statePersistence,
            scope = scope,
            livePursuitCoordinator = livePursuitCoordinator,
            sensorRepository = sensorRepository,
            profileRepository = profileRepository,
        )
    }

    private fun IncidentUpdate.incidentOrNull(): SecurityIncident? = when (this) {
        IncidentUpdate.Ignored -> null
        is IncidentUpdate.Opened -> incident
        is IncidentUpdate.Updated -> incident
        is IncidentUpdate.Escalated -> incident
        is IncidentUpdate.Closed -> incident
    }

    private fun IncidentUpdate.withIncident(incident: SecurityIncident): IncidentUpdate = when (this) {
        IncidentUpdate.Ignored -> this
        is IncidentUpdate.Opened -> IncidentUpdate.Opened(incident)
        is IncidentUpdate.Updated -> IncidentUpdate.Updated(incident)
        is IncidentUpdate.Escalated -> IncidentUpdate.Escalated(incident)
        is IncidentUpdate.Closed -> IncidentUpdate.Closed(incident)
    }

    private const val INCIDENT_QUIET_WINDOW_MS = 30_000L
    private const val TAG = "ProtectionRuntime"
}

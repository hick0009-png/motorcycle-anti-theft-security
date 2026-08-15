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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import com.example.motorcycleantitheftsensor.location.EncryptedMovementTrackingStore
import com.example.motorcycleantitheftsensor.location.MovementDisplacementPolicy
import com.example.motorcycleantitheftsensor.sensor.LocationObservationProvider

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
    )

    private fun buildGraph(context: Context): Graph {
        lateinit var coordinator: ProtectionCoordinator
        val wallClock = ProtectionClock(System::currentTimeMillis)
        val elapsedClock = ProtectionClock(SystemClock::elapsedRealtime)
        val snapshotStore = ProtectionSnapshotStore(context, wallClock)
        val repository = FileIncidentRepository(
            file = File(context.filesDir, "protection_incidents.bin"),
            maxRecords = 200,
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
                    snapshotStore.save(snapshot, heartbeat)
                }
            },
        )
        val telegram = TelegramBotClient(
            prefsManager = preferences,
            onTelegramContact = { atMs -> coordinator.recordTelegramContact(atMs) },
        )
        val sms = SmsFallbackManager(context, preferences)
        val labelResolver = com.example.motorcycleantitheftsensor.location.AndroidLocationLabelResolver(context)
        val delivery = IncidentDeliveryCoordinator(
            repository = repository,
            formatter = IncidentMessageFormatter { coordinator.snapshot.value },
            telegram = IncidentTransport(telegram::sendTelegramAlert),
            sms = IncidentTransport { message ->
                val destination = preferences.getSmsDestination()
                if (destination.isNullOrBlank() || preferences.getSmsAesKey().isNullOrBlank()) {
                    false
                } else {
                    sms.sendEncryptedSmsAlert(destination, "SECURITY_INCIDENT", message)
                }
            },
            labelResolver = labelResolver,
        )
        val processor = SensorObservationProcessor(
            staleAfterMs = 5_000L,
            debounceSamples = mapOf(
                SensorKind.VIBRATION to 3,
                SensorKind.LIGHT to 1,
                SensorKind.POWER_THERMAL to 1,
                SensorKind.MICROPHONE to 2,
            ),
            thresholdDeltas = mapOf(
                SensorKind.VIBRATION to 2.0,
                SensorKind.LIGHT to 10.0,
                SensorKind.POWER_THERMAL to 5.0,
                SensorKind.MICROPHONE to 0.25,
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

                DeliveryAction.SEND,
                DeliveryAction.SEND_CLOSE_SUMMARY,
                -> {
                    coordinator.recordIncident(incident)
                    val delivered = withContext(Dispatchers.IO) {
                        delivery.deliver(
                            incident = incident,
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
        val consumeIncidentBatch: (IncidentObservationBatch) -> Unit = { batch ->
            val sessionEpoch = coordinator.currentIncidentEpoch()
            scope.launch {
                try {
                    incidentMutex.lock()
                    val accepted = try {
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
                        enrichedUpdate.also { update -> process(update) }
                    } finally {
                        incidentMutex.unlock()
                    }
                    if (accepted == IncidentUpdate.Ignored) return@launch
                    delay(INCIDENT_QUIET_WINDOW_MS)
                    incidentMutex.lock()
                    try {
                        incidentEngine.closeIfQuiet(
                            nowElapsedMs = elapsedClock.nowMs(),
                            quietWindowMs = INCIDENT_QUIET_WINDOW_MS,
                        )?.let { closed -> process(closed) }
                    } finally {
                        incidentMutex.unlock()
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    coordinator.recordPersistenceFailure(PersistenceSource.INCIDENT_HISTORY)
                    Log.e(TAG, "Incident processing failed", error)
                }
            }
        }

        val locationProvider = LocationObservationProvider(context)
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
        )

        val runtime = AndroidProtectionRuntime(
            readinessProvider = AndroidRuntimeReadiness(context) {
                RemoteControlReadiness(
                    botTokenConfigured = !preferences.getBotToken().isNullOrBlank(),
                    ownerPaired = preferences.getAllowedChatIds().isNotEmpty(),
                )
            }::report,
            detectorFactory = { callback -> PlatformAndroidDetectorSet(context, locationProvider, callback) },
            observationProcessor = processor,
            elapsedClock = elapsedClock,
            stateProvider = { coordinator.snapshot.value.state },
            sensorSampleRecorder = { kind, atMs, detail, normalizedValue ->
                coordinator.recordSensorSample(kind, atMs, detail, normalizedValue)
            },
            incidentConsumer = consumeIncidentBatch,
        )

        coordinator = ProtectionCoordinator(
            initialSnapshot = ProtectionSnapshot.offline(wallClock.nowMs()),
            runtime = runtime,
            armingDelay = ArmingDelay { delay(10_000L) },
            clock = wallClock,
            incidentCloser = { reason ->
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
        )
        runtime.applySensitivity(preferences.getSensitivity())
        scope.launch {
            var activeSessionId: String? = null
            coordinator.snapshot.collect { snapshot ->
                when (snapshot.state) {
                    ProtectionState.ARMING -> {
                        if (activeSessionId == null) {
                            activeSessionId = UUID.randomUUID().toString()
                        }
                    }
                    ProtectionState.DISARMED_ONLINE,
                    ProtectionState.OFFLINE,
                    ProtectionState.SETUP_REQUIRED -> {
                        activeSessionId = null
                    }
                    else -> Unit
                }
                livePursuitCoordinator.onProtectionStateChanged(snapshot.state, activeSessionId)
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

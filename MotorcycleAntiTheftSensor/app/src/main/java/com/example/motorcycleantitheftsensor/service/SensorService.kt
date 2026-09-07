package com.example.motorcycleantitheftsensor.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.motorcycleantitheftsensor.MainActivity
import com.example.motorcycleantitheftsensor.R
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.data.LegacyAuthenticatorMigrationResult
import com.example.motorcycleantitheftsensor.data.removeLegacyAuthenticatorState
import com.example.motorcycleantitheftsensor.location.AndroidAppVisibilityProvider
import com.example.motorcycleantitheftsensor.location.AppVisibilityProvider
import com.example.motorcycleantitheftsensor.location.ForegroundServiceTypePolicy
import com.example.motorcycleantitheftsensor.location.ForegroundStartController
import com.example.motorcycleantitheftsensor.protection.AndroidBlackBoxExitSource
import com.example.motorcycleantitheftsensor.protection.BlackBoxExitWitness
import com.example.motorcycleantitheftsensor.protection.BlackBoxProcessStateSummary
import com.example.motorcycleantitheftsensor.protection.BlackBoxRecorder
import com.example.motorcycleantitheftsensor.protection.BlackBoxStateMapper
import com.example.motorcycleantitheftsensor.protection.FileBlackBoxExitMarkStore
import com.example.motorcycleantitheftsensor.protection.ClockChangeWatcher
import com.example.motorcycleantitheftsensor.protection.CommandOrigin
import com.example.motorcycleantitheftsensor.protection.BreadcrumbDetail
import com.example.motorcycleantitheftsensor.protection.BreadcrumbDomain
import com.example.motorcycleantitheftsensor.protection.BreadcrumbEvent
import com.example.motorcycleantitheftsensor.protection.EntryDriftAutoMeasure
import com.example.motorcycleantitheftsensor.protection.NetworkWatcher
import com.example.motorcycleantitheftsensor.protection.PermissionWatcher
import com.example.motorcycleantitheftsensor.protection.IncidentCloseReason
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.PersistenceSource
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionRecoveryPolicy
import com.example.motorcycleantitheftsensor.protection.ProtectionRecoveryGate
import com.example.motorcycleantitheftsensor.protection.ProtectionRecoveryHints
import com.example.motorcycleantitheftsensor.protection.ProtectionPersistenceOutcome
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionPersistenceRequest
import com.example.motorcycleantitheftsensor.protection.ProtectionRuntimeGraph
import com.example.motorcycleantitheftsensor.protection.ProtectionRecoveryState
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.RecoveryGenerationToken
import com.example.motorcycleantitheftsensor.protection.RecoveryPlan
import com.example.motorcycleantitheftsensor.protection.RecoveryTrigger
import com.example.motorcycleantitheftsensor.protection.SnapshotProjectionGate
import com.example.motorcycleantitheftsensor.telegram.TelegramBotClient
import com.example.motorcycleantitheftsensor.telegram.ProtectionStatusFormatter
import com.example.motorcycleantitheftsensor.telegram.TelegramCommandHandler
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import com.example.motorcycleantitheftsensor.sensor.EntryDriftRecorder
import com.example.motorcycleantitheftsensor.sensor.EntryDriftStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/** Foreground lifecycle adapter for the process-wide protection runtime. */
class SensorService : Service(), ServiceEnvironment {
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "anti_theft_protection_silent_v2"
        private const val SERVICE_HEARTBEAT_INTERVAL_MS = 5_000L
        private const val ARMING_GRACE_MS = 10_000L
        private const val SNAPSHOT_PROJECTION_INTERVAL_MS = 5_000L
        private const val WAKE_LOCK_LEASE_MS = 60 * 60 * 1_000L
        private const val WAKE_LOCK_RENEW_BEFORE_MS = 5 * 60 * 1_000L
        private const val TAG = "SensorService"

        /**
         * The quiet interval between backlog sweeps. Short enough that a recovered network
         * reaches the owner in the same minute, long enough that a flapping connection or a
         * chatty polling client cannot turn the incident history into a hot file.
         */
        private const val INCIDENT_FLUSH_MIN_INTERVAL_MS = 30_000L
        /** Outside the black box directory: a marker swept up by the prune retells old deaths. */
        private const val EXIT_MARK_FILE = "blackbox_exit_mark"
        const val ACTION_START_SERVICE = "ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_ARM = "ACTION_ARM"
        const val ACTION_DISARM = "ACTION_DISARM"
        const val ACTION_REFRESH_TELEGRAM_POLLING = "ACTION_REFRESH_TELEGRAM_POLLING"
        const val ACTION_START_DRIFT_LOG = "ACTION_START_DRIFT_LOG"
        const val ACTION_STOP_DRIFT_LOG = "ACTION_STOP_DRIFT_LOG"
        const val EXTRA_RECOVERY_TRIGGER = "EXTRA_RECOVERY_TRIGGER"

        /**
         * The measurement runs inside this service because it has to survive a screen-off
         * night: the service is already foreground and already holds a partial wake lock,
         * and an eight-hour recording started from an Activity would stop at the first doze.
         */
        val driftRecorderStatus: StateFlow<EntryDriftStatus?> get() = driftRecorderState
        private val driftRecorderState = MutableStateFlow<EntryDriftStatus?>(null)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandMutex = Mutex()
    private val initialization = CompletableDeferred<Unit>()
    private var wakeLock: RenewableWakeLock? = null
    private var foregroundRunning = false
    private var telegramPolling = false
    private val recoveryStarted = AtomicBoolean(false)
    private val notificationProjection = SnapshotProjectionGate(SNAPSHOT_PROJECTION_INTERVAL_MS)
    private val persistenceProjection = SnapshotProjectionGate(SNAPSHOT_PROJECTION_INTERVAL_MS)
    private var lastServiceHeartbeatAtMs: Long? = null
    private var lastPublishedArmed: Boolean? = null
    private var currentForegroundTypes = 0
    private val foregroundNotificationPolicy = ForegroundNotificationPolicy()
    private var lastPublishedFingerprint: ForegroundNotificationFingerprint? = null

    private lateinit var preferences: EncryptedPrefsManager
    private lateinit var graph: ProtectionRuntimeGraph.Graph
    private lateinit var recoveryGate: ProtectionRecoveryGate
    private lateinit var controller: SensorServiceController
    private lateinit var telegramClient: TelegramBotClient
    private lateinit var telegramRefreshBoundary: TelegramPollingRefreshBoundary
    private lateinit var heartbeatPinger: com.example.motorcycleantitheftsensor.telegram.HeartbeatPinger
    private var driftRecorder: EntryDriftRecorder? = null
    private var driftHandlerThread: HandlerThread? = null
    private var blackBox: BlackBoxRecorder? = null
    private val clockWatcher = ClockChangeWatcher { cause -> blackBox?.noteClockChange(cause) }
    private val networkWatcher = NetworkWatcher { event, transport ->
        blackBox?.note(BreadcrumbDomain.NET, event, listOf(transport))
        if (event == BreadcrumbEvent.GAINED) flushUndeliveredIncidents()
    }
    private val permissionWatcher = PermissionWatcher(
        holds = ::holdsCapability,
        onBaseline = { held ->
            blackBox?.note(BreadcrumbDomain.PERMISSION, BreadcrumbEvent.HAVE, held)
        },
        onChanged = { event, permission ->
            blackBox?.note(BreadcrumbDomain.PERMISSION, event, listOf(permission))
        },
    )
    private val blackBoxState = BlackBoxStateMapper()

    /** Elapsed time, so a clock change cannot postpone a backlog by hours or replay it. */
    @Volatile
    private var lastIncidentFlushAtElapsedMs = 0L

    override fun onCreate() {
        super.onCreate()
        preferences = EncryptedPrefsManager(this)
        graph = ProtectionRuntimeGraph.from(this)
        controller = SensorServiceController(graph.coordinator, this)
        telegramClient = TelegramBotClient(
            prefsManager = preferences,
            commandHandler = TelegramCommandHandler(
                coordinator = graph.coordinator,
                statusFormatter = ProtectionStatusFormatter(),
                locationFinder = graph.onDemandLocationFinder,
                liveStatusReader = graph.liveStatusReader,
            ),
            onTelegramContact = graph.coordinator::recordTelegramContact,
            breadcrumb = { event, details ->
                blackBox?.note(BreadcrumbDomain.TELEGRAM, event, details)
                // A call that just succeeded is the strongest evidence there is that the path
                // works — stronger than a regained transport, which can be a captive portal.
                // It also covers the outage a connectivity callback never sees: a network that
                // was up the whole time while Telegram itself was unreachable.
                if (event == BreadcrumbEvent.OK) flushUndeliveredIncidents()
            },
        )
        telegramRefreshBoundary = TelegramPollingRefreshBoundary(
            stopAndAwait = telegramClient::stopPollingAndAwait,
            resetCursor = {
                withContext(Dispatchers.IO) {
                    preferences.commitLastTelegramUpdateId(0L)
                }
            },
            start = telegramClient::startPolling,
        )
        heartbeatPinger = com.example.motorcycleantitheftsensor.telegram.HeartbeatPinger(
            context = this,
            prefsManager = preferences,
            telegramBotClient = telegramClient,
            snapshotSupplier = { graph.coordinator.snapshot.value },
            breadcrumb = { domain, event -> blackBox?.note(domain, event) },
        )
        heartbeatPinger.startHeartbeat()
        acquireWakeLock()
        serviceScope.launch {
            // Off the main thread: the first row of the day creates the file, and onCreate is
            // not a place to touch a disk.
            withContext(Dispatchers.IO) { startBlackBox() }
            val legacyCleanup = withContext(Dispatchers.IO) {
                preferences.removeLegacyAuthenticatorState()
            }
            if (legacyCleanup == LegacyAuthenticatorMigrationResult.FAILED) {
                Log.w(TAG, "Legacy authenticator cleanup failed")
            }
            recoveryGate = ProtectionRecoveryGate(loadRecoveryState())
            serviceScope.launch {
                graph.coordinator.snapshot.collect(::handleSnapshot)
            }
            serviceScope.launch {
                graph.coordinator.snapshot.collect { snapshot ->
                    val state = blackBoxState.map(snapshot)
                    withContext(Dispatchers.IO) { blackBox?.observe(state) }
                }
            }
            serviceScope.launch {
                while (currentCoroutineContext().isActive) {
                    wakeLock?.ensureLease(SystemClock.elapsedRealtime())
                    val nowMs = System.currentTimeMillis()
                    lastServiceHeartbeatAtMs = nowMs
                    graph.coordinator.recordServiceHeartbeat(nowMs)
                    graph.coordinator.evaluateFreshness(nowMs)
                    // Holds its own interval, so this five second loop does not become a five
                    // second permission poll.
                    permissionWatcher.poll(nowMs)
                    handleSnapshot(graph.coordinator.snapshot.value)
                    delay(SERVICE_HEARTBEAT_INTERVAL_MS)
                }
            }
            // The door watch measures this phone's drift out of the samples it is already
            // reading, so an owner is never asked to leave the phone on a table for a night
            // it could have spent guarding. Subscribing costs nothing while no session is
            // armed: the flow only carries samples while an orientation source is registered.
            graph.driftMeasurementStore?.let { store ->
                serviceScope.launch {
                    EntryDriftAutoMeasure(
                        samples = { graph.runtime.entryOrientationSamples() },
                        store = store,
                        currentSourceLabel = { graph.runtime.entryOrientationSource()?.label },
                    ).collect()
                }
            }
            initialization.complete(Unit)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Diagnostic recording is deliberately kept off the protection command path: it
        // must never invalidate recovery, arm, disarm, or touch the coordinator at all.
        when (intent?.action) {
            ACTION_START_DRIFT_LOG -> {
                ensureForeground()
                startDriftRecording()
                return START_STICKY
            }
            ACTION_STOP_DRIFT_LOG -> {
                stopDriftRecording()
                return START_STICKY
            }
        }
        val refreshTelegramPolling = intent?.action == ACTION_REFRESH_TELEGRAM_POLLING
        val action = SensorServiceAction.from(intent?.action)
        if (action !in setOf(SensorServiceAction.Start, SensorServiceAction.Ignore)) {
            graph.coordinator.invalidateRecovery()
        }
        ensureForeground()
        serviceScope.launch {
            initialization.await()
            lastServiceHeartbeatAtMs = System.currentTimeMillis()
            graph.coordinator.recordServiceHeartbeat(lastServiceHeartbeatAtMs!!)
            val shouldRunRecovery = commandMutex.withLock {
                if (
                    !recoveryGate.shouldPersistSnapshot() &&
                    action in setOf(SensorServiceAction.Start, SensorServiceAction.Ignore)
                ) {
                    recoveryStarted.compareAndSet(false, true)
                } else {
                    if (action !in setOf(SensorServiceAction.Start, SensorServiceAction.Ignore)) {
                        recoveryGate.supersedeRecovery()
                    }
                    false
                }
            }
            if (shouldRunRecovery) {
                applyRecovery(
                    recoveryToken = graph.coordinator.captureRecoveryToken(),
                    trigger = intent?.getStringExtra(EXTRA_RECOVERY_TRIGGER)
                        ?.let { runCatching { RecoveryTrigger.valueOf(it) }.getOrNull() }
                        ?: RecoveryTrigger.PROCESS_RECREATION,
                )
                handleInitializedCommand(action, refreshTelegramPolling, "start")
            } else {
                handleInitializedCommand(action, refreshTelegramPolling, commandNameOf(action))
            }
        }
        return if (action == SensorServiceAction.Stop) START_NOT_STICKY else START_STICKY
    }

    private suspend fun handleInitializedCommand(
        action: SensorServiceAction,
        refreshTelegramPolling: Boolean,
        commandName: String,
    ) {
        if (refreshTelegramPolling) {
            controller.refreshTelegramPolling()
        } else {
            controller.handle(action, commandId(commandName))
        }
    }

    /** Explicit protocol command ids; never derived from enum names (Task 8 contract). */
    private fun commandNameOf(action: SensorServiceAction): String = when (action) {
        SensorServiceAction.Arm -> "arm"
        SensorServiceAction.Disarm -> "disarm"
        SensorServiceAction.Start -> "start"
        SensorServiceAction.Stop -> "stop"
        SensorServiceAction.Ignore -> "ignore"
    }

    private suspend fun applyRecovery(
        recoveryToken: RecoveryGenerationToken,
        trigger: RecoveryTrigger,
    ) {
        // An interrupted profile switch converges to disarmed/selected-target BEFORE
        // any armed-recovery decision; its durable rewrite supersedes the captured
        // pre-switch intent, so the old profile is never rearmed.
        val switchResumed = try {
            graph.coordinator.resumeProfileSwitchIfNeeded()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Unable to resume pending profile switch", error)
            false
        }
        val recoveryState = recoveryGate.capturedState
        val plan = if (switchResumed) {
            RecoveryPlan(
                initialState = ProtectionState.DISARMED_ONLINE,
                restartDetectors = false,
            )
        } else {
            ProtectionRecoveryPolicy.plan(
                persistedState = recoveryState.hints.persistedState,
                intent = recoveryState.continuityIntent,
                trigger = trigger,
                continuityValid = recoveryState.continuityValid,
            )
        }
        if (plan.previousIncidentLifecycle == IncidentLifecycle.INTERRUPTED) {
            val interrupted = try {
                withContext(Dispatchers.IO) {
                    val recovered = recoveryState.hints.lastIncidentId
                        ?.let(graph.incidents::findById)
                        ?.let { incident ->
                            val nowMs = System.currentTimeMillis()
                            incident.copy(
                                lifecycle = IncidentLifecycle.INTERRUPTED,
                                updatedAtMs = nowMs,
                                closedAtMs = nowMs,
                                closeReason = IncidentCloseReason.PROCESS_INTERRUPTED,
                            )
                        }
                    if (recovered != null) graph.incidents.upsert(recovered)
                    recovered
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                graph.coordinator.recordPersistenceFailure(PersistenceSource.INCIDENT_HISTORY)
                Log.e(TAG, "Unable to persist interrupted incident during recovery", error)
                null
            }
            interrupted?.let { incident ->
                graph.coordinator.recordPersistenceRecovered(PersistenceSource.INCIDENT_HISTORY)
                graph.coordinator.recordIncident(incident)
            }
        }
        if (!recoveryGate.shouldApplyRecovery()) return
        if (plan.restartDetectors) {
            graph.coordinator.arm(commandId("recovery-arm"), CommandOrigin.RECOVERY, recoveryToken)
        } else {
            graph.coordinator.disarm(commandId("recovery-disarm"), CommandOrigin.RECOVERY, recoveryToken)
        }
        recoveryGate.markRecoveryComplete()
        handleSnapshot(graph.coordinator.snapshot.value)
    }

    private suspend fun handleSnapshot(snapshot: ProtectionSnapshot) {
        val nowMs = System.currentTimeMillis()
        if (notificationProjection.shouldProject(snapshot, nowMs)) {
            renderNotification(snapshot)
        }
        if (!recoveryGate.shouldPersistSnapshot()) return
        if (!persistenceProjection.shouldProject(snapshot, nowMs)) return
        val armed = snapshot.state in setOf(
            ProtectionState.ARMING,
            ProtectionState.ARMED_HEALTHY,
            ProtectionState.ARMED_DEGRADED,
            ProtectionState.ALERT_ACTIVE,
        )
        try {
            val outcome = graph.statePersistence.persist(
                ProtectionPersistenceRequest(snapshot, lastServiceHeartbeatAtMs),
            )
            if (outcome == ProtectionPersistenceOutcome.COMMITTED) {
                graph.coordinator.recordPersistenceRecovered(PersistenceSource.SNAPSHOT)
            }
        } catch (error: RuntimeException) {
            graph.coordinator.recordPersistenceFailure(PersistenceSource.SNAPSHOT)
            Log.e(TAG, "Unable to persist protection snapshot", error)
        }
        if (lastPublishedArmed != armed) {
            lastPublishedArmed = armed
            publishArmState(armed)
        }
    }

    override fun ensureForeground() {
        if (foregroundRunning) return
        createNotificationChannel()
        val snapshot = graph.coordinator.snapshot.value
        val attempts = foregroundAttempts(snapshot)
        val requestedTypes = attempts.first()
        val text = notificationText(snapshot)
        val notification = createNotification(snapshot)

        val result = foregroundStartController.start(
            attempts = attempts,
            gateway = { types ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, types)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        )
        currentForegroundTypes = result.usedForegroundTypes
        // Read from the types actually granted rather than from "something was refused": a
        // microphone claim the platform declines says nothing about location.
        graph.coordinator.recordLocationForegroundRestriction(
            ForegroundServiceTypePolicy.lostLocation(requestedTypes, result.usedForegroundTypes),
        )
        lastPublishedFingerprint = ForegroundNotificationFingerprint(text, result.usedForegroundTypes)
        foregroundRunning = true
    }

    /**
     * The claims this service may make right now, best first.
     *
     * The microphone is asked for only when the permission is in hand; the audio pipeline is
     * started by the runtime under the uses that run it, and a claim made without the
     * permission is refused rather than ignored.
     */
    private fun foregroundAttempts(snapshot: ProtectionSnapshot): List<Int> =
        ForegroundServiceTypePolicy.attempts(
            sdkInt = Build.VERSION.SDK_INT,
            locationForeground = requiresLocationForeground(snapshot),
            microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )

    override suspend fun stopForegroundAndSelf() {
        try {
            graph.livePursuitCoordinator.prepareForStop()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to prepare live pursuit for stop", error)
        }
        if (recoveryGate.shouldPersistSnapshot()) {
            try {
                val outcome = graph.statePersistence.persist(
                    ProtectionPersistenceRequest(
                        graph.coordinator.snapshot.value,
                        lastServiceHeartbeatAtMs,
                    ),
                )
                if (outcome == ProtectionPersistenceOutcome.COMMITTED) {
                    graph.coordinator.recordPersistenceRecovered(PersistenceSource.SNAPSHOT)
                }
            } catch (error: RuntimeException) {
                graph.coordinator.recordPersistenceFailure(PersistenceSource.SNAPSHOT)
                Log.e(TAG, "Unable to persist final protection snapshot", error)
            }
        }
        if (foregroundRunning) stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundRunning = false
        stopSelf()
    }

    private suspend fun loadRecoveryState(): ProtectionRecoveryState = try {
        withContext(Dispatchers.IO) { graph.snapshotStore.loadForRecovery() }
    } catch (error: RuntimeException) {
        Log.e(TAG, "Unable to load protection recovery snapshot", error)
        ProtectionRecoveryState(
            liveSnapshot = ProtectionSnapshot.offline(System.currentTimeMillis()),
            hints = ProtectionRecoveryHints(
                persistedState = ProtectionState.DISARMED_ONLINE,
                lastTransitionAtMs = null,
                lastServiceHeartbeatAtMs = null,
                lastTelegramContactAtMs = null,
                lastIncidentId = null,
            ),
        )
    }

    override fun ensureTelegramPolling(): Boolean {
        if (telegramPolling) return true
        telegramPolling = telegramClient.startPolling()
        return telegramPolling
    }

    override fun stopTelegramPolling() {
        if (!telegramPolling) return
        telegramClient.stopPolling()
        telegramPolling = false
    }

    override suspend fun refreshTelegramPolling(): Boolean {
        telegramPolling = telegramRefreshBoundary.refresh()
        return telegramPolling
    }

    override fun renderNotification(snapshot: ProtectionSnapshot) {
        if (!foregroundRunning) return
        val attempts = foregroundAttempts(snapshot)
        val requestedTypes = attempts.first()
        val text = notificationText(snapshot)
        val nextFingerprint = ForegroundNotificationFingerprint(text, requestedTypes)
        if (!foregroundNotificationPolicy.shouldPublish(lastPublishedFingerprint, nextFingerprint)) {
            return
        }
        val notification = createNotification(snapshot)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && currentForegroundTypes != requestedTypes) {
            val result = foregroundStartController.start(
                attempts = attempts,
                gateway = { types ->
                    startForeground(NOTIFICATION_ID, notification, types)
                }
            )
            currentForegroundTypes = result.usedForegroundTypes
            graph.coordinator.recordLocationForegroundRestriction(
                ForegroundServiceTypePolicy.lostLocation(requestedTypes, result.usedForegroundTypes),
            )
            lastPublishedFingerprint = ForegroundNotificationFingerprint(text, result.usedForegroundTypes)
        } else {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
            lastPublishedFingerprint = nextFingerprint
        }
    }

    private val appVisibilityProvider: AppVisibilityProvider = AndroidAppVisibilityProvider()
    private val foregroundStartController = ForegroundStartController()
    private val locationForegroundPolicy = LocationForegroundPolicy()

    private fun requiresLocationForeground(snapshot: ProtectionSnapshot): Boolean {
        val needsLocation = snapshot.state in setOf(
            ProtectionState.ARMED_HEALTHY,
            ProtectionState.ARMED_DEGRADED,
            ProtectionState.ALERT_ACTIVE
        )
        if (!needsLocation) return false

        val hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasBg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        val decision = locationForegroundPolicy.evaluate(
            hasFineLocation = hasFine,
            hasCoarseLocation = hasCoarse,
            isAppInForeground = appVisibilityProvider.isAppProcessForeground(),
            hasBackgroundLocation = hasBg,
        )
        return decision.mayUseLocationType
    }

    private fun createNotification(snapshot: ProtectionSnapshot): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(notificationText(snapshot))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notificationText(snapshot: ProtectionSnapshot): String =
        PresentationTextCatalog.foregroundNotificationBody(
            state = snapshot.state,
            detail = when (snapshot.state) {
                ProtectionState.SETUP_REQUIRED -> snapshot.permissionBlockers.sorted().joinToString()
                ProtectionState.ARMING ->
                    ceil(
                        (snapshot.lastTransitionAtMs + ARMING_GRACE_MS - System.currentTimeMillis())
                            .coerceAtLeast(0L) / 1_000.0,
                    ).toInt().toString()
                ProtectionState.ARMED_DEGRADED -> snapshot.degradationReasons.sorted().joinToString()
                ProtectionState.ALERT_ACTIVE -> snapshot.lastIncident?.id.orEmpty()
                else -> ""
            },
        )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_protection),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val platformWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MotorcycleAntiTheft::SensorWakeLock",
        ).apply { setReferenceCounted(false) }
        wakeLock = RenewableWakeLock(
            handle = object : WakeLockHandle {
                override val held: Boolean get() = platformWakeLock.isHeld

                override fun acquire(timeoutMs: Long) = platformWakeLock.acquire(timeoutMs)

                override fun release() = platformWakeLock.release()
            },
            leaseDurationMs = WAKE_LOCK_LEASE_MS,
            renewBeforeExpiryMs = WAKE_LOCK_RENEW_BEFORE_MS,
        ).also { it.ensureLease(SystemClock.elapsedRealtime()) }
    }

    private fun publishArmState(isArmed: Boolean) {
        sendBroadcast(Intent(ArmStateChangedEvent.ACTION).apply {
            setPackage(packageName)
            putExtra(ArmStateChangedEvent.EXTRA_ARMED, isArmed)
        })
    }

    private fun commandId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    /**
     * Starts the minute record.
     *
     * It runs for as long as the service does, armed or not, because the value of a minute
     * row is that it is unconditional: a row that only appears while armed cannot distinguish
     * a disarmed night from a killed one, which is the distinction the record exists to make.
     */
    /**
     * Whether the app is holding one watched capability right now.
     *
     * Asked of the service rather than of the watcher so that the watcher stays free of
     * Android, and so the mapping from a capability to the permission that grants it lives in
     * one place. Notifications count as held below API 33: there was no permission to hold,
     * and reporting "revoked" for something the platform never asked about would be a lie
     * about the phone rather than a fact about it.
     */
    /**
     * The night this exists for: the phone spent eight hours out of coverage with three
     * incidents recorded and every send timing out, the network came back at 08:17, the
     * heartbeat resumed — and the three incidents stayed in the file and nowhere else,
     * because one failed attempt was all any of them ever got.
     *
     * A regained transport is the cheapest true signal that the path may work again, and this
     * service already listens for it to write a breadcrumb. Doing nothing else with it was the
     * gap. The flush is idempotent and serialized, so a flapping connection costs attempts and
     * never duplicates.
     */
    private fun flushUndeliveredIncidents() {
        if (!::graph.isInitialized) return
        // Both triggers arrive in bursts — a connectivity callback flaps, and a polling client
        // reports every successful call. The backlog lives in the repository and reading it is
        // a disk read, so a quiet interval between sweeps costs nothing: a flush skipped here
        // is a flush the next signal performs.
        val at = SystemClock.elapsedRealtime()
        if (at - lastIncidentFlushAtElapsedMs < INCIDENT_FLUSH_MIN_INTERVAL_MS) return
        lastIncidentFlushAtElapsedMs = at
        val redeliverer = graph.incidentRedeliverer
        serviceScope.launch {
            val outcome = runCatching { redeliverer.flush() }.getOrNull() ?: return@launch
            if (outcome.attempted == 0) return@launch
            if (outcome.sent > 0) {
                blackBox?.note(BreadcrumbDomain.TELEGRAM, BreadcrumbEvent.RESEND)
            }
            if (outcome.stillFailing > 0) {
                // The class is genuinely unknown here: the transport reports a boolean, and
                // inventing a cause would be the one thing the breadcrumb vocabulary forbids.
                blackBox?.note(
                    BreadcrumbDomain.TELEGRAM,
                    BreadcrumbEvent.FAILED,
                    listOf(BreadcrumbDetail.UNKNOWN),
                )
            }
        }
    }

    private fun holdsCapability(capability: BreadcrumbDetail): Boolean = when (capability) {
        BreadcrumbDetail.PERM_LOCATION ->
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        BreadcrumbDetail.PERM_MICROPHONE ->
            hasPermission(Manifest.permission.RECORD_AUDIO)
        BreadcrumbDetail.PERM_NOTIFICATION ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        BreadcrumbDetail.PERM_SMS ->
            hasPermission(Manifest.permission.SEND_SMS)
        BreadcrumbDetail.PERM_BATTERY_UNRESTRICTED ->
            (getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isIgnoringBatteryOptimizations(packageName) == true
        else -> false
    }

    private fun hasPermission(name: String): Boolean =
        checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED

    private fun startBlackBox() {
        if (blackBox != null) return
        // The graph's writer, not one of our own: the export copies the record under the
        // same lock this appends with, and a second writer would be a second lock.
        val writer = graph.blackBoxWriter ?: return
        val bootIdHash = graph.blackBoxBootIdHash
        val recorder = BlackBoxRecorder(
            writer = writer,
            elapsedMs = SystemClock::elapsedRealtime,
            wallClockMs = System::currentTimeMillis,
            sensors = graph.blackBoxSensorTap?.let { tap -> tap::drain },
            publishStateSummary = { state, elapsed ->
                AndroidBlackBoxExitSource.publishStateSummary(
                    context = applicationContext,
                    summary = BlackBoxProcessStateSummary.encode(
                        state = state,
                        elapsedMs = elapsed,
                        bootIdHash = bootIdHash,
                        // Back from the name the row carries rather than out of the snapshot
                        // again: the blob has to describe the state that was written, and a
                        // second read could catch a profile change between the two.
                        profileOrdinal = ProtectionProfile.entries
                            .firstOrNull { profile -> profile.name == state.mode }?.ordinal,
                    ),
                )
            },
        )
        blackBox = recorder
        recorder.start(blackBoxState.map(graph.coordinator.snapshot.value))
        // After start, so that anything reaching this sink can actually be written: the graph's
        // Telegram client — the one incidents go out on — has been handing crumbs to a relay
        // with nothing on the other end since the process began. This is the other end.
        // Detached in onDestroy, because the graph is a singleton and outlives this run.
        graph.breadcrumbRelay.attach { domain, event, details ->
            recorder.note(domain, event, details)
            // Same reasoning as the polling client's sink: a call that just succeeded is proof
            // the path works, and anything still undelivered should go now. The quiet interval
            // in the flush keeps a burst of sends from re-reading the history each time, and
            // stops a flush's own sends from calling it back into another flush.
            if (event == BreadcrumbEvent.OK) flushUndeliveredIncidents()
        }
        clockWatcher.start(applicationContext)
        networkWatcher.start(applicationContext)
        // After the recorder exists, so the baseline row lands under this run's `start` row
        // and describes the run it belongs to.
        permissionWatcher.baseline(System.currentTimeMillis())

        // After the start row, so the explanation of a gap sits directly beneath the row that
        // opens the run which found it.
        val described = BlackBoxExitWitness(
            writer = writer,
            source = { AndroidBlackBoxExitSource.readExits(applicationContext) },
            markStore = FileBlackBoxExitMarkStore(File(filesDir, EXIT_MARK_FILE)),
            elapsedMs = SystemClock::elapsedRealtime,
            currentBootIdHash = bootIdHash,
        ).recordNewExits()
        if (described > 0) Log.i(TAG, "Black box described $described process exit(s)")
    }

    private fun startDriftRecording() {
        if (driftRecorder?.isRecording == true) return
        val thread = driftHandlerThread ?: HandlerThread("EntryDriftRecorder").apply { start() }
        driftHandlerThread = thread
        val recorder = driftRecorder ?: EntryDriftRecorder(
            context = applicationContext,
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager,
            handler = Handler(thread.looper),
            // The measurement outlives the recording: it is what decides whether this phone's
            // door watch may be offered at all, and for how long a session. The graph's store
            // is shared rather than a second one built here — it holds the encrypted
            // preferences open, and opening that file again by name reads ciphertext.
            measurementStore = graph.driftMeasurementStore,
        ).also { created ->
            driftRecorder = created
            serviceScope.launch {
                created.status.collect { driftRecorderState.value = it }
            }
        }
        if (!recorder.start()) {
            Log.w(TAG, "Drift recording could not start: no orientation source")
        }
    }

    /**
     * Stops the recording but keeps its thread. The recorder holds a Handler bound to that
     * looper for the life of the object, so quitting the thread here left a reused recorder
     * registered against a dead looper: it reported itself as recording and never received
     * a sample. The thread costs nothing while idle and is released in onDestroy.
     */
    private fun stopDriftRecording() {
        driftRecorder?.stop()
    }

    override fun onDestroy() {
        try {
            // Written before anything else is torn down. This row is the only thing that
            // tells a reader the service was stopped rather than killed, and every minute
            // gap in the file is read against it.
            clockWatcher.stop(applicationContext)
            networkWatcher.stop()
            // Before the recorder closes: a crumb arriving after `stop` would be a line about
            // this run written into whatever run comes next.
            graph.breadcrumbRelay.detach()
            blackBox?.stop()
            blackBox = null
            stopDriftRecording()
            driftHandlerThread?.quitSafely()
            driftHandlerThread = null
            if (::heartbeatPinger.isInitialized) {
                heartbeatPinger.stopHeartbeat()
            }
            graph.livePursuitCoordinator.abortLocal()
            serviceScope.cancel()
            graph.runtime.stopDetectors()
            graph.runtime.stopPowerStatusMonitoring()
            stopTelegramPolling()
            graph.coordinator.recordServiceStopped()
        } catch (e: Exception) {
            Log.w(TAG, "Exception during SensorService cleanup", e)
        } finally {
            wakeLock?.release()
            wakeLock = null
            super.onDestroy()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

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
import com.example.motorcycleantitheftsensor.location.ForegroundStartController
import com.example.motorcycleantitheftsensor.protection.BlackBoxHeader
import com.example.motorcycleantitheftsensor.protection.BlackBoxRecorder
import com.example.motorcycleantitheftsensor.protection.BlackBoxStateMapper
import com.example.motorcycleantitheftsensor.protection.BlackBoxWriter
import com.example.motorcycleantitheftsensor.protection.CommandOrigin
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.PersistenceSource
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionRecoveryPolicy
import com.example.motorcycleantitheftsensor.protection.ProtectionRecoveryGate
import com.example.motorcycleantitheftsensor.protection.ProtectionRecoveryHints
import com.example.motorcycleantitheftsensor.protection.ProtectionPersistenceOutcome
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
    private val blackBoxState = BlackBoxStateMapper()

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
            ),
            onTelegramContact = graph.coordinator::recordTelegramContact,
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
                    handleSnapshot(graph.coordinator.snapshot.value)
                    delay(SERVICE_HEARTBEAT_INTERVAL_MS)
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
                                closeReason = "process interrupted",
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
        val requestedTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (requiresLocationForeground(snapshot)) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
        } else {
            0
        }
        val specialUseOnly = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        val text = notificationText(snapshot)
        val notification = createNotification(snapshot)

        val result = foregroundStartController.start(
            requestedTypes = requestedTypes,
            specialUseOnlyTypes = specialUseOnly,
            gateway = { types ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, types)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        )
        currentForegroundTypes = result.usedForegroundTypes
        graph.coordinator.recordLocationForegroundRestriction(result.degradedReason != null)
        lastPublishedFingerprint = ForegroundNotificationFingerprint(text, result.usedForegroundTypes)
        foregroundRunning = true
    }

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
        val requestedTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (requiresLocationForeground(snapshot)) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
        } else {
            0
        }
        val specialUseOnly = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        val text = notificationText(snapshot)
        val nextFingerprint = ForegroundNotificationFingerprint(text, requestedTypes)
        if (!foregroundNotificationPolicy.shouldPublish(lastPublishedFingerprint, nextFingerprint)) {
            return
        }
        val notification = createNotification(snapshot)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && currentForegroundTypes != requestedTypes) {
            val result = foregroundStartController.start(
                requestedTypes = requestedTypes,
                specialUseOnlyTypes = specialUseOnly,
                gateway = { types ->
                    startForeground(NOTIFICATION_ID, notification, types)
                }
            )
            currentForegroundTypes = result.usedForegroundTypes
            graph.coordinator.recordLocationForegroundRestriction(result.degradedReason != null)
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
    private fun startBlackBox() {
        if (blackBox != null) return
        val wallMs = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val recorder = BlackBoxRecorder(
            writer = BlackBoxWriter(
                directory = File(filesDir, BlackBoxWriter.DIRECTORY),
                header = BlackBoxHeader(
                    device = "${Build.MANUFACTURER}/${Build.MODEL}",
                    androidSdk = Build.VERSION.SDK_INT,
                    appVersion = appVersionName(),
                    // The boot, not the process: rows on either side of a kill carry the same
                    // id, which is what separates "the app was killed" from "the phone rebooted".
                    bootId = ((wallMs - elapsed) / 1_000L).toString(),
                    wallAnchorMs = wallMs,
                    elapsedAtAnchorMs = elapsed,
                    sensors = blackBoxSensorInventory(),
                ),
                wallClockMs = System::currentTimeMillis,
            ),
            elapsedMs = SystemClock::elapsedRealtime,
            wallClockMs = System::currentTimeMillis,
            sensors = graph.blackBoxSensorTap?.let { tap -> tap::drain },
        )
        blackBox = recorder
        recorder.start(blackBoxState.map(graph.coordinator.snapshot.value))
    }

    /**
     * The cover page of the file: raw numbers with no statement of which sensor produced them,
     * at what resolution, cannot be interpreted afterwards by anyone, including us.
     */
    private fun blackBoxSensorInventory(): String =
        graph.sensorCatalog?.descriptors().orEmpty()
            .filterValues { descriptor -> descriptor.isAvailable }
            .entries
            .joinToString(";") { (source, descriptor) ->
                "${source.name}:${descriptor.name}:${descriptor.vendor}:${descriptor.resolution}"
            }

    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "unknown"

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

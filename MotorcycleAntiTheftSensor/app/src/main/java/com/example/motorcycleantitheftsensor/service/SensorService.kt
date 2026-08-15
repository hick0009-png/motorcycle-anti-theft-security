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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.motorcycleantitheftsensor.MainActivity
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.data.LegacyAuthenticatorMigrationResult
import com.example.motorcycleantitheftsensor.data.removeLegacyAuthenticatorState
import com.example.motorcycleantitheftsensor.location.AndroidAppVisibilityProvider
import com.example.motorcycleantitheftsensor.location.AppVisibilityProvider
import com.example.motorcycleantitheftsensor.location.ForegroundStartController
import com.example.motorcycleantitheftsensor.protection.CommandOrigin
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.PersistenceSource
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
import com.example.motorcycleantitheftsensor.protection.SnapshotProjectionGate
import com.example.motorcycleantitheftsensor.telegram.TelegramBotClient
import com.example.motorcycleantitheftsensor.telegram.ProtectionStatusFormatter
import com.example.motorcycleantitheftsensor.telegram.TelegramCommandHandler
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
        acquireWakeLock()
        serviceScope.launch {
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
                applyRecovery(graph.coordinator.captureRecoveryToken())
                handleInitializedCommand(action, refreshTelegramPolling, "start")
            } else {
                handleInitializedCommand(action, refreshTelegramPolling, action.name.lowercase())
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

    private suspend fun applyRecovery(
        recoveryToken: RecoveryGenerationToken,
    ) {
        val recoveryState = recoveryGate.capturedState
        val plan = ProtectionRecoveryPolicy.plan(recoveryState.hints.persistedState)
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
            .setContentTitle("Motorcycle Guard")
            .setContentText(notificationText(snapshot))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notificationText(snapshot: ProtectionSnapshot): String = when (snapshot.state) {
        ProtectionState.SETUP_REQUIRED -> "Setup required: ${snapshot.permissionBlockers.sorted().joinToString()}"
        ProtectionState.DISARMED_ONLINE -> "Disarmed — remote control online"
        ProtectionState.ARMING -> {
            val remainingMs = (snapshot.lastTransitionAtMs + ARMING_GRACE_MS - System.currentTimeMillis())
                .coerceAtLeast(0L)
            "Arming — ${ceil(remainingMs / 1_000.0).toInt()}s; calibrating sensors"
        }
        ProtectionState.ARMED_HEALTHY -> "Armed — all required protection healthy"
        ProtectionState.ARMED_DEGRADED -> "Armed — degraded: ${snapshot.degradationReasons.sorted().joinToString()}"
        ProtectionState.ALERT_ACTIVE -> "Alert active: ${snapshot.lastIncident?.id ?: "incident pending"}"
        ProtectionState.OFFLINE -> "Protection service offline"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Motorcycle Guard Protection",
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

    override fun onDestroy() {
        graph.livePursuitCoordinator.abortLocal()
        serviceScope.cancel()
        graph.runtime.stopDetectors()
        stopTelegramPolling()
        graph.coordinator.recordServiceStopped()
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

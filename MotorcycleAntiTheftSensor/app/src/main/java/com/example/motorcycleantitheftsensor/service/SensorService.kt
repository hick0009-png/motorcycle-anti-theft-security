package com.example.motorcycleantitheftsensor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.motorcycleantitheftsensor.MainActivity
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.protection.CommandOrigin
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.ProtectionRecoveryPolicy
import com.example.motorcycleantitheftsensor.protection.ProtectionRecoveryGate
import com.example.motorcycleantitheftsensor.protection.ProtectionRecoveryHints
import com.example.motorcycleantitheftsensor.protection.ProtectionRuntimeGraph
import com.example.motorcycleantitheftsensor.protection.ProtectionRecoveryState
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.security.TotpAuthenticator
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
        private const val CHANNEL_ID = "anti_theft_sensor_service_channel"
        private const val SERVICE_HEARTBEAT_INTERVAL_MS = 5_000L
        private const val ARMING_GRACE_MS = 10_000L
        private const val TAG = "SensorService"
        const val ACTION_START_SERVICE = "ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_ARM = "ACTION_ARM"
        const val ACTION_DISARM = "ACTION_DISARM"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandMutex = Mutex()
    private val initialization = CompletableDeferred<Unit>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundRunning = false
    private var telegramPolling = false
    private val recoveryStarted = AtomicBoolean(false)
    private var lastServiceHeartbeatAtMs: Long? = null
    private var lastPublishedArmed: Boolean? = null

    private lateinit var preferences: EncryptedPrefsManager
    private lateinit var graph: ProtectionRuntimeGraph.Graph
    private lateinit var recoveryGate: ProtectionRecoveryGate
    private lateinit var controller: SensorServiceController
    private lateinit var telegramClient: TelegramBotClient

    override fun onCreate() {
        super.onCreate()
        preferences = EncryptedPrefsManager(this)
        graph = ProtectionRuntimeGraph.from(this)
        controller = SensorServiceController(graph.coordinator, this)
        telegramClient = TelegramBotClient(
            context = this,
            prefsManager = preferences,
            totpAuthenticator = TotpAuthenticator(preferences),
            commandHandler = TelegramCommandHandler(
                coordinator = graph.coordinator,
                statusFormatter = ProtectionStatusFormatter(),
            ),
            onTelegramContact = graph.coordinator::recordTelegramContact,
        )
        acquireWakeLock()
        serviceScope.launch {
            recoveryGate = ProtectionRecoveryGate(loadRecoveryState())
            serviceScope.launch {
                graph.coordinator.snapshot.collect(::handleSnapshot)
            }
            serviceScope.launch {
                while (currentCoroutineContext().isActive) {
                    val nowMs = System.currentTimeMillis()
                    lastServiceHeartbeatAtMs = nowMs
                    graph.coordinator.recordServiceHeartbeat(nowMs)
                    graph.coordinator.evaluateFreshness(nowMs)
                    renderNotification(graph.coordinator.snapshot.value)
                    delay(SERVICE_HEARTBEAT_INTERVAL_MS)
                }
            }
            initialization.complete(Unit)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = SensorServiceAction.from(intent?.action)
        ensureForeground()
        serviceScope.launch {
            initialization.await()
            lastServiceHeartbeatAtMs = System.currentTimeMillis()
            graph.coordinator.recordServiceHeartbeat(lastServiceHeartbeatAtMs!!)
            commandMutex.withLock {
                if (
                    !recoveryGate.shouldPersistSnapshot() &&
                    action in setOf(SensorServiceAction.Start, SensorServiceAction.Ignore)
                ) {
                    if (recoveryStarted.compareAndSet(false, true)) {
                        applyRecovery()
                        controller.handle(action, commandId("start"))
                    }
                } else {
                    recoveryGate.markRecoveryComplete()
                    controller.handle(action, commandId(action.name.lowercase()))
                }
            }
        }
        return if (action == SensorServiceAction.Stop) START_NOT_STICKY else START_STICKY
    }

    private suspend fun applyRecovery() {
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
                Log.e(TAG, "Unable to persist interrupted incident during recovery", error)
                null
            }
            interrupted?.let(graph.coordinator::recordIncident)
        }
        if (plan.restartDetectors) {
            graph.coordinator.arm(commandId("recovery-arm"), CommandOrigin.RECOVERY)
        } else {
            graph.coordinator.disarm(commandId("recovery-disarm"), CommandOrigin.RECOVERY)
        }
        recoveryGate.markRecoveryComplete()
        handleSnapshot(graph.coordinator.snapshot.value)
    }

    private suspend fun handleSnapshot(snapshot: ProtectionSnapshot) {
        renderNotification(snapshot)
        if (!recoveryGate.shouldPersistSnapshot()) return
        val armed = snapshot.state in setOf(
            ProtectionState.ARMING,
            ProtectionState.ARMED_HEALTHY,
            ProtectionState.ARMED_DEGRADED,
            ProtectionState.ALERT_ACTIVE,
        )
        try {
            withContext(Dispatchers.IO) {
                preferences.setSystemArmed(armed)
                graph.snapshotStore.save(snapshot, lastServiceHeartbeatAtMs)
            }
        } catch (error: RuntimeException) {
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
        startForeground(NOTIFICATION_ID, createNotification(graph.coordinator.snapshot.value))
        foregroundRunning = true
    }

    override suspend fun stopForegroundAndSelf() {
        if (recoveryGate.shouldPersistSnapshot()) {
            try {
                withContext(Dispatchers.IO) {
                    graph.snapshotStore.save(graph.coordinator.snapshot.value, lastServiceHeartbeatAtMs)
                }
            } catch (error: RuntimeException) {
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
                demoModeEnabled = false,
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

    override fun renderNotification(snapshot: ProtectionSnapshot) {
        if (!foregroundRunning) return
        getSystemService(NotificationManager::class.java)?.notify(
            NOTIFICATION_ID,
            createNotification(snapshot),
        )
    }

    private fun createNotification(snapshot: ProtectionSnapshot): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val demoPrefix = if (snapshot.demoModeEnabled) "DEMO — " else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Motorcycle Guard")
            .setContentText(demoPrefix + notificationText(snapshot))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
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
            NotificationManager.IMPORTANCE_HIGH,
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MotorcycleAntiTheft::SensorWakeLock",
        ).apply {
            acquire(24 * 60 * 60 * 1_000L)
        }
    }

    private fun publishArmState(isArmed: Boolean) {
        sendBroadcast(Intent(ArmStateChangedEvent.ACTION).apply {
            setPackage(packageName)
            putExtra(ArmStateChangedEvent.EXTRA_ARMED, isArmed)
        })
    }

    private fun commandId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    override fun onDestroy() {
        serviceScope.cancel()
        graph.runtime.stopDetectors()
        stopTelegramPolling()
        graph.coordinator.recordServiceStopped()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

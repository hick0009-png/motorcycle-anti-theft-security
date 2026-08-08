package com.example.motorcycleantitheftsensor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.motorcycleantitheftsensor.MainActivity
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.sensor.AudioPeakDetector
import com.example.motorcycleantitheftsensor.sensor.LightIntrusionDetector
import com.example.motorcycleantitheftsensor.sensor.PowerThermalMonitor
import com.example.motorcycleantitheftsensor.sensor.VibrationDetector
import com.example.motorcycleantitheftsensor.security.TotpAuthenticator
import com.example.motorcycleantitheftsensor.telegram.RemoteCommand
import com.example.motorcycleantitheftsensor.telegram.TelegramBotClient
import com.example.motorcycleantitheftsensor.telephony.SmsFallbackManager

/**
 * SVC-01: SensorService
 * 24/7 Foreground Service holding a CPU WakeLock and orchestrating the Multi-Sensor Fusion Engine.
 */
class SensorService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "anti_theft_sensor_service_channel"
        const val ACTION_START_SERVICE = "ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_ARM = "ACTION_ARM"
        const val ACTION_DISARM = "ACTION_DISARM"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var prefsManager: EncryptedPrefsManager
    private lateinit var vibrationDetector: VibrationDetector
    private lateinit var lightIntrusionDetector: LightIntrusionDetector
    private lateinit var powerThermalMonitor: PowerThermalMonitor
    private lateinit var audioPeakDetector: AudioPeakDetector
    private lateinit var telegramClient: TelegramBotClient
    private lateinit var alertDispatcher: AlertDispatcher

    override fun onCreate() {
        super.onCreate()
        prefsManager = EncryptedPrefsManager(this)
        telegramClient = TelegramBotClient(this, prefsManager, TotpAuthenticator(prefsManager)) { command ->
            when (command) {
                RemoteCommand.Arm -> handleArm()
                is RemoteCommand.Disarm -> handleDisarm()
                is RemoteCommand.Sensitivity -> command.level?.let(prefsManager::setSensitivity)
                else -> Unit
            }
        }
        alertDispatcher = AlertDispatcher(
            telegram = AlertTransport { message -> telegramClient.sendTelegramAlert(message) },
            sms = AlertTransport { message ->
                val destination = prefsManager.getSmsDestination()
                if (destination.isNullOrBlank() || prefsManager.getSmsAesKey().isNullOrBlank()) {
                    false
                } else {
                    try {
                        SmsFallbackManager(this, prefsManager)
                            .sendEncryptedSmsAlert(destination, "SECURITY_ALERT", message)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }
            },
            smsConfigured = !prefsManager.getSmsDestination().isNullOrBlank() &&
                !prefsManager.getSmsAesKey().isNullOrBlank()
        )

        acquireWakeLock()
        initSensors()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MotorcycleAntiTheft::SensorWakeLock"
        ).apply {
            acquire(24 * 60 * 60 * 1000L) // 24 hours
        }
    }

    private fun initSensors() {
        vibrationDetector = VibrationDetector(this) { magnitude ->
            handleAlertTrigger("VIBRATION", "Vibration detected! Magnitude: %.2f m/s²".format(magnitude))
        }

        lightIntrusionDetector = LightIntrusionDetector(this) { lux ->
            handleAlertTrigger("LIGHT_INTRUSION", "Under-seat light detected! Ambient lux: %.1f".format(lux))
        }

        powerThermalMonitor = PowerThermalMonitor(
            this,
            onChargerDisconnected = {
                handleAlertTrigger("POWER_DISCONNECTED", "External charger wire disconnected!")
            },
            onThermalWarning = { tempC ->
                handleAlertTrigger("THERMAL_WARNING", "Critical battery temperature: %.1f°C! Power cutoff initiated.".format(tempC))
            }
        )

        audioPeakDetector = AudioPeakDetector(this) { db ->
            handleAlertTrigger("AUDIO_PEAK", "High decibel noise detected! Level: %.1f dB".format(db))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (SensorServiceAction.from(intent?.action)) {
            SensorServiceAction.Disarm,
            SensorServiceAction.Stop -> {
                handleDisarm()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            SensorServiceAction.Arm -> handleArm()
            SensorServiceAction.Start,
            SensorServiceAction.Ignore -> Unit
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        startSensors()
        telegramClient.startPolling()
        return START_STICKY
    }

    private fun handleArm() {
        if (!hasProtectionPermissions()) {
            prefsManager.setSystemArmed(false)
            return
        }
        prefsManager.setSystemArmed(true)
        startSensors()
    }

    private fun hasProtectionPermissions(): Boolean {
        val microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return microphoneGranted && notificationsGranted
    }

    private fun handleDisarm() {
        prefsManager.setSystemArmed(false)
        stopSensors()
        telegramClient.stopPolling()
    }

    private fun startSensors() {
        if (prefsManager.isSystemArmed()) {
            val sensitivity = prefsManager.getSensitivity()
            vibrationDetector.startListening(sensitivity)
            lightIntrusionDetector.startListening()
            powerThermalMonitor.startMonitoring()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                audioPeakDetector.startListening()
            }
        }
    }

    private fun stopSensors() {
        vibrationDetector.stopListening()
        lightIntrusionDetector.stopListening()
        powerThermalMonitor.stopMonitoring()
        audioPeakDetector.stopListening()
    }

    private fun handleAlertTrigger(alertType: String, message: String) {
        val event = AlertEvent(alertType, message, System.currentTimeMillis())
        Thread {
            alertDispatcher.dispatch(event)
        }.start()
        val broadcastIntent = Intent("com.example.motorcycleantitheftsensor.ALARM_EVENT").apply {
            setPackage(packageName)
            putExtra("ALERT_TYPE", alertType)
            putExtra("ALERT_MESSAGE", message)
            putExtra("TIMESTAMP", event.timestampMs)
        }
        sendBroadcast(broadcastIntent)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ Motorcycle Anti-Theft Active")
            .setContentText("Multi-Sensor Engine running 24/7 in background.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Motorcycle Anti-Theft Protection Service",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopSensors()
        telegramClient.stopPolling()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

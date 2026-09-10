package com.example.motorcycleantitheftsensor.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.motorcycleantitheftsensor.R
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import kotlin.math.sqrt

/**
 * Pre-unlock, local-only protection bridge. It never constructs the normal runtime graph and
 * therefore cannot access encrypted preferences or send Telegram/SMS while credential storage is locked.
 */
class DirectBootBootstrapService : Service(), SensorEventListener {
    private var sensorManager: SensorManager? = null
    private var baselineMagnitude: Float? = null
    private var movementDetected = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var handoffStarted = false
    private val unlockCheck = object : Runnable {
        override fun run() {
            if (handoffStarted) return
            val userUnlocked = getSystemService(UserManager::class.java)?.isUserUnlocked == true
            if (DirectBootBootstrapPolicy.shouldHandoffToFullRecovery(userUnlocked)) {
                handoffStarted = startFullRecovery()
                if (handoffStarted) {
                    stopSelf()
                    return
                }
            }
            mainHandler.postDelayed(this, UNLOCK_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification(movementDetected = false))
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { accelerometer ->
            sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        }
        mainHandler.post(unlockCheck)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (movementDetected || event.sensor.type != Sensor.TYPE_ACCELEROMETER || event.values.size < 3) return
        val magnitude = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2],
        )
        val baseline = baselineMagnitude
        if (baseline == null) {
            baselineMagnitude = magnitude
            return
        }
        if (DirectBootMovementPolicy.isMovement(kotlin.math.abs(magnitude - baseline))) {
            movementDetected = true
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification(movementDetected = true))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacks(unlockCheck)
        sensorManager?.unregisterListener(this)
        sensorManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startFullRecovery(): Boolean = try {
        val serviceIntent = Intent(this, SensorService::class.java).apply {
            action = SensorService.ACTION_START_SERVICE
            putExtra(SensorService.EXTRA_RECOVERY_TRIGGER, "ANDROID_USER_UNLOCKED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        true
    } catch (error: RuntimeException) {
        Log.e(TAG, "Unable to hand off direct-boot recovery after unlock", error)
        false
    }

    private fun notification(movementDetected: Boolean) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setContentTitle(PresentationTextCatalog.NOTIFICATION_TITLE)
        .setContentText(
            if (movementDetected) {
                PresentationTextCatalog.DIRECT_BOOT_MOVEMENT_BODY
            } else {
                PresentationTextCatalog.DIRECT_BOOT_WAITING_BODY
            },
        )
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            PresentationTextCatalog.DIRECT_BOOT_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.example.motorcycleantitheftsensor.action.STOP_DIRECT_BOOT_BOOTSTRAP"
        private const val CHANNEL_ID = "anti_theft_direct_boot"
        private const val NOTIFICATION_ID = 1002
        private const val UNLOCK_CHECK_INTERVAL_MS = 2_000L
        private const val TAG = "DirectBootBootstrap"
    }
}

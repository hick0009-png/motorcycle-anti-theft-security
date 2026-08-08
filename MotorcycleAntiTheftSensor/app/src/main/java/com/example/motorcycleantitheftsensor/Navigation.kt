package com.example.motorcycleantitheftsensor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.security.TotpAuthenticator
import com.example.motorcycleantitheftsensor.security.ProtectionPermissionPolicy
import com.example.motorcycleantitheftsensor.security.PairingCodePolicy
import com.example.motorcycleantitheftsensor.sensor.PowerThermalMonitor
import com.example.motorcycleantitheftsensor.sensor.SensorScanner
import com.example.motorcycleantitheftsensor.service.SensorService
import com.example.motorcycleantitheftsensor.telegram.TelegramBotClient
import com.example.motorcycleantitheftsensor.ui.DashboardScreen
import androidx.core.content.ContextCompat

@Composable
fun MainNavigation() {
    val context = LocalContext.current
    val prefsManager = remember { EncryptedPrefsManager(context) }
    val totpAuth = remember { TotpAuthenticator(prefsManager) }
    val telegramClient = remember { TelegramBotClient(context, prefsManager, totpAuth) }
    val powerThermalMonitor = remember { PowerThermalMonitor(context) { } }

    // Dynamic Hardware Sensor Discovery
    val hardwareSensors = remember { SensorScanner.scanHardwareSensors(context) }
    val requiredPermissions = remember { ProtectionPermissionPolicy.requiredPermissions(Build.VERSION.SDK_INT) }
    var missingPermissions by remember {
        mutableStateOf(requiredPermissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toSet())
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) {
        missingPermissions = requiredPermissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toSet()
    }

    var isArmed by remember { mutableStateOf(prefsManager.isSystemArmed()) }
    var sensitivity by remember { mutableIntStateOf(prefsManager.getSensitivity()) }
    var totpSecret by remember { mutableStateOf(prefsManager.getTotpSeed()) }
    var totpUri by remember { mutableStateOf<String?>(null) }
    var totpStatusMessage by remember {
        mutableStateOf(
            if (prefsManager.getTotpSeed() != null) "✅ Auth Configured (Google Authenticator)" else null
        )
    }
    var batteryTemp by remember { mutableFloatStateOf(powerThermalMonitor.getCurrentBatteryTemperature()) }
    var currentLux by remember { mutableFloatStateOf(0.0f) }
    var activeAlarmMessage by remember { mutableStateOf<String?>(null) }
    var botUsername by remember { mutableStateOf<String?>(null) }
    var botId by remember { mutableStateOf<String?>(null) }
    var allowedChatIds by remember { mutableStateOf(prefsManager.getAllowedChatIds()) }
    var pairingCode by remember { mutableStateOf(prefsManager.getPairingCode()?.value) }

    fun startControlService() {
        val serviceIntent = Intent(context, SensorService::class.java).apply {
            action = SensorService.ACTION_START_SERVICE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    LaunchedEffect(missingPermissions) {
        if (missingPermissions.isNotEmpty() && prefsManager.isSystemArmed()) {
            prefsManager.setSystemArmed(false)
            isArmed = false
        }
    }

    // Auto-Start Telegram Polling & Verify Bot Identity on App Launch
    LaunchedEffect(Unit) {
        if (allowedChatIds.isEmpty() && pairingCode == null) {
            pairingCode = prefsManager.createPairingCode(PairingCodePolicy()).value
        }
        val savedToken = prefsManager.getBotToken()
        if (!savedToken.isNullOrBlank()) {
            startControlService()
            telegramClient.verifyBotToken(savedToken) { isValid, uname, bId ->
                if (isValid) {
                    botUsername = uname
                    botId = bId
                }
            }
        }
    }

    // Register Real-Time ALARM_EVENT & SYSTEM_DISARM BroadcastReceiver
    DisposableEffect(Unit) {
        val alarmReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    "com.example.motorcycleantitheftsensor.ALARM_EVENT" -> {
                        val alertType = intent.getStringExtra("ALERT_TYPE") ?: "ALARM"
                        val alertMsg = intent.getStringExtra("ALERT_MESSAGE") ?: "Security breach detected!"
                        activeAlarmMessage = "[$alertType] $alertMsg"
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction("com.example.motorcycleantitheftsensor.ALARM_EVENT")
        }
        ContextCompat.registerReceiver(context, alarmReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        onDispose {
            try {
                context.unregisterReceiver(alarmReceiver)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Register Real-Time Ambient Light Sensor (Lux) Listener
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
                    currentLux = event.values[0]
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (lightSensor != null) {
            sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_UI)
        }

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    DashboardScreen(
        isArmed = isArmed,
        sensitivity = sensitivity,
        batteryTemp = batteryTemp,
        currentLux = currentLux,
        hardwareSensors = hardwareSensors,
        totpSecret = totpSecret,
        totpUri = totpUri,
        totpStatusMessage = totpStatusMessage,
        initialBotToken = prefsManager.getBotToken(),
        botUsername = botUsername,
        botId = botId,
        allowedChatIds = allowedChatIds,
        pairingCode = pairingCode,
        activeAlarmMessage = activeAlarmMessage,
        onDismissAlarm = { activeAlarmMessage = null },
        onToggleArm = {

            val newState = !isArmed
            if (newState && missingPermissions.isNotEmpty()) {
                permissionLauncher.launch(missingPermissions.toTypedArray())
            } else {
                prefsManager.setSystemArmed(newState)
                isArmed = newState

                val serviceIntent = Intent(context, SensorService::class.java).apply {
                    action = if (newState) SensorService.ACTION_ARM else SensorService.ACTION_DISARM
                }

                if (newState) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } else {
                    context.startService(serviceIntent)
                }
            }
        },
        onSensitivityChange = { newSens ->
            sensitivity = newSens
            prefsManager.setSensitivity(newSens)
        },
        onSaveToken = { token ->
            if (token.isNotBlank()) {
                prefsManager.saveBotToken(token.trim())
                startControlService()
                telegramClient.verifyBotToken(token.trim()) { isValid, uname, bId ->
                    if (isValid) {
                        botUsername = uname
                        botId = bId
                    }
                }
            }
        },
        onVerifyBot = { inputToken ->
            val tokenToVerify = inputToken.ifBlank { prefsManager.getBotToken() ?: "" }
            if (tokenToVerify.isNotBlank()) {
                prefsManager.saveBotToken(tokenToVerify.trim())
                startControlService()
                telegramClient.verifyBotToken(tokenToVerify) { isValid, uname, bId ->
                    if (isValid) {
                        botUsername = uname
                        botId = bId
                    }
                }
            }
            allowedChatIds = prefsManager.getAllowedChatIds()
        },
        onSendTestNotification = {
            telegramClient.sendTestAlertToOwners { success ->
                allowedChatIds = prefsManager.getAllowedChatIds()
            }
        },
        onSetupTotp = {
            totpUri = totpAuth.setupNewTotpSeed()
            totpSecret = prefsManager.getTotpSeed()
        },
        onVerifyTotpCode = { inputCode ->
            val result = totpAuth.verifyCode(inputCode)
            if (result == TotpAuthenticator.VerificationResult.SUCCESS) {
                totpStatusMessage = "✅ Auth Verified & Configured Successfully!"
                true
            } else {
                false
            }
        }
    )
}

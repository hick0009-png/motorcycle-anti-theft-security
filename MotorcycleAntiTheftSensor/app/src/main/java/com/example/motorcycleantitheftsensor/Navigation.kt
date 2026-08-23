package com.example.motorcycleantitheftsensor

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.protection.ProtectionRuntimeGraph
import com.example.motorcycleantitheftsensor.security.PairingCodePolicy
import com.example.motorcycleantitheftsensor.security.ProtectionPermissionPolicy
import com.example.motorcycleantitheftsensor.service.SensorService
import com.example.motorcycleantitheftsensor.telegram.TelegramBotClient
import com.example.motorcycleantitheftsensor.ui.AndroidProtectionSettingsGateway
import com.example.motorcycleantitheftsensor.ui.ProtectionAppActions
import com.example.motorcycleantitheftsensor.ui.ProtectionAppScreen
import com.example.motorcycleantitheftsensor.ui.ProtectionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MainNavigation(
    onSecureFlagChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val preferences = remember(applicationContext) { EncryptedPrefsManager(applicationContext) }
    val telegram = remember(applicationContext, preferences) {
        TelegramBotClient(preferences)
    }
    DisposableEffect(telegram) {
        onDispose {
            telegram.stopPolling()
        }
    }
    val graph = remember(applicationContext) { ProtectionRuntimeGraph.from(applicationContext) }
    val pairingCodePolicy = remember { PairingCodePolicy() }
    val startControlService: () -> Unit = remember(applicationContext) {
        {
            val intent = Intent(applicationContext, SensorService::class.java).apply {
                action = SensorService.ACTION_START_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
        }
    }
    val refreshControlService: () -> Unit = remember(applicationContext) {
        {
            val intent = Intent(applicationContext, SensorService::class.java).apply {
                action = SensorService.ACTION_REFRESH_TELEGRAM_POLLING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
        }
    }
    val settingsGateway = remember(preferences, telegram, pairingCodePolicy, refreshControlService, graph.sensorRepository) {
        AndroidProtectionSettingsGateway(
            preferences = preferences,
            telegram = telegram,
            pairingCodePolicy = pairingCodePolicy,
            refreshControlService = refreshControlService,
            sensorConfigRepository = graph.sensorRepository,
        )
    }
    val managedPermissions = remember {
        ProtectionPermissionPolicy.requiredPermissions(Build.VERSION.SDK_INT) +
            ProtectionPermissionPolicy.optionalPermissions()
    }
    fun currentMissingPermissions(): Set<String> = managedPermissions
        .filterTo(mutableSetOf()) { permission ->
            context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }
    var missingPermissions by remember { mutableStateOf(currentMissingPermissions()) }
    val protectionViewModel: ProtectionViewModel = viewModel {
        ProtectionViewModel(
            coordinator = graph.coordinator,
            incidents = graph.incidents,
            settings = settingsGateway,
            profileRepository = graph.profileRepository,
            initialMissingPermissions = missingPermissions,
        )
    }
    val uiState by protectionViewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        missingPermissions = currentMissingPermissions()
        protectionViewModel.updateMissingPermissions(missingPermissions)
    }

    LaunchedEffect(preferences, startControlService) {
        val tokenConfigured = withContext(Dispatchers.IO) {
            !preferences.getBotToken().isNullOrBlank()
        }
        if (tokenConfigured) startControlService()
    }

    ProtectionAppScreen(
        state = uiState,
        actions = ProtectionAppActions(
            selectDestination = protectionViewModel::selectDestination,
            arm = protectionViewModel::arm,
            disarm = protectionViewModel::disarm,
            clearHistory = protectionViewModel::clearHistory,
            changeSensitivity = protectionViewModel::changeSensitivity,
            requestPermissions = { permissionLauncher.launch(missingPermissions.toTypedArray()) },
            replaceBotToken = protectionViewModel::replaceBotToken,
            configureSmsFallback = protectionViewModel::configureSmsFallback,
            retry = protectionViewModel::retry,
            retrySettings = protectionViewModel::retrySettings,
            resetPairing = protectionViewModel::resetPairing,
            consumeMessage = protectionViewModel::consumeMessage,
            updateSensorConfiguration = protectionViewModel::updateSensorConfiguration,
            applySensorPreset = protectionViewModel::applySensorPreset,
            onSecureFlagChange = onSecureFlagChange,
            selectProfile = protectionViewModel::selectProfile,
            confirmProfileSwitch = protectionViewModel::confirmProfileSwitch,
            cancelProfileSwitch = protectionViewModel::cancelProfileSwitch,
            restoreRecommendedProfile = protectionViewModel::restoreRecommendedProfile,
            entrySetAngle = protectionViewModel::setEntryAngle,
            entryStartCommissioning = protectionViewModel::startEntryCommissioning,
            entryCancelCommissioning = protectionViewModel::cancelEntryCommissioning,
        ),
    )
}

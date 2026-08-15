package com.example.motorcycleantitheftsensor.ui.settings

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.motorcycleantitheftsensor.ui.ProtectionAppActions
import com.example.motorcycleantitheftsensor.ui.ProtectionUiState
import com.example.motorcycleantitheftsensor.ui.formatProtectionTimestamp
import com.example.motorcycleantitheftsensor.ui.friendlyPermissionName
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    state: ProtectionUiState,
    actions: ProtectionAppActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var replacementToken by remember { mutableStateOf("") }
    var smsDestination by rememberSaveable { mutableStateOf("") }
    var smsKey by remember { mutableStateOf("") }
    var sensitivityDraft by rememberSaveable { mutableIntStateOf(state.settings.sensitivity) }
    var confirmResetPairing by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            replacementToken = ""
            smsKey = ""
        }
    }

    LaunchedEffect(state.settings.sensitivity) {
        sensitivityDraft = state.settings.sensitivity
    }

    if (!state.settingsLoaded) {
        SettingsLoadState(
            loading = state.settingsLoading,
            error = state.settingsError,
            retry = actions.retrySettings,
            contentPadding = contentPadding,
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.protection.persistentGuidance?.let { guidance ->
            item(key = "persistent-guidance") {
                SettingsCard(title = guidance.titleTh) {
                    Text(guidance.bodyTh)
                }
            }
        }

        item(key = "settings-header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                if (state.settingsLoading) Text("Refreshing settings")
                state.settingsError?.let { error ->
                    Text(error)
                    Button(
                        onClick = actions.retrySettings,
                        enabled = !state.settingsOperationInFlight,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Retry settings")
                    }
                }
            }
        }

        item(key = "telegram-settings") {
            SettingsCard(title = "Telegram") {
                Text(if (state.settings.tokenConfigured) "Token configured" else "Token not configured")
                if (state.settings.pairedOwnerCount > 0) {
                    Text("Paired owners: ${state.settings.pairedOwnerCount}")
                }
                if (state.settings.pairingCode != null) {
                    Text("Pairing code: ${state.settings.pairingCode}")
                }
                if (state.settings.pairedOwnerCount == 0 && state.settings.pairingCode == null) {
                    Text("No paired owners")
                }
                OutlinedTextField(
                    value = replacementToken,
                    onValueChange = { replacementToken = it },
                    label = {
                        Text(if (state.settings.tokenConfigured) "Replace bot token" else "Bot token")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val newToken = replacementToken
                        actions.replaceBotToken(newToken)
                        replacementToken = ""
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    },
                    enabled = replacementToken.isNotBlank() && !state.settingsOperationInFlight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    if (state.settingsOperationInFlight) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Text("กำลังตรวจสอบและบันทึก...")
                        }
                    } else {
                        Text("Save bot token")
                    }
                }
                OutlinedButton(
                    onClick = { confirmResetPairing = true },
                    enabled = !state.settingsOperationInFlight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("Reset pairing")
                }
            }
        }

        item(key = "permissions") {
            SettingsCard(title = "Permissions") {
                if (state.settings.missingPermissions.isEmpty()) {
                    Text("All required permissions granted")
                } else {
                    Text("Some protection features need these permissions.")
                    state.settings.missingPermissions.sorted().forEach { permission ->
                        val shortName = permission.substringAfterLast('.')
                        val blocksProtection = state.protection.permissionBlockers.any { blocker ->
                            blocker == permission || blocker == shortName
                        }
                        val severity = if (blocksProtection) {
                            "Blocks protection"
                        } else {
                            "Reduced coverage"
                        }
                        Text("$severity: ${friendlyPermissionName(permission)}")
                    }
                    Button(
                        onClick = actions.requestPermissions,
                        enabled = !state.settingsOperationInFlight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text("Review permissions")
                    }
                }
            }
        }

        item(key = "sensitivity") {
            SettingsCard(title = "Sensitivity") {
                Text("Sensitivity: $sensitivityDraft (1-10)")
                AccessibleSensitivitySlider(
                    value = sensitivityDraft,
                    onValueChange = { sensitivityDraft = it },
                    onValueChangeFinished = { actions.changeSensitivity(sensitivityDraft) },
                    enabled = !state.settingsOperationInFlight,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item(key = "sms-settings") {
            SettingsCard(title = "SMS fallback") {
                Text(
                    if (state.settings.smsFallbackConfigured) {
                        "SMS fallback configured"
                    } else {
                        "SMS fallback not configured"
                    },
                )
                Text(
                    "SMS fallback is eligible only for real critical incidents after " +
                        "confirmed Telegram failure.",
                )
                OutlinedTextField(
                    value = smsDestination,
                    onValueChange = { smsDestination = it },
                    label = { Text("Replacement SMS destination") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = smsKey,
                    onValueChange = { smsKey = it },
                    label = { Text("Replacement encryption key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val destination = smsDestination
                        val key = smsKey
                        actions.configureSmsFallback(destination, key)
                        smsDestination = ""
                        smsKey = ""
                    },
                    enabled = smsDestination.isNotBlank() && smsKey.isNotBlank() &&
                        !state.settingsOperationInFlight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    if (state.settingsOperationInFlight) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Text("กำลังบันทึก SMS สำรอง...")
                        }
                    } else {
                        Text("Save SMS fallback")
                    }
                }
            }
        }

        item(key = "diagnostics") {
            SettingsCard(title = "Diagnostics") {
                DiagnosticRow(
                    "Protection state",
                    state.protection.state.name.lowercase().replace('_', ' '),
                )
                DiagnosticRow(
                    "Service",
                    if (state.protection.serviceRunning) "Running" else "Stopped",
                )
                DiagnosticRow(
                    "Telegram",
                    if (state.protection.telegramReachable) "Reachable" else "Unreachable",
                )
                DiagnosticRow(
                    "Last transition",
                    formatProtectionTimestamp(state.protection.lastTransitionAtMs),
                )
                DiagnosticRow(
                    "Battery",
                    state.protection.batteryLevelPercent?.let { "$it%" } ?: "Unavailable",
                )
            }
        }
    }

    if (confirmResetPairing) {
        AlertDialog(
            onDismissRequest = { confirmResetPairing = false },
            title = { Text("Reset pairing?") },
            text = {
                Text("Existing Telegram owners will need to pair again with a new code.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmResetPairing = false
                        actions.resetPairing()
                    },
                    enabled = !state.settingsOperationInFlight,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Confirm reset")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmResetPairing = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SettingsLoadState(
    loading: Boolean,
    error: String?,
    retry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) CircularProgressIndicator()
            Text(
                text = if (loading) "Loading settings" else "Unable to load settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            error?.let { Text(it) }
            if (!loading) {
                Button(
                    onClick = retry,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Retry settings")
                }
            }
        }
    }
}

private const val SENSITIVITY_SLIDER_TAG = "sensitivity_slider"

@Composable
private fun AccessibleSensitivitySlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val valueRange = 1f..10f
    val updateFromX: (Float, Float) -> Unit = { x, width ->
        val fraction = if (width > 0f) (x / width).coerceIn(0f, 1f) else 0f
        val nextValue = (valueRange.start + fraction * (valueRange.endInclusive - valueRange.start))
            .roundToInt()
            .coerceIn(valueRange.start.toInt(), valueRange.endInclusive.toInt())
        onValueChange(nextValue)
    }

    Box(
        modifier = modifier.height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Slider(
            value = value.toFloat(),
            onValueChange = {},
            valueRange = valueRange,
            steps = 8,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {},
        )
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .testTag(SENSITIVITY_SLIDER_TAG)
                .semantics {
                    contentDescription = "Protection sensitivity, $value out of 10"
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), valueRange, 8)
                    if (!enabled) disabled()
                    setProgress { targetValue ->
                        if (!enabled) {
                            false
                        } else {
                            onValueChange(targetValue.roundToInt().coerceIn(1, 10))
                            onValueChangeFinished()
                            true
                        }
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        updateFromX(down.position.x, size.width.toFloat())
                        down.consume()
                        var pressed = true
                        while (pressed) {
                            val change = awaitPointerEvent().changes
                                .firstOrNull { it.id == down.id }
                                ?: break
                            pressed = change.pressed
                            if (pressed) {
                                updateFromX(change.position.x, size.width.toFloat())
                                change.consume()
                            }
                        }
                        onValueChangeFinished()
                    }
                },
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value)
    }
}

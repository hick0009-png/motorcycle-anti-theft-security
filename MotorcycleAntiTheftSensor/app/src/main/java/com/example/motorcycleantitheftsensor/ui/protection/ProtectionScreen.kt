package com.example.motorcycleantitheftsensor.ui.protection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.ui.ProtectionAppActions
import com.example.motorcycleantitheftsensor.ui.ProtectionDestination
import com.example.motorcycleantitheftsensor.ui.ProtectionUiState
import com.example.motorcycleantitheftsensor.ui.formatProtectionTimestamp
import com.example.motorcycleantitheftsensor.ui.friendlyPermissionExplanation

@Composable
fun ProtectionScreen(
    state: ProtectionUiState,
    actions: ProtectionAppActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val protection = state.protection
    val disarmAction = protection.state == ProtectionState.ARMING ||
        protection.state == ProtectionState.ARMED_HEALTHY ||
        protection.state == ProtectionState.ARMED_DEGRADED ||
        protection.state == ProtectionState.ALERT_ACTIVE
    val actionEnabled = (!state.protectionOperationInFlight && protection.state == ProtectionState.DISARMED_ONLINE) ||
        disarmAction
    val blockingPermissionIssues = protection.permissionBlockers
        .map(::friendlyPermissionExplanation)
        .distinct()
        .sorted()
    val reducedCoveragePermissions = state.settings.missingPermissions
        .filterNot { permission ->
            protection.permissionBlockers.any { blocker ->
                blocker == permission || blocker == permission.substringAfterLast('.')
            }
        }
        .sorted()
    val permissionDegradationReasons = reducedCoveragePermissions
        .mapTo(mutableSetOf()) { permission -> "${permission.substringAfterLast('.')} unavailable" }
    val remainingDegradationReasons = protection.degradationReasons - permissionDegradationReasons

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val stateGuidance = com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(protection.state.toGuidanceCode())

        if (state.profile.showPicker) {
            item(key = "profile-picker") {
                ProfilePickerSection(onProfileSelected = actions.selectProfile)
            }
        }

        state.profile.pendingSwitchTarget?.let { target ->
            item(key = "profile-switch-confirmation") {
                ProfileSwitchConfirmationCard(
                    target = target,
                    onConfirm = actions.confirmProfileSwitch,
                    onCancel = actions.cancelProfileSwitch,
                )
            }
        }

        protection.persistentGuidance?.let { guidance ->
            item(key = "persistent-guidance") {
                StatusCard(title = guidance.titleTh) {
                    Text(guidance.bodyTh)
                }
            }
        }

        item(key = "protection-state") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stateGuidance.titleTh,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stateGuidance.bodyTh,
                    style = MaterialTheme.typography.bodyLarge,
                )
                state.armingSecondsRemaining?.let { seconds ->
                    Text(
                        text = "Armed in $seconds seconds",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Button(
                    onClick = if (disarmAction) actions.disarm else actions.arm,
                    enabled = actionEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        text = when (protection.state) {
                            ProtectionState.ALERT_ACTIVE -> "ปิดสัญญาณเตือน (Disarm)"
                            ProtectionState.ARMING,
                            ProtectionState.ARMED_HEALTHY,
                            ProtectionState.ARMED_DEGRADED -> "ปิดระบบป้องกัน (Disarm)"
                            else -> "เปิดระบบป้องกัน (Arm)"
                        }
                    )
                }
            }
        }

        if (blockingPermissionIssues.isNotEmpty()) {
            item(key = "permission-blockers") {
                StatusCard(title = "Protection blockers") {
                    blockingPermissionIssues.forEach { issue ->
                        Text(issue)
                    }
                    Button(
                        onClick = {
                            actions.selectDestination(ProtectionDestination.SETTINGS)
                        },
                        enabled = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text("Review permissions")
                    }
                }
            }
        }

        if (reducedCoveragePermissions.isNotEmpty()) {
            item(key = "reduced-permission-coverage") {
                StatusCard(title = "Reduced sensor coverage") {
                    reducedCoveragePermissions.forEach { permission ->
                        Text(friendlyPermissionExplanation(permission))
                    }
                    Button(
                        onClick = {
                            actions.selectDestination(ProtectionDestination.SETTINGS)
                        },
                        enabled = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text("Review permissions")
                    }
                }
            }
        }

        if (remainingDegradationReasons.isNotEmpty()) {
            item(key = "degradation-reasons") {
                StatusCard(title = "Degradation reasons") {
                    remainingDegradationReasons.sorted().forEach { reason ->
                        Text(reason)
                    }
                }
            }
        }

        item(key = "runtime-health") {
            StatusCard(title = "Runtime health") {
                StatusRow("Service", if (protection.serviceRunning) "Running" else "Stopped")
                StatusRow(
                    "Telegram polling",
                    if (protection.telegramPolling) "Running" else "Stopped",
                )
                StatusRow(
                    "Telegram reachability",
                    if (protection.telegramReachable) "Reachable" else "Unreachable",
                )
                StatusRow(
                    "Last Telegram contact",
                    protection.lastTelegramContactAtMs?.let(::formatProtectionTimestamp)
                        ?: "No contact",
                )
            }
        }

        item(key = "sensor-health-heading") {
            Text(
                text = "Sensor health",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }

        if (state.audio.state != com.example.motorcycleantitheftsensor.protection.AudioRuntimeState.OFF) {
            item(key = "audio-runtime-card") {
                StatusCard(
                    title = "Audio Threat Runtime",
                    modifier = Modifier.testTag(AUDIO_RUNTIME_CARD_TAG),
                ) {
                    StatusRow("Runtime State", state.audio.state.name.lowercase().replace('_', ' '))
                    StatusRow("Classifier Model", if (state.audio.modelReady) "Ready" else "Not ready")
                    StatusRow("Gate State", state.audio.gateState.name.lowercase())
                    state.audio.approximateLevelDbfs?.let { level ->
                        StatusRow("Audio Level", "%.1f dBFS".format(java.util.Locale.US, level))
                    }
                    state.audio.currentCandidate?.let { candidate ->
                        StatusRow(
                            "Threat Candidate",
                            "${candidate.category.name.lowercase().replace('_', ' ')} (${(candidate.confidence * 100).toInt()}%)",
                        )
                    }
                }
            }
        }

        items(SensorKind.entries, key = { "sensor-${it.name}" }) { sensor ->
            val health = protection.sensorHealth[sensor]
            StatusCard(title = sensor.displayName()) {
                if (sensor == SensorKind.MICROPHONE) {
                    Text(com.example.motorcycleantitheftsensor.ui.microphoneHealthText(health))
                    health?.detail?.takeIf(String::isNotBlank)?.let { detail -> Text(detail) }
                    if (state.audio.state != com.example.motorcycleantitheftsensor.protection.AudioRuntimeState.OFF) {
                        Text("Active: ${state.audio.state.name.lowercase().replace('_', ' ')}")
                    }
                } else if (protection.state == ProtectionState.DISARMED_ONLINE || protection.state == ProtectionState.SETUP_REQUIRED) {
                    Text("Live samples begin after arming")
                } else if (health == null) {
                    Text("Unavailable")
                } else {
                    Text(health.healthText())
                    health.detail?.takeIf(String::isNotBlank)?.let { detail -> Text(detail) }

                    health.latestReading?.let { reading ->
                        val valueString = if (reading.value != null) {
                            if (reading.unit != null) "${reading.value} ${reading.unit}" else "${reading.value}"
                        } else ""
                        val displayString = if (valueString.isNotEmpty()) "${reading.label}: $valueString" else reading.label
                        Text(displayString)
                    }

                    health.lastSampleAtMs?.let { lastSample ->
                        Text("Last sample: ${formatProtectionTimestamp(lastSample)}")
                    }
                }
            }
        }

        item(key = "battery") {
            StatusCard(title = "Battery") {
                StatusRow(
                    "Level",
                    protection.batteryLevelPercent?.let { "$it%" } ?: "Unavailable",
                )
                StatusRow(
                    "Temperature",
                    protection.batteryTemperatureCelsius?.let { "$it C" } ?: "Unavailable",
                )
            }
        }

        protection.lastIncident?.let { incident ->
            item(key = "latest-incident") {
                StatusCard(
                    title = if (protection.state == ProtectionState.ALERT_ACTIVE) {
                        "Active incident"
                    } else {
                        "Latest incident"
                    },
                ) {
                    StatusRow("Severity", incident.severity.displayName())
                    StatusRow("Lifecycle", incident.lifecycle.displayName())
                    StatusRow("Updated", formatProtectionTimestamp(incident.updatedAtMs))
                    StatusRow("Delivery", incident.deliveryState.displayName())
                }
            }
        }

        protection.lastDeliveryState?.let { deliveryState ->
            item(key = "latest-delivery") {
                StatusCard(title = "Latest delivery") {
                    Text(deliveryState.displayName())
                }
            }
        }

    }
}

const val AUDIO_RUNTIME_CARD_TAG = "audio-threat-status"

@Composable
private fun ProfilePickerSection(
    onProfileSelected: (com.example.motorcycleantitheftsensor.protection.ProtectionProfile) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "คุณกำลังปกป้องอะไร?",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        listOf(
            com.example.motorcycleantitheftsensor.protection.ProtectionProfile.VEHICLE to "Vehicle Guard",
            com.example.motorcycleantitheftsensor.protection.ProtectionProfile.ENTRY to "Entry Guard",
            com.example.motorcycleantitheftsensor.protection.ProtectionProfile.POWER to "Power Guard",
        ).forEach { (profile, label) ->
            Surface(
                onClick = { onProfileSelected(profile) },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("profile_card_${profile.name}"),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    if (profile != com.example.motorcycleantitheftsensor.protection.ProtectionProfile.VEHICLE) {
                        Text(
                            text = "Setup required",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSwitchConfirmationCard(
    target: com.example.motorcycleantitheftsensor.protection.ProtectionProfile,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    StatusCard(title = "ยืนยันการเปลี่ยนการใช้งาน") {
        Text(
            text = "การเปลี่ยนจะหยุดการป้องกันปัจจุบันและจะไม่เปิดใหม่อัตโนมัติ",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Keep current protection")
            }
            Button(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Stop protection and change use")
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value)
    }
}

private fun ProtectionState.toGuidanceCode(): com.example.motorcycleantitheftsensor.protection.GuidanceCode = when (this) {
    ProtectionState.SETUP_REQUIRED -> com.example.motorcycleantitheftsensor.protection.GuidanceCode.SETUP_REQUIRED
    ProtectionState.DISARMED_ONLINE -> com.example.motorcycleantitheftsensor.protection.GuidanceCode.DISARMED
    ProtectionState.ARMING -> com.example.motorcycleantitheftsensor.protection.GuidanceCode.ARMING
    ProtectionState.ARMED_HEALTHY -> com.example.motorcycleantitheftsensor.protection.GuidanceCode.ARMED_HEALTHY
    ProtectionState.ARMED_DEGRADED -> com.example.motorcycleantitheftsensor.protection.GuidanceCode.ARMED_DEGRADED
    ProtectionState.ALERT_ACTIVE -> com.example.motorcycleantitheftsensor.protection.GuidanceCode.ALERT_ACTIVE
    ProtectionState.OFFLINE -> com.example.motorcycleantitheftsensor.protection.GuidanceCode.OFFLINE
}



private fun SensorHealth?.healthText(): String = this?.state?.displayName() ?: "Unavailable"

private fun Enum<*>.displayName(): String = name
    .lowercase()
    .replace('_', ' ')
    .replaceFirstChar(Char::uppercase)

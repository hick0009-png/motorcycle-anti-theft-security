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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    val actionEnabled = !state.operationInFlight &&
        (disarmAction || protection.state == ProtectionState.DISARMED_ONLINE)
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
        item(key = "protection-state") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = protection.state.title(),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = protection.state.explanation(),
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
                    Text(if (disarmAction) "Disarm protection" else "Arm protection")
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
                        enabled = !state.operationInFlight,
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
                        enabled = !state.operationInFlight,
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
        items(SensorKind.entries, key = { "sensor-${it.name}" }) { sensor ->
            val health = protection.sensorHealth[sensor]
            StatusCard(title = sensor.displayName()) {
                Text(health.healthText())
                health?.detail?.takeIf(String::isNotBlank)?.let { detail -> Text(detail) }
                health?.lastSampleAtMs?.let { lastSample ->
                    Text("Last sample: ${formatProtectionTimestamp(lastSample)}")
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

        state.message?.let { message ->
            item(key = "message-${message.id}") {
                StatusCard(title = if (message.isError) "Action failed" else "Action complete") {
                    Text(message.text)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
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

private fun ProtectionState.title(): String = when (this) {
    ProtectionState.SETUP_REQUIRED -> "Setup required"
    ProtectionState.DISARMED_ONLINE -> "Protection disarmed"
    ProtectionState.ARMING -> "Arming protection"
    ProtectionState.ARMED_HEALTHY -> "Protection active"
    ProtectionState.ARMED_DEGRADED -> "Protection degraded"
    ProtectionState.ALERT_ACTIVE -> "Alert active"
    ProtectionState.OFFLINE -> "Protection offline"
}

private fun ProtectionState.explanation(): String = when (this) {
    ProtectionState.SETUP_REQUIRED -> "Complete required setup before arming protection."
    ProtectionState.DISARMED_ONLINE -> "Monitoring is online and ready to arm."
    ProtectionState.ARMING -> "Monitoring will activate when the countdown finishes."
    ProtectionState.ARMED_HEALTHY -> "All required protection systems are active."
    ProtectionState.ARMED_DEGRADED -> "Protection is active with reduced sensor coverage."
    ProtectionState.ALERT_ACTIVE -> "An active incident requires attention."
    ProtectionState.OFFLINE -> "The protection service is not available."
}

private fun SensorHealth?.healthText(): String = this?.state?.displayName() ?: "Unavailable"

private fun Enum<*>.displayName(): String = name
    .lowercase()
    .replace('_', ' ')
    .replaceFirstChar(Char::uppercase)

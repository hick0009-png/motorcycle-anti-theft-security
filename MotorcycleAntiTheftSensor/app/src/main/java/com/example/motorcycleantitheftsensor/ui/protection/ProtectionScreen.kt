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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.motorcycleantitheftsensor.R
import com.example.motorcycleantitheftsensor.protection.AudioRuntimeState
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.ProtectionValueFormatter
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog
import com.example.motorcycleantitheftsensor.ui.ProtectionAppActions
import com.example.motorcycleantitheftsensor.ui.ProtectionDestination
import com.example.motorcycleantitheftsensor.ui.ProtectionUiState
import com.example.motorcycleantitheftsensor.ui.audioGateStateLabel
import com.example.motorcycleantitheftsensor.ui.audioRuntimeStateLabel
import com.example.motorcycleantitheftsensor.ui.audioThreatCategoryLabel
import com.example.motorcycleantitheftsensor.ui.deliveryStateLabel
import com.example.motorcycleantitheftsensor.ui.formatProtectionTimestamp
import com.example.motorcycleantitheftsensor.ui.friendlyPermissionExplanation
import com.example.motorcycleantitheftsensor.ui.incidentLifecycleLabel
import com.example.motorcycleantitheftsensor.ui.microphoneHealthText
import com.example.motorcycleantitheftsensor.ui.sensorHealthStateLabel
import com.example.motorcycleantitheftsensor.ui.sensorKindLabel

/**
 * Outcome-first Protection screen (profile-aware Thai UX, Task 4): the state hero and its
 * single primary action lead, owner-actionable warnings stay visible, and every technical
 * diagnostic lives behind one advanced disclosure rendered with typed Thai labels.
 */
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
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val stateGuidance = UserGuidanceCatalog.content(protection.state.toGuidanceCode())

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
                        text = stringResource(R.string.protection_arming_countdown, seconds),
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
                            ProtectionState.ALERT_ACTIVE -> stringResource(R.string.action_stop_alarm)
                            ProtectionState.ARMING,
                            ProtectionState.ARMED_HEALTHY,
                            ProtectionState.ARMED_DEGRADED,
                            -> stringResource(R.string.action_disarm_protection)
                            else -> stringResource(R.string.action_arm_protection)
                        },
                    )
                }
            }
        }

        protection.persistentGuidance?.let { guidance ->
            item(key = "persistent-guidance") {
                StatusCard(title = guidance.titleTh) {
                    Text(guidance.bodyTh)
                }
            }
        }

        if (state.profile.showPicker) {
            item(key = "profile-picker") {
                ProfilePickerSection(
                    selectedProfile = null,
                    onProfileSelected = actions.selectProfile,
                    headingText = "คุณกำลังปกป้องอะไร?",
                )
            }
        } else {
            item(key = "profile-change-use") {
                ChangeUseSection(
                    selectedProfile = state.profile.selectedProfile,
                    onProfileSelected = actions.selectProfile,
                )
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

        if (state.profile.selectedProfile == ProtectionProfile.ENTRY) {
            item(key = "entry-guard") {
                EntryGuardSection(
                    profile = state.profile,
                    actions = actions,
                )
            }
        }

        if (state.profile.selectedProfile == ProtectionProfile.POWER) {
            item(key = "power-guard") {
                PowerGuardSection(
                    profile = state.profile,
                    actions = actions,
                )
            }
        }

        if (blockingPermissionIssues.isNotEmpty()) {
            item(key = "permission-blockers") {
                StatusCard(title = stringResource(R.string.protection_blockers_title)) {
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
                        Text(stringResource(R.string.action_review_permissions))
                    }
                }
            }
        }

        if (reducedCoveragePermissions.isNotEmpty()) {
            item(key = "reduced-permission-coverage") {
                StatusCard(title = stringResource(R.string.reduced_coverage_title)) {
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
                        Text(stringResource(R.string.action_review_permissions))
                    }
                }
            }
        }

        if (remainingDegradationReasons.isNotEmpty()) {
            item(key = "degradation-reasons") {
                StatusCard(title = stringResource(R.string.degradation_reasons_title)) {
                    remainingDegradationReasons.sorted().forEach { reason ->
                        Text(reason)
                    }
                }
            }
        }

        item(key = "advanced-diagnostics-toggle") {
            OutlinedButton(
                onClick = { advancedExpanded = !advancedExpanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(ADVANCED_DIAGNOSTICS_TOGGLE_TAG),
            ) {
                Text(
                    text = if (advancedExpanded) {
                        stringResource(R.string.advanced_disclosure_hide)
                    } else {
                        stringResource(R.string.advanced_disclosure_show)
                    },
                )
            }
        }

        if (advancedExpanded) {
            item(key = "runtime-health") {
                StatusCard(title = stringResource(R.string.runtime_health_title)) {
                    StatusRow(
                        "บริการหลัก",
                        if (protection.serviceRunning) "กำลังทำงาน" else "หยุดทำงาน",
                    )
                    StatusRow(
                        "การตรวจ Telegram",
                        if (protection.telegramPolling) "กำลังทำงาน" else "หยุดทำงาน",
                    )
                    StatusRow(
                        "การเข้าถึง Telegram",
                        if (protection.telegramReachable) "เข้าถึงได้" else "เข้าถึงไม่ได้",
                    )
                    StatusRow(
                        "ติดต่อล่าสุด",
                        protection.lastTelegramContactAtMs?.let(::formatProtectionTimestamp)
                            ?: "ยังไม่มีการติดต่อ",
                    )
                }
            }

            if (state.audio.state != AudioRuntimeState.OFF) {
                item(key = "audio-runtime-card") {
                    StatusCard(
                        title = "การตรวจจับเสียงผิดปกติ",
                        modifier = Modifier.testTag(AUDIO_RUNTIME_CARD_TAG),
                    ) {
                        StatusRow("สถานะ", audioRuntimeStateLabel(state.audio.state))
                        StatusRow(
                            "โมเดลจำแนกเสียง",
                            if (state.audio.modelReady) "พร้อมใช้งาน" else "ยังไม่พร้อม",
                        )
                        StatusRow("การกรองเสียง", audioGateStateLabel(state.audio.gateState))
                        state.audio.approximateLevelDbfs?.let { level ->
                            StatusRow("ระดับเสียง", ProtectionValueFormatter.audioDbfs(level).value)
                        }
                        state.audio.currentCandidate?.let { candidate ->
                            StatusRow(
                                "เสียงที่สงสัย",
                                "${audioThreatCategoryLabel(candidate.category)} · ความมั่นใจ ${(candidate.confidence * 100).toInt()}%",
                            )
                        }
                    }
                }
            }

            item(key = "sensor-health-heading") {
                Text(
                    text = "สถานะเซนเซอร์",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }

            items(SensorKind.entries, key = { "sensor-${it.name}" }) { sensor ->
                val health = protection.sensorHealth[sensor]
                StatusCard(title = sensorKindLabel(sensor)) {
                    if (sensor == SensorKind.MICROPHONE) {
                        Text(microphoneHealthText(health))
                        health?.detail?.takeIf(String::isNotBlank)?.let { detail -> Text(detail) }
                        if (state.audio.state != AudioRuntimeState.OFF) {
                            Text("สถานะเสียง: ${audioRuntimeStateLabel(state.audio.state)}")
                        }
                    } else if (
                        protection.state == ProtectionState.DISARMED_ONLINE ||
                        protection.state == ProtectionState.SETUP_REQUIRED
                    ) {
                        Text("การวัดสดจะเริ่มหลังเปิดระบบ")
                    } else if (health == null) {
                        Text("ไม่พร้อมใช้งาน")
                    } else {
                        Text(sensorHealthStateLabel(health.state))
                        health.detail?.takeIf(String::isNotBlank)?.let { detail -> Text(detail) }

                        health.latestReading?.let { reading ->
                            val valueString = if (reading.value != null) {
                                if (reading.unit != null) "${reading.value} ${reading.unit}" else "${reading.value}"
                            } else ""
                            val displayString =
                                if (valueString.isNotEmpty()) "${reading.label}: $valueString" else reading.label
                            Text(displayString)
                        }

                        health.lastSampleAtMs?.let { lastSample ->
                            Text("ตัวอย่างล่าสุด: ${formatProtectionTimestamp(lastSample)}")
                        }
                    }
                }
            }

            item(key = "battery") {
                StatusCard(title = "แบตเตอรี่") {
                    StatusRow(
                        "ระดับพลังงาน",
                        protection.batteryLevelPercent?.let { "$it%" } ?: "ยังไม่ทราบ",
                    )
                    StatusRow(
                        "อุณหภูมิ",
                        protection.batteryTemperatureCelsius
                            ?.let { ProtectionValueFormatter.temperatureCelsius(it.toDouble()) }
                            ?: "ยังไม่ทราบ",
                    )
                }
            }

            protection.lastIncident?.let { incident ->
                item(key = "latest-incident") {
                    StatusCard(
                        title = if (protection.state == ProtectionState.ALERT_ACTIVE) {
                            "เหตุการณ์ที่กำลังเกิด"
                        } else {
                            "เหตุการณ์ล่าสุด"
                        },
                    ) {
                        StatusRow("ความรุนแรง", PresentationTextCatalog.severityLabel(incident.severity))
                        StatusRow("สถานะเหตุการณ์", incidentLifecycleLabel(incident.lifecycle))
                        StatusRow("อัปเดต", formatProtectionTimestamp(incident.updatedAtMs))
                        StatusRow("การส่งข้อมูล", deliveryStateLabel(incident.deliveryState))
                    }
                }
            }

            protection.lastDeliveryState?.let { deliveryState ->
                item(key = "latest-delivery") {
                    StatusCard(title = "ผลการส่งล่าสุด") {
                        Text(deliveryStateLabel(deliveryState))
                    }
                }
            }
        }
    }
}

const val AUDIO_RUNTIME_CARD_TAG = "audio-threat-status"
const val ADVANCED_DIAGNOSTICS_TOGGLE_TAG = "advanced_diagnostics_toggle"

@Composable
private fun ChangeUseSection(
    selectedProfile: ProtectionProfile?,
    onProfileSelected: (ProtectionProfile) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "การใช้งานปัจจุบัน: ${profileLabel(selectedProfile)}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (!expanded) {
            Button(
                onClick = { expanded = true },
                enabled = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("change_use_button"),
            ) {
                Text(stringResource(R.string.action_change_use))
            }
        } else {
            ProfilePickerSection(
                selectedProfile = selectedProfile,
                onProfileSelected = { profile ->
                    expanded = false
                    onProfileSelected(profile)
                },
                headingText = "เลือกการใช้งานใหม่",
            )
        }
    }
}

private fun profileLabel(profile: ProtectionProfile?): String = when (profile) {
    null -> "ยังไม่ได้เลือก"
    else -> PresentationTextCatalog.profile(profile).name
}

@Composable
private fun ProfilePickerSection(
    selectedProfile: ProtectionProfile?,
    onProfileSelected: (ProtectionProfile) -> Unit,
    headingText: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = headingText,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        ProtectionProfile.entries.forEach { profile ->
            val label = PresentationTextCatalog.profile(profile).name
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
                    Text(
                        text = if (profile == selectedProfile) "$label (ปัจจุบัน)" else label,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (profile != ProtectionProfile.VEHICLE) {
                        Text(
                            text = "ต้องตั้งค่าก่อนใช้งาน",
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
    target: ProtectionProfile,
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
                Text(stringResource(R.string.action_keep_current_protection))
            }
            Button(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_stop_protection_and_change_use))
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

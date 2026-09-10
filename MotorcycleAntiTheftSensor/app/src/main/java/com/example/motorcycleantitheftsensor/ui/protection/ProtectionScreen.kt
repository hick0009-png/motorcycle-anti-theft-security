package com.example.motorcycleantitheftsensor.ui.protection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.motorcycleantitheftsensor.protection.AudioRuntimeState
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.AudioGateState
import com.example.motorcycleantitheftsensor.protection.AudioThreatCategory
import com.example.motorcycleantitheftsensor.protection.DeliveryState
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.IncidentSeverity
import com.example.motorcycleantitheftsensor.protection.ProfileDeviceSupport
import com.example.motorcycleantitheftsensor.protection.ProfileSupportReason
import com.example.motorcycleantitheftsensor.protection.ProfileSetupState
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.telegram.toGuidanceCode
import com.example.motorcycleantitheftsensor.protection.SensorHealthState
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog
import com.example.motorcycleantitheftsensor.ui.ProtectionAppActions
import com.example.motorcycleantitheftsensor.ui.ProtectionDestination
import com.example.motorcycleantitheftsensor.ui.ProtectionUiState
import com.example.motorcycleantitheftsensor.ui.audioRuntimeStateLabel
import com.example.motorcycleantitheftsensor.ui.formatProtectionTimestamp
import com.example.motorcycleantitheftsensor.ui.friendlyPermissionExplanation
import com.example.motorcycleantitheftsensor.ui.microphoneHealthText

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
    val actionEnabled = (!state.protectionOperationInFlight && protection.state in setOf(
        ProtectionState.DISARMED_ONLINE,
        ProtectionState.SETUP_REQUIRED,
    )) ||
        disarmAction
    val powerCalibrationRequired = state.profile.selectedProfile == ProtectionProfile.POWER &&
        state.profile.setupState == ProfileSetupState.SETUP_REQUIRED &&
        state.profile.powerCommissioning == null &&
        state.profile.armedProfile == null
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
        .mapTo(mutableSetOf()) { permission -> formatPermissionThai(permission) }
    val remainingDegradationReasons = (protection.degradationReasons - permissionDegradationReasons)
        .map(::formatDegradationReasonThai)
        .distinct()
        .sorted()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .testTag(PROTECTION_LIST_TAG)
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val stateGuidance = UserGuidanceCatalog.content(
            protection.state.toGuidanceCode(protection.setupBlocker),
        )

        item(key = "moto-guard-header") {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Moto Guard",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = "ศูนย์ควบคุมการปกป้อง",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        item(key = "protection-state") {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "สถานะการปกป้อง",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = stateGuidance.titleTh,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stateGuidance.bodyTh,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    state.armingSecondsRemaining?.let { seconds ->
                        Text(
                            text = PresentationTextCatalog.armingCountdown(seconds),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        // The door watch freezes its closed reference from the pose it settles on
                        // as the countdown ends, so a door moved during it skews the whole session.
                        if (state.profile.selectedProfile == ProtectionProfile.ENTRY) {
                            Text(
                                text = PresentationTextCatalog.ARMING_ENTRY_HOLD_STILL,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    if (powerCalibrationRequired) {
                        // States what blocks arming; the witness card below owns the
                        // action. Two buttons for one calibration is a merge artefact.
                        Text(
                            text = "ปรับเทียบไฟยืนยันก่อนเปิดระบบป้องกัน",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Button(
                        onClick = if (disarmAction) actions.disarm else actions.arm,
                        enabled = actionEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(
                            text = when (protection.state) {
                                ProtectionState.ALERT_ACTIVE -> PresentationTextCatalog.ACTION_STOP_ALARM
                                ProtectionState.ARMING,
                                ProtectionState.ARMED_HEALTHY,
                                ProtectionState.ARMED_DEGRADED,
                                -> PresentationTextCatalog.ACTION_DISARM_PROTECTION
                                else -> PresentationTextCatalog.ACTION_ARM_PROTECTION
                            },
                        )
                    }
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

        // The pre-redesign "header" + "protection-state" pair was superseded by the
        // moto-guard-header / protection-state cards above; the merge had reinstated
        // it, producing a duplicate LazyColumn key that crashed on first measure.

        if (state.profile.showPicker) {
            item(key = "profile-picker") {
                ProfilePickerSection(
                    selectedProfile = null,
                    selectedSetupState = null,
                    deviceSupport = state.profileDeviceSupport,
                    onProfileSelected = actions.selectProfile,
                    onClearDriftMeasurement = actions.clearDriftMeasurement,
                    headingText = "คุณกำลังปกป้องอะไร?",
                )
            }
        } else {
            item(key = "profile-change-use") {
                ChangeUseSection(
                    selectedProfile = state.profile.selectedProfile,
                    selectedSetupState = state.profile.setupState,
                    deviceSupport = state.profileDeviceSupport,
                    onProfileSelected = actions.selectProfile,
                    onClearDriftMeasurement = actions.clearDriftMeasurement,
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

        if (state.profile.selectedProfile == ProtectionProfile.VEHICLE) {
            item(key = "vehicle-guard") {
                StatusCard(title = "สรุปการดูแลยานพาหนะ") {
                    Text("เฝ้าระวังยานพาหนะด้วยเซนเซอร์ที่เปิดใช้งาน")
                    Text(
                        text = "รายละเอียดค่าจากเซนเซอร์อยู่ในรายละเอียดระบบ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.profile.selectedProfile == com.example.motorcycleantitheftsensor.protection.ProtectionProfile.ENTRY) {
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
                StatusCard(title = "สิ่งที่ต้องตั้งค่า") {
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
                        Text("ตรวจสอบสิทธิ์")
                    }
                }
            }
        }

        if (reducedCoveragePermissions.isNotEmpty()) {
            item(key = "reduced-permission-coverage") {
                StatusCard(title = "ความครอบคลุมของเซนเซอร์ลดลง") {
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
                        Text("ตรวจสอบสิทธิ์")
                    }
                }
            }
        }

        if (remainingDegradationReasons.isNotEmpty()) {
            item(key = "degradation-reasons") {
                StatusCard(title = "สาเหตุที่ระบบทำงานจำกัด") {
                    remainingDegradationReasons.forEach { reason ->
                        Text(reason)
                    }
                }
            }
        }

        item(key = "latest-event") {
            StatusCard(
                title = if (protection.state == ProtectionState.ALERT_ACTIVE) {
                    "เหตุการณ์ที่กำลังดำเนินอยู่"
                } else {
                    "เหตุการณ์ล่าสุด"
                },
            ) {
                val incident = protection.lastIncident
                if (incident == null) {
                    Text("ยังไม่มีเหตุการณ์")
                } else {
                    StatusRow("ระดับความรุนแรง", incident.severity.thaiDisplayName())
                    StatusRow("สถานะเหตุการณ์", incident.lifecycle.thaiDisplayName())
                    StatusRow("อัปเดต", formatProtectionTimestamp(incident.updatedAtMs))
                    StatusRow("สถานะการส่ง", incident.deliveryState.thaiDisplayName())
                }
            }
        }

        item(key = "system-details-toggle") {
            var systemDetailsExpanded by rememberSaveable { mutableStateOf(false) }
            Column {
                TextButton(
                    onClick = { systemDetailsExpanded = !systemDetailsExpanded },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag(ADVANCED_DIAGNOSTICS_TOGGLE_TAG),
                ) {
                    Text(
                        text = if (systemDetailsExpanded) "▲ ซ่อนรายละเอียดระบบ" else "▼ ดูรายละเอียดระบบ",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                AnimatedVisibility(visible = systemDetailsExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusCard(title = "สถานะการทำงาน") {
                            StatusRow("บริการ", if (protection.serviceRunning) "ทำงาน" else "หยุด")
                            StatusRow(
                                "การรับคำสั่ง Telegram",
                                if (protection.telegramPolling) "ทำงาน" else "หยุด",
                            )
                            StatusRow(
                                "สถานะ Telegram",
                                if (protection.telegramReachable) "เชื่อมต่อได้" else "เชื่อมต่อไม่ได้",
                            )
                            StatusRow(
                                "ติดต่อ Telegram ล่าสุด",
                                protection.lastTelegramContactAtMs?.let(::formatProtectionTimestamp)
                                    ?: "ยังไม่มีการติดต่อ",
                            )
                        }

                        Text(
                            text = "สถานะเซนเซอร์",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
                        )

                        if (state.audio.state != com.example.motorcycleantitheftsensor.protection.AudioRuntimeState.OFF) {
                            StatusCard(
                                title = "ระบบตรวจจับเสียง",
                                modifier = Modifier.testTag(AUDIO_RUNTIME_CARD_TAG),
                            ) {
                                StatusRow("สถานะ", state.audio.state.thaiDisplayName())
                                StatusRow("โมเดลจำแนก", if (state.audio.modelReady) "พร้อม" else "ไม่พร้อม")
                                StatusRow("ตัวกรองเสียง", state.audio.gateState.thaiDisplayName())
                                state.audio.approximateLevelDbfs?.let { level ->
                                    StatusRow("ระดับเสียง", "%.1f dBFS".format(java.util.Locale.US, level))
                                }
                                state.audio.currentCandidate?.let { candidate ->
                                    StatusRow(
                                        "ภัยคุกคามที่ตรวจพบ",
                                        "${candidate.category.thaiDisplayName()} (${(candidate.confidence * 100).toInt()}%)",
                                    )
                                }
                            }
                        }

                        SensorKind.entries.forEach { sensor ->
                            val health = protection.sensorHealth[sensor]
                            StatusCard(title = sensor.thaiDisplayName()) {
                                if (sensor == SensorKind.MICROPHONE) {
                                    Text(com.example.motorcycleantitheftsensor.ui.microphoneHealthText(health))
                                    health?.detail?.takeIf(String::isNotBlank)?.let { detail -> Text(detail) }
                                    if (state.audio.state != com.example.motorcycleantitheftsensor.protection.AudioRuntimeState.OFF) {
                                        Text("กำลังทำงาน: ${state.audio.state.thaiDisplayName()}")
                                    }
                                } else if (protection.state == ProtectionState.DISARMED_ONLINE || protection.state == ProtectionState.SETUP_REQUIRED) {
                                    Text(com.example.motorcycleantitheftsensor.ui.idleSensorRowText(sensor, health))
                                } else if (health == null) {
                                    Text("ไม่พร้อมใช้งาน")
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
                                        Text("อ่านค่าล่าสุด: ${formatProtectionTimestamp(lastSample)}")
                                    }
                                }
                            }
                        }

                        StatusCard(title = "แบตเตอรี่") {
                            StatusRow(
                                "ระดับ",
                                protection.batteryLevelPercent?.let { "$it%" } ?: "ไม่พร้อมใช้งาน",
                            )
                            StatusRow(
                                "อุณหภูมิ",
                                protection.batteryTemperatureCelsius?.let { "$it C" } ?: "ไม่พร้อมใช้งาน",
                            )
                        }
                    }
                }
            }
        }
    }
}

const val AUDIO_RUNTIME_CARD_TAG = "audio-threat-status"
const val PROTECTION_LIST_TAG = "ui.protection.LIST"

/** Single disclosure that keeps runtime diagnostics out of the calm default view. */
const val ADVANCED_DIAGNOSTICS_TOGGLE_TAG = "advanced_diagnostics_toggle"

@Composable
private fun ChangeUseSection(
    selectedProfile: com.example.motorcycleantitheftsensor.protection.ProtectionProfile?,
    selectedSetupState: ProfileSetupState?,
    deviceSupport: Map<ProtectionProfile, ProfileDeviceSupport>,
    onProfileSelected: (com.example.motorcycleantitheftsensor.protection.ProtectionProfile) -> Unit,
    onClearDriftMeasurement: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    StatusCard(title = "การใช้งานปัจจุบัน") {
        Text(profileLabel(selectedProfile), style = MaterialTheme.typography.titleLarge)
        if (!expanded) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("change_use_button"),
            ) {
                Text("เปลี่ยนการใช้งาน")
            }
        } else {
            ProfilePickerSection(
                selectedProfile = selectedProfile,
                selectedSetupState = selectedSetupState,
                deviceSupport = deviceSupport,
                onProfileSelected = { profile ->
                    expanded = false
                    onProfileSelected(profile)
                },
                onClearDriftMeasurement = onClearDriftMeasurement,
                headingText = "เลือกการใช้งานใหม่",
            )
        }
    }
}

private fun profileLabel(profile: com.example.motorcycleantitheftsensor.protection.ProtectionProfile?): String =
    when (profile) {
        com.example.motorcycleantitheftsensor.protection.ProtectionProfile.VEHICLE -> "ดูแลยานพาหนะ"
        com.example.motorcycleantitheftsensor.protection.ProtectionProfile.ENTRY -> "ดูแลทางเข้า"
        com.example.motorcycleantitheftsensor.protection.ProtectionProfile.POWER -> "ดูแลไฟเลี้ยง"
        null -> "ยังไม่ได้เลือก"
    }

@Composable
private fun ProfilePickerSection(
    selectedProfile: com.example.motorcycleantitheftsensor.protection.ProtectionProfile?,
    selectedSetupState: ProfileSetupState?,
    deviceSupport: Map<ProtectionProfile, ProfileDeviceSupport>,
    onProfileSelected: (com.example.motorcycleantitheftsensor.protection.ProtectionProfile) -> Unit,
    onClearDriftMeasurement: () -> Unit,
    headingText: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = headingText,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        listOf(
            com.example.motorcycleantitheftsensor.protection.ProtectionProfile.VEHICLE to "ดูแลยานพาหนะ",
            com.example.motorcycleantitheftsensor.protection.ProtectionProfile.ENTRY to "ดูแลทางเข้า",
            com.example.motorcycleantitheftsensor.protection.ProtectionProfile.POWER to "ดูแลไฟเลี้ยง",
        ).forEach { (profile, label) ->
            val profilePresentation = PresentationTextCatalog.profile(profile)
            val support = deviceSupport[profile] ?: ProfileDeviceSupport.Supported
            val consequence = PresentationTextCatalog.profileSupport(support)
            Surface(
                onClick = { onProfileSelected(profile) },
                enabled = support.selectable,
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (profile == selectedProfile) "$label (ปัจจุบัน)" else label,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        // Choosing a use this phone cannot detect with is the most
                        // expensive mistake on this screen: it reads as protection.
                        Text(
                            text = PresentationTextCatalog.profileSupportBadge(support),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("profile_support_${profile.name}"),
                        )
                    }
                    Text(
                        text = profilePresentation.promise,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (consequence != null) {
                        Text(
                            text = consequence,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("profile_support_reason_${profile.name}"),
                        )
                    }
                    if (profile != ProtectionProfile.VEHICLE && support.selectable) {
                        Text(
                            text = if (profile == selectedProfile && selectedSetupState == ProfileSetupState.READY) {
                                "พร้อมใช้งาน"
                            } else {
                                "ตรวจสอบการตั้งค่าก่อนใช้งาน"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Outside the card on purpose: the card itself is disabled, and a refusal the
            // owner can undo needs a control they can actually press. A measurement taken
            // while the phone was being handled is the only refusal here that is undoable.
            if (support is ProfileDeviceSupport.Unsupported &&
                support.reason == ProfileSupportReason.DRIFT_TOO_FAST
            ) {
                Text(
                    text = PresentationTextCatalog.DRIFT_LOG_CLEAR_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onClearDriftMeasurement,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag(DRIFT_MEASUREMENT_CLEAR_TAG),
                ) {
                    Text(PresentationTextCatalog.DRIFT_LOG_CLEAR)
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("ใช้การป้องกันปัจจุบันต่อ")
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("หยุดการป้องกันและเปลี่ยนการใช้งาน")
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
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}



private fun SensorHealth?.healthText(): String = this?.state?.thaiDisplayName() ?: "ไม่พร้อมใช้งาน"

private fun SensorKind.thaiDisplayName(): String = when (this) {
    SensorKind.VIBRATION -> "การสั่นสะเทือน"
    SensorKind.LIGHT -> "แสง"
    SensorKind.POWER_THERMAL -> "ไฟเลี้ยงและอุณหภูมิ"
    SensorKind.MICROPHONE -> "ไมโครโฟน"
    SensorKind.LOCATION -> "ตำแหน่ง"
}

private fun SensorHealthState.thaiDisplayName(): String = when (this) {
    SensorHealthState.UNAVAILABLE -> "ไม่พร้อมใช้งาน"
    SensorHealthState.AVAILABLE -> "พร้อมอ่านค่า"
    SensorHealthState.HEALTHY -> "ปกติ"
    SensorHealthState.STALE -> "ข้อมูลไม่ใหม่"
    SensorHealthState.FAILED -> "ทำงานผิดพลาด"
}

private fun IncidentSeverity.thaiDisplayName(): String = when (this) {
    IncidentSeverity.WARNING -> "เฝ้าระวัง"
    IncidentSeverity.CRITICAL -> "วิกฤต"
}

private fun IncidentLifecycle.thaiDisplayName(): String = when (this) {
    IncidentLifecycle.OPEN -> "กำลังดำเนินอยู่"
    IncidentLifecycle.CLOSED -> "สิ้นสุดแล้ว"
    IncidentLifecycle.INTERRUPTED -> "ถูกขัดจังหวะ"
}

private fun DeliveryState.thaiDisplayName(): String = when (this) {
    DeliveryState.PENDING -> "รอส่ง"
    DeliveryState.SENT -> "ส่งแล้ว"
    DeliveryState.FAILED -> "ส่งไม่สำเร็จ"
    DeliveryState.NOT_ELIGIBLE -> "ไม่เข้าเกณฑ์การส่ง"
}

/**
 * The screen carried its own Thai for the listening state while
 * [com.example.motorcycleantitheftsensor.ui.audioRuntimeStateLabel] carried another, written
 * for this very screen and never wired to it. Two names for one state is one too many, and
 * the shared one wins: it is the mapping the presentation layer tests pin.
 */
private fun AudioRuntimeState.thaiDisplayName(): String =
    com.example.motorcycleantitheftsensor.ui.audioRuntimeStateLabel(this)

private fun AudioGateState.thaiDisplayName(): String = when (this) {
    AudioGateState.DISABLED -> "ปิด"
    AudioGateState.QUIET -> "เงียบ"
    AudioGateState.OPEN -> "เปิดรับ"
}

private fun AudioThreatCategory.thaiDisplayName(): String = when (this) {
    AudioThreatCategory.IMPACT -> "เสียงกระแทก"
    AudioThreatCategory.BREAKING -> "เสียงแตกหัก"
    AudioThreatCategory.POWER_TOOL -> "เครื่องมือไฟฟ้า"
    AudioThreatCategory.METAL_TAMPER -> "การงัดโลหะ"
    AudioThreatCategory.ENGINE_START -> "เสียงสตาร์ตเครื่องยนต์"
    AudioThreatCategory.ENGINE_RUNNING -> "เสียงเครื่องยนต์ทำงาน"
}

private fun formatPermissionThai(permission: String): String {
    val name = when {
        permission.endsWith("RECORD_AUDIO") -> "ไมโครโฟน"
        permission.endsWith("POST_NOTIFICATIONS") -> "การแจ้งเตือน"
        permission.endsWith("ACCESS_FINE_LOCATION") -> "ตำแหน่ง"
        permission.endsWith("ACCESS_COARSE_LOCATION") -> "ตำแหน่ง"
        else -> permission.substringAfterLast('.')
    }
    return "$name ยังไม่พร้อม"
}

private fun formatDegradationReasonThai(reason: String): String {
    if (reason == "Power witness placement not revalidated") {
        return "ต้องยืนยันตำแหน่งไฟยืนยันอีกครั้ง"
    }

    val sensorName = reason.removeSuffix(" not healthy")
        .takeIf { reason.endsWith(" not healthy") }
        ?.let { rawName ->
            SensorKind.entries.firstOrNull { kind -> kind.name == rawName }?.thaiDisplayName()
        }
    if (sensorName != null) {
        return "${sensorName}ทำงานไม่ปกติ"
    }

    return "ระบบบางส่วนทำงานแบบจำกัด"
}

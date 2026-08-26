package com.example.motorcycleantitheftsensor.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.motorcycleantitheftsensor.autostart.BackgroundKeepAliveManager
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SensorCapability
import com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicy
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.SensorPreset
import com.example.motorcycleantitheftsensor.protection.SensorRole
import com.example.motorcycleantitheftsensor.protection.SensorSource
import com.example.motorcycleantitheftsensor.ui.ChargingRowState
import com.example.motorcycleantitheftsensor.ui.ProtectionAppActions
import com.example.motorcycleantitheftsensor.ui.ProtectionUiState
import com.example.motorcycleantitheftsensor.ui.WitnessRowState
import com.example.motorcycleantitheftsensor.ui.SettingsOperation
import com.example.motorcycleantitheftsensor.ui.formatProtectionTimestamp
import com.example.motorcycleantitheftsensor.ui.audioGateStateLabel
import com.example.motorcycleantitheftsensor.ui.audioRuntimeStateLabel
import com.example.motorcycleantitheftsensor.ui.audioThreatCategoryLabel
import com.example.motorcycleantitheftsensor.ui.friendlyPermissionName
import com.example.motorcycleantitheftsensor.ui.microphoneHealthText
import com.example.motorcycleantitheftsensor.protection.ProtectionValueFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Light-safe accent tokens for this screen. Neutral text/surface colors come from
 * [MaterialTheme.colorScheme] (onSurface / onSurfaceVariant / outline / surface);
 * only accents without a scheme equivalent are defined here. Every pairing meets
 * WCAG AA (≥4.5:1) on white.
 */
private val RowSurface = Color(0xFFF2F2F0)   // inner rows / chips
private val BorderNeutral = Color(0xFFC9C9C6) // borders / inactive tracks
private val ActionBlue = Color(0xFF1D4ED8)    // actions/links on white (6.3:1)
private val StatusGreen = Color(0xFF047857)   // armed/healthy text (5.2:1)
private val StatusAmber = Color(0xFFB45309)   // warning text (4.7:1)
private val StatusRed = Color(0xFFB91C1C)     // error text / destructive fill
private val ProgressCyan = Color(0xFF0891B2)  // decorative progress only

private enum class SettingsPage(
    val title: String,
    val summary: String,
) {
    OVERVIEW("ตั้งค่า", "จัดการการปกป้อง การแจ้งเตือน และการทำงานของแอป"),
    PROTECTION("การปกป้อง", "ปรับการตรวจจับให้เหมาะกับการใช้งานของคุณ"),
    DELIVERY_SECURITY(
        "การแจ้งเตือนและความปลอดภัย",
        "กำหนดช่องทางแจ้งเตือนและสิทธิที่จำเป็น",
    ),
    CONTINUITY("ความต่อเนื่องของระบบ", "ดูแลให้การปกป้องทำงานต่อเนื่อง"),
    ADVANCED("การวินิจฉัยขั้นสูง", "ตรวจดูรายละเอียดเชิงเทคนิคเมื่อจำเป็น"),
}

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
    var advancedDiagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.OVERVIEW) }

    val activeOp = state.activeSettingsOperation
    val savingBotToken = activeOp == SettingsOperation.REPLACE_BOT_TOKEN
    val savingSmsFallback = activeOp == SettingsOperation.SAVE_SMS_FALLBACK

    DisposableEffect(Unit) {
        onDispose {
            replacementToken = ""
            smsKey = ""
        }
    }

    LaunchedEffect(state.settings.sensitivity) {
        sensitivityDraft = state.settings.sensitivity
    }

    LaunchedEffect(state.settings.tokenConfigured) {
        if (!state.settingsOperationInFlight && state.settings.tokenConfigured && replacementToken.isNotBlank()) {
            replacementToken = ""
        }
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

    BackHandler(enabled = settingsPage != SettingsPage.OVERVIEW) {
        settingsPage = SettingsPage.OVERVIEW
    }

    if (settingsPage == SettingsPage.OVERVIEW) {
        SettingsOverview(
            contentPadding = contentPadding,
            modifier = modifier,
            openPage = { settingsPage = it },
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .testTag("ui.settings.LIST"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.protection.persistentGuidance?.let { guidance ->
            item(key = "persistent-guidance") {
                SettingsCard(title = "คำแนะนำจากระบบ: ${guidance.titleTh}") {
                    Text(
                        text = guidance.bodyTh,
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusAmber,
                    )
                }
            }
        }

        item(key = "settings-header") {
            SettingsPageHeader(
                page = settingsPage,
                returnToOverview = { settingsPage = SettingsPage.OVERVIEW },
            )
        }
        item(key = "settings-status") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.settingsLoading) {
                    Text(
                        "กำลังรีเฟรชการตั้งค่า...",
                        style = MaterialTheme.typography.bodySmall,
                        color = ProgressCyan,
                    )
                }
                state.settingsError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusRed,
                    )
                    Button(
                        onClick = actions.retrySettings,
                        enabled = !state.settingsOperationInFlight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ActionBlue),
                    ) {
                        Text("ลองใหม่อีกครั้ง")
                    }
                }
            }
        }

        if (settingsPage == SettingsPage.PROTECTION) {
        item(key = "section-current-use") {
            Text(
                text = "การใช้งานปัจจุบัน",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.semantics { heading() },
            )
            val selectedProfile = state.profile.selectedProfile
            if (selectedProfile == null) {
                SettingsCard(title = "ยังไม่ได้เลือกรูปแบบการใช้งาน") {
                    Text(
                        text = "เลือกรูปแบบการเฝ้าระวังจากหน้าปกป้องก่อน เพื่อให้การตรวจจับตรงกับจุดติดตั้ง",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val profilePresentation = PresentationTextCatalog.profile(selectedProfile)
                SettingsCard(title = profilePresentation.name) {
                    Text(
                        text = profilePresentation.promise,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "section-profile-detection") {
            Text(
                text = "การตรวจจับของรูปแบบนี้",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.semantics { heading() },
            )
            when (state.profile.selectedProfile) {
                ProtectionProfile.VEHICLE -> {
                    val movement = PresentationTextCatalog.capability(
                        ProtectionProfile.VEHICLE,
                        SensorCapability.MOVEMENT,
                    )
                    var sensitivityDraft by remember(state.settings.sensitivity) {
                        mutableIntStateOf(state.settings.sensitivity)
                    }
                    SettingsCard(title = movement.title) {
                        Text(
                            text = movement.explanation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "ระดับการตรวจจับปัจจุบัน",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Surface(
                                color = ActionBlue.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, ActionBlue.copy(alpha = 0.3f)),
                            ) {
                                Text(
                                    text = "$sensitivityDraft/10",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                        AccessibleSensitivitySlider(
                            value = sensitivityDraft,
                            onValueChange = { sensitivityDraft = it },
                            onValueChangeFinished = { actions.changeSensitivity(sensitivityDraft) },
                            enabled = !state.settingsOperationInFlight,
                            sliderContentDescription = "ระดับการตรวจจับ $sensitivityDraft เต็ม 10",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                ProtectionProfile.ENTRY -> {
                    val angle = state.profile.entryAngleDegrees ?: 15
                    SettingsCard(title = "มุมเปิดประตูที่ต้องการให้แจ้งเตือน") {
                        Text(
                            text = "แจ้งเมื่อประตูเปิดเกิน $angle° จากตำแหน่งปิด",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "มุมวัดจากตำแหน่งปิดที่ปรับเทียบไว้",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(5, 15, 30).forEach { choice ->
                                val selected = angle == choice
                                if (selected) {
                                    Button(
                                        onClick = { actions.entrySetAngle(choice) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = ActionBlue),
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Text("$choice°", fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { actions.entrySetAngle(choice) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp),
                                        border = BorderStroke(1.dp, BorderNeutral),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Text("$choice°")
                                    }
                                }
                            }
                        }
                    }
                }
                ProtectionProfile.POWER -> {
                    val rows = state.profile.powerSummary
                    SettingsCard(title = "สถานะไฟเลี้ยงจุดติดตั้ง") {
                        Text(
                            text = "ระบบยืนยันไฟเลี้ยงขาดเมื่อทั้งการชาร์จโทรศัพท์และไฟยืนยันหายต่อเนื่องตามเวลาที่กำหนด",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        DiagnosticRow(
                            "การชาร์จโทรศัพท์",
                            when (rows?.charging) {
                                ChargingRowState.CONNECTED -> "เชื่อมต่อ"
                                ChargingRowState.DISCONNECTED -> "หยุด"
                                ChargingRowState.UNKNOWN, null -> "ไม่ทราบ"
                            },
                        )
                        DiagnosticRow(
                            "ไฟยืนยันจุดติดตั้ง",
                            when (rows?.witness) {
                                WitnessRowState.DETECTED -> "พบ"
                                WitnessRowState.DARK -> "ไม่พบ"
                                WitnessRowState.UNAVAILABLE, null -> "ใช้งานไม่ได้"
                            },
                        )
                    }
                }
                null -> Unit
            }
        }

        }

        if (settingsPage == SettingsPage.DELIVERY_SECURITY) {
        item(key = "section-alert-channels") {
            Text(
                text = "การแจ้งเตือน",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.semantics { heading() },
            )
        }

        }

        if (settingsPage == SettingsPage.ADVANCED) {
        item(key = "section-advanced-diagnostics") {
            Text(
                text = "การวินิจฉัยขั้นสูง",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.semantics { heading() },
            )
            OutlinedButton(
                onClick = { advancedDiagnosticsExpanded = !advancedDiagnosticsExpanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                border = BorderStroke(1.dp, BorderNeutral),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    if (advancedDiagnosticsExpanded) "ซ่อนการวินิจฉัยขั้นสูง" else "แสดงการวินิจฉัยขั้นสูง",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (advancedDiagnosticsExpanded) {
        item(key = "sensor-fusion-settings") {
            var showAdvancedDialog by rememberSaveable { mutableStateOf(false) }
            var showNoPrimaryWarningDialog by rememberSaveable { mutableStateOf(false) }
            val configPolicy = remember { SensorConfigurationPolicy() }
            val currentConfig = state.settings.sensorConfiguration
                ?: remember { configPolicy.forPreset(SensorPreset.BALANCED) }
            val displayPreset = state.settings.sensorDisplayPreset ?: configPolicy.displayPreset(currentConfig)

            SettingsCard(title = "เซ็นเซอร์ขั้นสูงและบทบาทการตรวจจับ") {
                Text(
                    text = "ระบบตรวจจับการโจรกรรมหลายมิติแบบปรับแต่งได้",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Preset Selector
                Text(
                    text = "รูปแบบการทำงาน (Preset):",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SensorPreset.entries.forEach { preset ->
                        val isSelected = displayPreset.name == preset.name
                        val label = when (preset) {
                            SensorPreset.BALANCED -> "สมดุล (แนะนำ)"
                            SensorPreset.BATTERY_SAVER -> "ประหยัดพลังงาน"
                            SensorPreset.MAXIMUM_PROTECTION -> "ป้องกันสูงสุด"
                        }

                        if (isSelected) {
                            Button(
                                onClick = {
                                    val newConfig = configPolicy.forPreset(preset)
                                    actions.updateSensorConfiguration(newConfig)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ActionBlue,
                                    contentColor = Color.White,
                                ),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    val newConfig = configPolicy.forPreset(preset)
                                    actions.updateSensorConfiguration(newConfig)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                border = BorderStroke(1.dp, BorderNeutral),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                if (displayPreset == com.example.motorcycleantitheftsensor.protection.SensorPresetDisplay.CUSTOM) {
                    Text(
                        text = "รูปแบบ: กำหนดเอง",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = ProgressCyan,
                        ),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Capability Groups Cards
                SensorCapability.entries.forEach { capability ->
                    val capName = PresentationTextCatalog.capabilityName(capability)
                    val capConfig = currentConfig.capability(capability)
                    var sliderValue by remember(capConfig.sensitivity) { mutableIntStateOf(capConfig.sensitivity) }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = RowSurface.copy(alpha = 0.5f),
                        ),
                        border = BorderStroke(1.dp, BorderNeutral.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = capName,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "ความไวการตรวจจับ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Surface(
                                    color = ActionBlue.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, ActionBlue.copy(alpha = 0.3f)),
                                ) {
                                    Text(
                                        text = "ระดับการตรวจจับ $sliderValue/10",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = ProgressCyan,
                                        ),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            AccessibleSensitivitySlider(
                                value = sliderValue,
                                onValueChange = { newSens ->
                                    sliderValue = newSens
                                },
                                onValueChangeFinished = {
                                    val updatedConfig = configPolicy.withGroupSensitivity(currentConfig, capability, sliderValue)
                                    actions.updateSensorConfiguration(updatedConfig)
                                },
                                enabled = !state.settingsOperationInFlight,
                                sliderContentDescription = "ระดับการตรวจจับ $sliderValue เต็ม 10",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedButton(
                    onClick = { showAdvancedDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, ActionBlue.copy(alpha = 0.8f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ActionBlue),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("กำหนดบทบาทเซ็นเซอร์ขั้นสูง", fontWeight = FontWeight.SemiBold)
                }
            }

            if (showAdvancedDialog) {
                AlertDialog(
                    onDismissRequest = { showAdvancedDialog = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                    title = {
                        Text(
                            text = "กำหนดบทบาทเซ็นเซอร์ฮาร์ดแวร์",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    },
                    text = {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 440.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(SensorSource.entries.size) { index ->
                                val source = SensorSource.entries[index]
                                val srcConfig = currentConfig.source(source)
                                val srcName = PresentationTextCatalog.sourceName(source)
                                val capName = PresentationTextCatalog.capabilityName(source.capability)

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = RowSurface.copy(alpha = 0.7f),
                                    ),
                                    border = BorderStroke(1.dp, BorderNeutral),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = srcName,
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            ),
                                        )
                                        Text(
                                            text = "กลุ่ม: $capName",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )

                                        Spacer(modifier = Modifier.height(4.dp))

                                        // Role Selector (Compact 3-button row with weight to prevent overflow)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            val roles = listOf(
                                                SensorRole.PRIMARY to "หลัก",
                                                SensorRole.SUPPORTING to "ประกอบ",
                                                SensorRole.OFF to "ปิด",
                                            )

                                            roles.forEach { (role, label) ->
                                                val isSelected = srcConfig.role == role
                                                if (isSelected) {
                                                    Button(
                                                        onClick = {},
                                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .heightIn(min = 40.dp),
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = when (role) {
                                                                SensorRole.PRIMARY -> StatusGreen
                                                                SensorRole.SUPPORTING -> ActionBlue
                                                                SensorRole.OFF -> StatusRed
                                                            },
                                                            contentColor = Color.White,
                                                        ),
                                                        shape = RoundedCornerShape(8.dp),
                                                    ) {
                                                        Text(
                                                            text = label,
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontWeight = FontWeight.Bold,
                                                            ),
                                                            maxLines = 1,
                                                        )
                                                    }
                                                } else {
                                                    OutlinedButton(
                                                        onClick = {
                                                            val updatedConfig = configPolicy.withSourceRole(currentConfig, source, role)
                                                            val hasPrimary = configPolicy.armEligibility(updatedConfig) is com.example.motorcycleantitheftsensor.protection.SensorArmEligibility.Eligible
                                                            if (!hasPrimary) {
                                                                showNoPrimaryWarningDialog = true
                                                            } else {
                                                                actions.updateSensorConfiguration(updatedConfig)
                                                            }
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .heightIn(min = 40.dp),
                                                        border = BorderStroke(1.dp, BorderNeutral),
                                                        colors = ButtonDefaults.outlinedButtonColors(
                                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        ),
                                                        shape = RoundedCornerShape(8.dp),
                                                    ) {
                                                        Text(
                                                            text = label,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            maxLines = 1,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { showAdvancedDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = ActionBlue),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("เสร็จสิ้น", fontWeight = FontWeight.Bold)
                        }
                    },
                )
            }

            if (showNoPrimaryWarningDialog) {
                AlertDialog(
                    onDismissRequest = { showNoPrimaryWarningDialog = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                    title = {
                        Text(
                            text = "คำเตือน: ไม่มีเซ็นเซอร์หลัก",
                            color = StatusRed,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    text = {
                        Text(
                            text = "หากปิดเซ็นเซอร์หลักตัวสุดท้าย ระบบจะไม่สามารถตรวจจับการโจรกรรมเพื่อเริ่มส่งแจ้งเตือนได้ และจะไม่สามารถเปิดระบบป้องกัน (Arm) ได้",
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = { showNoPrimaryWarningDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("เข้าใจแล้ว", fontWeight = FontWeight.Bold)
                        }
                    },
                )
            }
        }

        }

        }

        if (settingsPage == SettingsPage.DELIVERY_SECURITY) {
        item(key = "telegram-settings") {
            SettingsCard(title = "Telegram Bot ควบคุมระยะไกล") {
                DiagnosticRow(
                    "สถานะ Bot Token",
                    if (state.settings.tokenConfigured) "ตั้งค่าแล้ว" else "ยังไม่ตั้งค่า",
                )
                if (state.settings.pairedOwnerCount > 0) {
                    DiagnosticRow("จำนวนเครื่องเจ้าของที่ผูก", "${state.settings.pairedOwnerCount} เครื่อง")
                }
                if (state.settings.pairingCode != null) {
                    var isPairingCodeRevealed by rememberSaveable { mutableStateOf(false) }
                    DisposableEffect(isPairingCodeRevealed) {
                        actions.onSecureFlagChange(isPairingCodeRevealed)
                        onDispose {
                            actions.onSecureFlagChange(false)
                        }
                    }
                    val codeDisplay = if (isPairingCodeRevealed) state.settings.pairingCode else "••••••"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            DiagnosticRow("รหัสเชื่อมต่อ (Pairing Code)", codeDisplay)
                        }
                        TextButton(
                            onClick = { isPairingCodeRevealed = !isPairingCodeRevealed },
                        ) {
                            Text(if (isPairingCodeRevealed) "ซ่อน" else "แสดง")
                        }
                    }
                }
                if (state.settings.pairedOwnerCount == 0 && state.settings.pairingCode == null) {
                    DiagnosticRow("การผูกบัญชี", "ยังไม่มีเครื่องเจ้าของผูก")
                }

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = replacementToken,
                    onValueChange = { replacementToken = it },
                    label = {
                        Text(if (state.settings.tokenConfigured) "เปลี่ยน Bot Token ใหม่" else "ใส่ Bot Token")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ActionBlue,
                        unfocusedBorderColor = BorderNeutral,
                        focusedLabelColor = ActionBlue,
                        unfocusedLabelColor = MaterialTheme.colorScheme.outline,
                    ),
                )
                Button(
                    onClick = {
                        val newToken = replacementToken
                        actions.replaceBotToken(newToken)
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    },
                    enabled = replacementToken.isNotBlank() && !state.settingsOperationInFlight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ActionBlue),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (savingBotToken) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                            Text("กำลังตรวจสอบและบันทึก...")
                        }
                    } else {
                        Text("บันทึก Bot Token", fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(
                    onClick = { confirmResetPairing = true },
                    enabled = !state.settingsOperationInFlight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.8f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("รีเซ็ตการจับคู่เจ้าของ (Reset Pairing)", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        item(key = "sms-settings") {
            SettingsCard(title = "SMS ฉุกเฉินสำรอง (SMS Fallback)") {
                DiagnosticRow(
                    "สถานะ SMS Fallback",
                    if (state.settings.smsFallbackConfigured) "พร้อมใช้งาน" else "ยังไม่ได้ตั้งค่า",
                )
                Text(
                    text = "SMS Fallback จะทำงานเฉพาะเมื่อเหตุการณ์วิกฤต (CRITICAL_BREACH) และการส่ง Telegram ล้มเหลวเท่านั้น (ไม่ส่งพิกัด GPS เพื่อความปลอดภัย)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = smsDestination,
                    onValueChange = { smsDestination = it },
                    label = { Text("เบอร์โทรศัพท์ปลายทาง (เช่น +66812345678)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ActionBlue,
                        unfocusedBorderColor = BorderNeutral,
                        focusedLabelColor = ActionBlue,
                        unfocusedLabelColor = MaterialTheme.colorScheme.outline,
                    ),
                )
                OutlinedTextField(
                    value = smsKey,
                    onValueChange = { smsKey = it },
                    label = { Text("คีย์เข้ารหัส SMS (Encryption Key)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ActionBlue,
                        unfocusedBorderColor = BorderNeutral,
                        focusedLabelColor = ActionBlue,
                        unfocusedLabelColor = MaterialTheme.colorScheme.outline,
                    ),
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
                    colors = ButtonDefaults.buttonColors(containerColor = ActionBlue),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (savingSmsFallback) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                            Text("กำลังบันทึก SMS สำรอง...")
                        }
                    } else {
                        Text("บันทึกการตั้งค่า SMS", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        }

        if (settingsPage == SettingsPage.CONTINUITY) {
        item(key = "section-continuity") {
            Text(
                text = "ความต่อเนื่องของระบบ",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.semantics { heading() },
            )
        }

        item(key = "permissions") {
            SettingsCard(title = "สิทธิการเข้าถึงของระบบ (Permissions)") {
                if (state.settings.missingPermissions.isEmpty()) {
                    DiagnosticRow("สถานะสิทธิ", "ได้รับสิทธิที่จำเป็นครบถ้วนแล้ว")
                } else {
                    Text(
                        "ฟีเจอร์บางส่วนต้องการสิทธิการเข้าถึงเพิ่มเติมเพื่อให้ครอบคลุมการทำงาน:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusAmber,
                    )
                    state.settings.missingPermissions.sorted().forEach { permission ->
                        val shortName = permission.substringAfterLast('.')
                        val blocksProtection = state.protection.permissionBlockers.any { blocker ->
                            blocker == permission || blocker == shortName
                        }
                        val severity = if (blocksProtection) {
                            "ปิดกั้นการทำงานหลัก"
                        } else {
                            "ลดความครอบคลุม"
                        }
                        DiagnosticRow(severity, friendlyPermissionName(permission))
                    }
                    Button(
                        onClick = actions.requestPermissions,
                        enabled = !state.settingsOperationInFlight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ActionBlue),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("ขอสิทธิการเข้าถึงที่ขาด", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item(key = "keep-alive-settings") {
            val context = androidx.compose.ui.platform.LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            var keepAliveStatus by remember {
                mutableStateOf(BackgroundKeepAliveManager.checkStatus(context))
            }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        keepAliveStatus = BackgroundKeepAliveManager.checkStatus(context)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            SettingsCard(title = "การทำงานเบื้องหลังตลอด 24 ชม. (Keep-Alive)") {
                Text(
                    text = "ป้องกันไม่ให้ระบบ Android หรือตัวประหยัดพลังงาน (Huawei PowerGenie, Xiaomi ฯลฯ) ปิดแอปเมื่อจอดับ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DiagnosticRow(
                    "โหมดประหยัดแบตเตอรี่",
                    if (keepAliveStatus.isBatteryOptimizedIgnored) "ยกเว้นแล้ว" else "ยังไม่ยกเว้น",
                )
                Button(
                    onClick = {
                        BackgroundKeepAliveManager.requestBatteryOptimizationExemption(context)
                        keepAliveStatus = BackgroundKeepAliveManager.checkStatus(context)
                    },
                    enabled = !keepAliveStatus.isBatteryOptimizedIgnored,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (keepAliveStatus.isBatteryOptimizedIgnored) StatusGreen else ActionBlue,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        if (keepAliveStatus.isBatteryOptimizedIgnored) "ได้รับการยกเว้นแล้ว" else "ขอยกเว้นการประหยัดแบตเตอรี่",
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (keepAliveStatus.isOemAutoStartAvailable) {
                    DiagnosticRow(
                        "การจัดการของเครื่อง (${keepAliveStatus.manufacturer})",
                        "จำเป็นต้องเปิดสิทธิ์ Auto-start",
                    )
                    OutlinedButton(
                        onClick = {
                            BackgroundKeepAliveManager.openOemAutoStartSettings(context)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        border = BorderStroke(1.dp, ActionBlue),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ActionBlue),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("เปิดการตั้งค่า Auto-Start (${keepAliveStatus.manufacturer})", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        }

        if (settingsPage == SettingsPage.ADVANCED) {
        item(key = "diagnostics") {
            SettingsCard(title = "สถานะการทำงานของระบบ (Diagnostics)") {
                DiagnosticRow(
                    "สถานะการป้องกัน",
                    PresentationTextCatalog.protectionStateLabel(state.protection.state),
                )
                DiagnosticRow(
                    "เบื้องหลัง Service",
                    if (state.protection.serviceRunning) "กำลังทำงาน" else "หยุดทำงาน",
                )
                DiagnosticRow(
                    "การเชื่อมต่อ Telegram",
                    if (state.protection.telegramReachable) "เชื่อมต่อได้" else "ไม่สามารถติดต่อได้",
                )
                DiagnosticRow(
                    "เปลี่ยนสถานะล่าสุดเมื่อ",
                    formatProtectionTimestamp(state.protection.lastTransitionAtMs),
                )
                DiagnosticRow(
                    "ระดับแบตเตอรี่",
                    state.protection.batteryLevelPercent?.let { "$it%" } ?: "ไม่มีข้อมูล",
                )
            }
        }

        item(key = "audio-diagnostics") {
            val micHealth = state.protection.sensorHealth[SensorKind.MICROPHONE]
            val audio = state.audio
            SettingsCard(title = "การวิเคราะห์เสียงคุกคาม") {
                DiagnosticRow(
                    "ฮาร์ดแวร์ไมโครโฟน",
                    microphoneHealthText(micHealth),
                )
                DiagnosticRow(
                    "สถานะ Audio Runtime",
                    audioRuntimeStateLabel(audio.state),
                )
                DiagnosticRow(
                    "ประตูสัญญาณ (Energy Gate)",
                    audioGateStateLabel(audio.gateState),
                )
                DiagnosticRow(
                    "โมเดลจำแนกเสียง (YamNet)",
                    if (audio.modelReady) "พร้อมทำงาน" else "ยังไม่พร้อม",
                )
                DiagnosticRow(
                    "ระดับความดังเสียง (dBFS)",
                    audio.approximateLevelDbfs?.let { "%.1f dBFS".format(Locale.US, it) } ?: "ไม่มีข้อมูล",
                )
                DiagnosticRow(
                    "ระดับเสียงรบกวนพื้นฐาน",
                    if (audio.baselineMedianDbfs != null && audio.baselineP95Dbfs != null) {
                        "%.1f / %.1f dBFS".format(Locale.US, audio.baselineMedianDbfs, audio.baselineP95Dbfs)
                    } else "กำลังปรับเทียบ...",
                )
                DiagnosticRow(
                    "ความหน่วงการจำแนก AI",
                    if (audio.lastInferenceMs != null && audio.averageInferenceMs != null) {
                        "${ProtectionValueFormatter.duration(audio.lastInferenceMs)} (เฉลี่ย ${ProtectionValueFormatter.duration(audio.averageInferenceMs)})"
                    } else "ไม่มีข้อมูล",
                )
                DiagnosticRow(
                    "เฟรมที่ตกหล่น / รีสตาร์ต",
                    "${audio.droppedFrames} เฟรม, รีสตาร์ต ${audio.restartCount} ครั้ง",
                )
                audio.currentCandidate?.let { candidate ->
                    DiagnosticRow(
                        "เสียงคุกคามที่ตรวจพบล่าสุด",
                        "${audioThreatCategoryLabel(candidate.category)} (${(candidate.confidence * 100).toInt()}%)",
                    )
                    audio.candidateExpiresInSeconds?.let { expires ->
                        DiagnosticRow(
                            "หมดอายุใน",
                            "${expires} วินาที",
                        )
                    }
                }
            }
        }
        }
    }

    if (confirmResetPairing) {
        AlertDialog(
            onDismissRequest = { confirmResetPairing = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "รีเซ็ตการจับคู่เจ้าของ?",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = StatusRed,
                    ),
                )
            },
            text = {
                Text(
                    text = "เครื่องเจ้าของเดิมทั้งหมดใน Telegram จะต้องใช้รหัส Pairing Code ใหม่ในการจับคู่เข้าระบบอีกครั้ง",
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmResetPairing = false
                        actions.resetPairing()
                    },
                    enabled = !state.settingsOperationInFlight,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("ยืนยันรีเซ็ต", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmResetPairing = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("ยกเลิก", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            if (loading) CircularProgressIndicator(color = ActionBlue)
            Text(
                text = if (loading) "กำลังโหลดการตั้งค่า..." else "ไม่สามารถโหลดการตั้งค่าได้",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.semantics { heading() },
            )
            error?.let { Text(it, color = StatusRed) }
            if (!loading) {
                Button(
                    onClick = retry,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ActionBlue),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("ลองใหม่อีกครั้ง")
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
    sliderContentDescription: String = "ระดับการตรวจจับ $value เต็ม 10",
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
            colors = SliderDefaults.colors(
                thumbColor = ActionBlue,
                activeTrackColor = ActionBlue,
                inactiveTrackColor = BorderNeutral,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {},
        )
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .testTag(SENSITIVITY_SLIDER_TAG)
                .semantics {
                    contentDescription = sliderContentDescription
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
private fun SettingsPageHeader(
    page: SettingsPage,
    returnToOverview: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ui.settings.${page.name}_HEADER"),
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextButton(
                onClick = returnToOverview,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            ) {
                Text("กลับไปหน้าตั้งค่า")
            }
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                ),
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = page.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.84f),
            )
        }
    }
}

@Composable
private fun SettingsOverview(
    contentPadding: PaddingValues,
    modifier: Modifier,
    openPage: (SettingsPage) -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .testTag("ui.settings.LIST"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "settings-overview-header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = SettingsPage.OVERVIEW.title,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = SettingsPage.OVERVIEW.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsPage.entries
            .filter { it != SettingsPage.OVERVIEW }
            .forEach { page ->
                item(key = "settings-page-${page.name}") {
                    SettingsOverviewCard(page = page, onClick = { openPage(page) })
                }
            }
    }
}

@Composable
private fun SettingsOverviewCard(
    page: SettingsPage,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, BorderNeutral),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = page.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "เปิดการตั้งค่า",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = ActionBlue,
                ),
            )
        }
    }
}

@Composable
private fun SettingsCategoryPage(
    page: SettingsPage,
    contentPadding: PaddingValues,
    modifier: Modifier,
    returnToOverview: () -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .testTag("ui.settings.LIST"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "settings-page-header-${page.name}") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = returnToOverview,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("กลับไปหน้าตั้งค่า")
                }
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = page.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, BorderNeutral),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }
}

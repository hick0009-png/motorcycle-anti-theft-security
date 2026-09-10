package com.example.motorcycleantitheftsensor.ui.protection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.motorcycleantitheftsensor.protection.EntryWatchLevel
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProfileSetupState
import com.example.motorcycleantitheftsensor.ui.EntryCommissioningPhase
import com.example.motorcycleantitheftsensor.ui.ProtectionAppActions
import com.example.motorcycleantitheftsensor.ui.ProtectionProfileUiState

/**
 * เข็มทิศประตู (door compass) section: guided two-cycle commissioning with live angle,
 * door-angle control 5-90° with quick choices 5/15/30, and the armed summary
 * a readiness summary / `แจ้งเมื่อเกิน N°` per spec section 9.
 */
@Composable
fun EntryGuardSection(
    profile: ProtectionProfileUiState,
    actions: ProtectionAppActions,
) {
    val commissioning = profile.commissioning
    var selectedAngle by rememberSaveable { mutableStateOf(profile.entryAngleDegrees ?: 15) }
    var selectedCloseThreshold by rememberSaveable { mutableStateOf(4) }
    var selectedAxisTolerance by rememberSaveable { mutableStateOf(16) }
    var angleControlsExpanded by rememberSaveable { mutableStateOf(false) }
    var angleSetupExpanded by rememberSaveable { mutableStateOf(false) }
    // Once commissioned the section falls to the ready branch, which only re-picks the alert
    // angle; this reopens the full setup form so an owner can recalibrate the hinge itself
    // without waiting for the model to be invalidated for them.
    var recalibrateExpanded by rememberSaveable { mutableStateOf(false) }
    val soundLevel = profile.entryLevel == EntryWatchLevel.SOUND_AND_MOVEMENT

    val maxAllowedClose = (selectedAngle - 2).coerceAtLeast(2)
    LaunchedEffect(selectedAngle) {
        if (selectedCloseThreshold > maxAllowedClose) {
            selectedCloseThreshold = maxAllowedClose
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "เข็มทิศประตู",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )

            if (commissioning != null) {
                val phaseTitle = when (commissioning.phase) {
                    EntryCommissioningPhase.STILL_CHECK -> "ขั้นตอนตรวจจับจุดนิ่ง (5 วินาที)"
                    EntryCommissioningPhase.CYCLE_ONE -> "รอบที่ 1/2: เปิดและปิดประตู"
                    EntryCommissioningPhase.CYCLE_TWO -> "รอบที่ 2/2: ยืนยันแนวแกนบานพับ"
                    EntryCommissioningPhase.COMMISSIONED -> "ปรับเทียบสำเร็จ!"
                    EntryCommissioningPhase.FAILED -> "ปรับเทียบไม่สำเร็จ กรุณาลองใหม่"
                }
                val phaseInstruction = when (commissioning.phase) {
                    EntryCommissioningPhase.STILL_CHECK -> "ปิดประตูให้สนิทและถือโทรศัพท์ให้นิ่ง 5 วินาทีเพื่อตั้งระนาบศูนย์"
                    EntryCommissioningPhase.CYCLE_ONE -> "เปิดประตูเกิน ${commissioning.selectedAngleDeg}° แล้วปิดกลับให้สนิท (ต่ำกว่า %d°)".format(commissioning.closeThresholdDeg.toInt())
                    EntryCommissioningPhase.CYCLE_TWO -> "✔ ผ่านรอบแรกแล้ว! เปิดประตูอีกครั้งเกิน ${commissioning.selectedAngleDeg}° แล้วปิดกลับให้สนิท (แกนคลาดเคลื่อนไม่เกิน %d°)".format(commissioning.axisToleranceDeg.toInt())
                    EntryCommissioningPhase.COMMISSIONED -> "บันทึกแนวบานพับเรียบร้อย พร้อมเปิดการเฝ้าระวัง"
                    EntryCommissioningPhase.FAILED -> "ระบบไม่สามารถบันทึกแนวบานพับได้ กรุณากดยกเลิกและเริ่มใหม่"
                }
                Text(
                    text = "%.1f°".format(commissioning.liveAngleDeg),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = phaseTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(text = phaseInstruction, style = MaterialTheme.typography.bodyMedium)

                if (commissioning.failureReason != null) {
                    val notice = when {
                        commissioning.failureReason.contains("axis-mismatch") ->
                            "คำแนะนำ: แนวเปิดรอบ 2 เอียงต่างจากรอบแรก กรุณาเปิด-ปิดตามแนวเดิม หรือเลือกโหมดผ่อนปรน (22°)"
                        commissioning.failureReason.contains("opposite-opening-direction") ->
                            "คำแนะนำ: ทิศทางการเปิดกลับด้าน กรุณาเปิดไปทิศทางเดิม"
                        commissioning.failureReason.contains("peak-too-small") ->
                            "คำแนะนำ: มุมเปิดยังไม่ถึง ${commissioning.selectedAngleDeg}° กรุณาเปิดให้กว้างขึ้น"
                        commissioning.failureReason.contains("missing-first-cycle") ->
                            "คำแนะนำ: ไม่พบข้อมูลรอบแรก กรุณาลองใหม่อีกครั้ง"
                        else ->
                            "คำแนะนำ: การปรับเทียบไม่ผ่านเกณฑ์ กรุณากดยกเลิกและเริ่มใหม่"
                    }
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text(
                    text = "ติดตั้งให้แน่น: ประตูโลหะขนาดใหญ่ แม่เหล็ก หรือการย้ายโทรศัพท์ ทำให้ข้อมูลเข็มทิศเสื่อม",
                    style = MaterialTheme.typography.bodySmall,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val tareEnabled = commissioning.phase == EntryCommissioningPhase.STILL_CHECK ||
                        commissioning.phase == EntryCommissioningPhase.CYCLE_ONE ||
                        commissioning.phase == EntryCommissioningPhase.CYCLE_TWO
                    OutlinedButton(
                        onClick = actions.entryTareZero,
                        enabled = tareEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text("ตั้งจุดนี้เป็น 0°")
                    }
                    Button(
                        onClick = actions.entryCancelCommissioning,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text("ยกเลิก")
                    }
                }
            } else if (
                profile.setupState == ProfileSetupState.SETUP_REQUIRED ||
                (soundLevel && angleSetupExpanded) ||
                recalibrateExpanded
            ) {
                if (soundLevel && angleSetupExpanded) {
                    // Reached by choice from level one, so it needs a way back out; the
                    // uncommissioned angle level has nothing to go back to.
                    OutlinedButton(
                        onClick = { angleSetupExpanded = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(PresentationTextCatalog.ENTRY_LEVEL_ANGLE_HIDE)
                    }
                } else if (recalibrateExpanded) {
                    // Recalibration is a choice made from a working watch, so it must be
                    // possible to back out and keep the model already in place.
                    OutlinedButton(
                        onClick = { recalibrateExpanded = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text("ยกเลิกการปรับเทียบใหม่")
                    }
                }
                Text(
                    if (recalibrateExpanded) {
                        "ปรับเทียบแนวประตูใหม่"
                    } else {
                        "ปรับเทียบตำแหน่งปิดของประตูก่อนเริ่มใช้งาน"
                    }
                )
                Text("1. มุมแจ้งเตือนเมื่อเปิดเกิน: $selectedAngle°")
                Text("แจ้งเมื่อประตูเปิดเกิน $selectedAngle° จากตำแหน่งปิด")
                Slider(
                    value = selectedAngle.toFloat(),
                    onValueChange = { selectedAngle = it.toInt().coerceIn(5, 90) },
                    valueRange = 5f..90f,
                    steps = 84,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(5, 15, 30).forEach { choice ->
                        val isSelected = choice == selectedAngle
                        if (isSelected) {
                            Button(
                                onClick = { selectedAngle = choice },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            ) {
                                Text("$choice°")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { selectedAngle = choice },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            ) {
                                Text("$choice°")
                            }
                        }
                    }
                }

                Text("2. ระยะชดเชยจุดปิดประตู (Close Deadband): $selectedCloseThreshold°")
                Text(
                    text = "ยอมรับว่าประตูปิดสนิทเมื่อมุมกลับมาต่ำกว่า $selectedCloseThreshold° (ช่วยกรณีขอบยางหรือกลอนไม่คืนที่ 0° เป๊ะ)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(3, 4, 6).forEach { choice ->
                        val isSelected = choice == selectedCloseThreshold
                        val isEnabled = choice <= maxAllowedClose
                        val label = if (choice == 4) "$choice° (แนะนำ)" else "$choice°"
                        if (isSelected) {
                            Button(
                                onClick = { selectedCloseThreshold = choice },
                                enabled = isEnabled,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            ) {
                                Text(label)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { selectedCloseThreshold = choice },
                                enabled = isEnabled,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            ) {
                                Text(label)
                            }
                        }
                    }
                }

                Text("3. ความยืดหยุ่นแนวบานพับ: $selectedAxisTolerance°")
                val axisDescription = when (selectedAxisTolerance) {
                    10 -> "โหมดแม่นยำ (10°): สำหรับประตูเหล็กหรือบานพับแน่นหนา ไม่มีการสั่นคลอน"
                    22 -> "โหมดผ่อนปรน (22°): สำหรับประตูไม้ บานพับหลวม หรือการจับถือทดสอบ"
                    else -> "โหมดทั่วไป (16° - แนะนำ): สำหรับประตูบ้านทั่วไป เปิดด้วยมือปกติ"
                }
                Text(
                    text = axisDescription,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        Triple(10, "10°", "แม่นยำ"),
                        Triple(16, "16°", "ทั่วไป"),
                        Triple(22, "22°", "ผ่อนปรน"),
                    ).forEach { (tol, angleLabel, modeLabel) ->
                        val isSelected = tol == selectedAxisTolerance
                        val buttonModifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                        val buttonPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                        if (isSelected) {
                            Button(
                                onClick = { selectedAxisTolerance = tol },
                                modifier = buttonModifier,
                                contentPadding = buttonPadding,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        text = angleLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    Text(
                                        text = modeLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { selectedAxisTolerance = tol },
                                modifier = buttonModifier,
                                contentPadding = buttonPadding,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        text = angleLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    Text(
                                        text = modeLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        // The wizard takes over on the next frame; drop the reopen flag now so
                        // finishing returns to the ready summary rather than this form again.
                        recalibrateExpanded = false
                        actions.entryStartCommissioningWithOptions(
                            selectedAngle,
                            selectedCloseThreshold.toDouble(),
                            selectedAxisTolerance.toDouble(),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("เริ่มปรับเทียบ")
                }
                // Part of setting the door watch up, not a diagnostic: what this measures
                // decides whether this phone may be offered the watch at all.
                EntryDriftMeasurementCard(
                    alertAngleDeg = selectedAngle,
                    onSetRecording = actions.setDriftRecording,
                    onClearMeasurement = actions.clearDriftMeasurement,
                )
            } else if (profile.setupState == ProfileSetupState.READY && soundLevel) {
                // Level one: armed and watching, with nothing to calibrate. The upgrade is
                // offered rather than demanded, and the owner is told plainly what it buys.
                Text(
                    text = PresentationTextCatalog.ENTRY_LEVEL_SOUND_TITLE,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(text = PresentationTextCatalog.ENTRY_LEVEL_SOUND_BODY)
                Text(
                    text = PresentationTextCatalog.ENTRY_LEVEL_ANGLE_UPGRADE_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { angleSetupExpanded = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag(ENTRY_ANGLE_UPGRADE_TAG),
                ) {
                    Text(PresentationTextCatalog.ENTRY_LEVEL_ANGLE_UPGRADE)
                }
            } else if (profile.setupState == ProfileSetupState.READY) {
                Text("พร้อมเฝ้าระวังทางเข้า", style = MaterialTheme.typography.headlineMedium)
                Text("แจ้งเมื่อเกิน ${profile.entryAngleDegrees ?: 15}° จากตำแหน่งปิด")
                if (profile.entryRequiresControlledRearm) {
                    Text(
                        text = "มุมแจ้งเตือนถูกเปลี่ยนขณะอาร์ม — ปิดระบบ ปรับเทียบ แล้วเปิดใหม่ เพื่อใช้มุมใหม่",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedButton(
                    onClick = { angleControlsExpanded = !angleControlsExpanded },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(if (angleControlsExpanded) "ซ่อนการปรับมุม" else "ปรับมุมแจ้งเตือน")
                }
                AnimatedVisibility(visible = angleControlsExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Slider(
                            value = selectedAngle.toFloat(),
                            onValueChange = { selectedAngle = it.toInt().coerceIn(5, 90) },
                            valueRange = 5f..90f,
                            steps = 84,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5, 15, 30).forEach { choice ->
                                OutlinedButton(
                                    onClick = {
                                        selectedAngle = choice
                                        actions.entrySetAngle(choice)
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text("$choice°")
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { actions.entrySetAngle(selectedAngle) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text("บันทึกมุม $selectedAngle°")
                        }
                    }
                }
                // Changing the alert angle above only moves the threshold; recalibrating
                // re-measures the closed reference and hinge axis, which is the only way to
                // fix a model that has drifted while still valid.
                OutlinedButton(
                    onClick = { recalibrateExpanded = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("ปรับเทียบประตูใหม่")
                }
            } else {
                Text("ตรวจสอบสถานะการตั้งค่าก่อนใช้งาน")
            }
        }
    }
}

/** The control that takes an owner from the sound level to the angle level. */
const val ENTRY_ANGLE_UPGRADE_TAG = "ui.protection.entry.UPGRADE_TO_ANGLE"

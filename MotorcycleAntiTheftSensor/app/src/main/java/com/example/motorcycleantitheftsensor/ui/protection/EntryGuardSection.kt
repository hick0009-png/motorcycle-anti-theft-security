package com.example.motorcycleantitheftsensor.ui.protection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    var angleControlsExpanded by rememberSaveable { mutableStateOf(false) }

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
                val phaseText = when (commissioning.phase) {
                    EntryCommissioningPhase.STILL_CHECK -> "ถือปิดประตูและวางโทรศัพท์ให้นิ่ง 5 วินาที"
                    EntryCommissioningPhase.CYCLE_ONE,
                    EntryCommissioningPhase.CYCLE_TWO,
                    -> "เปิดประตูจนเกิน ${commissioning.selectedAngleDeg}° แล้วปิดกลับ ให้ครบ 2 รอบ"
                    EntryCommissioningPhase.COMMISSIONED -> "ปรับเทียบสำเร็จ"
                    EntryCommissioningPhase.FAILED -> "ปรับเทียบไม่สำเร็จ กรุณาลองใหม่"
                }
                Text(
                    text = "%.0f°".format(commissioning.liveAngleDeg),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(text = phaseText, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "ติดตั้งให้แน่น: ประตูโลหะขนาดใหญ่ แม่เหล็ก หรือการย้ายโทรศัพท์ ทำให้ข้อมูลเข็มทิศเสื่อม",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = actions.entryCancelCommissioning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("ยกเลิกการปรับเทียบ")
                }
            } else if (profile.setupState == ProfileSetupState.SETUP_REQUIRED) {
                Text("ปรับเทียบตำแหน่งปิดของประตูก่อนเริ่มใช้งาน")
                Slider(
                    value = selectedAngle.toFloat(),
                    onValueChange = { selectedAngle = it.toInt().coerceIn(5, 90) },
                    valueRange = 5f..90f,
                    steps = 84,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 15, 30).forEach { choice ->
                        OutlinedButton(
                            onClick = { selectedAngle = choice },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("$choice°")
                        }
                    }
                }
                Text("แจ้งเมื่อประตูเปิดเกิน $selectedAngle° จากตำแหน่งปิด")
                Button(
                    onClick = { actions.entryStartCommissioning(selectedAngle) },
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
            } else {
                Text("ตรวจสอบสถานะการตั้งค่าก่อนใช้งาน")
            }
        }
    }
}

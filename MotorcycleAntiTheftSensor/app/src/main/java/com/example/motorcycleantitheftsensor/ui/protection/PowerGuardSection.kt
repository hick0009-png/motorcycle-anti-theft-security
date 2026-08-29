package com.example.motorcycleantitheftsensor.ui.protection

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.motorcycleantitheftsensor.protection.ProfileSetupState
import com.example.motorcycleantitheftsensor.ui.ChargingRowState
import com.example.motorcycleantitheftsensor.ui.ProtectionAppActions
import com.example.motorcycleantitheftsensor.ui.ProtectionProfileUiState
import com.example.motorcycleantitheftsensor.ui.PowerCommissioningPhase
import com.example.motorcycleantitheftsensor.ui.WitnessRowState
import com.example.motorcycleantitheftsensor.ui.formatProtectionTimestamp

/**
 * Power Guard section (spec sections 3.6/4.3): guided lamp off/on witness commissioning
 * with live lux, the per-arm placement confirmation, and two independent summary rows
 * (charging state and witness-light state). Neither row alone claims an outage; each
 * carries its own text, glyph, color, and a recovery instruction when action is needed.
 */
@Composable
fun PowerGuardSection(
    profile: ProtectionProfileUiState,
    actions: ProtectionAppActions,
) {
    val commissioning = profile.powerCommissioning

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
                text = "สถานะไฟเลี้ยง",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )

            if (commissioning != null) {
                val phaseText = when (commissioning.phase) {
                    PowerCommissioningPhase.DARK_WINDOW ->
                        "ขั้นที่ 1/2: ปิดไฟยืนยันทิ้งไว้ 5 วินาที (โทรศัพท์ต้องเสียบชาร์จอยู่)"
                    PowerCommissioningPhase.LIT_WINDOW ->
                        "ขั้นที่ 2/2: เปิดไฟยืนยันทิ้งไว้ 5 วินาที"
                    PowerCommissioningPhase.COMMISSIONED -> "ปรับเทียบสำเร็จ"
                    PowerCommissioningPhase.FAILED -> "ปรับเทียบไม่สำเร็จ กรุณาลองใหม่"
                }
                commissioning.liveLux?.let { lux ->
                    Text(
                        text = "%.0f lux".format(lux),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                Text(text = phaseText, style = MaterialTheme.typography.bodyLarge)
                commissioning.failureReason?.let { reason ->
                    Text(
                        text = "ช่วงแสงไม่แยกกันพอ — ตรวจสอบฝาครอบแล้วเริ่มใหม่",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = actions.powerCancelCommissioning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("ยกเลิกการปรับเทียบ")
                }
            } else if (profile.setupState == ProfileSetupState.SETUP_REQUIRED) {
                Text("ปรับเทียบไฟยืนยันก่อนเริ่มใช้งาน")
                Text(
                    text = "เสียบที่ชาร์จไว้ แล้วทำตามคำแนะนำการปิด/เปิดไฟยืนยัน",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = actions.powerStartCommissioning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("เริ่มปรับเทียบ")
                }
            } else if (profile.setupState == ProfileSetupState.READY) {
                profile.powerSummary?.let { summary ->
                    val confirmedFault = summary.confirmedFault
                    val charging = when (summary.charging) {
                        ChargingRowState.CHARGING -> Triple(
                            "●",
                            "กำลังชาร์จ",
                            PowerHealthy,
                        )
                        ChargingRowState.DISCHARGING -> Triple(
                            "○",
                            "ไม่ได้เสียบสายชาร์จ",
                            if (confirmedFault) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ChargingRowState.FULL -> Triple(
                            "●",
                            "ชาร์จเต็ม · ยังเสียบสายอยู่",
                            PowerHealthy,
                        )
                        ChargingRowState.NOT_CHARGING -> Triple(
                            "▲",
                            "ไม่ได้ชาร์จในขณะนี้",
                            if (confirmedFault) MaterialTheme.colorScheme.error else PowerWarning,
                        )
                        ChargingRowState.UNKNOWN -> Triple(
                            "◌",
                            "ยังไม่ทราบสถานะการชาร์จ",
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val witness = when (summary.witness) {
                        WitnessRowState.AVAILABLE -> Triple(
                            "○",
                            "ไฟยืนยัน: เซนเซอร์พร้อมอ่านค่า",
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        WitnessRowState.DETECTED -> Triple(
                            "●",
                            "ไฟยืนยัน: ตรวจพบแสง",
                            PowerHealthy,
                        )
                        WitnessRowState.DARK -> Triple(
                            "○",
                            "ไฟยืนยัน: ไม่พบแสง",
                            if (confirmedFault) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        WitnessRowState.UNAVAILABLE -> Triple(
                            "◌",
                            "ไฟยืนยัน: ยังยืนยันไม่ได้",
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SignalRow(glyph = charging.first, text = charging.second, tint = charging.third)
                    SignalRow(glyph = witness.first, text = witness.second, tint = witness.third)
                    Text(
                        text = "อัปเดตล่าสุด: ${summary.lastUpdatedAtMs?.let(::formatProtectionTimestamp) ?: "ยังไม่มีข้อมูล"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (confirmedFault) {
                        RecoveryHint("ตรวจสอบแหล่งจ่ายไฟและไฟยืนยัน")
                    }
                }
            } else {
                Text("ตรวจสอบสถานะการตั้งค่าก่อนใช้งาน")
            }
        }
    }
}

private val PowerHealthy = Color(0xFF047857)
private val PowerWarning = Color(0xFFB45309)

@Composable
private fun SignalRow(glyph: String, text: String, tint: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = glyph, color = tint)
        Text(text = text)
    }
}

@Composable
private fun RecoveryHint(instruction: String) {
    Text(
        text = instruction,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp),
    )
}

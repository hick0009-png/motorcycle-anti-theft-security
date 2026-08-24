package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.AudioRuntimeState
import com.example.motorcycleantitheftsensor.protection.ChargingState
import com.example.motorcycleantitheftsensor.protection.DeliveryState
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.IncidentSeverity
import com.example.motorcycleantitheftsensor.protection.IncidentSummary
import com.example.motorcycleantitheftsensor.protection.IncidentType
import com.example.motorcycleantitheftsensor.protection.LightHealthDetail
import com.example.motorcycleantitheftsensor.protection.LocationHealthDetail
import com.example.motorcycleantitheftsensor.protection.LocationTrackingState
import com.example.motorcycleantitheftsensor.protection.MicrophoneHealthDetail
import com.example.motorcycleantitheftsensor.protection.PowerThermalHealthDetail
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.protection.SensorHealthState
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.VibrationHealthDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class ProtectionStatusFormatterTest {

    private val formatter = ProtectionStatusFormatter()

    @Test
    fun test01_healthyFullReportExactTemplate() {
        val nowMs = 1_700_000_000_000L // arbitrary fixed timestamp
        val snapshot = ProtectionSnapshot.offline(nowMs - 24 * 60 * 1000L).copy(
            state = ProtectionState.ARMED_HEALTHY,
            protectionActivatedAtMs = nowMs - 24 * 60 * 1000L,
            sensitivityLevel = 8,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 1_000L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowMs - 3_000L,
            sensorHealth = mapOf(
                SensorKind.VIBRATION to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    vibrationDetail = VibrationHealthDetail(
                        hardwareAvailable = true,
                        isRegistered = true,
                        lastSampleElapsedMs = nowMs - 1_000L,
                    ),
                ),
                SensorKind.LIGHT to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    lightDetail = LightHealthDetail(
                        hardwareSupported = true,
                        isRegistered = true,
                        lastLux = 126.0,
                        lastSampleWallClockMs = nowMs - 2_000L,
                        lastSampleElapsedMs = nowMs - 2_000L,
                    ),
                ),
                SensorKind.MICROPHONE to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    microphoneDetail = MicrophoneHealthDetail(
                        audioState = AudioRuntimeState.LISTENING,
                        isRegistered = true,
                        modelReady = true,
                        lastAudioSampleElapsedMs = nowMs - 1_000L,
                    ),
                ),
                SensorKind.LOCATION to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    locationDetail = LocationHealthDetail(
                        trackingState = LocationTrackingState.TRACKING,
                        isRegistered = true,
                        lastFixWallClockMs = nowMs - 6_000L,
                        lastFixElapsedMs = nowMs - 6_000L,
                        accuracyMeters = 19.0f,
                    ),
                ),
                SensorKind.POWER_THERMAL to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    powerThermalDetail = PowerThermalHealthDetail(
                        sourceAvailable = true,
                        isRegistered = true,
                    ),
                ),
            ),
            batteryLevelPercent = 100,
            batteryTemperatureCelsius = 32.0f,
            chargingState = ChargingState.CHARGING,
            lastIncident = IncidentSummary(
                id = "vibration-incident-1",
                severity = IncidentSeverity.WARNING,
                lifecycle = IncidentLifecycle.CLOSED,
                updatedAtMs = nowMs - 60_000L,
                deliveryState = DeliveryState.SENT,
                type = IncidentType.VIBRATION,
            ),
            lastDeliveryState = DeliveryState.SENT,
        )

        val output = formatter.format(snapshot, nowMs, nowMs)

        val expectedLines = listOf(
            "🛡️ สถานะระบบป้องกัน",
            "",
            "สถานะระบบ: กำลังป้องกัน",
            "ทำงานมาแล้ว: 24 นาที",
            "[ 🏃 การเคลื่อนไหว ]",
            "ความไวการตรวจจับ: 8/10",
            "",
            "📡 ระบบหลัก",
            "✅ Service: ทำงาน",
            "✅ Telegram: เชื่อมต่อ | ติดต่อล่าสุด 3 วินาทีที่แล้ว",
            "",
            "🔎 เซนเซอร์กำลังตรวจจับ: 5/5",
            "✅ การสั่น: ทำงาน | ล่าสุด 1 วินาที",
            "✅ แสง: ทำงาน | 126 lux | ล่าสุด 2 วินาที",
            "✅ ไมโครโฟน: กำลังฟัง | ตัวจำแนกเสียงพร้อม",
            "✅ GPS: กำลังติดตาม | ล่าสุด 6 วินาที | ±19 เมตร",
            "✅ พลังงาน/อุณหภูมิ: ทำงาน",
            "",
            "🔋 แบตเตอรี่: 100%",
            "🌡️ อุณหภูมิเครื่อง (แบตเตอรี่): 32.0°C",
            "🔌 แหล่งจ่ายไฟ: กำลังชาร์จ",
            "",
            "🚨 เหตุการณ์ล่าสุด",
            "ประเภท: ตรวจพบการสั่น",
            "สถานะ: เหตุการณ์สิ้นสุดแล้ว",
            "📤 Telegram: ส่งสำเร็จ",
            "",
            "✅ ระบบทำงานครบ ไม่พบปัญหา",
        )

        expectedLines.forEach { line ->
            if (line.isNotEmpty()) {
                assertTrue("Output should contain line: '$line'", output.contains(line))
            }
        }
    }

    @Test
    fun test02_sectionOrderIsConstant() {
        val nowMs = 1_000_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 1_000L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowMs - 1_000L,
            sensorHealth = mapOf(
                SensorKind.VIBRATION to SensorHealth(SensorHealthState.HEALTHY, vibrationDetail = VibrationHealthDetail(isRegistered = true, lastSampleElapsedMs = nowMs)),
                SensorKind.LIGHT to SensorHealth(SensorHealthState.HEALTHY, lightDetail = LightHealthDetail(hardwareSupported = true, isRegistered = true, lastSampleElapsedMs = nowMs)),
                SensorKind.MICROPHONE to SensorHealth(SensorHealthState.HEALTHY, microphoneDetail = MicrophoneHealthDetail(audioState = AudioRuntimeState.LISTENING, isRegistered = true, modelReady = true, lastAudioSampleElapsedMs = nowMs)),
                SensorKind.LOCATION to SensorHealth(SensorHealthState.HEALTHY, locationDetail = LocationHealthDetail(trackingState = LocationTrackingState.TRACKING, isRegistered = true, lastFixElapsedMs = nowMs, accuracyMeters = 10f)),
                SensorKind.POWER_THERMAL to SensorHealth(SensorHealthState.HEALTHY, powerThermalDetail = PowerThermalHealthDetail(sourceAvailable = true, isRegistered = true)),
            ),
            lastIncident = IncidentSummary(
                id = "incident-1",
                severity = IncidentSeverity.WARNING,
                lifecycle = IncidentLifecycle.OPEN,
                updatedAtMs = nowMs,
                deliveryState = DeliveryState.PENDING,
            ),
        )

        val output = formatter.format(snapshot, nowMs, nowMs)

        val idxHeader = output.indexOf("🛡️ สถานะระบบป้องกัน")
        val idxMain = output.indexOf("📡 ระบบหลัก")
        val idxSensors = output.indexOf("🔎 เซนเซอร์")
        val idxBattery = output.indexOf("🔋 แบตเตอรี่:")
        val idxIncident = output.indexOf("🚨 เหตุการณ์ล่าสุด")
        val idxIssues = output.indexOf("✅ ระบบทำงานครบ ไม่พบปัญหา")

        assertTrue(idxHeader >= 0)
        assertTrue(idxMain > idxHeader)
        assertTrue(idxSensors > idxMain)
        assertTrue(idxBattery > idxSensors)
        assertTrue(idxIncident > idxBattery)
        assertTrue(idxIssues > idxIncident)
    }

    @Test
    fun test03_armDurationThaiFormats() {
        val nowMs = 1_700_000_000_000L

        // 10 seconds
        val snap10s = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            protectionActivatedAtMs = nowMs - 10_000L,
        )
        assertTrue(formatter.format(snap10s, nowMs, nowMs).contains("ทำงานมาแล้ว: 10 วินาที"))

        // 24 minutes
        val snap24m = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            protectionActivatedAtMs = nowMs - 24 * 60 * 1000L,
        )
        assertTrue(formatter.format(snap24m, nowMs, nowMs).contains("ทำงานมาแล้ว: 24 นาที"))

        // 3 hours 12 minutes
        val snap3h12m = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            protectionActivatedAtMs = nowMs - (3 * 3600 + 12 * 60) * 1000L,
        )
        assertTrue(formatter.format(snap3h12m, nowMs, nowMs).contains("ทำงานมาแล้ว: 3 ชั่วโมง 12 นาที"))

        // 2 days 5 hours
        val snap2d5h = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            protectionActivatedAtMs = nowMs - (2 * 86400 + 5 * 3600) * 1000L,
        )
        assertTrue(formatter.format(snap2d5h, nowMs, nowMs).contains("ทำงานมาแล้ว: 2 วัน 5 ชั่วโมง"))
    }

    @Test
    fun test04_disarmedReportHeaderAndSensorLines() {
        val nowMs = 1_000_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.DISARMED_ONLINE,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 1_000L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowMs - 1_000L,
            sensorHealth = mapOf(
                SensorKind.VIBRATION to SensorHealth(SensorHealthState.AVAILABLE),
                SensorKind.LIGHT to SensorHealth(SensorHealthState.AVAILABLE, lightDetail = LightHealthDetail(hardwareSupported = true)),
                SensorKind.MICROPHONE to SensorHealth(SensorHealthState.AVAILABLE),
                SensorKind.LOCATION to SensorHealth(SensorHealthState.AVAILABLE),
                SensorKind.POWER_THERMAL to SensorHealth(SensorHealthState.AVAILABLE),
            ),
        )

        val output = formatter.format(snapshot, nowMs, nowMs)
        assertTrue(output.contains("สถานะระบบ: ปลดการป้องกันแล้ว"))
        assertFalse("shared status must not use vehicle-only wording", output.contains("สถานะรถ"))
        assertFalse(output.contains("ทำงานมาแล้ว:"))
        assertTrue(output.contains("🔎 เซนเซอร์: หยุดตามคำสั่ง Disarm | พร้อมใช้งาน 5/5"))
        assertTrue(output.contains("✅ การสั่น: พร้อมใช้งาน"))
        assertTrue(output.contains("✅ แสง: พร้อมใช้งาน"))
        assertTrue(output.contains("✅ ไมโครโฟน: พร้อมใช้งาน"))
        assertTrue(output.contains("✅ GPS: พร้อมใช้งาน"))
        assertTrue(output.contains("✅ พลังงาน/อุณหภูมิ: พร้อมใช้งาน"))
        assertTrue(output.contains("✅ ระบบทำงานครบ ไม่พบปัญหา"))
    }

    @Test
    fun test05_armingReportHeader() {
        val nowMs = 1_000_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMING,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 1_000L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowMs - 1_000L,
            sensorHealth = mapOf(
                SensorKind.VIBRATION to SensorHealth(SensorHealthState.AVAILABLE),
                SensorKind.LIGHT to SensorHealth(SensorHealthState.AVAILABLE, lightDetail = LightHealthDetail(hardwareSupported = true)),
                SensorKind.MICROPHONE to SensorHealth(SensorHealthState.AVAILABLE),
                SensorKind.LOCATION to SensorHealth(SensorHealthState.AVAILABLE),
                SensorKind.POWER_THERMAL to SensorHealth(SensorHealthState.AVAILABLE),
            ),
        )

        val output = formatter.format(snapshot, nowMs, nowMs)
        assertTrue(output.contains("สถานะระบบ: กำลังเริ่มการป้องกัน (รอการเปิดระบบ)"))
        assertTrue(output.contains("🔎 เซนเซอร์: กำลังเริ่มการทำงาน | พร้อมใช้งาน 5/5"))
    }

    @Test
    fun test06_staleGpsFixWithThaiGuidance() {
        val nowMs = 1_000_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_DEGRADED,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 1_000L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowMs - 1_000L,
            sensorHealth = mapOf(
                SensorKind.VIBRATION to SensorHealth(SensorHealthState.HEALTHY, vibrationDetail = VibrationHealthDetail(isRegistered = true, lastSampleElapsedMs = nowMs)),
                SensorKind.LIGHT to SensorHealth(SensorHealthState.HEALTHY, lightDetail = LightHealthDetail(hardwareSupported = true, isRegistered = true, lastSampleElapsedMs = nowMs)),
                SensorKind.MICROPHONE to SensorHealth(SensorHealthState.HEALTHY, microphoneDetail = MicrophoneHealthDetail(audioState = AudioRuntimeState.LISTENING, isRegistered = true, modelReady = true, lastAudioSampleElapsedMs = nowMs)),
                SensorKind.LOCATION to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    locationDetail = LocationHealthDetail(
                        trackingState = LocationTrackingState.TRACKING,
                        isRegistered = true,
                        lastFixWallClockMs = nowMs - 47_000L,
                        lastFixElapsedMs = nowMs - 47_000L,
                    ),
                ),
                SensorKind.POWER_THERMAL to SensorHealth(SensorHealthState.HEALTHY, powerThermalDetail = PowerThermalHealthDetail(sourceAvailable = true, isRegistered = true)),
            ),
        )

        val output = formatter.format(snapshot, nowMs, nowMs)
        assertTrue(output.contains("🔎 เซนเซอร์กำลังตรวจจับ: 4/5"))
        assertTrue(output.contains("⚠️ GPS: พิกัดล่าสุด 47 วินาทีที่แล้ว | ข้อมูลเก่า"))
        assertTrue(output.contains("วิธีแก้: ตรวจว่าเปิดตำแหน่งและวางโทรศัพท์ในจุดรับสัญญาณได้"))
    }

    @Test
    fun test07_missingMicrophonePermissionWithThaiGuidance() {
        val nowMs = 1_000_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_DEGRADED,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 1_000L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowMs - 1_000L,
            permissionBlockers = setOf("RECORD_AUDIO"),
            sensorHealth = mapOf(
                SensorKind.MICROPHONE to SensorHealth(
                    state = SensorHealthState.UNAVAILABLE,
                    microphoneDetail = MicrophoneHealthDetail(
                        permissionGranted = false,
                    ),
                ),
            ),
        )

        val output = formatter.format(snapshot, nowMs, nowMs)
        assertTrue(output.contains("❌ ไมโครโฟน: ไม่มีสิทธิ์ใช้งาน"))
        assertTrue(output.contains("วิธีแก้: เปิดสิทธิ์ Microphone ในการตั้งค่าแอป แล้ว Arm ใหม่"))
    }

    @Test
    fun test08_privacyAssertionsStrictNoLeaks() {
        val nowMs = 1_000_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 1_000L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowMs - 1_000L,
            sensorHealth = mapOf(
                SensorKind.LOCATION to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    locationDetail = LocationHealthDetail(
                        trackingState = LocationTrackingState.TRACKING,
                        isRegistered = true,
                        lastFixWallClockMs = nowMs - 5_000L,
                        lastFixElapsedMs = nowMs - 5_000L,
                        accuracyMeters = 15.0f,
                    ),
                ),
            ),
        )

        val output = formatter.format(snapshot, nowMs, nowMs)

        // Must not contain tokens, chat IDs, full coordinates, or map URLs
        assertFalse("Must not leak bot token", output.contains("bot", ignoreCase = false))
        assertFalse("Must not leak chat ID", output.contains("chat_id", ignoreCase = true))
        assertFalse("Must not leak map urls", output.contains("maps.google.com", ignoreCase = true))
        assertFalse("Must not leak latitude", output.contains("latitude", ignoreCase = true))
        assertFalse("Must not leak longitude", output.contains("longitude", ignoreCase = true))
        assertFalse("Must not leak stack traces", output.contains("Exception") || output.contains("StackTrace"))

        // Coordinate pattern (e.g. 13.7563, 100.5018) must not exist
        val coordPattern = Pattern.compile("\\b\\d{1,3}\\.\\d{4,}\\b")
        assertFalse("Must not contain precise decimal coordinates", coordPattern.matcher(output).find())
    }

    @Test
    fun test09_nullSafetyOnEmptySnapshot() {
        val snapshot = ProtectionSnapshot.offline(0L)
        val output = formatter.format(snapshot, 0L, 0L)

        assertTrue(output.contains("สถานะระบบ: ออฟไลน์"))
        assertTrue(output.contains("❌ Service: ออฟไลน์"))
        assertTrue(output.contains("❌ Telegram: ขาดการเชื่อมต่อ"))
        assertTrue(output.contains("🔋 แบตเตอรี่: ยังไม่มีข้อมูล"))
        assertTrue(output.contains("🌡️ อุณหภูมิเครื่อง (แบตเตอรี่): ยังไม่มีข้อมูล"))
        assertTrue(output.contains("🔌 แหล่งจ่ายไฟ: ยังไม่มีข้อมูล"))
    }
}

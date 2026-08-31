package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.AudioRuntimeState
import com.example.motorcycleantitheftsensor.protection.ChargingState
import com.example.motorcycleantitheftsensor.protection.DeliveryState
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.IncidentSeverity
import com.example.motorcycleantitheftsensor.protection.IncidentSummary
import com.example.motorcycleantitheftsensor.protection.IncidentType
import com.example.motorcycleantitheftsensor.protection.LightHealthDetail
import com.example.motorcycleantitheftsensor.protection.LocationFailureCode
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionStatusProjectionTest {

    @Test
    fun test01_healthyFullReportAndAllFiveSensors() {
        val nowMs = 1_700_000_000_000L
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
                    lastSampleAtMs = nowMs - 1_000L,
                    vibrationDetail = VibrationHealthDetail(
                        hardwareAvailable = true,
                        isRegistered = true,
                        lastSampleElapsedMs = nowMs - 1_000L,
                    ),
                ),
                SensorKind.LIGHT to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    lastSampleAtMs = nowMs - 2_000L,
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
                    lastSampleAtMs = nowMs - 1_000L,
                    microphoneDetail = MicrophoneHealthDetail(
                        audioState = AudioRuntimeState.LISTENING,
                        isRegistered = true,
                        modelReady = true,
                        lastAudioSampleElapsedMs = nowMs - 1_000L,
                        permissionGranted = true,
                    ),
                ),
                SensorKind.LOCATION to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    lastSampleAtMs = nowMs - 6_000L,
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
                        chargingState = ChargingState.CHARGING,
                        batteryLevelPercent = 100,
                        temperatureCelsius = 32.0f,
                    ),
                ),
            ),
            batteryLevelPercent = 100,
            batteryTemperatureCelsius = 32.0f,
            chargingState = ChargingState.CHARGING,
            lastIncident = IncidentSummary(
                id = "tamper-001",
                severity = IncidentSeverity.WARNING,
                lifecycle = IncidentLifecycle.CLOSED,
                updatedAtMs = nowMs - 100_000L,
                deliveryState = DeliveryState.SENT,
                type = IncidentType.VIBRATION,
            ),
            lastDeliveryState = DeliveryState.SENT,
        )

        val projection = ProtectionStatusProjection.evaluate(snapshot, nowMs, nowMs)

        assertEquals("กำลังป้องกัน", projection.protectionState.displayStatusTh)
        assertEquals("24 นาที", projection.protectionState.armDurationTh)
        assertEquals("[ 🏃 การเคลื่อนไหว ]\nความไวการตรวจจับ: 8/10", projection.protectionState.sensitivityTh)

        assertEquals("✅ Service: ทำงาน", projection.primarySystems.serviceStatusTh)
        assertTrue(projection.primarySystems.serviceHealthy)
        assertEquals("✅ Telegram: เชื่อมต่อ | ติดต่อล่าสุด 3 วินาทีที่แล้ว", projection.primarySystems.telegramStatusTh)
        assertTrue(projection.primarySystems.telegramHealthy)

        assertEquals("🔎 เซนเซอร์กำลังตรวจจับ: 5/5", projection.sensorSummary.headerTh)
        assertEquals(5, projection.sensorSummary.activeCount)
        assertEquals(5, projection.sensorSummary.readyCount)

        assertEquals("✅ การสั่น: ทำงาน | ล่าสุด 1 วินาที", projection.sensorSummary.sensors[SensorKind.VIBRATION]?.statusLineTh)
        assertEquals("✅ แสง: ทำงาน | 126 lux | ล่าสุด 2 วินาที", projection.sensorSummary.sensors[SensorKind.LIGHT]?.statusLineTh)
        assertEquals("✅ ไมโครโฟน: กำลังฟัง | ตัวจำแนกเสียงพร้อม", projection.sensorSummary.sensors[SensorKind.MICROPHONE]?.statusLineTh)
        assertEquals("✅ GPS: กำลังติดตาม | ล่าสุด 6 วินาที | ±19 เมตร", projection.sensorSummary.sensors[SensorKind.LOCATION]?.statusLineTh)
        assertEquals("✅ พลังงาน/อุณหภูมิ: ทำงาน", projection.sensorSummary.sensors[SensorKind.POWER_THERMAL]?.statusLineTh)

        assertEquals("🔋 แบตเตอรี่: 100%", projection.batteryPower.batteryPercentTh)
        assertEquals("🌡️ อุณหภูมิเครื่อง (แบตเตอรี่): 32.0°C", projection.batteryPower.temperatureTh)
        assertEquals("🔌 สายชาร์จ: เสียบอยู่ | กำลังชาร์จ", projection.batteryPower.powerSourceTh)

        assertNotNull(projection.lastIncident)
        assertEquals("ตรวจพบการสั่น", projection.lastIncident?.typeTh)
        assertEquals("เหตุการณ์สิ้นสุดแล้ว", projection.lastIncident?.statusTh)
        assertEquals("ส่งสำเร็จ", projection.lastIncident?.deliveryTh)

        assertFalse(projection.issuesSummary.hasIssues)
        assertEquals("✅ ระบบทำงานครบ ไม่พบปัญหา", projection.issuesSummary.summaryMessageTh)
    }

    @Test
    fun test02_gpsTrackingWaitingForFirstFix() {
        val nowMs = 100_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 1_000L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowMs - 1_000L,
            sensorHealth = mapOf(
                SensorKind.LOCATION to SensorHealth(
                    state = SensorHealthState.AVAILABLE,
                    locationDetail = LocationHealthDetail(
                        trackingState = LocationTrackingState.WAITING_FOR_FIX,
                        isRegistered = true,
                        lastFixWallClockMs = null,
                    ),
                ),
            ),
        )

        val projection = ProtectionStatusProjection.evaluate(snapshot, nowMs, nowMs)
        val gps = projection.sensorSummary.sensors[SensorKind.LOCATION]
        assertNotNull(gps)
        assertEquals("⏳ GPS: กำลังติดตาม | รอพิกัดแรก", gps?.statusLineTh)
        assertTrue(gps?.isReadyOrWaiting == true)
        assertFalse(gps?.isStale == true)
    }

    @Test
    fun test03_gpsFreshFixWithAgeAndAccuracy() {
        val nowMs = 100_000L
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
                        lastFixWallClockMs = nowMs - 6_000L,
                        lastFixElapsedMs = nowMs - 6_000L,
                        accuracyMeters = 19.4f,
                    ),
                ),
            ),
        )

        val projection = ProtectionStatusProjection.evaluate(snapshot, nowMs, nowMs)
        val gps = projection.sensorSummary.sensors[SensorKind.LOCATION]
        assertEquals("✅ GPS: กำลังติดตาม | ล่าสุด 6 วินาที | ±19 เมตร", gps?.statusLineTh)
        assertTrue(gps?.isHealthyOrWorking == true)
    }

    @Test
    fun test04_gpsFixOver30SecondsIsStale() {
        val nowMs = 100_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_DEGRADED,
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
                        lastFixWallClockMs = nowMs - 47_000L,
                        lastFixElapsedMs = nowMs - 47_000L,
                        accuracyMeters = 20.0f,
                    ),
                ),
            ),
        )

        val projection = ProtectionStatusProjection.evaluate(snapshot, nowMs, nowMs)
        val gps = projection.sensorSummary.sensors[SensorKind.LOCATION]
        assertEquals("⚠️ GPS: พิกัดล่าสุด 47 วินาทีที่แล้ว | ข้อมูลเก่า", gps?.statusLineTh)
        assertTrue(gps?.isStale == true)
        assertFalse(gps?.isHealthyOrWorking == true)
        assertEquals("ตรวจว่าเปิดตำแหน่งและวางโทรศัพท์ในจุดรับสัญญาณได้", gps?.issueRecommendation?.guidanceTh)
    }

    @Test
    fun test05_gpsStoppedOnlyIssueInArmedOrAlert() {
        val nowMs = 100_000L
        // During ARMED, stopped is an issue
        val armedSnapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_DEGRADED,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 1_000L,
            sensorHealth = mapOf(
                SensorKind.LOCATION to SensorHealth(
                    state = SensorHealthState.UNAVAILABLE,
                    locationDetail = LocationHealthDetail(
                        trackingState = LocationTrackingState.STOPPED,
                        isRegistered = false,
                    ),
                ),
            ),
        )
        val armedProj = ProtectionStatusProjection.evaluate(armedSnapshot, nowMs, nowMs)
        assertEquals("❌ GPS: หยุดทำงาน", armedProj.sensorSummary.sensors[SensorKind.LOCATION]?.statusLineTh)
        assertTrue(armedProj.issuesSummary.hasIssues)

        // During DISARMED, stopped is NOT an issue
        val disarmedSnapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.DISARMED_ONLINE,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 1_000L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowMs - 1_000L,
            sensorHealth = mapOf(
                SensorKind.LOCATION to SensorHealth(
                    state = SensorHealthState.AVAILABLE,
                    locationDetail = LocationHealthDetail(isRegistered = false),
                ),
            ),
        )
        val disarmedProj = ProtectionStatusProjection.evaluate(disarmedSnapshot, nowMs, nowMs)
        assertEquals("✅ GPS: พร้อมใช้งาน", disarmedProj.sensorSummary.sensors[SensorKind.LOCATION]?.statusLineTh)
        assertFalse(disarmedProj.issuesSummary.hasIssues)
    }

    @Test
    fun test06_disarmShowsStoppedByCommandWithoutFalseAlarm() {
        val nowMs = 100_000L
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

        val projection = ProtectionStatusProjection.evaluate(snapshot, nowMs, nowMs)
        assertEquals("ปลดการป้องกันแล้ว", projection.protectionState.displayStatusTh)
        assertNull(projection.protectionState.armDurationTh)
        assertEquals("🔎 เซนเซอร์: หยุดตามคำสั่ง Disarm | พร้อมใช้งาน 5/5", projection.sensorSummary.headerTh)
        assertFalse(projection.issuesSummary.hasIssues)
    }

    @Test
    fun test07_alertActiveMaintainsGpsTracking() {
        val nowMs = 100_000L
        val snapshot = ProtectionSnapshot.offline(nowMs - 60_000L).copy(
            state = ProtectionState.ALERT_ACTIVE,
            protectionActivatedAtMs = nowMs - 60_000L,
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
                        lastFixWallClockMs = nowMs - 2_000L,
                        lastFixElapsedMs = nowMs - 2_000L,
                        accuracyMeters = 15.0f,
                    ),
                ),
            ),
        )

        val projection = ProtectionStatusProjection.evaluate(snapshot, nowMs, nowMs)
        assertEquals("🚨 กำลังส่งสัญญาณเตือน", projection.protectionState.displayStatusTh)
        assertEquals("1 นาที", projection.protectionState.armDurationTh)
        assertEquals("✅ GPS: กำลังติดตาม | ล่าสุด 2 วินาที | ±15 เมตร", projection.sensorSummary.sensors[SensorKind.LOCATION]?.statusLineTh)
    }

    @Test
    fun test08_microphoneListeningAndModelReady() {
        val nowMs = 100_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 1_000L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowMs - 1_000L,
            sensorHealth = mapOf(
                SensorKind.MICROPHONE to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    microphoneDetail = MicrophoneHealthDetail(
                        audioState = AudioRuntimeState.LISTENING,
                        isRegistered = true,
                        modelReady = true,
                        lastAudioSampleElapsedMs = nowMs - 1_000L,
                    ),
                ),
            ),
        )

        val projection = ProtectionStatusProjection.evaluate(snapshot, nowMs, nowMs)
        assertEquals("✅ ไมโครโฟน: กำลังฟัง | ตัวจำแนกเสียงพร้อม", projection.sensorSummary.sensors[SensorKind.MICROPHONE]?.statusLineTh)
    }

    @Test
    fun test09_microphoneCalibrating() {
        val nowMs = 100_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 1_000L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowMs - 1_000L,
            sensorHealth = mapOf(
                SensorKind.MICROPHONE to SensorHealth(
                    state = SensorHealthState.AVAILABLE,
                    microphoneDetail = MicrophoneHealthDetail(
                        audioState = AudioRuntimeState.CALIBRATING,
                        isRegistered = true,
                    ),
                ),
            ),
        )

        val projection = ProtectionStatusProjection.evaluate(snapshot, nowMs, nowMs)
        assertEquals("⏳ ไมโครโฟน: กำลังคาลิเบรต", projection.sensorSummary.sensors[SensorKind.MICROPHONE]?.statusLineTh)
    }

    @Test
    fun test10_microphonePermissionMissingWithThaiGuidance() {
        val nowMs = 100_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_DEGRADED,
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

        val projection = ProtectionStatusProjection.evaluate(snapshot, nowMs, nowMs)
        val mic = projection.sensorSummary.sensors[SensorKind.MICROPHONE]
        assertEquals("❌ ไมโครโฟน: ไม่มีสิทธิ์ใช้งาน", mic?.statusLineTh)
        assertEquals("เปิดสิทธิ์ Microphone ในการตั้งค่าแอป แล้ว Arm ใหม่", mic?.issueRecommendation?.guidanceTh)
    }

    @Test
    fun test11_microphoneStaleAfter5Seconds() {
        val nowMs = 100_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_DEGRADED,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 1_000L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowMs - 1_000L,
            sensorHealth = mapOf(
                SensorKind.MICROPHONE to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    microphoneDetail = MicrophoneHealthDetail(
                        audioState = AudioRuntimeState.LISTENING,
                        isRegistered = true,
                        modelReady = true,
                        lastAudioSampleElapsedMs = nowMs - 6_000L,
                    ),
                ),
            ),
        )

        val projection = ProtectionStatusProjection.evaluate(snapshot, nowMs, nowMs)
        val mic = projection.sensorSummary.sensors[SensorKind.MICROPHONE]
        assertEquals("⚠️ ไมโครโฟน: ข้อมูลเก่า", mic?.statusLineTh)
        assertTrue(mic?.isStale == true)
    }

    @Test
    fun test12_lightHardwareUnavailable() {
        val nowMs = 100_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            degradationReasons = setOf("LIGHT unavailable"),
            sensorHealth = mapOf(
                SensorKind.VIBRATION to SensorHealth(SensorHealthState.AVAILABLE),
                SensorKind.LIGHT to SensorHealth(
                    state = SensorHealthState.UNAVAILABLE,
                    lightDetail = LightHealthDetail(hardwareSupported = false),
                ),
                SensorKind.MICROPHONE to SensorHealth(SensorHealthState.AVAILABLE),
                SensorKind.LOCATION to SensorHealth(SensorHealthState.AVAILABLE),
                SensorKind.POWER_THERMAL to SensorHealth(SensorHealthState.AVAILABLE),
            ),
        )

        val projection = ProtectionStatusProjection.evaluate(snapshot, nowMs, nowMs)
        val light = projection.sensorSummary.sensors[SensorKind.LIGHT]
        assertEquals("⚠️ แสง: เครื่องนี้ไม่รองรับ", light?.statusLineTh)
        assertTrue(light?.isHardwareUnsupported == true)
        assertEquals(4, projection.sensorSummary.readyCount)
    }

    @Test
    fun test13_freshnessBoundaryVibrationAndLightAt5Seconds() {
        val nowMs = 100_000L
        val freshSnapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            sensorHealth = mapOf(
                SensorKind.VIBRATION to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    vibrationDetail = VibrationHealthDetail(
                        isRegistered = true,
                        lastSampleElapsedMs = nowMs - 5_000L,
                    ),
                ),
            ),
        )
        val freshProj = ProtectionStatusProjection.evaluate(freshSnapshot, nowMs, nowMs)
        assertEquals("✅ การสั่น: ทำงาน | ล่าสุด 5 วินาที", freshProj.sensorSummary.sensors[SensorKind.VIBRATION]?.statusLineTh)

        val staleSnapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_DEGRADED,
            sensorHealth = mapOf(
                SensorKind.VIBRATION to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    vibrationDetail = VibrationHealthDetail(
                        isRegistered = true,
                        lastSampleElapsedMs = nowMs - 5_001L,
                    ),
                ),
            ),
        )
        val staleProj = ProtectionStatusProjection.evaluate(staleSnapshot, nowMs, nowMs)
        assertEquals("⚠️ การสั่น: ข้อมูลเก่า", staleProj.sensorSummary.sensors[SensorKind.VIBRATION]?.statusLineTh)
    }

    @Test
    fun test14_batteryLevelAndTemperatureNotSwapped() {
        val nowMs = 100_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            batteryLevelPercent = 85,
            batteryTemperatureCelsius = 37.5f,
        )

        val projection = ProtectionStatusProjection.evaluate(snapshot, nowMs, nowMs)
        assertEquals("🔋 แบตเตอรี่: 85%", projection.batteryPower.batteryPercentTh)
        assertEquals("🌡️ อุณหภูมิเครื่อง (แบตเตอรี่): 37.5°C", projection.batteryPower.temperatureTh)
    }

    @Test
    fun test15_chargingStateMapping() {
        val nowMs = 100_000L

        val charging = ProtectionStatusProjection.evaluate(
            ProtectionSnapshot.offline(nowMs).copy(chargingState = ChargingState.CHARGING), nowMs, nowMs
        )
        assertEquals("🔌 สายชาร์จ: เสียบอยู่ | กำลังชาร์จ", charging.batteryPower.powerSourceTh)

        val discharging = ProtectionStatusProjection.evaluate(
            ProtectionSnapshot.offline(nowMs).copy(chargingState = ChargingState.DISCHARGING), nowMs, nowMs
        )
        assertEquals("🔌 สายชาร์จ: ไม่ได้เสียบ", discharging.batteryPower.powerSourceTh)

        val notCharging = ProtectionStatusProjection.evaluate(
            ProtectionSnapshot.offline(nowMs).copy(chargingState = ChargingState.NOT_CHARGING), nowMs, nowMs
        )
        assertEquals("🔌 สายชาร์จ: ไม่ได้เสียบ", notCharging.batteryPower.powerSourceTh)

        val full = ProtectionStatusProjection.evaluate(
            ProtectionSnapshot.offline(nowMs).copy(chargingState = ChargingState.FULL), nowMs, nowMs
        )
        assertEquals("🔌 สายชาร์จ: เสียบอยู่ | แบตเตอรี่เต็ม", full.batteryPower.powerSourceTh)

        val unknown = ProtectionStatusProjection.evaluate(
            ProtectionSnapshot.offline(nowMs).copy(chargingState = ChargingState.UNKNOWN), nowMs, nowMs
        )
        assertEquals("🔌 สายชาร์จ: ยังไม่มีข้อมูล", unknown.batteryPower.powerSourceTh)
    }

    @Test
    fun test16_powerThermalNotStaleFromTimeAlone() {
        val nowMs = 100_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            sensorHealth = mapOf(
                SensorKind.POWER_THERMAL to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    lastSampleAtMs = nowMs - 600_000L,
                    powerThermalDetail = PowerThermalHealthDetail(
                        sourceAvailable = true,
                        isRegistered = true,
                        lastUpdateWallClockMs = nowMs - 600_000L,
                    ),
                ),
            ),
        )

        val projection = ProtectionStatusProjection.evaluate(snapshot, nowMs, nowMs)
        assertEquals("✅ พลังงาน/อุณหภูมิ: ทำงาน", projection.sensorSummary.sensors[SensorKind.POWER_THERMAL]?.statusLineTh)
        assertTrue(projection.sensorSummary.sensors[SensorKind.POWER_THERMAL]?.isHealthyOrWorking == true)
    }

    @Test
    fun test17_serviceHeartbeat10sAndTelegramContact20s() {
        val nowMs = 100_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 11_000L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowMs - 21_000L,
        )

        val projection = ProtectionStatusProjection.evaluate(snapshot, nowMs, nowMs)
        assertEquals("⚠️ Service: ขาดการตอบสนอง", projection.primarySystems.serviceStatusTh)
        assertEquals("⚠️ Telegram: การติดต่อล่าช้า | ติดต่อล่าสุด 21 วินาทีที่แล้ว", projection.primarySystems.telegramStatusTh)
    }

    @Test
    fun test18_armDurationPreservedAcrossAlert() {
        val nowMs = 1_700_000_000_000L
        val armStartMs = nowMs - (3 * 3600 + 12 * 60) * 1000L // 3 hr 12 min

        val snapshotAlert = ProtectionSnapshot.offline(nowMs - 5_000L).copy(
            state = ProtectionState.ALERT_ACTIVE,
            protectionActivatedAtMs = armStartMs,
        )

        val proj = ProtectionStatusProjection.evaluate(snapshotAlert, nowMs, nowMs)
        assertEquals("3 ชั่วโมง 12 นาที", proj.protectionState.armDurationTh)
    }

    @Test
    fun test19_incidentLifecycleAndDeliveryMapping() {
        val nowMs = 100_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            lastIncident = IncidentSummary(
                id = "audio-threat-1",
                severity = IncidentSeverity.CRITICAL,
                lifecycle = IncidentLifecycle.OPEN,
                updatedAtMs = nowMs,
                deliveryState = DeliveryState.PENDING,
                type = IncidentType.AUDIO,
            ),
        )

        val proj = ProtectionStatusProjection.evaluate(snapshot, nowMs, nowMs)
        assertNotNull(proj.lastIncident)
        assertEquals("ตรวจพบเสียงผิดปกติ", proj.lastIncident?.typeTh)
        assertEquals("กำลังเกิดเหตุ", proj.lastIncident?.statusTh)
        assertEquals("กำลังส่ง", proj.lastIncident?.deliveryTh)
    }

    @Test
    fun test20_nullSafetyAndEmptySnapshot() {
        val nowMs = 100_000L
        val snapshot = ProtectionSnapshot.offline(nowMs)

        val proj = ProtectionStatusProjection.evaluate(snapshot, nowMs, nowMs)
        assertEquals("ออฟไลน์", proj.protectionState.displayStatusTh)
        assertNull(proj.protectionState.armDurationTh)
        assertEquals("🔋 แบตเตอรี่: ยังไม่มีข้อมูล", proj.batteryPower.batteryPercentTh)
        assertEquals("🌡️ อุณหภูมิเครื่อง (แบตเตอรี่): ยังไม่มีข้อมูล", proj.batteryPower.temperatureTh)
        assertEquals("🔌 สายชาร์จ: ยังไม่มีข้อมูล", proj.batteryPower.powerSourceTh)
        assertNull(proj.lastIncident)
    }

    @Test
    fun emptyHealthMapReportsZeroOfFiveNotFiveOfFive() {
        val p = ProtectionStatusProjection.evaluate(
            ProtectionSnapshot.offline(100_000L).copy(state = ProtectionState.ARMED_DEGRADED),
            nowWallClockMs = 100_000L,
            nowElapsedMs = 50_000L,
        )
        assertEquals(0, p.sensorSummary.activeCount)
        assertEquals(0, p.sensorSummary.readyCount)
    }

    @Test
    fun waitingGpsCountsOnlyWhenRegistered() {
        fun active(registered: Boolean): Int {
            val health = SensorHealth(
                SensorHealthState.AVAILABLE,
                locationDetail = LocationHealthDetail(
                    trackingState = LocationTrackingState.WAITING_FOR_FIX,
                    isRegistered = registered,
                ),
            )
            return ProtectionStatusProjection.evaluate(
                ProtectionSnapshot.offline(100_000L).copy(
                    state = ProtectionState.ARMING,
                    sensorHealth = mapOf(SensorKind.LOCATION to health),
                ),
                100_000L,
                50_000L,
            ).sensorSummary.activeCount
        }
        assertEquals(0, active(false))
        assertEquals(1, active(true))
    }

    @Test
    fun missingHeartbeatIsNotHealthyEvenWhenServiceRunningFlagIsTrue() {
        val p = ProtectionStatusProjection.evaluate(
            ProtectionSnapshot.offline(100_000L).copy(serviceRunning = true),
            100_000L,
            50_000L,
        )
        assertFalse(p.primarySystems.serviceHealthy)
    }

    @Test
    fun futureHeartbeatAndTelegramContactAreClockAnomalies() {
        val p = ProtectionStatusProjection.evaluate(
            ProtectionSnapshot.offline(100_000L).copy(
                serviceRunning = true,
                lastServiceHeartbeatAtMs = 100_001L,
                telegramPolling = true,
                telegramReachable = true,
                lastTelegramContactAtMs = 100_001L,
            ),
            100_000L,
            50_000L,
        )
        assertFalse(p.primarySystems.serviceHealthy)
        assertFalse(p.primarySystems.telegramHealthy)
    }

    @Test
    fun microphoneFreshnessUsesNowElapsedMs() {
        val health = SensorHealth(
            SensorHealthState.HEALTHY,
            microphoneDetail = MicrophoneHealthDetail(
                audioState = AudioRuntimeState.LISTENING,
                isRegistered = true,
                modelReady = true,
                lastAudioSampleElapsedMs = 44_999L,
            ),
        )
        val p = ProtectionStatusProjection.evaluate(
            ProtectionSnapshot.offline(1_700_000_000_000L).copy(
                state = ProtectionState.ARMED_DEGRADED,
                sensorHealth = mapOf(SensorKind.MICROPHONE to health),
            ),
            nowWallClockMs = 1_700_000_000_000L,
            nowElapsedMs = 50_000L,
        )
        assertTrue(p.sensorSummary.sensors[SensorKind.MICROPHONE]?.isStale == true)
    }

    @Test
    fun gpsFreshnessUsesLastFixElapsedMsAtThirtySecondBoundary() {
        fun stale(fixAt: Long): Boolean {
            val health = SensorHealth(
                SensorHealthState.HEALTHY,
                locationDetail = LocationHealthDetail(
                    trackingState = LocationTrackingState.TRACKING,
                    isRegistered = true,
                    lastFixElapsedMs = fixAt,
                    accuracyMeters = 10f,
                ),
            )
            return ProtectionStatusProjection.evaluate(
                ProtectionSnapshot.offline(100_000L).copy(
                    state = ProtectionState.ARMED_HEALTHY,
                    sensorHealth = mapOf(SensorKind.LOCATION to health),
                ),
                100_000L,
                50_000L,
            ).sensorSummary.sensors.getValue(SensorKind.LOCATION).isStale
        }
        assertFalse(stale(20_000L))
        assertTrue(stale(19_999L))
    }

    @Test
    fun typedLocationFailureCodeControlsGuidanceWithoutFailureReasonText() {
        val health = SensorHealth(
            SensorHealthState.UNAVAILABLE,
            locationDetail = LocationHealthDetail(
                trackingState = LocationTrackingState.UNAVAILABLE,
                isRegistered = false,
                permissionGranted = false,
                failureCode = LocationFailureCode.PERMISSION_DENIED,
                failureReason = "unrelated diagnostic text",
            ),
        )
        val p = ProtectionStatusProjection.evaluate(
            ProtectionSnapshot.offline(100_000L).copy(sensorHealth = mapOf(SensorKind.LOCATION to health)),
            100_000L,
            50_000L,
        )
        assertTrue(p.sensorSummary.sensors.getValue(SensorKind.LOCATION).statusLineTh.contains("สิทธิ์"))
    }

    @Test
    fun incidentTypeUsesTypedFieldWhenIdIsUuid() {
        val summary = IncidentSummary(
            id = "550e8400-e29b-41d4-a716-446655440000",
            severity = IncidentSeverity.CRITICAL,
            lifecycle = IncidentLifecycle.OPEN,
            updatedAtMs = 100_000L,
            deliveryState = DeliveryState.PENDING,
            type = IncidentType.AUDIO,
        )
        val p = ProtectionStatusProjection.evaluate(
            ProtectionSnapshot.offline(100_000L).copy(lastIncident = summary),
            100_000L,
            50_000L,
        )
        assertEquals("ตรวจพบเสียงผิดปกติ", p.lastIncident?.typeTh)
    }

    @Test
    fun powerDoesNotBecomeStaleAfterFiveSeconds() {
        val power = SensorHealth(
            SensorHealthState.HEALTHY,
            powerThermalDetail = PowerThermalHealthDetail(
                sourceAvailable = true,
                isRegistered = true,
                lastUpdateWallClockMs = 1_000L,
            ),
        )
        val p = ProtectionStatusProjection.evaluate(
            ProtectionSnapshot.offline(100_000L).copy(
                state = ProtectionState.ARMED_HEALTHY,
                sensorHealth = mapOf(SensorKind.POWER_THERMAL to power),
            ),
            100_000L,
            50_000L,
        )
        assertTrue(p.sensorSummary.sensors[SensorKind.POWER_THERMAL]?.isHealthyOrWorking == true)
    }

    @Test
    fun testReadyCount_microphoneAndGpsConditions() {
        val nowMs = 100_000L
        // Mic with permissionDenied
        val snapMicDenied = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMING,
            sensorHealth = mapOf(
                SensorKind.MICROPHONE to SensorHealth(
                    SensorHealthState.AVAILABLE,
                    microphoneDetail = MicrophoneHealthDetail(permissionGranted = false),
                ),
            ),
        )
        assertEquals(0, ProtectionStatusProjection.evaluate(snapMicDenied, nowMs, nowMs).sensorSummary.readyCount)

        // Mic with permissionBlockers
        val snapMicBlocked = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMING,
            permissionBlockers = setOf("RECORD_AUDIO"),
            sensorHealth = mapOf(
                SensorKind.MICROPHONE to SensorHealth(
                    SensorHealthState.AVAILABLE,
                    microphoneDetail = MicrophoneHealthDetail(permissionGranted = true),
                ),
            ),
        )
        assertEquals(0, ProtectionStatusProjection.evaluate(snapMicBlocked, nowMs, nowMs).sensorSummary.readyCount)

        // GPS with permissionDenied failureCode
        val snapGpsPermDenied = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMING,
            sensorHealth = mapOf(
                SensorKind.LOCATION to SensorHealth(
                    SensorHealthState.AVAILABLE,
                    locationDetail = LocationHealthDetail(
                        permissionGranted = false,
                        failureCode = LocationFailureCode.PERMISSION_DENIED,
                    ),
                ),
            ),
        )
        assertEquals(0, ProtectionStatusProjection.evaluate(snapGpsPermDenied, nowMs, nowMs).sensorSummary.readyCount)

        // GPS with NO_PROVIDERS_AVAILABLE
        val snapGpsNoProviders = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMING,
            sensorHealth = mapOf(
                SensorKind.LOCATION to SensorHealth(
                    SensorHealthState.AVAILABLE,
                    locationDetail = LocationHealthDetail(
                        failureCode = LocationFailureCode.NO_PROVIDERS_AVAILABLE,
                    ),
                ),
            ),
        )
        assertEquals(0, ProtectionStatusProjection.evaluate(snapGpsNoProviders, nowMs, nowMs).sensorSummary.readyCount)

        // GPS with REGISTRATION_FAILED
        val snapGpsRegFailed = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMING,
            sensorHealth = mapOf(
                SensorKind.LOCATION to SensorHealth(
                    SensorHealthState.AVAILABLE,
                    locationDetail = LocationHealthDetail(
                        failureCode = LocationFailureCode.REGISTRATION_FAILED,
                    ),
                ),
            ),
        )
        assertEquals(0, ProtectionStatusProjection.evaluate(snapGpsRegFailed, nowMs, nowMs).sensorSummary.readyCount)

        // GPS with HARDWARE_UNAVAILABLE
        val snapGpsHwUnavailable = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMING,
            sensorHealth = mapOf(
                SensorKind.LOCATION to SensorHealth(
                    SensorHealthState.AVAILABLE,
                    locationDetail = LocationHealthDetail(
                        failureCode = LocationFailureCode.HARDWARE_UNAVAILABLE,
                    ),
                ),
            ),
        )
        assertEquals(0, ProtectionStatusProjection.evaluate(snapGpsHwUnavailable, nowMs, nowMs).sensorSummary.readyCount)

        // GPS with permissionBlockers
        val snapGpsBlocked = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMING,
            permissionBlockers = setOf("ACCESS_FINE_LOCATION"),
            sensorHealth = mapOf(
                SensorKind.LOCATION to SensorHealth(
                    SensorHealthState.AVAILABLE,
                    locationDetail = LocationHealthDetail(permissionGranted = true),
                ),
            ),
        )
        assertEquals(0, ProtectionStatusProjection.evaluate(snapGpsBlocked, nowMs, nowMs).sensorSummary.readyCount)
    }

    @Test
    fun testMicrophone_missingFreshnessHandling() {
        val nowMs = 100_000L
        // Registered + CALIBRATING -> ⏳ ไมโครโฟน: กำลังคาลิเบรต
        val calSnap = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            sensorHealth = mapOf(
                SensorKind.MICROPHONE to SensorHealth(
                    SensorHealthState.AVAILABLE,
                    microphoneDetail = MicrophoneHealthDetail(
                        audioState = AudioRuntimeState.CALIBRATING,
                        isRegistered = true,
                        lastAudioSampleElapsedMs = null,
                    ),
                ),
            ),
        )
        val calProj = ProtectionStatusProjection.evaluate(calSnap, nowMs, nowMs)
        assertEquals("⏳ ไมโครโฟน: กำลังคาลิเบรต", calProj.sensorSummary.sensors[SensorKind.MICROPHONE]?.statusLineTh)
        assertTrue(calProj.sensorSummary.sensors[SensorKind.MICROPHONE]?.isReadyOrWaiting == true)

        // Registered + STARTING -> ⏳ ไมโครโฟน: กำลังคาลิเบรต
        val startSnap = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            sensorHealth = mapOf(
                SensorKind.MICROPHONE to SensorHealth(
                    SensorHealthState.AVAILABLE,
                    microphoneDetail = MicrophoneHealthDetail(
                        audioState = AudioRuntimeState.STARTING,
                        isRegistered = true,
                        lastAudioSampleElapsedMs = null,
                    ),
                ),
            ),
        )
        val startProj = ProtectionStatusProjection.evaluate(startSnap, nowMs, nowMs)
        assertEquals("⏳ ไมโครโฟน: กำลังคาลิเบรต", startProj.sensorSummary.sensors[SensorKind.MICROPHONE]?.statusLineTh)

        // Unregistered in armed -> ❌ ไมโครโฟน: ไม่พร้อมใช้งาน + issue
        val unregSnap = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_DEGRADED,
            sensorHealth = mapOf(
                SensorKind.MICROPHONE to SensorHealth(
                    SensorHealthState.AVAILABLE,
                    microphoneDetail = MicrophoneHealthDetail(
                        audioState = AudioRuntimeState.OFF,
                        isRegistered = false,
                        lastAudioSampleElapsedMs = null,
                    ),
                ),
            ),
        )
        val unregProj = ProtectionStatusProjection.evaluate(unregSnap, nowMs, nowMs)
        val mic = unregProj.sensorSummary.sensors[SensorKind.MICROPHONE]
        assertEquals("❌ ไมโครโฟน: ไม่พร้อมใช้งาน", mic?.statusLineTh)
        assertTrue(mic?.isUnavailable == true)
        assertEquals("ตรวจสอบไมโครโฟนของตัวเครื่อง", mic?.issueRecommendation?.guidanceTh)
    }

    @Test
    fun testGpsArming_requiresIsRegistered() {
        val nowMs = 100_000L
        val snapRegistered = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMING,
            sensorHealth = mapOf(
                SensorKind.LOCATION to SensorHealth(
                    SensorHealthState.AVAILABLE,
                    locationDetail = LocationHealthDetail(isRegistered = true),
                ),
            ),
        )
        val projReg = ProtectionStatusProjection.evaluate(snapRegistered, nowMs, nowMs)
        assertTrue(projReg.sensorSummary.sensors[SensorKind.LOCATION]?.isReadyOrWaiting == true)

        val snapNullDetail = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMING,
            sensorHealth = mapOf(
                SensorKind.LOCATION to SensorHealth(SensorHealthState.AVAILABLE, locationDetail = null),
            ),
        )
        val projNull = ProtectionStatusProjection.evaluate(snapNullDetail, nowMs, nowMs)
        assertFalse(projNull.sensorSummary.sensors[SensorKind.LOCATION]?.isReadyOrWaiting == true)
        assertTrue(projNull.sensorSummary.sensors[SensorKind.LOCATION]?.isUnavailable == true)
    }

    @Test
    fun testPowerThermal_requiresIsRegisteredInArmedAndArming() {
        val nowMs = 100_000L
        // Armed with isRegistered = true
        val snapReg = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            sensorHealth = mapOf(
                SensorKind.POWER_THERMAL to SensorHealth(
                    SensorHealthState.HEALTHY,
                    powerThermalDetail = PowerThermalHealthDetail(sourceAvailable = true, isRegistered = true),
                ),
            ),
        )
        val projReg = ProtectionStatusProjection.evaluate(snapReg, nowMs, nowMs)
        assertTrue(projReg.sensorSummary.sensors[SensorKind.POWER_THERMAL]?.isHealthyOrWorking == true)

        // Armed with isRegistered = false
        val snapUnreg = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_DEGRADED,
            sensorHealth = mapOf(
                SensorKind.POWER_THERMAL to SensorHealth(
                    SensorHealthState.AVAILABLE,
                    powerThermalDetail = PowerThermalHealthDetail(sourceAvailable = true, isRegistered = false),
                ),
            ),
        )
        val projUnreg = ProtectionStatusProjection.evaluate(snapUnreg, nowMs, nowMs)
        assertFalse(projUnreg.sensorSummary.sensors[SensorKind.POWER_THERMAL]?.isHealthyOrWorking == true)
        assertTrue(projUnreg.sensorSummary.sensors[SensorKind.POWER_THERMAL]?.isUnavailable == true)
        assertEquals("⚠️ พลังงาน/อุณหภูมิ: ข้อมูลไม่สมบูรณ์", projUnreg.sensorSummary.sensors[SensorKind.POWER_THERMAL]?.statusLineTh)
    }

    @Test
    fun testArmDuration_strictlyRequiresProtectionActivatedAtMs() {
        val nowMs = 1_700_000_000_000L
        // protectionActivatedAtMs null, lastTransitionAtMs present -> must NOT fallback to lastTransitionAtMs
        val snapNoActivation = ProtectionSnapshot.offline(nowMs - 60_000L).copy(
            state = ProtectionState.ARMED_HEALTHY,
            lastTransitionAtMs = nowMs - 60_000L,
            protectionActivatedAtMs = null,
        )
        val projNoActivation = ProtectionStatusProjection.evaluate(snapNoActivation, nowMs, nowMs)
        assertNull(projNoActivation.protectionState.armDurationTh)

        // Future activation time -> null
        val snapFuture = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            protectionActivatedAtMs = nowMs + 10_000L,
        )
        assertNull(ProtectionStatusProjection.evaluate(snapFuture, nowMs, nowMs).protectionState.armDurationTh)

        // Zero activation time -> null
        val snapZero = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            protectionActivatedAtMs = 0L,
        )
        assertNull(ProtectionStatusProjection.evaluate(snapZero, nowMs, nowMs).protectionState.armDurationTh)

        // Valid past activation time -> formatted
        val snapValid = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            protectionActivatedAtMs = nowMs - 120_000L,
        )
        assertEquals("2 นาที", ProtectionStatusProjection.evaluate(snapValid, nowMs, nowMs).protectionState.armDurationTh)
    }

    @Test
    fun testSanityGuards_rangeAndNonFiniteChecks() {
        val nowMs = 100_000L

        // Negative lux or NaN lux
        val snapBadLux = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            sensorHealth = mapOf(
                SensorKind.LIGHT to SensorHealth(
                    SensorHealthState.HEALTHY,
                    lightDetail = LightHealthDetail(
                        hardwareSupported = true,
                        isRegistered = true,
                        lastLux = -15.0,
                        lastSampleElapsedMs = nowMs - 1000L,
                    ),
                ),
            ),
        )
        val projBadLux = ProtectionStatusProjection.evaluate(snapBadLux, nowMs, nowMs)
        assertEquals("✅ แสง: ทำงาน | ล่าสุด 1 วินาที", projBadLux.sensorSummary.sensors[SensorKind.LIGHT]?.statusLineTh)

        // GPS accuracy null or NaN in low accuracy state -> without ±0 เมตร
        val snapBadAcc = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            sensorHealth = mapOf(
                SensorKind.LOCATION to SensorHealth(
                    SensorHealthState.HEALTHY,
                    locationDetail = LocationHealthDetail(
                        trackingState = LocationTrackingState.TRACKING,
                        isRegistered = true,
                        lastFixElapsedMs = nowMs - 1000L,
                        accuracyMeters = Float.NaN,
                    ),
                ),
            ),
        )
        val projBadAcc = ProtectionStatusProjection.evaluate(snapBadAcc, nowMs, nowMs)
        assertEquals("⚠️ GPS: ความแม่นยำต่ำ", projBadAcc.sensorSummary.sensors[SensorKind.LOCATION]?.statusLineTh)

        // Battery level out of range
        val snapBadBattery = ProtectionSnapshot.offline(nowMs).copy(
            batteryLevelPercent = 150,
            batteryTemperatureCelsius = Float.NaN,
        )
        val projBadBattery = ProtectionStatusProjection.evaluate(snapBadBattery, nowMs, nowMs)
        assertEquals("🔋 แบตเตอรี่: ยังไม่มีข้อมูล", projBadBattery.batteryPower.batteryPercentTh)
        assertEquals("🌡️ อุณหภูมิเครื่อง (แบตเตอรี่): ยังไม่มีข้อมูล", projBadBattery.batteryPower.temperatureTh)
    }
}

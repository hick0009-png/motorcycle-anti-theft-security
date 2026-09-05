package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.AudioRuntimeState
import com.example.motorcycleantitheftsensor.protection.ChargingState
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
import com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog
import com.example.motorcycleantitheftsensor.protection.GuidanceCode
import com.example.motorcycleantitheftsensor.protection.ProtectionStateTelegramNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionStatusTruthPathIntegrationTest {

    private val formatter = ProtectionStatusFormatter()

    @Test
    fun test01_freshArmedLiveFixShowsActive5Of5() {
        val nowMs = 1_700_000_000_000L
        val snapshot = ProtectionSnapshot.offline(nowMs - 60_000L).copy(
            state = ProtectionState.ARMED_HEALTHY,
            protectionActivatedAtMs = nowMs - 60_000L,
            sensitivityLevel = 5,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 2_000L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowMs - 1_000L,
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
                        lastLux = 55.0,
                        lastSampleWallClockMs = nowMs - 1_500L,
                        lastSampleElapsedMs = nowMs - 1_500L,
                    ),
                ),
                SensorKind.MICROPHONE to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    microphoneDetail = MicrophoneHealthDetail(
                        audioState = AudioRuntimeState.LISTENING,
                        isRegistered = true,
                        modelReady = true,
                        lastAudioSampleElapsedMs = nowMs - 800L,
                        permissionGranted = true,
                    ),
                ),
                SensorKind.LOCATION to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    locationDetail = LocationHealthDetail(
                        trackingState = LocationTrackingState.TRACKING,
                        isRegistered = true,
                        lastFixWallClockMs = nowMs - 4_000L,
                        lastFixElapsedMs = nowMs - 4_000L,
                        accuracyMeters = 12.0f,
                    ),
                ),
                SensorKind.POWER_THERMAL to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    powerThermalDetail = PowerThermalHealthDetail(
                        sourceAvailable = true,
                        isRegistered = true,
                        chargingState = ChargingState.CHARGING,
                        batteryLevelPercent = 90,
                        temperatureCelsius = 30.5f,
                    ),
                ),
            ),
            batteryLevelPercent = 90,
            batteryTemperatureCelsius = 30.5f,
            chargingState = ChargingState.CHARGING,
        )

        val output = formatter.format(snapshot, nowMs, nowMs)
        assertTrue(output.contains("🔎 เซ็นเซอร์ทั้งหมด: ทำงาน 5/5"))
        assertTrue(output.contains("✅ การสั่น: ทำงาน"))
        assertTrue(output.contains("✅ แสง: ทำงาน | 55 lux"))
        assertTrue(output.contains("✅ ไมโครโฟน: กำลังฟัง | ตัวจำแนกเสียงพร้อม"))
        assertTrue(output.contains("✅ GPS: กำลังติดตาม | ล่าสุด 4 วินาที | ±12 เมตร"))
        assertTrue(output.contains("✅ พลังงาน/อุณหภูมิ: ทำงาน"))
        assertTrue(output.contains("✅ ระบบทำงานครบ ไม่พบปัญหา"))
    }

    @Test
    fun test02_armingStateReportsWaitingGpsWithoutStaleWarning() {
        val nowMs = 1_700_000_000_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMING,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 500L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowMs - 500L,
            sensorHealth = mapOf(
                SensorKind.VIBRATION to SensorHealth(
                    state = SensorHealthState.AVAILABLE,
                    vibrationDetail = VibrationHealthDetail(hardwareAvailable = true, isRegistered = false),
                ),
                SensorKind.LIGHT to SensorHealth(
                    state = SensorHealthState.AVAILABLE,
                    lightDetail = LightHealthDetail(hardwareSupported = true, isRegistered = false),
                ),
                SensorKind.MICROPHONE to SensorHealth(
                    state = SensorHealthState.AVAILABLE,
                    microphoneDetail = MicrophoneHealthDetail(audioState = AudioRuntimeState.CALIBRATING, isRegistered = true),
                ),
                SensorKind.LOCATION to SensorHealth(
                    state = SensorHealthState.AVAILABLE,
                    locationDetail = LocationHealthDetail(
                        trackingState = LocationTrackingState.WAITING_FOR_FIX,
                        isRegistered = true,
                    ),
                ),
                SensorKind.POWER_THERMAL to SensorHealth(
                    state = SensorHealthState.AVAILABLE,
                    powerThermalDetail = PowerThermalHealthDetail(sourceAvailable = true, isRegistered = false),
                ),
            ),
        )

        val output = formatter.format(snapshot, nowMs, nowMs)
        assertTrue(output.contains("กำลังเริ่มการเฝ้า"))
        assertTrue(output.contains("🔎 เซ็นเซอร์ทั้งหมด: กำลังเริ่ม | พร้อมใช้งาน 5/5"))
        assertTrue(output.contains("⏳ GPS: กำลังติดตาม | รอพิกัดแรก"))
        assertFalse(output.contains("ข้อมูลเก่า"))
    }

    @Test
    fun test03_staleGpsFixShowsActive4Of5WithStaleWarning() {
        val nowMs = 1_700_000_000_000L
        val snapshot = ProtectionSnapshot.offline(nowMs - 120_000L).copy(
            state = ProtectionState.ARMED_DEGRADED,
            protectionActivatedAtMs = nowMs - 120_000L,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 1_000L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowMs - 1_000L,
            sensorHealth = mapOf(
                SensorKind.VIBRATION to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    vibrationDetail = VibrationHealthDetail(isRegistered = true, lastSampleElapsedMs = nowMs - 500L),
                ),
                SensorKind.LIGHT to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    lightDetail = LightHealthDetail(hardwareSupported = true, isRegistered = true, lastSampleElapsedMs = nowMs - 500L),
                ),
                SensorKind.MICROPHONE to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    microphoneDetail = MicrophoneHealthDetail(audioState = AudioRuntimeState.LISTENING, isRegistered = true, modelReady = true, lastAudioSampleElapsedMs = nowMs - 500L),
                ),
                SensorKind.LOCATION to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    locationDetail = LocationHealthDetail(
                        trackingState = LocationTrackingState.TRACKING,
                        isRegistered = true,
                        lastFixWallClockMs = nowMs - 45_000L,
                        lastFixElapsedMs = nowMs - 45_000L,
                        accuracyMeters = 15.0f,
                    ),
                ),
                SensorKind.POWER_THERMAL to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    powerThermalDetail = PowerThermalHealthDetail(sourceAvailable = true, isRegistered = true),
                ),
            ),
        )

        val output = formatter.format(snapshot, nowMs, nowMs)
        assertTrue(output.contains("🔎 เซ็นเซอร์ทั้งหมด: ทำงาน 4/5"))
        assertTrue(output.contains("⚠️ GPS: พิกัดล่าสุด 45 วินาทีที่แล้ว | ข้อมูลเก่า"))
        assertTrue(output.contains("วิธีแก้: ตรวจว่าเปิดตำแหน่งและวางโทรศัพท์ในจุดรับสัญญาณได้"))
    }

    @Test
    fun test04_unsupportedLightHardwareReportsMissingLightGracefully() {
        val nowMs = 1_700_000_000_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowMs - 1_000L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowMs - 1_000L,
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

        val output = formatter.format(snapshot, nowMs, nowMs)
        assertTrue(output.contains("⚠️ แสง: เครื่องนี้ไม่รองรับ"))
        assertFalse(output.contains("❌ แสง"))
    }

    @Test
    fun test05_missingMicPermissionShowsTypedWarning() {
        val nowMs = 1_700_000_000_000L
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
                        hardwareAvailable = true,
                    ),
                ),
            ),
        )

        val output = formatter.format(snapshot, nowMs, nowMs)
        assertTrue(output.contains("❌ ไมโครโฟน: ไม่มีสิทธิ์ใช้งาน"))
        assertTrue(output.contains("วิธีแก้: เปิดสิทธิ์ Microphone ในการตั้งค่าแอป แล้ว Arm ใหม่"))
    }

    @Test
    fun test06_disarmedStateReportsReady5Of5() {
        val nowMs = 1_700_000_000_000L
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
        assertTrue(output.contains("ยังไม่ได้เปิดการเฝ้า"))
        assertTrue(output.contains("🔎 เซ็นเซอร์ทั้งหมด: หยุดตามคำสั่ง /disarm | พร้อมใช้งาน 5/5"))
        assertTrue(output.contains("✅ ระบบทำงานครบ ไม่พบปัญหา"))
    }

    @Test
    fun test07_offlineServiceShowsOfflineIssue() {
        val nowMs = 1_700_000_000_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.OFFLINE,
            serviceRunning = false,
        )

        val output = formatter.format(snapshot, nowMs, nowMs)
        assertTrue(output.contains("❌ Service: ออฟไลน์"))
        assertTrue(output.contains("วิธีแก้: เปิดแอปในอุปกรณ์เพื่อเริ่มบริการป้องกัน"))
    }

    @Test
    fun test09_disparateClockDomainMaintainsHealthyEvaluation() {
        // Wall clock (Epoch milliseconds, ~2026)
        val nowWallClockMs = 1_771_234_567_000L
        // Elapsed monotonic uptime milliseconds (e.g. phone booted 1 hour ago)
        val nowElapsedRealtimeMs = 3_600_000L

        val snapshot = ProtectionSnapshot.offline(nowWallClockMs - 30_000L).copy(
            state = ProtectionState.ARMED_HEALTHY,
            protectionActivatedAtMs = nowWallClockMs - 30_000L,
            sensitivityLevel = 7,
            serviceRunning = true,
            lastServiceHeartbeatAtMs = nowWallClockMs - 1_000L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = nowWallClockMs - 2_000L,
            sensorHealth = mapOf(
                SensorKind.VIBRATION to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    lastSampleAtMs = nowWallClockMs - 500L,
                    vibrationDetail = VibrationHealthDetail(
                        hardwareAvailable = true,
                        isRegistered = true,
                        lastSampleWallClockMs = nowWallClockMs - 500L,
                        lastSampleElapsedMs = nowElapsedRealtimeMs - 500L,
                    ),
                ),
                SensorKind.LIGHT to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    lastSampleAtMs = nowWallClockMs - 1_000L,
                    lightDetail = LightHealthDetail(
                        hardwareSupported = true,
                        isRegistered = true,
                        lastLux = 140.0,
                        lastSampleWallClockMs = nowWallClockMs - 1_000L,
                        lastSampleElapsedMs = nowElapsedRealtimeMs - 1_000L,
                    ),
                ),
                SensorKind.MICROPHONE to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    lastSampleAtMs = nowWallClockMs - 400L,
                    microphoneDetail = MicrophoneHealthDetail(
                        audioState = AudioRuntimeState.LISTENING,
                        isRegistered = true,
                        modelReady = true,
                        lastAudioSampleAtMs = nowWallClockMs - 400L,
                        lastAudioSampleElapsedMs = nowElapsedRealtimeMs - 400L,
                        hardwareAvailable = true,
                        permissionGranted = true,
                    ),
                ),
                SensorKind.LOCATION to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    lastSampleAtMs = nowWallClockMs - 2_000L,
                    locationDetail = LocationHealthDetail(
                        trackingState = LocationTrackingState.TRACKING,
                        isRegistered = true,
                        lastFixWallClockMs = nowWallClockMs - 2_000L,
                        lastFixElapsedMs = nowElapsedRealtimeMs - 2_000L,
                        accuracyMeters = 8.5f,
                        hardwareAvailable = true,
                        permissionGranted = true,
                    ),
                ),
                SensorKind.POWER_THERMAL to SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    powerThermalDetail = PowerThermalHealthDetail(
                        sourceAvailable = true,
                        isRegistered = true,
                        chargingState = ChargingState.CHARGING,
                        batteryLevelPercent = 88,
                        temperatureCelsius = 29.0f,
                        lastUpdateWallClockMs = nowWallClockMs - 1_000L,
                    ),
                ),
            ),
            batteryLevelPercent = 88,
            batteryTemperatureCelsius = 29.0f,
            chargingState = ChargingState.CHARGING,
        )

        // Evaluate with realistic disparate clocks
        val output = formatter.format(snapshot, nowWallClockMs, nowElapsedRealtimeMs)

        assertTrue("Output should indicate 5/5 active sensors", output.contains("🔎 เซ็นเซอร์ทั้งหมด: ทำงาน 5/5"))
        assertTrue("Output should format 30 seconds arm duration", output.contains("เฝ้ามาแล้ว 30 วินาที"))
        assertTrue("Output should report healthy state", output.contains("✅ ระบบทำงานครบ ไม่พบปัญหา"))
        assertFalse("Output should not report clock anomaly", output.contains("เวลาในระบบผิดปกติ"))
        assertFalse("Output should not report stale", output.contains("ข้อมูลเก่า"))
    }

    @Test
    fun test10_botMentionParsing() {
        val parsed1 = RemoteCommand.parse("/status@MyVehicleBot")
        assertEquals(RemoteCommand.Status, parsed1)

        val parsed2 = RemoteCommand.parse("/arm@SecurityBot")
        assertEquals(RemoteCommand.Arm, parsed2)

        val parsed3 = RemoteCommand.parse("/disarm@SecurityBot")
        assertEquals(RemoteCommand.Disarm, parsed3)

        val parsed4 = RemoteCommand.parse("/help@SecurityBot")
        assertEquals(RemoteCommand.Help, parsed4)
    }

    @Test
    fun test11_telegramMessageSplitting() {
        val shortMsg = "Hello World"
        val chunks1 = splitTelegramMessage(shortMsg, maxLength = 100)
        assertEquals(1, chunks1.size)
        assertEquals("Hello World", chunks1[0])

        val longText = (1..50).joinToString("\n") { "Line $it: " + "A".repeat(100) }
        val chunks2 = splitTelegramMessage(longText, maxLength = 500)
        assertTrue(chunks2.size > 1)
        for (chunk in chunks2) {
            assertTrue(chunk.length <= 500)
        }
    }

    @Test
    fun test12_configuredSensitivityIsReportedAccuratelyInStatus() {
        val nowMs = 1_700_000_000_000L
        val snapshot = ProtectionSnapshot.offline(nowMs).copy(
            sensitivityLevel = 8,
        )

        val output = formatter.format(snapshot, nowMs, nowMs)
        assertTrue("Output should indicate sensitivity 8/10", output.contains("ความไวการตรวจจับ: 8/10"))
    }

    @Test
    fun test13_stateTransitionTelegramAlerts() {
        val notifier = ProtectionStateTelegramNotifier()

        // DISARMED -> ARMING
        assertEquals(
            listOf("ℹ️ กำลังเปิดการป้องกัน รอการปรับเทียบเซนเซอร์"),
            notifier.messagesFor(ProtectionState.DISARMED_ONLINE, ProtectionState.ARMING)
        )

        // ARMING -> ARMED_HEALTHY
        assertEquals(
            listOf("✅ การป้องกันทำงานปกติ"),
            notifier.messagesFor(ProtectionState.ARMING, ProtectionState.ARMED_HEALTHY)
        )

        // ARMING -> ARMED_DEGRADED with hard failure
        val degradedReason = "VIBRATION not healthy"
        assertEquals(
            listOf("⚠️ การป้องกันทำงานแบบจำกัด: เซนเซอร์แรงสั่นไม่พร้อมใช้งาน"),
            notifier.messagesFor(ProtectionState.ARMING, ProtectionState.ARMED_DEGRADED, setOf(degradedReason))
        )

        // ARMED -> DISARMED
        assertEquals(
            listOf("ℹ️ ปิดการป้องกันแล้ว"),
            notifier.messagesFor(ProtectionState.ARMED_HEALTHY, ProtectionState.DISARMED_ONLINE)
        )
    }
}

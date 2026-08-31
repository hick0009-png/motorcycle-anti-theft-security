package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.AudioRuntimeState
import com.example.motorcycleantitheftsensor.protection.ChargingState
import com.example.motorcycleantitheftsensor.protection.DeliveryState
import com.example.motorcycleantitheftsensor.protection.FreshnessState
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
import com.example.motorcycleantitheftsensor.protection.ProtectionHealthPolicy
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.protection.SensorHealthState
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.VibrationHealthDetail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

data class IssueRecommendation(
    val issueTh: String,
    val guidanceTh: String,
)

data class SensorItemProjection(
    val kind: SensorKind,
    val statusLineTh: String,
    val isHealthyOrWorking: Boolean,
    val isReadyOrWaiting: Boolean,
    val isStale: Boolean,
    val isUnavailable: Boolean,
    val isFailed: Boolean,
    val isHardwareUnsupported: Boolean = false,
    val issueRecommendation: IssueRecommendation? = null,
)

data class ProtectionStateProjection(
    val state: ProtectionState,
    val displayStatusTh: String,
    val armDurationTh: String?,
    val sensitivityTh: String,
)

data class PrimarySystemsProjection(
    val serviceStatusTh: String,
    val serviceHealthy: Boolean,
    val telegramStatusTh: String,
    val telegramHealthy: Boolean,
    val serviceIssue: IssueRecommendation? = null,
    val telegramIssue: IssueRecommendation? = null,
)

data class SensorSummaryProjection(
    val headerTh: String,
    val activeCount: Int,
    val readyCount: Int,
    val totalCount: Int = 5,
    val sensors: Map<SensorKind, SensorItemProjection>,
)

data class BatteryPowerProjection(
    val batteryPercentTh: String,
    val temperatureTh: String,
    val powerSourceTh: String,
)

data class LastIncidentProjection(
    val hasIncident: Boolean,
    val typeTh: String?,
    val timeTh: String?,
    val statusTh: String?,
    val deliveryTh: String?,
)

data class IssuesSummaryProjection(
    val hasIssues: Boolean,
    val summaryMessageTh: String,
    val issues: List<IssueRecommendation>,
    val informationalNotices: List<String> = emptyList(),
)

data class ProtectionStatusProjection(
    val snapshotRevision: Long,
    val protectionState: ProtectionStateProjection,
    val primarySystems: PrimarySystemsProjection,
    val sensorSummary: SensorSummaryProjection,
    val batteryPower: BatteryPowerProjection,
    val lastIncident: LastIncidentProjection?,
    val issuesSummary: IssuesSummaryProjection,
) {
    companion object {
        const val FRESHNESS_VIBRATION_MS = 5_000L
        const val FRESHNESS_LIGHT_MS = 5_000L
        const val FRESHNESS_MIC_MS = 5_000L
        const val FRESHNESS_GPS_MS = 30_000L
        const val FRESHNESS_HEARTBEAT_MS = 10_000L
        const val FRESHNESS_TELEGRAM_MS = 20_000L

        private val healthPolicy = ProtectionHealthPolicy()

        fun evaluate(
            snapshot: ProtectionSnapshot,
            nowWallClockMs: Long,
            nowElapsedMs: Long = nowWallClockMs,
        ): ProtectionStatusProjection {
            val stateProjection = projectState(snapshot, nowWallClockMs)
            val systemsProjection = projectPrimarySystems(snapshot, nowWallClockMs)
            val sensorSummaryProjection = projectSensors(snapshot, nowWallClockMs, nowElapsedMs)
            val batteryPowerProjection = projectBatteryPower(snapshot)
            val lastIncidentProjection = projectLastIncident(snapshot)
            val issuesProjection = projectIssues(
                systemsProjection,
                sensorSummaryProjection,
            )

            return ProtectionStatusProjection(
                snapshotRevision = snapshot.revision,
                protectionState = stateProjection,
                primarySystems = systemsProjection,
                sensorSummary = sensorSummaryProjection,
                batteryPower = batteryPowerProjection,
                lastIncident = lastIncidentProjection,
                issuesSummary = issuesProjection,
            )
        }

        private fun projectState(snapshot: ProtectionSnapshot, nowWallClockMs: Long): ProtectionStateProjection {
            val displayStatusTh = when (snapshot.state) {
                ProtectionState.ARMED_HEALTHY,
                ProtectionState.ARMED_DEGRADED -> "กำลังป้องกัน"
                ProtectionState.ALERT_ACTIVE -> "🚨 กำลังส่งสัญญาณเตือน"
                ProtectionState.DISARMED_ONLINE -> "ปลดการป้องกันแล้ว"
                ProtectionState.ARMING -> "กำลังเริ่มการป้องกัน (รอการเปิดระบบ)"
                ProtectionState.OFFLINE -> "ออฟไลน์"
                ProtectionState.SETUP_REQUIRED -> "ต้องตั้งค่าระบบก่อนเปิดการป้องกัน"
            }

            val isArmedOrAlert = snapshot.state in setOf(
                ProtectionState.ARMED_HEALTHY,
                ProtectionState.ARMED_DEGRADED,
                ProtectionState.ALERT_ACTIVE,
            )

            val armDurationTh = if (isArmedOrAlert) {
                val startMs = snapshot.protectionActivatedAtMs
                if (startMs != null && startMs != 0L && startMs <= nowWallClockMs) {
                    val durationMs = nowWallClockMs - startMs
                    formatDurationThai(durationMs)
                } else {
                    null
                }
            } else {
                null
            }

            val sensitivityTh = "[ 🏃 การเคลื่อนไหว ]\nความไวการตรวจจับ: ${snapshot.sensitivityLevel}/10"

            return ProtectionStateProjection(
                state = snapshot.state,
                displayStatusTh = displayStatusTh,
                armDurationTh = armDurationTh,
                sensitivityTh = sensitivityTh,
            )
        }

        private fun formatDurationThai(durationMs: Long): String {
            val totalSeconds = durationMs / 1000L
            return when {
                totalSeconds < 60L -> "$totalSeconds วินาที"
                totalSeconds < 3600L -> "${totalSeconds / 60L} นาที"
                totalSeconds < 86400L -> {
                    val hours = totalSeconds / 3600L
                    val mins = (totalSeconds % 3600L) / 60L
                    if (mins > 0L) "$hours ชั่วโมง $mins นาที" else "$hours ชั่วโมง"
                }
                else -> {
                    val days = totalSeconds / 86400L
                    val hours = (totalSeconds % 86400L) / 3600L
                    if (hours > 0L) "$days วัน $hours ชั่วโมง" else "$days วัน"
                }
            }
        }

        private fun projectPrimarySystems(snapshot: ProtectionSnapshot, nowWallClockMs: Long): PrimarySystemsProjection {
            val serviceFreshness = healthPolicy.freshness(snapshot.lastServiceHeartbeatAtMs, nowWallClockMs, FRESHNESS_HEARTBEAT_MS)
            val serviceHealthy: Boolean
            val serviceStatusTh: String
            val serviceIssue: IssueRecommendation?

            if (!snapshot.serviceRunning) {
                serviceHealthy = false
                serviceStatusTh = "❌ Service: ออฟไลน์"
                serviceIssue = IssueRecommendation("❌ Service: ออฟไลน์", "เปิดแอปในอุปกรณ์เพื่อเริ่มบริการป้องกัน")
            } else when (serviceFreshness) {
                FreshnessState.FRESH -> {
                    serviceHealthy = true
                    serviceStatusTh = "✅ Service: ทำงาน"
                    serviceIssue = null
                }
                FreshnessState.STALE -> {
                    serviceHealthy = false
                    serviceStatusTh = "⚠️ Service: ขาดการตอบสนอง"
                    serviceIssue = IssueRecommendation("⚠️ Service: ขาดการตอบสนอง", "ตรวจสอบสถานะแอปในอุปกรณ์")
                                    }
                                    FreshnessState.CLOCK_ANOMALY -> {
                    serviceHealthy = false
                    serviceStatusTh = "⚠️ Service: เวลาในระบบผิดปกติ"
                    serviceIssue = IssueRecommendation("⚠️ Service: เวลาในระบบผิดปกติ", "ตรวจสอบการตั้งค่าเวลาบนอุปกรณ์")
                }
                FreshnessState.MISSING -> {
                                    serviceHealthy = false
                                    serviceStatusTh = "⚠️ Service: ขาดการตอบสนอง"
                                    serviceIssue = IssueRecommendation("⚠️ Service: ขาดการตอบสนอง", "ตรวจสอบสถานะแอปในอุปกรณ์")
                                }
            }

            val telegramFreshness = healthPolicy.freshness(snapshot.lastTelegramContactAtMs, nowWallClockMs, FRESHNESS_TELEGRAM_MS)
            val telegramHealthy: Boolean
            val telegramStatusTh: String
            val telegramIssue: IssueRecommendation?

            if (!snapshot.telegramPolling || !snapshot.telegramReachable) {
                telegramHealthy = false
                telegramStatusTh = "❌ Telegram: ขาดการเชื่อมต่อ"
                telegramIssue = IssueRecommendation("❌ Telegram: ขาดการเชื่อมต่อ", "ตรวจสอบสัญญาณอินเทอร์เน็ตของอุปกรณ์")
            } else when (telegramFreshness) {
                FreshnessState.FRESH -> {
                    telegramHealthy = true
                    val ageMs = nowWallClockMs - (snapshot.lastTelegramContactAtMs ?: nowWallClockMs)
                    val ageSec = (ageMs / 1000L).coerceAtLeast(0L)
                    telegramStatusTh = "✅ Telegram: เชื่อมต่อ | ติดต่อล่าสุด $ageSec วินาทีที่แล้ว"
                    telegramIssue = null
                }
                FreshnessState.STALE -> {
                    telegramHealthy = false
                    val ageMs = nowWallClockMs - (snapshot.lastTelegramContactAtMs ?: nowWallClockMs)
                    val ageSec = (ageMs / 1000L).coerceAtLeast(0L)
                    telegramStatusTh = "⚠️ Telegram: การติดต่อล่าช้า | ติดต่อล่าสุด $ageSec วินาทีที่แล้ว"
                    telegramIssue = IssueRecommendation("⚠️ Telegram: การติดต่อล่าช้า", "ตรวจสอบสัญญาณอินเทอร์เน็ตของอุปกรณ์")
                }
                FreshnessState.CLOCK_ANOMALY -> {
                    telegramHealthy = false
                    telegramStatusTh = "⚠️ Telegram: เวลาในระบบผิดปกติ"
                    telegramIssue = IssueRecommendation("⚠️ Telegram: เวลาในระบบผิดปกติ", "ตรวจสอบการตั้งค่าเวลาบนอุปกรณ์")
                }
                FreshnessState.MISSING -> {
                    telegramHealthy = false
                    telegramStatusTh = "⚠️ Telegram: ยังไม่มีการยืนยันการติดต่อ"
                    telegramIssue = IssueRecommendation("⚠️ Telegram: ยังไม่มีการยืนยันการติดต่อ", "รอการเชื่อมต่อบอท Telegram")
                }
            }

            return PrimarySystemsProjection(
                serviceStatusTh = serviceStatusTh,
                serviceHealthy = serviceHealthy,
                telegramStatusTh = telegramStatusTh,
                telegramHealthy = telegramHealthy,
                serviceIssue = serviceIssue,
                telegramIssue = telegramIssue,
            )
        }

        private fun projectSensors(
            snapshot: ProtectionSnapshot,
            nowWallClockMs: Long,
            nowElapsedMs: Long,
        ): SensorSummaryProjection {
            val isArmedOrAlert = snapshot.state in setOf(
                ProtectionState.ARMED_HEALTHY,
                ProtectionState.ARMED_DEGRADED,
                ProtectionState.ALERT_ACTIVE,
            )
            val isArming = snapshot.state == ProtectionState.ARMING

            val vibItem = projectVibration(snapshot, nowElapsedMs, isArmedOrAlert, isArming)
            val lightItem = projectLight(snapshot, nowElapsedMs, isArmedOrAlert, isArming)
            val micItem = projectMicrophone(snapshot, nowElapsedMs, isArmedOrAlert, isArming)
            val gpsItem = projectGps(snapshot, nowElapsedMs, isArmedOrAlert, isArming)
            val pwrItem = projectPowerThermal(snapshot, isArmedOrAlert, isArming)

            val sensors = mapOf(
                SensorKind.VIBRATION to vibItem,
                SensorKind.LIGHT to lightItem,
                SensorKind.MICROPHONE to micItem,
                SensorKind.LOCATION to gpsItem,
                SensorKind.POWER_THERMAL to pwrItem,
            )

            val activeCount = if (isArmedOrAlert) {
                sensors.values.count { it.isHealthyOrWorking }
            } else if (isArming) {
                sensors.values.count { it.isHealthyOrWorking || it.isReadyOrWaiting }
            } else {
                0
            }

            val readyCount = snapshot.sensorHealth.count { (kind, health) ->
                if (health.state == SensorHealthState.UNAVAILABLE || health.state == SensorHealthState.FAILED) {
                    return@count false
                }
                when (kind) {
                    SensorKind.LIGHT -> {
                        val detail = health.lightDetail
                        val unsupported = detail?.hardwareSupported == false || snapshot.degradationReasons.any { it.contains("LIGHT", ignoreCase = true) }
                        !unsupported
                    }
                    SensorKind.MICROPHONE -> {
                        val detail = health.microphoneDetail
                        val hwOk = detail?.hardwareAvailable != false
                        val permOk = detail?.permissionGranted != false && !snapshot.permissionBlockers.any {
                            it.contains("RECORD_AUDIO", ignoreCase = true) || it.contains("MICROPHONE", ignoreCase = true)
                        }
                        hwOk && permOk
                    }
                    SensorKind.LOCATION -> {
                        val detail = health.locationDetail
                        val hwOk = detail?.hardwareAvailable != false
                        val permOk = detail?.permissionGranted != false && !snapshot.permissionBlockers.any {
                            it.contains("LOCATION", ignoreCase = true) || it.contains("ACCESS_FINE_LOCATION", ignoreCase = true)
                        }
                        val failureCodeOk = detail?.failureCode !in setOf(
                            LocationFailureCode.PERMISSION_DENIED,
                            LocationFailureCode.NO_PROVIDERS_AVAILABLE,
                            LocationFailureCode.REGISTRATION_FAILED,
                            LocationFailureCode.HARDWARE_UNAVAILABLE,
                        )
                        hwOk && permOk && failureCodeOk
                    }
                    SensorKind.VIBRATION -> {
                        val detail = health.vibrationDetail
                        detail?.hardwareAvailable != false
                    }
                    SensorKind.POWER_THERMAL -> {
                        val detail = health.powerThermalDetail
                        detail?.sourceAvailable != false
                    }
                }
            }

            val headerTh = when {
                isArmedOrAlert -> "🔎 เซนเซอร์กำลังตรวจจับ: $activeCount/5"
                isArming -> "🔎 เซนเซอร์: กำลังเริ่มการทำงาน | พร้อมใช้งาน $readyCount/5"
                snapshot.state == ProtectionState.SETUP_REQUIRED -> "🔎 เซนเซอร์: ต้องตั้งค่าระบบก่อน | พร้อมใช้งาน $readyCount/5"
                snapshot.state == ProtectionState.OFFLINE -> "🔎 เซนเซอร์: ออฟไลน์ | พร้อมใช้งาน $readyCount/5"
                else -> "🔎 เซนเซอร์: หยุดตามคำสั่ง Disarm | พร้อมใช้งาน $readyCount/5"
            }

            return SensorSummaryProjection(
                headerTh = headerTh,
                activeCount = activeCount,
                readyCount = readyCount,
                totalCount = 5,
                sensors = sensors,
            )
        }

        private fun projectVibration(
            snapshot: ProtectionSnapshot,
            nowElapsedMs: Long,
            isArmedOrAlert: Boolean,
            isArming: Boolean,
        ): SensorItemProjection {
            val health = snapshot.sensorHealth[SensorKind.VIBRATION] ?: return SensorItemProjection(
                kind = SensorKind.VIBRATION,
                statusLineTh = "❌ การสั่น: ไม่พร้อม",
                isHealthyOrWorking = false,
                isReadyOrWaiting = false,
                isStale = false,
                isUnavailable = true,
                isFailed = false,
                issueRecommendation = if (isArmedOrAlert) IssueRecommendation("❌ การสั่น: ไม่พร้อม", "ตรวจสอบเซนเซอร์ตรวจจับความเคลื่อนไหว") else null,
            )

            val detail = health.vibrationDetail
            val lastElapsedMs = detail?.lastSampleElapsedMs
            val freshness = healthPolicy.freshness(lastElapsedMs, nowElapsedMs, FRESHNESS_VIBRATION_MS)
            val hwAvailable = detail?.hardwareAvailable ?: (health.state != SensorHealthState.UNAVAILABLE)

            if (!hwAvailable || health.state == SensorHealthState.UNAVAILABLE) {
                return SensorItemProjection(
                    kind = SensorKind.VIBRATION,
                    statusLineTh = "❌ การสั่น: ไม่พร้อม",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = false,
                    isStale = false,
                    isUnavailable = true,
                    isFailed = false,
                    issueRecommendation = if (isArmedOrAlert) IssueRecommendation("❌ การสั่น: ไม่พร้อม", "ตรวจสอบเซนเซอร์ตรวจจับความเคลื่อนไหว") else null,
                )
            }

            if (health.state == SensorHealthState.FAILED) {
                return SensorItemProjection(
                    kind = SensorKind.VIBRATION,
                    statusLineTh = "❌ การสั่น: ผิดพลาด",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = false,
                    isStale = false,
                    isUnavailable = false,
                    isFailed = true,
                    issueRecommendation = IssueRecommendation("❌ การสั่น: ผิดพลาด", "รีสตาร์ทแอปหรือตรวจสอบฮาร์ดแวร์เซนเซอร์"),
                )
            }

            if (isArmedOrAlert) {
                return when (freshness) {
                    FreshnessState.FRESH -> {
                        val ageMs = nowElapsedMs - (lastElapsedMs ?: nowElapsedMs)
                        val ageSec = (ageMs / 1000L).coerceAtLeast(0L)
                        SensorItemProjection(
                            kind = SensorKind.VIBRATION,
                            statusLineTh = "✅ การสั่น: ทำงาน | ล่าสุด $ageSec วินาที",
                            isHealthyOrWorking = true,
                            isReadyOrWaiting = false,
                            isStale = false,
                            isUnavailable = false,
                            isFailed = false,
                        )
                    }
                    FreshnessState.STALE -> SensorItemProjection(
                        kind = SensorKind.VIBRATION,
                        statusLineTh = "⚠️ การสั่น: ข้อมูลเก่า",
                        isHealthyOrWorking = false,
                        isReadyOrWaiting = false,
                        isStale = true,
                        isUnavailable = false,
                        isFailed = false,
                        issueRecommendation = IssueRecommendation("⚠️ การสั่น: ข้อมูลเก่า", "ตรวจสอบเซนเซอร์ตรวจจับความเคลื่อนไหวหรือ Arm ใหม่"),
                    )
                    FreshnessState.CLOCK_ANOMALY -> SensorItemProjection(
                        kind = SensorKind.VIBRATION,
                        statusLineTh = "⚠️ การสั่น: เวลาในระบบผิดปกติ",
                        isHealthyOrWorking = false,
                        isReadyOrWaiting = false,
                        isStale = true,
                        isUnavailable = false,
                        isFailed = false,
                        issueRecommendation = IssueRecommendation("⚠️ การสั่น: เวลาในระบบผิดปกติ", "ตรวจสอบระบบเวลาของโทรศัพท์"),
                    )
                    FreshnessState.MISSING -> {
                        if (detail?.isRegistered == true) {
                            SensorItemProjection(
                                kind = SensorKind.VIBRATION,
                                statusLineTh = "⏳ การสั่น: กำลังเริ่ม",
                                isHealthyOrWorking = false,
                                isReadyOrWaiting = true,
                                isStale = false,
                                isUnavailable = false,
                                isFailed = false,
                            )
                        } else {
                            SensorItemProjection(
                                kind = SensorKind.VIBRATION,
                                statusLineTh = "❌ การสั่น: ไม่พร้อม",
                                isHealthyOrWorking = false,
                                isReadyOrWaiting = false,
                                isStale = false,
                                isUnavailable = true,
                                isFailed = false,
                                issueRecommendation = IssueRecommendation("❌ การสั่น: ไม่พร้อม", "ตรวจสอบเซนเซอร์ตรวจจับความเคลื่อนไหว"),
                            )
                        }
                    }
                }
            } else if (isArming) {
                return SensorItemProjection(
                    kind = SensorKind.VIBRATION,
                    statusLineTh = "⏳ การสั่น: กำลังเริ่ม",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = true,
                    isStale = false,
                    isUnavailable = false,
                    isFailed = false,
                )
            } else {
                return SensorItemProjection(
                    kind = SensorKind.VIBRATION,
                    statusLineTh = "✅ การสั่น: พร้อมใช้งาน",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = true,
                    isStale = false,
                    isUnavailable = false,
                    isFailed = false,
                )
            }
        }

        private fun projectLight(
            snapshot: ProtectionSnapshot,
            nowElapsedMs: Long,
            isArmedOrAlert: Boolean,
            isArming: Boolean,
        ): SensorItemProjection {
            val health = snapshot.sensorHealth[SensorKind.LIGHT] ?: return SensorItemProjection(
                kind = SensorKind.LIGHT,
                statusLineTh = "❌ แสง: ไม่พร้อม",
                isHealthyOrWorking = false,
                isReadyOrWaiting = false,
                isStale = false,
                isUnavailable = true,
                isFailed = false,
                issueRecommendation = if (isArmedOrAlert) IssueRecommendation("❌ แสง: ไม่พร้อม", "ตรวจสอบเซนเซอร์วัดแสง") else null,
            )

            val detail = health.lightDetail
            val hardwareUnsupported = detail?.hardwareSupported == false ||
                health.state == SensorHealthState.UNAVAILABLE ||
                snapshot.degradationReasons.any { it.contains("LIGHT", ignoreCase = true) }

            if (hardwareUnsupported) {
                return SensorItemProjection(
                    kind = SensorKind.LIGHT,
                    statusLineTh = "⚠️ แสง: เครื่องนี้ไม่รองรับ",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = false,
                    isStale = false,
                    isUnavailable = true,
                    isFailed = false,
                    isHardwareUnsupported = true,
                    issueRecommendation = null,
                )
            }

            if (health.state == SensorHealthState.FAILED) {
                return SensorItemProjection(
                    kind = SensorKind.LIGHT,
                    statusLineTh = "❌ แสง: ผิดพลาด",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = false,
                    isStale = false,
                    isUnavailable = false,
                    isFailed = true,
                    issueRecommendation = IssueRecommendation("❌ แสง: ผิดพลาด", "ตรวจสอบเซนเซอร์วัดแสงของเครื่อง"),
                )
            }

            val lastElapsedMs = detail?.lastSampleElapsedMs
            val freshness = healthPolicy.freshness(lastElapsedMs, nowElapsedMs, FRESHNESS_LIGHT_MS)
            val rawLux = detail?.lastLux ?: health.latestReading?.value
            val lux = if (rawLux != null && rawLux.isFinite() && rawLux >= 0.0) rawLux else null

            if (isArmedOrAlert) {
                return when (freshness) {
                    FreshnessState.FRESH -> {
                        val ageMs = nowElapsedMs - (lastElapsedMs ?: nowElapsedMs)
                        val ageSec = (ageMs / 1000L).coerceAtLeast(0L)
                        val luxStr = if (lux != null) "${lux.roundToInt()} lux | " else ""
                        SensorItemProjection(
                            kind = SensorKind.LIGHT,
                            statusLineTh = "✅ แสง: ทำงาน | ${luxStr}ล่าสุด $ageSec วินาที",
                            isHealthyOrWorking = true,
                            isReadyOrWaiting = false,
                            isStale = false,
                            isUnavailable = false,
                            isFailed = false,
                        )
                    }
                    FreshnessState.STALE -> SensorItemProjection(
                        kind = SensorKind.LIGHT,
                        statusLineTh = "⚠️ แสง: ข้อมูลเก่า",
                        isHealthyOrWorking = false,
                        isReadyOrWaiting = false,
                        isStale = true,
                        isUnavailable = false,
                        isFailed = false,
                        issueRecommendation = IssueRecommendation("⚠️ แสง: ข้อมูลเก่า", "ตรวจสอบเซนเซอร์วัดแสงของเครื่อง"),
                    )
                    FreshnessState.CLOCK_ANOMALY -> SensorItemProjection(
                        kind = SensorKind.LIGHT,
                        statusLineTh = "⚠️ แสง: เวลาในระบบผิดปกติ",
                        isHealthyOrWorking = false,
                        isReadyOrWaiting = false,
                        isStale = true,
                        isUnavailable = false,
                        isFailed = false,
                        issueRecommendation = IssueRecommendation("⚠️ แสง: เวลาในระบบผิดปกติ", "ตรวจสอบระบบเวลาของโทรศัพท์"),
                    )
                    FreshnessState.MISSING -> SensorItemProjection(
                        kind = SensorKind.LIGHT,
                        statusLineTh = "⏳ แสง: รอข้อมูล",
                        isHealthyOrWorking = false,
                        isReadyOrWaiting = true,
                        isStale = false,
                        isUnavailable = false,
                        isFailed = false,
                    )
                }
            } else if (isArming) {
                return SensorItemProjection(
                    kind = SensorKind.LIGHT,
                    statusLineTh = "⏳ แสง: รอข้อมูล",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = true,
                    isStale = false,
                    isUnavailable = false,
                    isFailed = false,
                )
            } else {
                return SensorItemProjection(
                    kind = SensorKind.LIGHT,
                    statusLineTh = "✅ แสง: พร้อมใช้งาน",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = true,
                    isStale = false,
                    isUnavailable = false,
                    isFailed = false,
                )
            }
        }

        private fun projectMicrophone(
            snapshot: ProtectionSnapshot,
            nowElapsedMs: Long,
            isArmedOrAlert: Boolean,
            isArming: Boolean,
        ): SensorItemProjection {
            val health = snapshot.sensorHealth[SensorKind.MICROPHONE] ?: return SensorItemProjection(
                kind = SensorKind.MICROPHONE,
                statusLineTh = "❌ ไมโครโฟน: ไม่พร้อมใช้งาน",
                isHealthyOrWorking = false,
                isReadyOrWaiting = false,
                isStale = false,
                isUnavailable = true,
                isFailed = false,
                issueRecommendation = if (isArmedOrAlert) IssueRecommendation("❌ ไมโครโฟน: ไม่พร้อมใช้งาน", "ตรวจสอบไมโครโฟนของตัวเครื่อง") else null,
            )

            val detail = health.microphoneDetail
            val hasPermissionBlocker = snapshot.permissionBlockers.any {
                it.contains("RECORD_AUDIO", ignoreCase = true) || it.contains("MICROPHONE", ignoreCase = true)
            }
            val permissionDenied = (detail != null && !detail.permissionGranted) || hasPermissionBlocker

            if (permissionDenied) {
                return SensorItemProjection(
                    kind = SensorKind.MICROPHONE,
                    statusLineTh = "❌ ไมโครโฟน: ไม่มีสิทธิ์ใช้งาน",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = false,
                    isStale = false,
                    isUnavailable = true,
                    isFailed = false,
                    issueRecommendation = IssueRecommendation("❌ ไมโครโฟน: ไม่มีสิทธิ์ใช้งาน", "เปิดสิทธิ์ Microphone ในการตั้งค่าแอป แล้ว Arm ใหม่"),
                )
            }

            if (detail?.hardwareAvailable == false || health.state == SensorHealthState.UNAVAILABLE) {
                return SensorItemProjection(
                    kind = SensorKind.MICROPHONE,
                    statusLineTh = "❌ ไมโครโฟน: ไม่พร้อมใช้งาน",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = false,
                    isStale = false,
                    isUnavailable = true,
                    isFailed = false,
                    issueRecommendation = if (isArmedOrAlert) IssueRecommendation("❌ ไมโครโฟน: ไม่พร้อมใช้งาน", "ตรวจสอบไมโครโฟนของตัวเครื่อง") else null,
                )
            }

            if (health.state == SensorHealthState.FAILED || detail?.audioState == AudioRuntimeState.FAILED) {
                return SensorItemProjection(
                    kind = SensorKind.MICROPHONE,
                    statusLineTh = "❌ ไมโครโฟน: เกิดข้อผิดพลาด",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = false,
                    isStale = false,
                    isUnavailable = false,
                    isFailed = true,
                    issueRecommendation = IssueRecommendation("❌ ไมโครโฟน: เกิดข้อผิดพลาด", "ตรวจสอบไมโครโฟนหรือรีสตาร์ทแอป"),
                )
            }

            val audioState = detail?.audioState ?: AudioRuntimeState.OFF
            val modelReady = detail?.modelReady == true
            val lastElapsedMs = detail?.lastAudioSampleElapsedMs
            val freshness = healthPolicy.freshness(lastElapsedMs, nowElapsedMs, FRESHNESS_MIC_MS)

            if (isArmedOrAlert) {
                if (detail?.isRegistered == false || audioState == AudioRuntimeState.OFF) {
                    return SensorItemProjection(
                        kind = SensorKind.MICROPHONE,
                        statusLineTh = "❌ ไมโครโฟน: ไม่พร้อมใช้งาน",
                        isHealthyOrWorking = false,
                        isReadyOrWaiting = false,
                        isStale = false,
                        isUnavailable = true,
                        isFailed = false,
                        issueRecommendation = IssueRecommendation("❌ ไมโครโฟน: ไม่พร้อมใช้งาน", "ตรวจสอบไมโครโฟนของตัวเครื่อง"),
                    )
                }
                if (audioState == AudioRuntimeState.CALIBRATING || audioState == AudioRuntimeState.STARTING) {
                    return SensorItemProjection(
                        kind = SensorKind.MICROPHONE,
                        statusLineTh = "⏳ ไมโครโฟน: กำลังคาลิเบรต",
                        isHealthyOrWorking = false,
                        isReadyOrWaiting = true,
                        isStale = false,
                        isUnavailable = false,
                        isFailed = false,
                    )
                }
                return when (freshness) {
                    FreshnessState.FRESH -> {
                        val statusLine = if (modelReady) {
                            "✅ ไมโครโฟน: กำลังฟัง | ตัวจำแนกเสียงพร้อม"
                        } else {
                            "✅ ไมโครโฟน: กำลังฟัง | โมเดลยังไม่พร้อม"
                        }
                        SensorItemProjection(
                            kind = SensorKind.MICROPHONE,
                            statusLineTh = statusLine,
                            isHealthyOrWorking = true,
                            isReadyOrWaiting = false,
                            isStale = false,
                            isUnavailable = false,
                            isFailed = false,
                        )
                    }
                    FreshnessState.STALE -> SensorItemProjection(
                        kind = SensorKind.MICROPHONE,
                        statusLineTh = "⚠️ ไมโครโฟน: ข้อมูลเก่า",
                        isHealthyOrWorking = false,
                        isReadyOrWaiting = false,
                        isStale = true,
                        isUnavailable = false,
                        isFailed = false,
                        issueRecommendation = IssueRecommendation("⚠️ ไมโครโฟน: ข้อมูลเก่า", "ตรวจสอบการทำงานของไมโครโฟนหรือรีสตาร์ทแอป"),
                    )
                    FreshnessState.CLOCK_ANOMALY -> SensorItemProjection(
                        kind = SensorKind.MICROPHONE,
                        statusLineTh = "⚠️ ไมโครโฟน: เวลาในระบบผิดปกติ",
                        isHealthyOrWorking = false,
                        isReadyOrWaiting = false,
                        isStale = true,
                        isUnavailable = false,
                        isFailed = false,
                        issueRecommendation = IssueRecommendation("⚠️ ไมโครโฟน: เวลาในระบบผิดปกติ", "ตรวจสอบระบบเวลาของโทรศัพท์"),
                    )
                    FreshnessState.MISSING -> {
                        if (detail?.isRegistered == true && (audioState == AudioRuntimeState.STARTING || audioState == AudioRuntimeState.CALIBRATING)) {
                            SensorItemProjection(
                                kind = SensorKind.MICROPHONE,
                                statusLineTh = "⏳ ไมโครโฟน: กำลังคาลิเบรต",
                                isHealthyOrWorking = false,
                                isReadyOrWaiting = true,
                                isStale = false,
                                isUnavailable = false,
                                isFailed = false,
                            )
                        } else {
                            SensorItemProjection(
                                kind = SensorKind.MICROPHONE,
                                statusLineTh = "❌ ไมโครโฟน: ไม่พร้อมใช้งาน",
                                isHealthyOrWorking = false,
                                isReadyOrWaiting = false,
                                isStale = false,
                                isUnavailable = true,
                                isFailed = false,
                                issueRecommendation = IssueRecommendation("❌ ไมโครโฟน: ไม่พร้อมใช้งาน", "ตรวจสอบไมโครโฟนของตัวเครื่อง"),
                            )
                        }
                    }
                }
            } else if (isArming) {
                return SensorItemProjection(
                    kind = SensorKind.MICROPHONE,
                    statusLineTh = "⏳ ไมโครโฟน: กำลังคาลิเบรต",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = true,
                    isStale = false,
                    isUnavailable = false,
                    isFailed = false,
                )
            } else {
                return SensorItemProjection(
                    kind = SensorKind.MICROPHONE,
                    statusLineTh = "✅ ไมโครโฟน: พร้อมใช้งาน",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = true,
                    isStale = false,
                    isUnavailable = false,
                    isFailed = false,
                )
            }
        }

        private fun projectGps(
            snapshot: ProtectionSnapshot,
            nowElapsedMs: Long,
            isArmedOrAlert: Boolean,
            isArming: Boolean,
        ): SensorItemProjection {
            val health = snapshot.sensorHealth[SensorKind.LOCATION] ?: return SensorItemProjection(
                kind = SensorKind.LOCATION,
                statusLineTh = "❌ GPS: ไม่พร้อมใช้งาน",
                isHealthyOrWorking = false,
                isReadyOrWaiting = false,
                isStale = false,
                isUnavailable = true,
                isFailed = false,
                issueRecommendation = if (isArmedOrAlert) IssueRecommendation("❌ GPS: ไม่พร้อมใช้งาน", "ตรวจสอบระบบพิกัดตำแหน่งของเครื่อง") else null,
            )

            val detail = health.locationDetail
            val hasPermissionBlocker = snapshot.permissionBlockers.any {
                it.contains("LOCATION", ignoreCase = true) || it.contains("ACCESS_FINE_LOCATION", ignoreCase = true)
            }
            val isPermissionDenied = hasPermissionBlocker || detail?.failureCode == LocationFailureCode.PERMISSION_DENIED || detail?.permissionGranted == false

            if (isPermissionDenied) {
                return SensorItemProjection(
                    kind = SensorKind.LOCATION,
                    statusLineTh = "❌ GPS: ปิดใช้งานตำแหน่งหรือไม่มีสิทธิ์",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = false,
                    isStale = false,
                    isUnavailable = true,
                    isFailed = false,
                    issueRecommendation = IssueRecommendation("❌ GPS: ปิดใช้งานตำแหน่งหรือไม่มีสิทธิ์", "เปิด Location ในการตั้งค่าเครื่องและอนุญาตสิทธิ์ตำแหน่ง"),
                )
            }

            if (detail?.failureCode == LocationFailureCode.HARDWARE_UNAVAILABLE || detail?.hardwareAvailable == false) {
                return SensorItemProjection(
                    kind = SensorKind.LOCATION,
                    statusLineTh = "❌ GPS: ฮาร์ดแวร์ไม่รองรับ",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = false,
                    isStale = false,
                    isUnavailable = true,
                    isFailed = false,
                    issueRecommendation = if (isArmedOrAlert) IssueRecommendation("❌ GPS: ฮาร์ดแวร์ไม่รองรับ", "ตรวจสอบโมดูล GPS ของตัวเครื่อง") else null,
                )
            }

            if (detail?.failureCode == LocationFailureCode.NO_PROVIDERS_AVAILABLE) {
                return SensorItemProjection(
                    kind = SensorKind.LOCATION,
                    statusLineTh = "❌ GPS: ไม่พบผู้ให้บริการตำแหน่ง",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = false,
                    isStale = false,
                    isUnavailable = true,
                    isFailed = false,
                    issueRecommendation = IssueRecommendation("❌ GPS: ไม่พบผู้ให้บริการตำแหน่ง", "เปิดใช้งาน GPS หรือบริการระบุตำแหน่งในเครื่อง"),
                )
            }

            if (health.state == SensorHealthState.FAILED || detail?.failureCode == LocationFailureCode.REGISTRATION_FAILED) {
                return SensorItemProjection(
                    kind = SensorKind.LOCATION,
                    statusLineTh = "❌ GPS: เกิดข้อผิดพลาด",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = false,
                    isStale = false,
                    isUnavailable = false,
                    isFailed = true,
                    issueRecommendation = IssueRecommendation("❌ GPS: เกิดข้อผิดพลาด", "ตรวจสอบการทำงานของระบบตำแหน่งหรือ Arm ใหม่"),
                )
            }

            val trackingState = detail?.trackingState ?: LocationTrackingState.STOPPED
            val isRegistered = detail?.isRegistered == true
            val lastElapsedMs = detail?.lastFixElapsedMs
            val accuracy = detail?.accuracyMeters
            val hasValidAccuracy = accuracy != null && accuracy.isFinite() && accuracy >= 0f
            val isAccurate = accuracy != null && accuracy.isFinite() && accuracy in 0f..100f
            val freshness = healthPolicy.freshness(lastElapsedMs, nowElapsedMs, FRESHNESS_GPS_MS)

            if (isArmedOrAlert) {
                if (trackingState == LocationTrackingState.WAITING_FOR_FIX || (isRegistered && lastElapsedMs == null)) {
                    return SensorItemProjection(
                        kind = SensorKind.LOCATION,
                        statusLineTh = "⏳ GPS: กำลังติดตาม | รอพิกัดแรก",
                        isHealthyOrWorking = false,
                        isReadyOrWaiting = isRegistered,
                        isStale = false,
                        isUnavailable = !isRegistered,
                        isFailed = false,
                    )
                }

                if (!isRegistered) {
                    return SensorItemProjection(
                        kind = SensorKind.LOCATION,
                        statusLineTh = "❌ GPS: หยุดทำงาน",
                        isHealthyOrWorking = false,
                        isReadyOrWaiting = false,
                        isStale = false,
                        isUnavailable = true,
                        isFailed = false,
                        issueRecommendation = IssueRecommendation("❌ GPS: หยุดทำงาน", "ตรวจสอบการทำงานของระบบตำแหน่งหรือ Arm ใหม่"),
                    )
                }

                return when (freshness) {
                    FreshnessState.FRESH -> {
                        if (!isAccurate) {
                            val accStr = if (hasValidAccuracy) " | ±${accuracy.roundToInt()} เมตร" else ""
                            SensorItemProjection(
                                kind = SensorKind.LOCATION,
                                statusLineTh = "⚠️ GPS: ความแม่นยำต่ำ$accStr",
                                isHealthyOrWorking = false,
                                isReadyOrWaiting = false,
                                isStale = true,
                                isUnavailable = false,
                                isFailed = false,
                                issueRecommendation = IssueRecommendation("⚠️ GPS: ความแม่นยำต่ำ", "วางโทรศัพท์ในจุดที่รับสัญญาณดาวเทียมได้ดีขึ้น"),
                            )
                        } else {
                            val ageMs = nowElapsedMs - (lastElapsedMs ?: nowElapsedMs)
                            val ageSec = (ageMs / 1000L).coerceAtLeast(0L)
                            val accStr = if (accuracy != null) " | ±${accuracy.roundToInt()} เมตร" else ""
                            SensorItemProjection(
                                kind = SensorKind.LOCATION,
                                statusLineTh = "✅ GPS: กำลังติดตาม | ล่าสุด $ageSec วินาที$accStr",
                                isHealthyOrWorking = true,
                                isReadyOrWaiting = false,
                                isStale = false,
                                isUnavailable = false,
                                isFailed = false,
                            )
                        }
                    }
                    FreshnessState.STALE -> {
                        val ageMs = nowElapsedMs - (lastElapsedMs ?: nowElapsedMs)
                        val ageSec = (ageMs / 1000L).coerceAtLeast(0L)
                        SensorItemProjection(
                            kind = SensorKind.LOCATION,
                            statusLineTh = "⚠️ GPS: พิกัดล่าสุด $ageSec วินาทีที่แล้ว | ข้อมูลเก่า",
                            isHealthyOrWorking = false,
                            isReadyOrWaiting = false,
                            isStale = true,
                            isUnavailable = false,
                            isFailed = false,
                            issueRecommendation = IssueRecommendation("⚠️ GPS: พิกัดล่าสุด $ageSec วินาทีที่แล้ว | ข้อมูลเก่า", "ตรวจว่าเปิดตำแหน่งและวางโทรศัพท์ในจุดรับสัญญาณได้"),
                        )
                    }
                    FreshnessState.CLOCK_ANOMALY -> SensorItemProjection(
                        kind = SensorKind.LOCATION,
                        statusLineTh = "⚠️ GPS: เวลาในระบบผิดปกติ",
                        isHealthyOrWorking = false,
                        isReadyOrWaiting = false,
                        isStale = true,
                        isUnavailable = false,
                        isFailed = false,
                        issueRecommendation = IssueRecommendation("⚠️ GPS: เวลาในระบบผิดปกติ", "ตรวจสอบระบบเวลาของโทรศัพท์"),
                    )
                    FreshnessState.MISSING -> SensorItemProjection(
                        kind = SensorKind.LOCATION,
                        statusLineTh = "⏳ GPS: กำลังติดตาม | รอพิกัดแรก",
                        isHealthyOrWorking = false,
                        isReadyOrWaiting = true,
                        isStale = false,
                        isUnavailable = false,
                        isFailed = false,
                    )
                }
            } else if (isArming) {
                val isReady = detail?.isRegistered == true
                return SensorItemProjection(
                    kind = SensorKind.LOCATION,
                    statusLineTh = "⏳ GPS: กำลังติดตาม | รอพิกัดแรก",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = isReady,
                    isStale = false,
                    isUnavailable = !isReady,
                    isFailed = false,
                )
            } else {
                return SensorItemProjection(
                    kind = SensorKind.LOCATION,
                    statusLineTh = "✅ GPS: พร้อมใช้งาน",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = true,
                    isStale = false,
                    isUnavailable = false,
                    isFailed = false,
                )
            }
        }

        private fun projectPowerThermal(
            snapshot: ProtectionSnapshot,
            isArmedOrAlert: Boolean,
            isArming: Boolean,
        ): SensorItemProjection {
            val health = snapshot.sensorHealth[SensorKind.POWER_THERMAL] ?: return SensorItemProjection(
                kind = SensorKind.POWER_THERMAL,
                statusLineTh = "⚠️ พลังงาน/อุณหภูมิ: ข้อมูลไม่สมบูรณ์",
                isHealthyOrWorking = false,
                isReadyOrWaiting = false,
                isStale = false,
                isUnavailable = true,
                isFailed = false,
                issueRecommendation = if (isArmedOrAlert) IssueRecommendation("⚠️ พลังงาน/อุณหภูมิ: ข้อมูลไม่สมบูรณ์", "ตรวจสอบสถานะแบตเตอรี่ของเครื่อง") else null,
            )

            val detail = health.powerThermalDetail
            val isAvailable = detail?.sourceAvailable != false && health.state != SensorHealthState.UNAVAILABLE

            if (!isAvailable) {
                return SensorItemProjection(
                    kind = SensorKind.POWER_THERMAL,
                    statusLineTh = "⚠️ พลังงาน/อุณหภูมิ: ข้อมูลไม่สมบูรณ์",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = false,
                    isStale = false,
                    isUnavailable = true,
                    isFailed = false,
                    issueRecommendation = if (isArmedOrAlert) IssueRecommendation("⚠️ พลังงาน/อุณหภูมิ: ข้อมูลไม่สมบูรณ์", "ตรวจสอบสถานะแบตเตอรี่ของเครื่อง") else null,
                )
            }

            if (isArmedOrAlert || isArming) {
                val isRegistered = detail?.isRegistered == true
                if (isRegistered) {
                    return SensorItemProjection(
                        kind = SensorKind.POWER_THERMAL,
                        statusLineTh = "✅ พลังงาน/อุณหภูมิ: ทำงาน",
                        isHealthyOrWorking = true,
                        isReadyOrWaiting = false,
                        isStale = false,
                        isUnavailable = false,
                        isFailed = false,
                    )
                } else {
                    return SensorItemProjection(
                        kind = SensorKind.POWER_THERMAL,
                        statusLineTh = "⚠️ พลังงาน/อุณหภูมิ: ข้อมูลไม่สมบูรณ์",
                        isHealthyOrWorking = false,
                        isReadyOrWaiting = false,
                        isStale = false,
                        isUnavailable = true,
                        isFailed = false,
                        issueRecommendation = if (isArmedOrAlert) IssueRecommendation("⚠️ พลังงาน/อุณหภูมิ: ข้อมูลไม่สมบูรณ์", "ตรวจสอบสถานะแบตเตอรี่ของเครื่อง") else null,
                    )
                }
            } else {
                return SensorItemProjection(
                    kind = SensorKind.POWER_THERMAL,
                    statusLineTh = "✅ พลังงาน/อุณหภูมิ: พร้อมใช้งาน",
                    isHealthyOrWorking = false,
                    isReadyOrWaiting = true,
                    isStale = false,
                    isUnavailable = false,
                    isFailed = false,
                )
            }
        }

        private fun projectBatteryPower(snapshot: ProtectionSnapshot): BatteryPowerProjection {
            val pwrHealth = snapshot.sensorHealth[SensorKind.POWER_THERMAL]
            val pwrDetail = pwrHealth?.powerThermalDetail

            val rawBattery = snapshot.batteryLevelPercent ?: pwrDetail?.batteryLevelPercent
            val batteryLevel = if (rawBattery != null && rawBattery in 0..100) rawBattery else null
            val batteryPercentTh = if (batteryLevel != null) {
                "🔋 แบตเตอรี่: $batteryLevel%"
            } else {
                "🔋 แบตเตอรี่: ยังไม่มีข้อมูล"
            }

            val rawTemp = snapshot.batteryTemperatureCelsius ?: pwrDetail?.temperatureCelsius
            val temp = if (rawTemp != null && rawTemp.isFinite()) rawTemp else null
            val temperatureTh = if (temp != null) {
                String.format(Locale.US, "🌡️ อุณหภูมิเครื่อง (แบตเตอรี่): %.1f°C", temp)
            } else {
                "🌡️ อุณหภูมิเครื่อง (แบตเตอรี่): ยังไม่มีข้อมูล"
            }

            val charging = if (snapshot.chargingState != ChargingState.UNKNOWN) {
                snapshot.chargingState
            } else {
                pwrDetail?.chargingState ?: ChargingState.UNKNOWN
            }

            val powerSourceTh = when (charging) {
                ChargingState.CHARGING -> "🔌 สายชาร์จ: เสียบอยู่ | กำลังชาร์จ"
                ChargingState.DISCHARGING,
                ChargingState.NOT_CHARGING -> "🔌 สายชาร์จ: ไม่ได้เสียบ"
                ChargingState.FULL -> "🔌 สายชาร์จ: เสียบอยู่ | แบตเตอรี่เต็ม"
                ChargingState.UNKNOWN -> "🔌 สายชาร์จ: ยังไม่มีข้อมูล"
            }

            return BatteryPowerProjection(
                batteryPercentTh = batteryPercentTh,
                temperatureTh = temperatureTh,
                powerSourceTh = powerSourceTh,
            )
        }

        private fun projectLastIncident(snapshot: ProtectionSnapshot): LastIncidentProjection? {
            val incident = snapshot.lastIncident ?: return null

            val typeTh = when (incident.type) {
                IncidentType.VIBRATION -> "ตรวจพบการสั่น"
                IncidentType.TAMPER -> "ตรวจพบการงัดแงะหรือขยับรถ"
                IncidentType.AUDIO -> "ตรวจพบเสียงผิดปกติ"
                IncidentType.POWER -> "ตรวจพบการตัดสายไฟหรือถอดสายชาร์จ"
                IncidentType.THERMAL -> "ตรวจพบความร้อนสูงผิดปกติ"
                IncidentType.ENTRY_DOOR -> "ตรวจพบประตูเปิด"
                null -> when (incident.severity) {
                    IncidentSeverity.CRITICAL -> "ตรวจพบความผิดปกติระดับวิกฤต"
                    else -> "ตรวจพบความผิดปกติ"
                }
            }

            val timeTh = if (incident.updatedAtMs > 0L) {
                val sdf = SimpleDateFormat("HH:mm", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("Asia/Bangkok")
                }
                sdf.format(Date(incident.updatedAtMs))
            } else {
                "--:--"
            }

            val statusTh = when (incident.lifecycle) {
                IncidentLifecycle.OPEN -> "กำลังเกิดเหตุ"
                IncidentLifecycle.CLOSED -> "เหตุการณ์สิ้นสุดแล้ว"
                IncidentLifecycle.INTERRUPTED -> "เหตุการณ์ถูกยกเลิก"
            }

            val deliveryState = snapshot.lastDeliveryState ?: incident.deliveryState
            val deliveryTh = when (deliveryState) {
                DeliveryState.SENT -> "ส่งสำเร็จ"
                DeliveryState.PENDING -> "กำลังส่ง"
                DeliveryState.FAILED -> "ล้มเหลว"
                DeliveryState.NOT_ELIGIBLE -> "ไม่จำเป็นต้องส่ง"
            }

            return LastIncidentProjection(
                hasIncident = true,
                typeTh = typeTh,
                timeTh = timeTh,
                statusTh = statusTh,
                deliveryTh = deliveryTh,
            )
        }

        private fun projectIssues(
            primarySystems: PrimarySystemsProjection,
            sensors: SensorSummaryProjection,
        ): IssuesSummaryProjection {
            val issues = mutableListOf<IssueRecommendation>()
            val informationalNotices = mutableListOf<String>()

            primarySystems.serviceIssue?.let { issues.add(it) }
            primarySystems.telegramIssue?.let { issues.add(it) }

            sensors.sensors.values.forEach { sensor ->
                sensor.issueRecommendation?.let { issues.add(it) }
                if (sensor.isHardwareUnsupported) {
                    informationalNotices.add("⚠️ ${sensorKindThaiName(sensor.kind)}: เครื่องนี้ไม่รองรับฮาร์ดแวร์")
                }
            }

            val hasIssues = issues.isNotEmpty()
            val summaryMessageTh = if (!hasIssues && informationalNotices.isEmpty()) {
                "✅ ระบบทำงานครบ ไม่พบปัญหา"
            } else if (!hasIssues && informationalNotices.isNotEmpty()) {
                "✅ ระบบทำงานพร้อม ไม่พบปัญหาขัดข้อง"
            } else {
                "⚠️ ตรวจพบข้อขัดข้องในระบบ"
            }

            return IssuesSummaryProjection(
                hasIssues = hasIssues,
                summaryMessageTh = summaryMessageTh,
                issues = issues,
                informationalNotices = informationalNotices,
            )
        }

        /** Thai hardware-kind name so notices never render raw enum identifiers (Task 8). */
        private fun sensorKindThaiName(kind: SensorKind): String = when (kind) {
            SensorKind.VIBRATION -> "การสั่นสะเทือน"
            SensorKind.LIGHT -> "แสงบริเวณจุดติดตั้ง"
            SensorKind.POWER_THERMAL -> "ไฟและอุณหภูมิ"
            SensorKind.MICROPHONE -> "ไมโครโฟน"
            SensorKind.LOCATION -> "ตำแหน่ง"
        }
    }
}

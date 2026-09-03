package com.example.motorcycleantitheftsensor.protection

enum class GuidanceCode {
    SETUP_REQUIRED, DISARMED, ARMING, ARMED_HEALTHY, ARMED_DEGRADED,
    ALERT_ACTIVE, OFFLINE, SERVICE_RECOVERED,
    BOT_VERIFYING, BOT_CONNECTED, BOT_TOKEN_INVALID, TELEGRAM_UNREACHABLE,
    PAIRING_REQUIRED, PAIRING_ACCEPTED, PAIRING_INVALID_OR_EXPIRED,
    UNAUTHORIZED_COMMAND,
    COMMAND_STATUS_SUCCESS, COMMAND_ARM_APPLIED, COMMAND_ARM_REJECTED,
    COMMAND_DISARM_APPLIED, COMMAND_DISARM_REJECTED,
    COMMAND_SENSITIVITY_APPLIED, COMMAND_SENSITIVITY_INVALID, COMMAND_HELP,
    COMMAND_UNKNOWN, PROFILE_UNSUPPORTED, PROFILE_SELECTED,
    SENSOR_HEALTHY, SENSOR_UNAVAILABLE, SENSOR_PERMISSION_MISSING,
    SENSOR_SAMPLE_FAILED, INCIDENT_OPENED, INCIDENT_UPDATED, INCIDENT_ESCALATED,
    INCIDENT_CLOSED, TELEGRAM_DELIVERY_SENDING, TELEGRAM_DELIVERY_SENT,
    TELEGRAM_DELIVERY_FAILED, SMS_FALLBACK_USED,
    NOTIFICATION_PERMISSION_MISSING, MICROPHONE_PERMISSION_MISSING,
    LOCATION_PERMISSION_MISSING, SMS_PERMISSION_DENIED,
    SETTINGS_SAVE_SUCCESS, SETTINGS_SAVE_FAILED
}

enum class GuidanceSeverity { INFO, SUCCESS, WARNING, CRITICAL }

enum class GuidanceAction {
    NONE,
    OPEN_TELEGRAM_SETTINGS,
    OPEN_PERMISSION_SETTINGS,
    OPEN_PROTECTION,
    OPEN_EVENTS,
    RETRY_NON_SENSITIVE_SETTINGS
}

enum class ReasonLabel {
    TELEGRAM_NOT_READY,
    LIGHT_SENSOR_UNAVAILABLE,
    NOTIFICATION_PERMISSION_MISSING
}

sealed class GuidanceDetail {
    object None : GuidanceDetail()
    data class ArmingSeconds(val seconds: Int) : GuidanceDetail()
    data class SensitivityLevel(val level: Int) : GuidanceDetail()
    data class ProtectionStateValue(val state: ProtectionState) : GuidanceDetail()
    data class IncidentTypeValue(val incidentType: IncidentType) : GuidanceDetail()
    data class SensorKindValue(val sensorKind: SensorKind) : GuidanceDetail()
    data class SafeReason(val reason: ReasonLabel) : GuidanceDetail()

    /**
     * Why this phone cannot carry a protection use, carried whole rather than as a code.
     *
     * The sentence the owner reads names a missing sensor or a measured drift rate with the
     * hours behind it, so the text cannot be looked up from an enum alone.
     */
    data class ProfileSupportValue(val support: ProfileDeviceSupport) : GuidanceDetail()
}

data class GuidanceContent(
    val titleTh: String,
    val bodyTh: String,
    val telegramTh: String?,
    val severity: GuidanceSeverity,
    val action: GuidanceAction,
    val persistent: Boolean,
)


object UserGuidanceCatalog {
    fun content(code: GuidanceCode, detail: GuidanceDetail = GuidanceDetail.None): GuidanceContent {
        val raw = when (code) {
            GuidanceCode.SETUP_REQUIRED -> GuidanceContent(
                titleTh = "ต้องตั้งค่าระบบก่อนเปิดการป้องกัน",
                bodyTh = "ตั้งค่า Bot และจับคู่เจ้าของให้ครบ",
                telegramTh = "⚠️ ระบบยังตั้งค่าไม่ครบ ดูหน้าการตั้งค่าในแอป",
                severity = GuidanceSeverity.WARNING,
                action = GuidanceAction.OPEN_TELEGRAM_SETTINGS,
                persistent = true
            )
            GuidanceCode.DISARMED -> GuidanceContent(
                titleTh = "การป้องกันปิดอยู่",
                bodyTh = "ระบบออนไลน์และพร้อมเปิดการป้องกัน",
                telegramTh = "ℹ️ ปิดการป้องกันแล้ว",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.OPEN_PROTECTION,
                persistent = false
            )
            GuidanceCode.ARMING -> GuidanceContent(
                titleTh = "กำลังเปิดการป้องกัน",
                bodyTh = "กำลังปรับเทียบเซนเซอร์ เหลือ {seconds} วินาที",
                telegramTh = "ℹ️ กำลังเปิดการป้องกัน รอการปรับเทียบเซนเซอร์",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.ARMED_HEALTHY -> GuidanceContent(
                titleTh = "การป้องกันทำงานปกติ",
                bodyTh = "ระบบหลักพร้อมทำงาน",
                telegramTh = "✅ การป้องกันทำงานปกติ",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.OPEN_PROTECTION,
                persistent = false
            )
            GuidanceCode.ARMED_DEGRADED -> GuidanceContent(
                titleTh = "การป้องกันทำงานแบบจำกัด",
                bodyTh = "{reason}",
                telegramTh = "⚠️ การป้องกันทำงานแบบจำกัด: {reason}",
                severity = GuidanceSeverity.WARNING,
                action = GuidanceAction.OPEN_PROTECTION,
                persistent = true
            )
            GuidanceCode.ALERT_ACTIVE -> GuidanceContent(
                titleTh = "กำลังส่งสัญญาณเตือนภัย",
                bodyTh = "ตรวจพบความผิดปกติและกำลังส่งสัญญาณเตือนภัย",
                telegramTh = "🚨 กำลังส่งสัญญาณเตือนภัย ตรวจพบความผิดปกติ",
                severity = GuidanceSeverity.CRITICAL,
                action = GuidanceAction.OPEN_EVENTS,
                persistent = true
            )
            GuidanceCode.OFFLINE -> GuidanceContent(
                titleTh = "ระบบออฟไลน์",
                bodyTh = "บริการหลักหยุดทำงาน",
                telegramTh = "⚠️ ระบบออฟไลน์ บริการหลักหยุดทำงาน",
                severity = GuidanceSeverity.WARNING,
                action = GuidanceAction.OPEN_PROTECTION,
                persistent = true
            )
            GuidanceCode.SERVICE_RECOVERED -> GuidanceContent(
                titleTh = "บริการเริ่มใหม่สำเร็จ",
                bodyTh = "ระบบกู้คืนสถานะเดิมแล้ว",
                telegramTh = "✅ บริการเริ่มใหม่สำเร็จ ระบบกู้คืนสถานะเดิมแล้ว",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.OPEN_PROTECTION,
                persistent = false
            )
            GuidanceCode.BOT_VERIFYING -> GuidanceContent(
                titleTh = "กำลังทดสอบเชื่อมต่อ Bot…",
                bodyTh = "โปรดรอสักครู่",
                telegramTh = null,
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.BOT_CONNECTED -> GuidanceContent(
                titleTh = "เชื่อมต่อ Telegram Bot สำเร็จ",
                bodyTh = "พร้อมรับส่งข้อความแจ้งเตือน",
                telegramTh = null,
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.BOT_TOKEN_INVALID -> GuidanceContent(
                titleTh = "Token ไม่ถูกต้องหรือเชื่อมต่อไม่ได้",
                bodyTh = "ตรวจสอบ Token จาก BotFather แล้วลองใหม่",
                telegramTh = null,
                severity = GuidanceSeverity.WARNING,
                action = GuidanceAction.OPEN_TELEGRAM_SETTINGS,
                persistent = true
            )
            GuidanceCode.TELEGRAM_UNREACHABLE -> GuidanceContent(
                titleTh = "ติดต่อ Telegram ไม่ได้",
                bodyTh = "ตรวจสอบอินเทอร์เน็ตของอุปกรณ์ที่ติดตั้ง",
                telegramTh = "⚠️ ติดต่อ Telegram ไม่ได้ ตรวจสอบอินเทอร์เน็ตของอุปกรณ์",
                severity = GuidanceSeverity.WARNING,
                action = GuidanceAction.OPEN_TELEGRAM_SETTINGS,
                persistent = true
            )
            GuidanceCode.PAIRING_REQUIRED -> GuidanceContent(
                titleTh = "ต้องจับคู่ Telegram ก่อน",
                bodyTh = "พิมพ์ /pair <รหัส> จากแอปในอุปกรณ์",
                telegramTh = "🔒 ต้องจับคู่ Telegram ก่อน พิมพ์ /pair <รหัส> จากแอปในอุปกรณ์",
                severity = GuidanceSeverity.WARNING,
                action = GuidanceAction.OPEN_TELEGRAM_SETTINGS,
                persistent = true
            )
            GuidanceCode.PAIRING_ACCEPTED -> GuidanceContent(
                titleTh = "จับคู่ Telegram สำเร็จ",
                bodyTh = "บัญชีนี้สามารถสั่งงานระบบได้",
                telegramTh = "✅ จับคู่ Telegram สำเร็จ บัญชีนี้สามารถสั่งงานระบบได้",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.PAIRING_INVALID_OR_EXPIRED -> GuidanceContent(
                titleTh = "รหัสจับคู่ไม่ถูกต้องหรือหมดอายุ",
                bodyTh = "รหัสไม่ถูกต้องหรือหมดอายุ สร้างรหัสใหม่ในแอป",
                telegramTh = "⚠️ รหัสจับคู่ไม่ถูกต้องหรือหมดอายุ",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.RETRY_NON_SENSITIVE_SETTINGS,
                persistent = false
            )
            GuidanceCode.UNAUTHORIZED_COMMAND -> GuidanceContent(
                titleTh = "คำสั่งจากบัญชีที่ไม่ได้รับอนุญาต",
                bodyTh = "ไม่มีการเปลี่ยนสถานะของระบบ",
                telegramTh = "🔒 บัญชีนี้ไม่ได้รับอนุญาตให้สั่งงาน",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.COMMAND_STATUS_SUCCESS -> GuidanceContent(
                titleTh = "สถานะระบบ",
                bodyTh = "อัปเดตข้อมูลสถานะ",
                telegramTh = "ℹ️ สถานะระบบ: {protectionStatus}",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.COMMAND_ARM_APPLIED -> GuidanceContent(
                titleTh = "เปิดการป้องกันแล้ว",
                bodyTh = "กำลังเปิดการป้องกัน…",
                telegramTh = "✅ กำลังเปิดการป้องกัน โปรดรอการปรับเทียบเซนเซอร์",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.COMMAND_ARM_REJECTED -> GuidanceContent(
                titleTh = "ไม่สามารถเปิดการป้องกันได้",
                bodyTh = "ไม่สามารถเปิดการป้องกันได้: {safeReason}",
                telegramTh = "⚠️ เปิดการป้องกันไม่ได้: {safeReason}",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.COMMAND_DISARM_APPLIED -> GuidanceContent(
                titleTh = "ปลดการป้องกันสำเร็จ",
                bodyTh = "ปลดการป้องกันสำเร็จ",
                telegramTh = "✅ ปลดการป้องกันสำเร็จ",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.COMMAND_DISARM_REJECTED -> GuidanceContent(
                titleTh = "ไม่สามารถปลดการป้องกันได้",
                bodyTh = "ไม่สามารถปลดการป้องกันได้: {safeReason}",
                telegramTh = "⚠️ ปลดการป้องกันไม่ได้: {safeReason}",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.COMMAND_SENSITIVITY_APPLIED -> GuidanceContent(
                titleTh = "บันทึกระดับการตรวจจับแล้ว",
                bodyTh = "บันทึกระดับการตรวจจับแล้ว",
                telegramTh = "✅ บันทึกระดับการตรวจจับ {level}/10 แล้ว (ใช้ได้เฉพาะเซ็นเซอร์ที่รองรับในโหมดยานพาหนะ)",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.COMMAND_SENSITIVITY_INVALID -> GuidanceContent(
                titleTh = "ระดับการตรวจจับไม่ถูกต้อง",
                bodyTh = "ระดับการตรวจจับไม่ถูกต้อง",
                telegramTh = "⚠️ ระดับการตรวจจับต้องอยู่ระหว่าง 1 ถึง 10",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.COMMAND_HELP -> GuidanceContent(
                titleTh = "คำสั่งที่ใช้ได้",
                bodyTh = "ดูรายการคำสั่งใน Telegram",
                telegramTh = "ℹ️ คำสั่ง: /status, /arm, /disarm, /sensitivity 1-10 ปรับระดับการตรวจจับ " +
                    "(/sensitivity เป็นคำสั่งเดิม ใช้ได้เฉพาะเซ็นเซอร์ที่รองรับในโหมดยานพาหนะ)",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.COMMAND_UNKNOWN -> GuidanceContent(
                titleTh = "คำสั่งไม่สำเร็จ",
                bodyTh = "พิมพ์ /help เพื่อดูคำสั่งที่ใช้ได้",
                telegramTh = "ℹ️ ไม่พบคำสั่ง พิมพ์ /help เพื่อดูคำสั่งที่ใช้ได้",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.PROFILE_SELECTED -> GuidanceContent(
                titleTh = "เลือกการใช้งานแล้ว",
                bodyTh = "ตรวจสอบการตั้งค่าของการใช้งานนี้ก่อนเปิดระบบ",
                telegramTh = null,
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.OPEN_PROTECTION,
                persistent = false
            )
            GuidanceCode.PROFILE_UNSUPPORTED -> GuidanceContent(
                titleTh = "ใช้โหมดนี้บนเครื่องนี้ไม่ได้",
                bodyTh = "{profileSupport}",
                telegramTh = "⚠️ ใช้โหมดนี้บนเครื่องนี้ไม่ได้: {profileSupport}",
                severity = GuidanceSeverity.WARNING,
                action = GuidanceAction.OPEN_PROTECTION,
                persistent = false
            )
            GuidanceCode.SENSOR_HEALTHY -> GuidanceContent(
                titleTh = "เซนเซอร์พร้อมใช้งาน",
                bodyTh = "{sensorName} กำลังอ่านค่า",
                telegramTh = null,
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.SENSOR_UNAVAILABLE -> GuidanceContent(
                titleTh = "เซนเซอร์ไม่พร้อมใช้งาน",
                bodyTh = "{sensorName}: {safeReason}",
                telegramTh = "รวมใน /status",
                severity = GuidanceSeverity.WARNING,
                action = GuidanceAction.OPEN_PROTECTION,
                persistent = true
            )
            GuidanceCode.SENSOR_PERMISSION_MISSING -> GuidanceContent(
                titleTh = "ต้องอนุญาตสิทธิ์",
                bodyTh = "เปิดสิทธิ์ที่จำเป็นเพื่อใช้งานฟีเจอร์นี้",
                telegramTh = null,
                severity = GuidanceSeverity.WARNING,
                action = GuidanceAction.OPEN_PERMISSION_SETTINGS,
                persistent = true
            )
            GuidanceCode.SENSOR_SAMPLE_FAILED -> GuidanceContent(
                titleTh = "อ่านค่าเซนเซอร์ไม่สำเร็จ",
                bodyTh = "ระบบยังทำงานด้วยเซนเซอร์ที่พร้อม",
                telegramTh = "รวมใน /status",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.INCIDENT_OPENED -> GuidanceContent(
                titleTh = "ตรวจพบ {incidentType}",
                bodyTh = "กำลังส่งการแจ้งเตือน",
                telegramTh = "🚨 ตรวจพบ {incidentType}",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.INCIDENT_UPDATED -> GuidanceContent(
                titleTh = "เหตุการณ์เดิมกำลังอัปเดต",
                bodyTh = "บันทึกหลักฐานเพิ่มโดยไม่ส่งข้อความซ้ำ",
                telegramTh = null,
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.INCIDENT_ESCALATED -> GuidanceContent(
                titleTh = "เหตุการณ์รุนแรงขึ้น",
                bodyTh = "{incidentType}",
                telegramTh = "🚨 เหตุยกระดับเป็นวิกฤต: {incidentType}",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.INCIDENT_CLOSED -> GuidanceContent(
                titleTh = "เหตุการณ์สิ้นสุดแล้ว",
                // The stillness window is why a movement incident closes; saying it on a
                // power or door incident misreports what the system actually observed.
                bodyTh = when ((detail as? GuidanceDetail.IncidentTypeValue)?.incidentType) {
                    IncidentType.POWER -> "ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว"
                    IncidentType.ENTRY_DOOR -> "ประตูปิดและนิ่งแล้ว"
                    else -> "ไม่มีความเคลื่อนไหวต่อเนื่อง 30 วินาที"
                },
                telegramTh = "ℹ️ เหตุการณ์สิ้นสุดแล้ว",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.TELEGRAM_DELIVERY_SENDING -> GuidanceContent(
                titleTh = "กำลังส่ง Telegram…",
                bodyTh = "กำลังส่ง Telegram…",
                telegramTh = "ไม่มีข้อความเพิ่ม",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.TELEGRAM_DELIVERY_SENT -> GuidanceContent(
                titleTh = "ส่ง Telegram สำเร็จ",
                bodyTh = "ส่ง Telegram สำเร็จ",
                telegramTh = "ข้อความเหตุการณ์หลัก",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.TELEGRAM_DELIVERY_FAILED -> GuidanceContent(
                titleTh = "ส่ง Telegram ไม่สำเร็จ",
                bodyTh = "ระบบจะรายงานสถานะการเชื่อมต่อ",
                telegramTh = "ไม่มีการตอบซ้ำ",
                severity = GuidanceSeverity.WARNING,
                action = GuidanceAction.OPEN_PROTECTION,
                persistent = true
            )
            GuidanceCode.SMS_FALLBACK_USED -> GuidanceContent(
                titleTh = "ใช้ช่องทางสำรองสำหรับเหตุการณ์วิกฤต",
                bodyTh = "ใช้ช่องทางสำรองสำหรับเหตุการณ์วิกฤต",
                telegramTh = "ไม่เปิดเผยปลายทางหรือ key",
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.NOTIFICATION_PERMISSION_MISSING -> GuidanceContent(
                titleTh = "ยังไม่ได้อนุญาตการแจ้งเตือน",
                bodyTh = "เปิดสิทธิ์เพื่อเห็นสถานะสำคัญบนอุปกรณ์เครื่องนี้",
                telegramTh = null,
                severity = GuidanceSeverity.WARNING,
                action = GuidanceAction.OPEN_PERMISSION_SETTINGS,
                persistent = true
            )
            GuidanceCode.MICROPHONE_PERMISSION_MISSING -> GuidanceContent(
                titleTh = "ไมโครโฟนยังไม่พร้อม",
                bodyTh = "การตรวจจับเสียงจะไม่ทำงาน แต่ระบบส่วนอื่นยังทำงาน",
                telegramTh = null,
                severity = GuidanceSeverity.WARNING,
                action = GuidanceAction.OPEN_PERMISSION_SETTINGS,
                persistent = true
            )
            GuidanceCode.LOCATION_PERMISSION_MISSING -> GuidanceContent(
                titleTh = "ตำแหน่งยังไม่พร้อม",
                bodyTh = "ข้อมูลตำแหน่งจะไม่ถูกรวมในเหตุการณ์",
                telegramTh = null,
                severity = GuidanceSeverity.WARNING,
                action = GuidanceAction.OPEN_PERMISSION_SETTINGS,
                persistent = true
            )
            GuidanceCode.SMS_PERMISSION_DENIED -> GuidanceContent(
                titleTh = "SMS ถูกปิดเพื่อความปลอดภัย",
                bodyTh = "ไม่มีการส่ง SMS จนกว่าผู้ใช้จะอนุญาตตาม flow ที่กำหนด",
                telegramTh = null,
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.SETTINGS_SAVE_SUCCESS -> GuidanceContent(
                titleTh = "บันทึกการตั้งค่าสำเร็จ",
                bodyTh = "บันทึกการตั้งค่าสำเร็จ",
                telegramTh = null,
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.NONE,
                persistent = false
            )
            GuidanceCode.SETTINGS_SAVE_FAILED -> GuidanceContent(
                titleTh = "บันทึกการตั้งค่าไม่สำเร็จ",
                bodyTh = "ตรวจสอบข้อมูลแล้วลองใหม่",
                telegramTh = null,
                severity = GuidanceSeverity.INFO,
                action = GuidanceAction.RETRY_NON_SENSITIVE_SETTINGS,
                persistent = false
            )
        }

        val seconds = (detail as? GuidanceDetail.ArmingSeconds)?.seconds?.toString() ?: "0"
        val level = (detail as? GuidanceDetail.SensitivityLevel)?.level?.toString() ?: "0"
        val state = (detail as? GuidanceDetail.ProtectionStateValue)?.state?.name ?: ""
        val incidentType = (detail as? GuidanceDetail.IncidentTypeValue)
            ?.incidentType
            ?.let(PresentationTextCatalog::incidentTypeLabel)
            ?: ""
        val sensorName = (detail as? GuidanceDetail.SensorKindValue)?.sensorKind?.name ?: ""
        val safeReason = (detail as? GuidanceDetail.SafeReason)?.reason?.name ?: ""
        val profileSupport = (detail as? GuidanceDetail.ProfileSupportValue)
            ?.support
            ?.let(PresentationTextCatalog::profileSupport)
            ?: "เครื่องนี้ไม่มีเซ็นเซอร์ที่โหมดนี้ต้องใช้"
        val permissionName = "Permission"
        val featureName = "Feature"

        fun String?.resolve(): String? {
            return this?.replace("{seconds}", seconds)
                ?.replace("{level}", level)
                ?.replace("{state}", state)
                ?.replace("{incidentType}", incidentType)
                ?.replace("{sensorName}", sensorName)
                ?.replace("{safeReason}", safeReason)
                ?.replace("{profileSupport}", profileSupport)
                ?.replace("{reason}", safeReason)
                ?.replace("{permissionName}", permissionName)
                ?.replace("{featureName}", featureName)
        }

        return raw.copy(
            titleTh = raw.titleTh.resolve()!!,
            bodyTh = raw.bodyTh.resolve()!!,
            telegramTh = raw.telegramTh.resolve()
        )
    }
}

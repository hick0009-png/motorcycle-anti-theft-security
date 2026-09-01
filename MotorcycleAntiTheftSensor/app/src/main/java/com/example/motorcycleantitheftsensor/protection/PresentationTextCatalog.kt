package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.sensor.SensorAvailability
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PresentationTextCatalog {

    const val NEUTRAL_INCIDENT_GUIDANCE = "ตรวจสอบสถานการณ์และดำเนินการตามความเหมาะสม"

    /**
     * Approved profile card wording from the profile-aware Thai UX specification.
     * Profile names are protection contexts, never installation-type inferences.
     */
    fun profile(profile: ProtectionProfile): ProfilePresentation = when (profile) {
        ProtectionProfile.VEHICLE -> ProfilePresentation(
            name = "ยานพาหนะ",
            promise = "แจ้งเตือนเมื่อรถยนต์หรือรถจักรยานยนต์ถูกกระทบ ขยับ หรือเคลื่อนย้าย",
        )
        ProtectionProfile.ENTRY -> ProfilePresentation(
            name = "ประตูและทางเข้า",
            promise = "แจ้งเตือนเมื่อประตูที่ติดตั้งโทรศัพท์ไว้เปิดเกินมุมที่กำหนด หรือเกิดแรงกระแทก",
        )
        ProtectionProfile.POWER -> ProfilePresentation(
            name = "ไฟเลี้ยงจุดติดตั้ง",
            promise = "เฝ้าระวังสายชาร์จและไฟยืนยันของปลั๊กหรือรางไฟที่ตั้งค่าไว้",
        )
    }

    /**
     * Why a profile locks the sensors it does not use, in the owner's words.
     *
     * [SensorLockPresentation.notice] heads the advanced sensor card; [reason] repeats
     * on each locked row. Both are null for a profile that locks nothing, and null when
     * no profile is selected — an unselected state must not claim anything is locked.
     */
    fun sensorLock(profile: ProtectionProfile?): SensorLockPresentation = when (profile) {
        null, ProtectionProfile.VEHICLE, ProtectionProfile.ENTRY -> SensorLockPresentation()
        ProtectionProfile.POWER -> SensorLockPresentation(
            notice = "โหมดไฟเลี้ยงใช้เฉพาะเซ็นเซอร์แสง (ไฟยืนยัน) และสถานะการชาร์จ " +
                "เซ็นเซอร์อื่นถูกล็อกไว้ในโหมดนี้ ปรับค่าไม่ได้",
            reason = "โหมดไฟเลี้ยงไม่ใช้เซ็นเซอร์นี้ — การขยับตอนถอด/เสียบสายชาร์จ " +
                "จะเปิดเหตุการณ์ซ้อนกับเหตุการณ์ไฟเลี้ยง",
            presetNotice = "กำหนดโดยโหมดไฟเลี้ยง",
        )
    }

    /** Evidence-role labels approved by the spec (§3.3). */
    fun evidenceRoleLabel(role: SensorRole): String = when (role) {
        SensorRole.PRIMARY -> "ใช้ยืนยันหลัก"
        SensorRole.SUPPORTING -> "ใช้ประกอบการยืนยัน"
        SensorRole.OFF -> "ไม่ใช้"
    }

    /**
     * Profile-aware capability wording. The primary adjustable control exists only
     * where a real bounded detector parameter changes; supporting evidence never
     * claims theft, forced entry, or a site-wide outage by itself.
     */
    fun capability(profile: ProtectionProfile, capability: SensorCapability): CapabilityPresentation =
        when (profile) {
            ProtectionProfile.VEHICLE -> when (capability) {
                SensorCapability.MOVEMENT -> CapabilityPresentation(
                    title = "การขยับที่ต้องการให้แจ้งเตือน",
                    explanation = "ปรับระดับการตรวจจับได้ตั้งแต่ ต้องขยับมากจึงตรวจพบ ถึง ขยับเล็กน้อยก็ตรวจพบ",
                    roleLabel = evidenceRoleLabel(SensorRole.PRIMARY),
                    isPrimaryControl = true,
                    isGenericSensitivityControl = true,
                )
                SensorCapability.ROTATION -> CapabilityPresentation(
                    title = "การหมุนหรือเอียง",
                    explanation = "ใช้ประกอบการยืนยันการขยับหรือเคลื่อนย้าย",
                    roleLabel = evidenceRoleLabel(SensorRole.SUPPORTING),
                    isPrimaryControl = false,
                    isGenericSensitivityControl = false,
                )
                SensorCapability.MAGNETIC -> CapabilityPresentation(
                    title = "สนามแม่เหล็กรอบจุดติดตั้ง",
                    explanation = "ใช้ประกอบการยืนยันเมื่อสิ่งแวดล้อมบริเวณจุดติดตั้งเปลี่ยน",
                    roleLabel = evidenceRoleLabel(SensorRole.SUPPORTING),
                    isPrimaryControl = false,
                    isGenericSensitivityControl = false,
                )
                SensorCapability.LIGHT -> CapabilityPresentation(
                    title = "แสงบริเวณจุดติดตั้ง",
                    explanation = "ใช้ประกอบการยืนยันเมื่อสิ่งที่บังเซ็นเซอร์ถูกนำออก",
                    roleLabel = evidenceRoleLabel(SensorRole.SUPPORTING),
                    isPrimaryControl = false,
                    isGenericSensitivityControl = false,
                )
                SensorCapability.PROXIMITY -> CapabilityPresentation(
                    title = "วัตถุใกล้โทรศัพท์",
                    explanation = "แจ้งสถานะใกล้หรือไกลเพื่อประกอบการยืนยัน ไม่วัดระยะเป็นเซนติเมตร",
                    roleLabel = evidenceRoleLabel(SensorRole.SUPPORTING),
                    isPrimaryControl = false,
                    isGenericSensitivityControl = false,
                )
            }
            ProtectionProfile.ENTRY -> when (capability) {
                SensorCapability.MOVEMENT -> CapabilityPresentation(
                    title = "แรงกระแทกหรือการสั่น",
                    explanation = "ใช้ประกอบการยืนยันแรงกระแทกที่บานประตู",
                    roleLabel = evidenceRoleLabel(SensorRole.SUPPORTING),
                    isPrimaryControl = false,
                    isGenericSensitivityControl = false,
                )
                SensorCapability.ROTATION -> CapabilityPresentation(
                    title = "มุมเปลี่ยนจากตำแหน่งเริ่มต้น",
                    explanation = "วัดมุมเทียบตำแหน่งปิดที่ปรับเทียบไว้ ไม่ใช่ความไวการหมุน",
                    roleLabel = evidenceRoleLabel(SensorRole.PRIMARY),
                    isPrimaryControl = false,
                    isGenericSensitivityControl = false,
                )
                SensorCapability.MAGNETIC -> CapabilityPresentation(
                    title = "สนามแม่เหล็กบริเวณประตู",
                    explanation = "ใช้ประกอบการยืนยันเท่านั้น ไม่ใช่หลักฐานว่าประตูเปิด",
                    roleLabel = evidenceRoleLabel(SensorRole.SUPPORTING),
                    isPrimaryControl = false,
                    isGenericSensitivityControl = false,
                )
                SensorCapability.LIGHT -> CapabilityPresentation(
                    title = "แสงบริเวณประตู",
                    explanation = "ใช้ประกอบการยืนยันเมื่อแสงที่จุดติดตั้งเปลี่ยน",
                    roleLabel = evidenceRoleLabel(SensorRole.SUPPORTING),
                    isPrimaryControl = false,
                    isGenericSensitivityControl = false,
                )
                SensorCapability.PROXIMITY -> CapabilityPresentation(
                    title = "วัตถุใกล้โทรศัพท์",
                    explanation = "แจ้งสถานะใกล้หรือไกลเพื่อประกอบการยืนยัน ไม่วัดระยะเป็นเซนติเมตร",
                    roleLabel = evidenceRoleLabel(SensorRole.SUPPORTING),
                    isPrimaryControl = false,
                    isGenericSensitivityControl = false,
                )
            }
            ProtectionProfile.POWER -> when (capability) {
                SensorCapability.MOVEMENT -> CapabilityPresentation(
                    title = "การสั่นหรือถูกขยับ",
                    explanation = "ใช้ประกอบการยืนยันว่าโทรศัพท์หรือขายึดถูกขยับ",
                    roleLabel = evidenceRoleLabel(SensorRole.SUPPORTING),
                    isPrimaryControl = false,
                    isGenericSensitivityControl = false,
                )
                SensorCapability.ROTATION -> CapabilityPresentation(
                    title = "การเปลี่ยนการวางแนว",
                    explanation = "ใช้ประกอบการยืนยันเมื่อการวางโทรศัพท์เปลี่ยน",
                    roleLabel = evidenceRoleLabel(SensorRole.SUPPORTING),
                    isPrimaryControl = false,
                    isGenericSensitivityControl = false,
                )
                SensorCapability.MAGNETIC -> CapabilityPresentation(
                    title = "สนามแม่เหล็กบริเวณจุดติดตั้ง",
                    explanation = "ใช้ประกอบการยืนยันเท่านั้น",
                    roleLabel = evidenceRoleLabel(SensorRole.SUPPORTING),
                    isPrimaryControl = false,
                    isGenericSensitivityControl = false,
                )
                SensorCapability.LIGHT -> CapabilityPresentation(
                    title = "ไฟยืนยันจุดติดตั้ง",
                    explanation = "ยืนยันด้วยช่วงสว่างหรือมืดที่ตั้งค่าไว้ตอนเปิดระบบ ไม่ใช้ความไวแสงทั่วไป",
                    roleLabel = evidenceRoleLabel(SensorRole.PRIMARY),
                    isPrimaryControl = true,
                    isGenericSensitivityControl = false,
                )
                SensorCapability.PROXIMITY -> CapabilityPresentation(
                    title = "วัตถุใกล้โทรศัพท์",
                    explanation = "แจ้งสถานะใกล้หรือไกลเพื่อประกอบการยืนยัน ไม่วัดระยะเป็นเซนติเมตร",
                    roleLabel = evidenceRoleLabel(SensorRole.SUPPORTING),
                    isPrimaryControl = false,
                    isGenericSensitivityControl = false,
                )
            }
        }

    /**
     * What a device limitation costs the owner, in the owner's words. Never a scolding
     * about their phone: it says what this use can and cannot do here.
     */
    fun profileSupport(support: ProfileDeviceSupport): String? = when (support) {
        ProfileDeviceSupport.Supported -> null
        is ProfileDeviceSupport.Degraded -> when (support.reason) {
            ProfileSupportReason.NO_LIGHT_SENSOR ->
                "เครื่องนี้ไม่มีเซ็นเซอร์วัดแสง จะใช้ได้เฉพาะสัญญาณการชาร์จ " +
                    "ยืนยันสองทางไม่ได้ ความแม่นยำลดลง"
            ProfileSupportReason.NO_GYROSCOPE_COMPASS_ONLY ->
                "เครื่องนี้ไม่มีไจโรสโคป จะวัดมุมด้วยเข็มทิศอย่างเดียว " +
                    "ต้องใช้เวลายืนยันนานขึ้น"
            ProfileSupportReason.NO_MOVEMENT_SENSOR, ProfileSupportReason.NO_ANGLE_SENSOR -> null
        }
        is ProfileDeviceSupport.Unsupported -> when (support.reason) {
            ProfileSupportReason.NO_ANGLE_SENSOR ->
                "เครื่องนี้ไม่มีไจโรสโคปและเข็มทิศ จึงวัดมุมการเปิดประตูไม่ได้"
            ProfileSupportReason.NO_MOVEMENT_SENSOR ->
                "เครื่องนี้ไม่มีมาตรวัดความเร่ง จึงตรวจการขยับหรือเคลื่อนย้ายไม่ได้"
            ProfileSupportReason.NO_LIGHT_SENSOR, ProfileSupportReason.NO_GYROSCOPE_COMPASS_ONLY -> null
        }
    }

    fun profileSupportBadge(support: ProfileDeviceSupport): String = when (support) {
        ProfileDeviceSupport.Supported -> "🟢 เครื่องนี้ใช้ได้"
        is ProfileDeviceSupport.Degraded -> "🟡 ใช้ได้บางส่วน"
        is ProfileDeviceSupport.Unsupported -> "⚪ ใช้ไม่ได้"
    }

    /**
     * Hardware availability wording. "จำกัด" must never read as a fault: the sensor is
     * present and usable, it just cannot carry a confirmation that assumes a rate.
     */
    fun sensorAvailabilityLabel(availability: SensorAvailability): String = when (availability) {
        SensorAvailability.AVAILABLE -> "ใช้ได้"
        SensorAvailability.LIMITED -> "จำกัด"
        SensorAvailability.MISSING -> "ไม่มีในเครื่องนี้"
    }

    fun sensorAvailabilityBadge(availability: SensorAvailability): String = when (availability) {
        SensorAvailability.AVAILABLE -> "🟢"
        SensorAvailability.LIMITED -> "🟡"
        SensorAvailability.MISSING -> "⚪"
    }

    /** TalkBack reads this instead of the emoji, which it would announce as a colour. */
    fun sensorAvailabilityContentDescription(name: String, availability: SensorAvailability): String =
        "$name: ${sensorAvailabilityLabel(availability)}"

    fun sensorInventoryLine(available: Int, limited: Int, missing: Int): String =
        "เซ็นเซอร์ในเครื่องนี้: มี $available · จำกัด $limited · ไม่มี $missing"

    /** Fixed labels for the lock affordances, so the screen never invents its own. */
    const val SENSOR_LOCK_CHIP = "ล็อกโดยโหมด"
    const val SENSOR_LOCK_CHANGE_USE = "เปลี่ยนการใช้งาน"

    /** "<source> ล็อกโดยโหมด... ตั้งค่าไม่ได้" for TalkBack, which cannot see dimming. */
    fun sensorLockedContentDescription(name: String, profile: ProtectionProfile): String =
        "$name ถูกล็อกในโหมด${profile(profile).name} ตั้งค่าไม่ได้"

    fun capabilityName(capability: SensorCapability): String = when (capability) {
        SensorCapability.MOVEMENT -> "การเคลื่อนไหว"
        SensorCapability.ROTATION -> "การหมุนและเอียง"
        SensorCapability.MAGNETIC -> "สนามแม่เหล็ก"
        SensorCapability.LIGHT -> "แสงบริเวณจุดติดตั้ง"
        SensorCapability.PROXIMITY -> "ระยะประชิด"
    }

    fun sourceName(source: SensorSource): String = when (source) {
        SensorSource.SIGNIFICANT_MOTION -> "ตรวจจับการเคลื่อนไหวหลัก"
        SensorSource.ACCELEROMETER -> "เซ็นเซอร์วัดความเร่ง"
        SensorSource.LINEAR_ACCELERATION -> "เซ็นเซอร์วัดความเร่งเชิงเส้น"
        SensorSource.GYROSCOPE -> "ไจโรสโคป"
        SensorSource.ROTATION_VECTOR -> "เวกเตอร์การหมุน 3 มิติ"
        SensorSource.GAME_ROTATION_VECTOR -> "เวกเตอร์การหมุนสัมพัทธ์"
        SensorSource.MAGNETIC_FIELD -> "เซ็นเซอร์วัดสนามแม่เหล็ก"
        SensorSource.GEOMAGNETIC_ROTATION_VECTOR -> "เวกเตอร์สนามแม่เหล็กโลก"
        SensorSource.AMBIENT_LIGHT -> "เซ็นเซอร์วัดแสง"
        SensorSource.PROXIMITY -> "เซ็นเซอร์วัดระยะประชิด"
    }

    fun roleName(role: SensorRole): String = when (role) {
        SensorRole.PRIMARY -> "เซ็นเซอร์หลัก"
        SensorRole.SUPPORTING -> "เซ็นเซอร์สนับสนุน"
        SensorRole.OFF -> "ปิดใช้งาน"
    }

    fun presetName(preset: SensorPresetDisplay): String = when (preset) {
        SensorPresetDisplay.BATTERY_SAVER -> "ประหยัดแบตเตอรี่"
        SensorPresetDisplay.BALANCED -> "สมดุล (แนะนำ)"
        SensorPresetDisplay.MAXIMUM_PROTECTION -> "ป้องกันสูงสุด"
        SensorPresetDisplay.CUSTOM -> "กำหนดเอง"
    }

    fun incidentTitle(type: IncidentType): String = when (type) {
        IncidentType.VIBRATION -> "🚨 รถอาจถูกเคลื่อนย้าย"
        IncidentType.TAMPER -> "⚠️ พบการงัดแงะหรือเปิดเบาะ"
        IncidentType.POWER -> "🔌 แหล่งจ่ายไฟถูกตัด"
        IncidentType.THERMAL -> "🌡️ อุณหภูมิผิดปกติ"
        IncidentType.AUDIO -> "🔊 เสียงผิดปกติบริเวณจุดติดตั้ง"
        IncidentType.ENTRY_DOOR -> "🚪 ตรวจพบประตูเปิด"
    }

    /** Compact incident wording for sentences and external messages; never expose enum names. */
    fun incidentTypeLabel(type: IncidentType): String = when (type) {
        IncidentType.VIBRATION -> "การเคลื่อนไหวผิดปกติ"
        IncidentType.TAMPER -> "การงัดแงะหรือเปิดเบาะ"
        IncidentType.POWER -> "แหล่งจ่ายไฟผิดปกติ"
        IncidentType.THERMAL -> "อุณหภูมิผิดปกติ"
        IncidentType.AUDIO -> "เสียงผิดปกติบริเวณจุดติดตั้ง"
        IncidentType.ENTRY_DOOR -> "ประตูเปิด"
    }

    fun severityLabel(severity: IncidentSeverity): String = when (severity) {
        IncidentSeverity.WARNING -> "เตือนภัย"
        IncidentSeverity.CRITICAL -> "วิกฤต"
    }

    /**
     * Single-sourced incident lifecycle/delivery Thai labels (profile-aware Thai UX,
     * Task 5): the Events history screen and Protection diagnostics must render
     * identical wording, so display code never derives labels from enum names.
     */
    fun incidentLifecycleLabel(lifecycle: IncidentLifecycle): String = when (lifecycle) {
        IncidentLifecycle.OPEN -> "กำลังดำเนินเหตุการณ์"
        IncidentLifecycle.CLOSED -> "สิ้นสุดแล้ว"
        IncidentLifecycle.INTERRUPTED -> "ยกระดับเป็นวิกฤต"
    }

    fun deliveryStateLabel(state: DeliveryState): String = when (state) {
        DeliveryState.PENDING -> "รอส่ง"
        DeliveryState.SENT -> "ส่งสำเร็จ"
        DeliveryState.FAILED -> "ส่งไม่สำเร็จ"
        DeliveryState.NOT_ELIGIBLE -> "ไม่เข้าเงื่อนไขการส่ง"
    }

    /**
     * Events history screen terms (profile-aware Thai UX, Task 5). `strings.xml`
     * mirrors these values for resource-based rendering; host tests pin them here
     * so resource drift fails the build.
     */
    const val EVENTS_LOADING = "กำลังโหลดเหตุการณ์"
    const val EVENTS_EMPTY_TITLE = "ยังไม่มีเหตุการณ์"
    const val EVENTS_EMPTY_DETAIL = "เหตุการณ์จะแสดงที่นี่เมื่อระบบป้องกันบันทึกไว้"
    const val EVENTS_ERROR_TITLE = "เกิดข้อผิดพลาดในการโหลดเหตุการณ์"
    const val EVENTS_RETRY = "ลองใหม่"
    const val EVENTS_HEADER = "ประวัติเหตุการณ์"
    const val EVENTS_CLEAR_HISTORY = "ล้างประวัติ"
    const val EVENTS_CLEAR_CONFIRM_TITLE = "ยืนยันการล้างประวัติ"
    const val EVENTS_CLEAR_CONFIRM_BODY = "การดำเนินการนี้จะลบประวัติเหตุการณ์ในเครื่องอย่างถาวร"
    const val EVENTS_CONFIRM_CLEAR = "ยืนยัน"
    const val EVENTS_CANCEL = "ยกเลิก"

    /** Truthful source label: persisted incidents are real recorded events only. */
    const val REAL_EVENT_SOURCE = "เหตุการณ์จริง"

    /** Typed row-line formatters; never leak raw enum names or English field prefixes. */
    fun formatEventSourceLine(): String = "แหล่งข้อมูล: $REAL_EVENT_SOURCE"

    fun formatEventSeverityLine(severity: IncidentSeverity): String =
        "ความรุนแรง: ${severityLabel(severity)}"

    fun formatEventLifecycleLine(lifecycle: IncidentLifecycle): String =
        "สถานะเหตุการณ์: ${incidentLifecycleLabel(lifecycle)}"

    fun formatEventDeliveryLine(state: DeliveryState): String =
        "การแจ้งเตือน: ${deliveryStateLabel(state)}"

    const val EVENT_EVIDENCE_PREFIX = "หลักฐาน: "
    const val EVENT_TIME_PREFIX = "เวลา: "

    /**
     * Notification presentation (profile-aware Thai UX, Task 6). The title and channel
     * names are installation-neutral; resources mirror these values while host tests pin
     * them so wording cannot drift back to legacy brand or raw state enums.
     */
    const val NOTIFICATION_TITLE = "ระบบป้องกัน"
    const val FOREGROUND_CHANNEL_NAME = "สถานะการป้องกัน"
    const val DIRECT_BOOT_CHANNEL_NAME = "การกู้คืนหลังบูต"

    /**
     * Pre-unlock recovery facts only: these bodies never read or reveal Telegram/SMS/
     * TOTP/pairing/encrypted-incident data and never claim protection already resumed —
     * resumption is announced only after the coordinator confirms it post-unlock.
     */
    const val DIRECT_BOOT_WAITING_BODY = "การกู้คืนการป้องกันรอการปลดล็อกอุปกรณ์"
    const val DIRECT_BOOT_MOVEMENT_BODY = "ตรวจพบการขยับขณะอุปกรณ์ล็อกอยู่ ปลดล็อกเพื่อใช้การป้องกันเต็มรูปแบบ"

    /**
     * Foreground notification body built only from the authoritative [state] plus a
     * caller-supplied [detail] (blockers / arming seconds / degradation reasons /
     * incident id). Shared state labels keep wording identical to the app UI.
     */
    fun foregroundNotificationBody(state: ProtectionState, detail: String = ""): String {
        val suffix = if (detail.isBlank()) "" else ": $detail"
        return when (state) {
            ProtectionState.SETUP_REQUIRED -> "ต้องตั้งค่าก่อนใช้งาน$suffix"
            ProtectionState.DISARMED_ONLINE -> "ระบบปิดอยู่ — ควบคุมจากระยะไกลได้"
            ProtectionState.ARMING ->
                if (detail.isBlank()) {
                    "กำลังเปิดระบบและปรับเทียบ"
                } else {
                    "กำลังเปิดระบบและปรับเทียบ อีก $detail วินาที"
                }
            ProtectionState.ARMED_HEALTHY -> protectionStateLabel(ProtectionState.ARMED_HEALTHY)
            ProtectionState.ARMED_DEGRADED -> "การป้องกันทำงานแบบจำกัด$suffix"
            ProtectionState.ALERT_ACTIVE ->
                if (detail.isBlank()) "พบเหตุการณ์ผิดปกติ" else "พบเหตุการณ์ผิดปกติ: $detail"
            ProtectionState.OFFLINE -> "บริการป้องกันออฟไลน์"
        }
    }

    fun protectionStateLabel(state: ProtectionState): String = when (state) {
        ProtectionState.SETUP_REQUIRED -> "ต้องตั้งค่าเริ่มต้น"
        ProtectionState.DISARMED_ONLINE -> "ระบบปิดอยู่"
        ProtectionState.ARMING -> "กำลังเปิดระบบและปรับเทียบ"
        ProtectionState.ARMED_HEALTHY -> "การป้องกันทำงานสมบูรณ์"
        ProtectionState.ARMED_DEGRADED -> "การป้องกันทำงานแบบจำกัด"
        ProtectionState.ALERT_ACTIVE -> "พบเหตุการณ์ผิดปกติ"
        ProtectionState.OFFLINE -> "ออฟไลน์"
    }

    fun formatEvidence(evidence: IncidentEvidence): IncidentEvidencePresentation {
        val src = evidence.source
        val label = if (src != null) sourceName(src) else capabilityName(evidence.capability ?: SensorCapability.MOVEMENT)
        val desc = when (evidence.unit) {
            SensorUnit.METERS_PER_SECOND_SQUARED -> "ตรวจพบแรงสั่นต่อเนื่อง (${String.format(Locale.US, "%.1f", evidence.baselineDelta)} m/s²)"
            SensorUnit.DEGREES -> "มุมของรถเปลี่ยนประมาณ ${String.format(Locale.US, "%.1f", evidence.baselineDelta)}°"
            SensorUnit.RADIANS_PER_SECOND -> "การหมุนความเร็ว ${String.format(Locale.US, "%.2f", evidence.normalizedValue)} rad/s"
            SensorUnit.MICROTESLA -> "สนามแม่เหล็กรอบรถเปลี่ยนจากค่าตอนเปิดระบบ (${String.format(Locale.US, "%.1f", evidence.baselineDelta)} µT)"
            SensorUnit.LUX_RATIO -> "แสงบริเวณจุดติดตั้งเพิ่มขึ้นจากค่าตอนเปิดระบบ"
            SensorUnit.NORMALIZED_STATE -> "สถานะวัตถุใกล้โทรศัพท์เปลี่ยนจาก ใกล้ เป็น ไกล"
            SensorUnit.TRIGGER -> "เซ็นเซอร์ตรวจพบการเคลื่อนไหวของตัวรถ"
            null -> "ตรวจพบสัญญาณความผิดปกติ"
        }
        return IncidentEvidencePresentation(
            label = label,
            valueDescription = desc,
        )
    }

    fun formatTimestamp(epochMs: Long): String {
        val sdf = SimpleDateFormat("d MMM yyyy HH:mm", Locale("th", "TH"))
        return sdf.format(Date(epochMs))
    }
}

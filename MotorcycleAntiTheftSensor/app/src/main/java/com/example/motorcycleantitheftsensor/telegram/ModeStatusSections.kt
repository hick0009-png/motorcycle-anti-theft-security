package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.DoorGateReading
import com.example.motorcycleantitheftsensor.protection.ChargingState
import com.example.motorcycleantitheftsensor.protection.EntryDriftVerdict
import com.example.motorcycleantitheftsensor.protection.EntryModeFacts
import com.example.motorcycleantitheftsensor.protection.EntryWatchLevel
import com.example.motorcycleantitheftsensor.protection.LightHealthDetail
import com.example.motorcycleantitheftsensor.protection.PowerModeFacts
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProfileDeviceSupport
import com.example.motorcycleantitheftsensor.protection.ProfileSetupState
import com.example.motorcycleantitheftsensor.protection.ProtectionModeContext
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionProfilePolicy
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.SensorRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The readings that are true only at the instant they are taken.
 *
 * These are read when the owner asks and never stored on [ProtectionSnapshot]. An angle
 * that changes with every gyroscope sample would enter the snapshot's projection key,
 * make every sample a semantic change, and write a durable snapshot for each one — in
 * the mode that is sold on being left armed overnight. `/where` already works this way.
 *
 * Every field is nullable and null means "could not be read now", never an error: the
 * mode is usually not armed, and a report that omitted the line entirely would leave the
 * owner unable to tell a closed door from a question nobody asked.
 */
data class LiveStatusReadings(
    /** Door angle against the frozen armed baseline, degrees. */
    val doorAngleDeg: Double? = null,
    /** The residual and direction gates that run before the angle is ever consulted. */
    val doorGate: DoorGateReading? = null,
    /** Latest witness-lamp reading, lux. */
    val witnessLux: Double? = null,
    /** Whether the arbiter currently holds the lamp to be lit. */
    val witnessLit: Boolean? = null,
    /** Milliseconds left on a running loss/recovery confirmation, if one is running. */
    val confirmationCountdownMs: Long? = null,
    /** Metres between the parking anchor and the last usable fix. */
    val metersFromParking: Double? = null,
    /** The distance this mode would call movement, in metres. */
    val parkingThresholdMeters: Double? = null,
    /** Whether a live pursuit is streaming right now. */
    val pursuitActive: Boolean? = null,
    /**
     * The SMS fallback destination, already masked.
     *
     * Not a mode reading, and it rides here for one reason: it lives in encrypted
     * preferences that the formatter must stay clear of, and this is the one supplier
     * already invoked at the moment the owner asks. It must arrive masked — the raw
     * number must never reach this layer, let alone the chat.
     */
    val smsFallbackMasked: String? = null,
)

/**
 * Reads [LiveStatusReadings] at the moment a status command is answered.
 *
 * @return null when this build has no runtime to ask — the report then says every live
 *   line is unreadable, which is the truth, rather than omitting them.
 */
fun interface LiveStatusReader {
    suspend fun read(profile: ProtectionProfile?): LiveStatusReadings?
}

/** One titled block of the report; [lines] is never empty. */
data class ModeSectionProjection(
    val titleTh: String,
    val lines: List<String>,
)

/**
 * Section A. What is being watched, and for how long — the two facts that have to survive
 * being read as a notification preview and nothing else.
 */
data class ModeIdentityProjection(
    val headerTh: String,
    val armDurationTh: String?,
    /** A state that overrides the mode: switching, unsupported hardware, setup pending. */
    val noticeTh: String? = null,
)

/**
 * Builds the mode-aware halves of the status report.
 *
 * Every rule here reads [ProtectionProfilePolicy] rather than restating it. The report
 * that told a Power Guard owner to fix their GPS did so because this layer kept its own
 * list of five sensor kinds, and no amount of care in one table keeps two tables equal.
 */
/**
 * The `/status` witness threshold line, in lux.
 *
 * While armed, the arbiter judges against the commissioned bands re-expressed for the light
 * present at Arm — a motorcycle armed in daylight is watched against a boundary several times
 * the commissioned one. Reporting the frozen commissioned numbers there tells the owner a
 * dark/lit cutoff the running detector is not using, so the armed (scaled) pair wins whenever
 * it is present; the commissioned pair is the honest answer only while disarmed. The two armed
 * fields are always written and cleared together, so a lone one is treated as absent rather
 * than mixed with a commissioned partner.
 */
internal fun powerThresholdText(
    armedDarkThresholdLux: Double?,
    armedLitThresholdLux: Double?,
    commissionedDarkThresholdLux: Double?,
    commissionedLitThresholdLux: Double?,
): String {
    val armed = armedDarkThresholdLux != null && armedLitThresholdLux != null
    val darkLux = if (armed) armedDarkThresholdLux else commissionedDarkThresholdLux
    val litLux = if (armed) armedLitThresholdLux else commissionedLitThresholdLux
    return if (darkLux == null || litLux == null) {
        "เกณฑ์: ยังไม่ได้ปรับเทียบไฟยืนยัน"
    } else {
        "เกณฑ์: ต่ำกว่า ${darkLux.toInt()} lux = ดับ · สูงกว่า ${litLux.toInt()} lux = สว่าง"
    }
}

object ModeStatusSections {

    private const val UNKNOWN_NOT_ARMED = "ยังไม่ได้อาร์ม"

    /**
     * @param context null when this installation has no profile layer at all — an older
     *   build, or a host test with no repository. Treated exactly as a mode nobody has
     *   chosen yet, because from the owner's side it is the same situation.
     */
    fun identity(
        snapshot: ProtectionSnapshot,
        context: ProtectionModeContext?,
        nowWallClockMs: Long,
    ): ModeIdentityProjection {
        val profile = context?.selectedProfile
        val stateTh = stateLabel(snapshot.state)
        val header = if (profile == null) {
            "🛡️ ยังไม่ได้เลือกโหมด · $stateTh"
        } else {
            "🛡️ โหมด${PresentationTextCatalog.profile(profile).name} · $stateTh"
        }
        val armDuration = armedDuration(snapshot, nowWallClockMs)?.let { "เฝ้ามาแล้ว $it" }
        return ModeIdentityProjection(
            headerTh = header,
            armDurationTh = armDuration,
            noticeTh = context?.let(::notice),
        )
    }

    /**
     * The one state a mode-aware report must not paper over: mid-switch, the old mode has
     * stopped and the new one has not been armed, so naming either would claim a watch
     * that is not running.
     */
    private fun notice(context: ProtectionModeContext): String? {
        val switchingTo = context.switchingTo
        if (switchingTo != null && switchingTo != context.selectedProfile) {
            val from = context.selectedProfile?.let { PresentationTextCatalog.profile(it).name }
                ?: "ยังไม่ได้เลือก"
            val to = PresentationTextCatalog.profile(switchingTo).name
            return "🔄 กำลังสลับโหมด: $from → $to\n" +
                "⚠️ ระหว่างนี้ยังไม่มีการเฝ้า สั่ง /arm เมื่อสลับเสร็จ"
        }
        val support = context.support
        if (support is ProfileDeviceSupport.Unsupported) {
            val why = PresentationTextCatalog.profileSupport(support)
            return "⛔ เครื่องนี้ใช้โหมดนี้ไม่ได้ จึงอาร์มไม่ได้" + (why?.let { "\n$it" } ?: "")
        }
        if (context.setupState == ProfileSetupState.SETUP_REQUIRED) {
            return "⚠️ โหมดนี้ยังตั้งค่าไม่ครบ จึงยังอาร์มไม่ได้ — เปิดแอปเพื่อตั้งค่าให้เสร็จ"
        }
        if (support is ProfileDeviceSupport.Degraded) {
            return PresentationTextCatalog.profileSupport(support)?.let { "🟡 $it" }
        }
        return null
    }

    /**
     * Section B, built from the role table rather than from prose, so that a mode which
     * changes what it detects with cannot leave a sentence behind saying otherwise.
     */
    fun watchScope(
        snapshot: ProtectionSnapshot,
        context: ProtectionModeContext?,
    ): ModeSectionProjection {
        val profile = context?.selectedProfile
            ?: return ModeSectionProjection(
                titleTh = "🎯 ตอนนี้เฝ้าอะไรอยู่",
                lines = listOf(
                    "⚠️ ยังไม่ได้เลือกโหมดการใช้งาน — รายงานนี้จึงแสดงเซ็นเซอร์ทั้งหมด",
                    "เปิดแอปแล้วเลือกโหมดเพื่อให้รายงานตรงกับสิ่งที่คุณเฝ้าจริง",
                    // Kept here, unlike in the door and lamp modes: with no mode chosen the
                    // legacy configuration is what runs, and this slider really does move
                    // the vibration detector's threshold.
                    "ความไวการตรวจจับ: ${snapshot.sensitivityLevel}/10",
                ),
            )
        val entryLevel = context.entryLevel ?: EntryWatchLevel.DOOR_ANGLE
        val roles = ProtectionProfilePolicy.signalRoles(profile, entryLevel)
        // The door watch's headline promise is about an angle, which the lower level cannot
        // measure. Repeating it there and then admitting on the next line that degrees are
        // unavailable is worse than saying at once what this level actually does. The
        // distinction lives in the catalog because the state-change alert has to draw it too.
        val lines = mutableListOf(PresentationTextCatalog.profilePromise(profile, entryLevel))

        if (profile == ProtectionProfile.ENTRY) {
            lines += "ระดับการเฝ้า: " + PresentationTextCatalog.entryLevelLabel(entryLevel)
        }

        val hosts = ProtectionProfilePolicy.hosts(profile, entryLevel)
        lines += "เปิดเหตุได้เอง: " + hosts.joinToString(" · ") { PresentationTextCatalog.hostName(it) }

        val supporting = roles.filterValues { it == SensorRole.SUPPORTING }.keys
        if (supporting.isNotEmpty()) {
            lines += "ใช้ประกอบ: " + supporting.joinToString(" · ") { sensorName(it, profile) }
        }

        // Silence about a switched-off sensor reads as a broken app to anyone who saw the
        // old report list it. Naming them as deliberate is the whole point of the line.
        val off = roles.filterValues { it == SensorRole.OFF }.keys
        if (off.isNotEmpty()) {
            lines += "โหมดนี้ไม่ใช้: " +
                off.joinToString(" · ") { sensorName(it, profile) } +
                " (ปิดไว้ตั้งใจ ไม่ใช่ความผิดปกติ)"
        }

        // Only the vehicle watch reads this number. Printing it under a door or a lamp
        // invites the owner to spend /sensitivity on a detector that never sees it.
        if (profile == ProtectionProfile.VEHICLE) {
            lines += "ความไวการตรวจจับ: ${snapshot.sensitivityLevel}/10"
        }

        return ModeSectionProjection(titleTh = "🎯 โหมดนี้เฝ้าอะไร", lines = lines)
    }

    /** Section C: the blocks that exist only for this mode. Empty for an unchosen mode. */
    fun modeSections(
        snapshot: ProtectionSnapshot,
        context: ProtectionModeContext,
        live: LiveStatusReadings?,
        nowWallClockMs: Long,
    ): List<ModeSectionProjection> = when (context.selectedProfile) {
        null -> emptyList()
        ProtectionProfile.VEHICLE -> vehicleSections(snapshot, live)
        ProtectionProfile.ENTRY -> entrySections(snapshot, context, live, nowWallClockMs)
        ProtectionProfile.POWER -> powerSections(snapshot, context, live, nowWallClockMs)
    }

    private fun vehicleSections(
        snapshot: ProtectionSnapshot,
        live: LiveStatusReadings?,
    ): List<ModeSectionProjection> {
        val locationDetail = snapshot.sensorHealth[SensorKind.LOCATION]?.locationDetail
        val fixLine = when {
            locationDetail?.lastFixWallClockMs == null -> "พิกัดล่าสุด: ยังไม่มีพิกัด (กำลังหาสัญญาณ)"
            else -> {
                // Never the coordinates themselves. This report goes to a chat that keeps
                // its own history on every signed-in device; /where exists for the times
                // the owner actually wants the position, and asks for it deliberately.
                val accuracy = locationDetail.accuracyMeters
                    ?.let { " ±${it.toInt()} เมตร" }
                    ?: ""
                "พิกัดล่าสุด: มีแล้ว$accuracy (ดูตำแหน่งด้วย /where)"
            }
        }
        val distanceLine = when {
            live?.metersFromParking == null -> "ห่างจากจุดจอด: ยังไม่ได้ตั้งจุดจอด ($UNKNOWN_NOT_ARMED)"
            else -> {
                val threshold = live.parkingThresholdMeters
                val thresholdTh = threshold?.let { " (เกณฑ์ ${it.toInt()} เมตร)" } ?: ""
                "ห่างจากจุดจอด: ${live.metersFromParking.toInt()} เมตร$thresholdTh"
            }
        }
        val pursuitLine = when (live?.pursuitActive) {
            true -> "การไล่ตามสด: กำลังส่งตำแหน่งอยู่"
            false -> "การไล่ตามสด: ไม่ได้เปิดอยู่"
            null -> "การไล่ตามสด: ไม่ทราบ"
        }
        return listOf(
            ModeSectionProjection(
                titleTh = "📍 ตำแหน่งและการเคลื่อนที่",
                lines = listOf(fixLine, distanceLine, pursuitLine),
            ),
            ModeSectionProjection(
                titleTh = "🔌 สายชาร์จ",
                lines = listOf(chargingLine(snapshot)),
            ),
        )
    }

    private fun entrySections(
        snapshot: ProtectionSnapshot,
        context: ProtectionModeContext,
        live: LiveStatusReadings?,
        nowWallClockMs: Long,
    ): List<ModeSectionProjection> {
        val facts = context.modeFacts as? EntryModeFacts
        if (context.entryLevel == EntryWatchLevel.SOUND_AND_MOVEMENT) {
            return listOf(
                ModeSectionProjection(
                    titleTh = "🚪 ระดับการเฝ้า: เสียงและการขยับ",
                    lines = entrySoundLevelLines(),
                ),
            )
        }
        val angleLines = entryThresholdLines(facts).toMutableList()
        angleLines += when {
            live?.doorAngleDeg == null -> "มุมขณะนี้: อ่านไม่ได้ ($UNKNOWN_NOT_ARMED)"
            else -> {
                val closed = facts != null && live.doorAngleDeg < facts.closeThresholdDegrees
                val verdict = if (closed) " (ปิดอยู่)" else ""
                "มุมขณะนี้: ${String.format(Locale.US, "%.1f", live.doorAngleDeg)}°$verdict"
            }
        }
        // The two gates that run before the angle. A door watch that keeps answering "the mount
        // moved" to an ordinary opening is failing one of them, and nothing else in the report
        // says which — so it says which.
        live?.doorGate?.let { gate ->
            val residualOk = gate.swingResidualDeg <= gate.toleranceDeg
            val directionOk = gate.twistSignedDeg >= 0.0
            angleLines += "เหวี่ยงนอกแกน: ${String.format(Locale.US, "%.1f", gate.swingResidualDeg)}° " +
                "/ เพดาน ${String.format(Locale.US, "%.1f", gate.toleranceDeg)}° " +
                (if (residualOk) "(ผ่าน)" else "(เกิน — ถูกตัดสินว่าขายึดขยับ)")
            angleLines += "ทิศทางหมุน: ${String.format(Locale.US, "%+.1f", gate.twistSignedDeg)}° " +
                (if (directionOk) "(ตรงกับที่ปรับเทียบ)" else "(สวนทาง — ถูกตัดสินว่าขายึดขยับ)")
        }
        angleLines += hingeModelLine(facts)

        val sections = mutableListOf(
            ModeSectionProjection(titleTh = "🚪 มุมประตู", lines = angleLines),
        )
        ceilingSection(snapshot, facts, nowWallClockMs)?.let(sections::add)
        return sections
    }

    /**
     * Not one degree anywhere in this block. Nothing at the sound-and-movement level
     * measures an angle, and a threshold printed here would be a promise nothing can keep —
     * which is why every surface that speaks for that level reads these lines rather than
     * writing its own.
     */
    private fun entrySoundLevelLines(): List<String> = listOf(
        "ระดับนี้บอกไม่ได้ว่าประตูเปิดกว้างแค่ไหน",
        "เปิดเหตุการณ์เมื่อเสียงกับการขยับเกิดขึ้นพร้อมกันเท่านั้น",
        "ทางไปต่อ: ปรับเทียบบานพับในแอปเพื่อขึ้นเป็นระดับมุมประตู",
    )

    /**
     * What the door watch is set to alert on.
     *
     * Settings only — no live reading and no session — which is what lets the report for a
     * mode that is not running state them without borrowing a running mode's numbers.
     */
    private fun entryThresholdLines(facts: EntryModeFacts?): List<String> {
        if (facts == null) return listOf("เกณฑ์แจ้งเตือน: ยังอ่านค่าที่ตั้งไว้ไม่ได้")
        return listOf(
            "เกณฑ์แจ้งเตือน: เปิดเกิน ${facts.angleThresholdDegrees}° " +
                "ค้างนาน ${facts.openConfirmationMs} มิลลิวินาที",
            "ถือว่าปิดเมื่อ: ต่ำกว่า ${facts.closeThresholdDegrees}° " +
                "นาน ${facts.closeConfirmationMs / 1000L} วินาที",
        )
    }

    private fun hingeModelLine(facts: EntryModeFacts?): String {
        if (facts == null || !facts.hingeModelCommissioned) return "โมเดลบานพับ: ยังไม่ได้ปรับเทียบ"
        val source = PresentationTextCatalog.orientationSourceName(facts.hingeOrientationSourceLabel)
            ?.let { " · ใช้$it" }
            ?: ""
        val when_ = facts.hingeCommissionedAtWallMs
            ?.let { "ปรับเทียบไว้เมื่อ ${formatDateTimeTh(it)}" }
            ?: "ปรับเทียบแล้ว (ไม่ได้บันทึกวันเวลาไว้)"
        return "โมเดลบานพับ: $when_$source"
    }

    /**
     * The one line that justifies the whole mode-aware report.
     *
     * An owner who armed a door watch for nine hours on a phone that measured itself
     * trustworthy for eight has, today, no way at all to know. They get an alert caused by
     * the phone's own drift, believe someone opened the door — and after the third one,
     * stop believing any alert this app sends.
     */
    private fun ceilingSection(
        snapshot: ProtectionSnapshot,
        facts: EntryModeFacts?,
        nowWallClockMs: Long,
    ): ModeSectionProjection? {
        val verdict = facts?.driftVerdict ?: return null
        // Past the ceiling the exceeded wording already states the budget and the elapsed
        // time. Printing the general advice above it repeats the same number twice and
        // buries the sentence that has actually changed.
        val exceeded = EntryCeilingPolicy.exceededLine(verdict, snapshot, nowWallClockMs)
        val lines = listOf(exceeded ?: PresentationTextCatalog.driftBudgetLine(verdict))
        return ModeSectionProjection(titleTh = "⏳ เพดานเวลาที่เชื่อได้", lines = lines)
    }

    private fun powerSections(
        snapshot: ProtectionSnapshot,
        context: ProtectionModeContext,
        live: LiveStatusReadings?,
        nowWallClockMs: Long,
    ): List<ModeSectionProjection> {
        val facts = context.modeFacts as? PowerModeFacts
        val lines = mutableListOf<String>()
        lines += when (live?.witnessLit) {
            true -> "สถานะ: สว่าง (ไฟมาปกติ)"
            false -> "สถานะ: ดับ"
            null -> "สถานะ: ไม่ทราบ ($UNKNOWN_NOT_ARMED)"
        }
        // The health map already carries the last light sample, and it is not in the
        // projection key, so reading it here costs nothing and answers even when no live
        // supplier was wired in.
        val lightDetail = snapshot.sensorHealth[SensorKind.LIGHT]?.lightDetail
        val lux = live?.witnessLux ?: lightDetail?.lastLux
        lines += lux
            ?.let { "ค่าที่วัดได้ขณะนี้: ${it.toInt()} lux" }
            ?: "ค่าที่วัดได้ขณะนี้: อ่านไม่ได้"
        lines += powerThresholdLines(facts, lightDetail)
        lines += live?.confirmationCountdownMs
            ?.takeIf { it > 0L }
            ?.let { "กำลังนับถอยหลัง: อีก ${(it / 1000L).coerceAtLeast(1L)} วินาที" }
            ?: "กำลังนับถอยหลัง: ไม่ได้นับอยู่"
        lines += witnessModelLine(facts)
        return listOf(
            ModeSectionProjection(titleTh = "💡 ไฟยืนยัน", lines = lines),
            ModeSectionProjection(titleTh = "🔌 สายชาร์จ", lines = listOf(chargingLine(snapshot))),
        )
    }

    /** The lamp watch's settings, with the same no-live-reading rule as the door's. */
    private fun powerThresholdLines(
        facts: PowerModeFacts?,
        lightDetail: LightHealthDetail?,
    ): List<String> {
        val threshold = powerThresholdText(
            armedDarkThresholdLux = lightDetail?.armedWitnessDarkThresholdLux,
            armedLitThresholdLux = lightDetail?.armedWitnessLitThresholdLux,
            commissionedDarkThresholdLux = facts?.witnessDarkThresholdLux,
            commissionedLitThresholdLux = facts?.witnessLitThresholdLux,
        )
        if (facts == null) return listOf(threshold)
        return listOf(
            threshold,
            "ยืนยันไฟดับเมื่อค้างครบ: ${facts.lossConfirmationMs / 1000L} วินาที",
            "ยืนยันไฟกลับมาเมื่อค้างครบ: ${facts.recoveryConfirmationMs / 1000L} วินาที",
        )
    }

    private fun witnessModelLine(facts: PowerModeFacts?): String = when {
        facts == null || !facts.witnessCommissioned -> "โมเดลไฟยืนยัน: ยังไม่ได้ปรับเทียบ"
        facts.witnessCommissionedAtWallMs != null ->
            "โมเดลไฟยืนยัน: ปรับเทียบไว้เมื่อ ${formatDateTimeTh(facts.witnessCommissionedAtWallMs)}"
        else -> "โมเดลไฟยืนยัน: ปรับเทียบแล้ว (ไม่ได้บันทึกวันเวลาไว้)"
    }

    /**
     * How ready a mode is, for the report about a mode that is not the one running.
     *
     * Every line here is durable: hardware support, whether setup is finished, the
     * thresholds, when the model was calibrated, and what this phone measured about its own
     * drift. Nothing is read from the running session, because none of it belongs to the
     * running session — and a line borrowed from there would describe the wrong watch while
     * appearing to describe this one.
     */
    fun readiness(context: ProtectionModeContext): ModeSectionProjection {
        val lines = mutableListOf<String>()
        lines += PresentationTextCatalog.profileSupportBadge(context.support) +
            (PresentationTextCatalog.profileSupport(context.support)?.let { " — $it" } ?: "")
        lines += when (context.setupState) {
            ProfileSetupState.READY -> "การตั้งค่า: ครบแล้ว พร้อมอาร์ม"
            ProfileSetupState.SETUP_REQUIRED -> "การตั้งค่า: ยังไม่ครบ ต้องตั้งค่าในแอปให้เสร็จก่อนจึงอาร์มได้"
            ProfileSetupState.UNAVAILABLE -> "การตั้งค่า: โหมดนี้ใช้กับเครื่องนี้ไม่ได้"
        }
        when (context.selectedProfile) {
            ProtectionProfile.ENTRY -> if (
                context.entryLevel == EntryWatchLevel.SOUND_AND_MOVEMENT
            ) {
                // The angle threshold, the hinge model and the drift ceiling are all about a
                // measurement this level does not take. Printing them here would undo the
                // one rule the door watch's own section exists to keep.
                lines += entrySoundLevelLines()
            } else {
                val facts = context.modeFacts as? EntryModeFacts
                lines += entryThresholdLines(facts)
                lines += hingeModelLine(facts)
                // The rate is a property of this phone, not of a session, so it is the same
                // sentence whether or not the door watch happens to be the one running.
                facts?.driftVerdict?.let {
                    lines += "เพดานเวลาที่เชื่อได้: " + PresentationTextCatalog.driftBudgetLine(it)
                }
            }
            ProtectionProfile.POWER -> {
                val facts = context.modeFacts as? PowerModeFacts
                // Readiness describes a mode that is not the one running, so it has no armed
                // reference to scale by: the commissioned bands are the durable, correct answer.
                lines += powerThresholdLines(facts, lightDetail = null)
                lines += witnessModelLine(facts)
            }
            // The vehicle watch keeps its two owner-visible numbers elsewhere: the
            // sensitivity is printed above by watchScope, and the distance that counts as
            // movement is decided per pair of fixes and has no value to print without them.
            ProtectionProfile.VEHICLE -> lines += "โหมดนี้ไม่มีค่าปรับเทียบที่ต้องตั้งไว้ล่วงหน้า"
            null -> Unit
        }
        return ModeSectionProjection(titleTh = "⚙️ ความพร้อมของโหมดนี้", lines = lines)
    }

    private fun chargingLine(snapshot: ProtectionSnapshot): String = when (snapshot.chargingState) {
        ChargingState.CHARGING -> "สถานะ: เสียบอยู่ | กำลังชาร์จ"
        ChargingState.FULL -> "สถานะ: เสียบอยู่ | แบตเตอรี่เต็ม"
        ChargingState.DISCHARGING, ChargingState.NOT_CHARGING -> "สถานะ: ไม่ได้เสียบ"
        ChargingState.UNKNOWN -> "สถานะ: ไม่ทราบ"
    }

    /** The name of a sensor kind as this mode uses it, not as the hardware is catalogued. */
    fun sensorName(kind: SensorKind, profile: ProtectionProfile?): String = when (kind) {
        SensorKind.VIBRATION -> "การสั่น"
        SensorKind.LIGHT -> if (profile == ProtectionProfile.POWER) "แสง/ไฟยืนยัน" else "แสง"
        SensorKind.MICROPHONE -> "ไมโครโฟน"
        SensorKind.LOCATION -> "GPS"
        SensorKind.POWER_THERMAL -> "พลังงาน/สายชาร์จ"
    }

    /** The door watch's host is an orientation verdict, which is no [SensorKind] at all. */
    const val ORIENTATION_ROW_NAME = "ทิศทาง/มุม"

    fun stateLabel(state: ProtectionState): String = when (state) {
        ProtectionState.ARMED_HEALTHY, ProtectionState.ARMED_DEGRADED -> "กำลังป้องกัน"
        ProtectionState.ALERT_ACTIVE -> "🚨 กำลังส่งสัญญาณเตือน"
        ProtectionState.DISARMED_ONLINE -> "ยังไม่ได้เปิดการเฝ้า"
        ProtectionState.ARMING -> "กำลังเริ่มการเฝ้า"
        ProtectionState.OFFLINE -> "ออฟไลน์"
        ProtectionState.SETUP_REQUIRED -> "ต้องตั้งค่าก่อนจึงจะเฝ้าได้"
    }

    /** Whether this state means a watch is actually running, decided in one place. */
    fun isWatching(state: ProtectionState): Boolean =
        state in ARMED_OR_ALERT || state == ProtectionState.ARMING

    fun armedDuration(snapshot: ProtectionSnapshot, nowWallClockMs: Long): String? {
        if (!isWatching(snapshot.state)) return null
        val startMs = snapshot.protectionActivatedAtMs ?: return null
        if (startMs == 0L || startMs > nowWallClockMs) return null
        return formatDurationTh(nowWallClockMs - startMs)
    }

    fun formatDurationTh(durationMs: Long): String {
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

    private fun formatDateTimeTh(wallClockMs: Long): String =
        SimpleDateFormat("d MMM HH:mm", Locale.forLanguageTag("th-TH")).apply {
            timeZone = TimeZone.getTimeZone("Asia/Bangkok")
        }.format(Date(wallClockMs))

    val ARMED_OR_ALERT = setOf(
        ProtectionState.ARMED_HEALTHY,
        ProtectionState.ARMED_DEGRADED,
        ProtectionState.ALERT_ACTIVE,
    )
}

/**
 * Whether an armed door session has outlived the hours this phone measured itself good
 * for, and what to say about it.
 *
 * A pure function of the snapshot so that both the status report and the unprompted
 * warning decide it the same way. Two implementations of "past the ceiling" would
 * eventually disagree, and the one the owner sees would not be the one that decided
 * whether to tell them.
 */
object EntryCeilingPolicy {

    /**
     * @return null when there is no ceiling to exceed — the phone measured itself
     *   trustworthy, or measured nothing, or nothing is armed. Only [EntryDriftVerdict.Limited]
     *   carries a number, and [EntryDriftVerdict.Unusable] never armed in the first place.
     */
    fun exceededBy(
        verdict: EntryDriftVerdict,
        snapshot: ProtectionSnapshot,
        nowWallClockMs: Long,
    ): Long? {
        if (verdict !is EntryDriftVerdict.Limited) return null
        if (snapshot.state !in ModeStatusSections.ARMED_OR_ALERT) return null
        val startMs = snapshot.protectionActivatedAtMs ?: return null
        if (startMs <= 0L || startMs > nowWallClockMs) return null
        val armedMs = nowWallClockMs - startMs
        val ceilingMs = (verdict.hoursToThreshold * 3_600_000.0).toLong()
        if (ceilingMs <= 0L || armedMs <= ceilingMs) return null
        return armedMs - ceilingMs
    }

    fun trustedHoursPhrase(verdict: EntryDriftVerdict.Limited): String =
        PresentationTextCatalog.hoursPhrase(verdict.hoursToThreshold.toInt())

    /** The status-report wording, or null while the session is still inside its ceiling. */
    fun exceededLine(
        verdict: EntryDriftVerdict,
        snapshot: ProtectionSnapshot,
        nowWallClockMs: Long,
    ): String? {
        if (exceededBy(verdict, snapshot, nowWallClockMs) == null) return null
        val limited = verdict as EntryDriftVerdict.Limited
        val armedFor = ModeStatusSections.formatDurationTh(
            nowWallClockMs - (snapshot.protectionActivatedAtMs ?: nowWallClockMs),
        )
        return "⚠️ เฝ้าต่อเนื่องได้${trustedHoursPhrase(limited)} " +
            "แต่ตอนนี้อาร์มมาแล้ว $armedFor\n" +
            "เกินเพดานแล้ว การแจ้งเตือนหลังจากนี้อาจเกิดจากมุมที่ไหลเอง ไม่ใช่ประตูเปิด"
    }

    /** The issue and its remedy, for section G. */
    const val ISSUE_TH = "⚠️ อาร์มนานเกินเพดานเวลาที่เครื่องนี้เชื่อได้"
    const val GUIDANCE_TH = "สั่ง /disarm แล้ว /arm ใหม่ เพื่อเริ่มนับมุมใหม่"
}

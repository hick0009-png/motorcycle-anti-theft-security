package com.example.motorcycleantitheftsensor.protection

/**
 * What every hardware source contributes to every protection use, in the owner's words.
 *
 * Reached through `PresentationTextCatalog.contribution(profile, source)`; it lives in its
 * own file because it is thirty entries of copy rather than a lookup, and because each one
 * had to be checked against the engine before it could be written:
 *
 * - Only an observation carrying [SensorRole.PRIMARY] can open an incident
 *   (`IncidentEngine.isPrimaryRole`). A supporting source is registered and its readings
 *   become evidence, but it can never start an alert on its own.
 * - A light reading never opens an incident whatever its role — `IncidentEngine` records
 *   it as a precursor and returns. Its role decides whether a *later* vibration may open
 *   one, and a light change followed by vibration inside the correlation window raises the
 *   incident from "รถอาจถูกเคลื่อนย้าย" to "พบการงัดแงะหรือเปิดเบาะ" at critical severity.
 * - Two different sources reporting inside the window put both on the same incident rather
 *   than opening a second one, which is what "เติมหลักฐาน" means here.
 * - Arming needs at least one primary source healthy, not all of them
 *   (`ProtectionCoordinator.hasReadyPrimary` is an `any`), so adding a primary does not
 *   make arming slower. Removing the last one makes arming impossible.
 * - Every source except significant motion needs its calibration window before it counts
 *   as healthy; significant motion is ready immediately (`SensorCalibrationManager`).
 *
 * Where the role on the settings screen does not govern the detection the owner would
 * assume it governs, the entry carries a [SensorContribution.caveatTh] instead of pretending
 * otherwise. Two such places exist today: the door watch reads the rotation sensors
 * directly, and Power Guard registers the light sensor directly.
 */
internal object SensorContributionCatalog {

    private const val COST_CONTINUOUS = "กินแบตปานกลาง ส่งค่าต่อเนื่องตลอดเวลาที่เฝ้าอยู่"
    private const val COST_CONTINUOUS_HIGH = "กินแบตมากกว่าตัวอื่นในกลุ่มเดียวกัน ส่งค่าต่อเนื่องตลอดเวลาที่เฝ้าอยู่"
    private const val COST_CONTINUOUS_LOW = "กินแบตต่ำ ส่งค่าต่อเนื่องแต่ถี่น้อยกว่าไจโรสโคป"
    private const val COST_ON_CHANGE = "กินแบตต่ำ ส่งค่าเฉพาะตอนที่ค่าที่วัดได้เปลี่ยน"
    private const val COST_TRIGGER = "แทบไม่กินแบต ตัวเครื่องปลุกระบบเองเมื่อมีการเคลื่อนไหว"
    private const val COST_LOCKED = "ไม่กินแบตในโหมดนี้ เพราะไม่ถูกลงทะเบียนเลย"

    private const val LAST_PRIMARY_WARNING =
        "ถ้านี่เป็นตัวหลักตัวสุดท้าย ระบบจะเริ่มป้องกันไม่ได้จนกว่าจะมีตัวหลักอย่างน้อยหนึ่งตัว"

    /** The door watch reads rotation directly; see the runtime's entry detector. */
    private const val ENTRY_ROTATION_CAVEAT =
        "การตรวจ \"ประตูเปิด\" อ่านเซ็นเซอร์การหมุนโดยตรงเสมอ ไม่ขึ้นกับบทบาทที่ตั้งตรงนี้ — " +
            "ค่าที่ตั้งที่นี่มีผลกับการตรวจจับแบบรวมสัญญาณที่ทำงานคู่ขนานเท่านั้น"

    /** Power Guard registers the light sensor itself, the same way the door watch does. */
    private const val POWER_LIGHT_CAVEAT =
        "การเฝ้าไฟยืนยันอ่านเซ็นเซอร์แสงโดยตรงตลอดเวลาที่โหมดนี้ทำงาน ไม่ขึ้นกับบทบาทที่ตั้งตรงนี้ — " +
            "ลดบทบาทลงจะไม่ทำให้การเฝ้าไฟยืนยันหยุด"

    fun contribution(profile: ProtectionProfile, source: SensorSource): SensorContribution {
        val role = ProtectionProfilePolicy.recommendedRoles(profile).getValue(source)
        return when (profile) {
            ProtectionProfile.VEHICLE -> vehicle(source, role)
            ProtectionProfile.ENTRY -> entry(source, role)
            ProtectionProfile.POWER -> power(source, role)
        }
    }

    private fun vehicle(source: SensorSource, role: SensorRole): SensorContribution = when (source) {
        SensorSource.SIGNIFICANT_MOTION -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "การเคลื่อนไหวที่ชัดเจนพอจนตัวเครื่องปลุกระบบขึ้นมาเอง เช่น รถถูกเข็นหรือยกออกจากจุดจอด " +
                "ไม่มีระดับความไวให้ปรับ",
            asPrimaryTh = "เปิดเหตุการณ์ได้เอง และเป็นตัวหลักตัวเดียวที่พร้อมทันทีโดยไม่ต้องรอปรับเทียบ " +
                "แต่รายงานเป็นครั้ง ๆ ไม่ได้บอกว่าแรงแค่ไหน",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ แต่เมื่อตัวหลักเปิดเหตุการณ์ไว้แล้ว " +
                "การรายงานของตัวนี้จะเข้าไปเป็นหลักฐานอีกแหล่งของเหตุการณ์เดียวกัน",
            ifOffTh = "ไม่ถูกลงทะเบียนเลย การเข็นช้า ๆ ที่ไม่สั่นแรงจะเหลือให้เซ็นเซอร์ตัวอื่นจับอย่างเดียว",
            costTh = COST_TRIGGER,
        )
        SensorSource.ACCELEROMETER -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "แรงสั่นและแรงกระแทกที่ตัวรถ เช่น ถูกเตะ ถูกงัด ยกขาตั้ง " +
                "วัดจากความเร่งรวมเทียบกับตอนเริ่มเฝ้า",
            asPrimaryTh = "เปิดเหตุการณ์ \"รถอาจถูกเคลื่อนย้าย\" ได้เอง และเป็นตัวที่ระบบใช้ตั้งต้น " +
                "ถ้าไม่ได้ตั้งตัวหลักไว้เลย",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ ต้องมีตัวหลักตัวอื่นจับได้ในช่วงเวลาใกล้กัน $LAST_PRIMARY_WARNING",
            ifOffTh = "แรงสั่นและแรงกระแทกจะไม่ถูกตรวจเลย",
            costTh = COST_CONTINUOUS,
        )
        SensorSource.LINEAR_ACCELERATION -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "การเคลื่อนที่จริงของรถโดยตัดแรงโน้มถ่วงออก จึงแยก \"ถูกเข็นออกไป\" " +
                "ออกจาก \"แค่เอียง\" ได้ดีกว่าความเร่งรวม",
            asPrimaryTh = "เปิดเหตุการณ์ได้เองเมื่อพบการเคลื่อนที่เกินเกณฑ์ เหมาะกับจุดจอดที่สั่นตลอดเวลา " +
                "เช่น ริมถนน เพราะไม่นับแรงสั่นที่ไม่ทำให้รถขยับจริง",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ แต่ทำให้เหตุการณ์ที่ความเร่งรวมเปิดไว้ " +
                "มีหลักฐานจากคนละแหล่งในเหตุการณ์เดียวกัน",
            ifOffTh = "การถูกเข็นเบา ๆ จะเหลือให้ความเร่งรวมจับอย่างเดียว ซึ่งแยกจากรถบรรทุกวิ่งผ่านได้ยากกว่า",
            costTh = COST_CONTINUOUS,
        )
        SensorSource.GYROSCOPE -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "ความเร็วในการหมุนของตัวรถ เช่น ยกขาตั้ง งัดล้อ เข็นเลี้ยว",
            asPrimaryTh = "เปิดเหตุการณ์ได้เองเมื่อพบการหมุนเร็วเกินเกณฑ์ แม้ยังไม่มีแรงสั่น",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ แต่เมื่อมาตรวัดความเร่งจับได้ เหตุการณ์เดียวกันจะมีหลักฐาน " +
                "จากทั้งการสั่นและการหมุน อ่านง่ายขึ้นในข้อความแจ้งเตือน",
            ifOffTh = "ประหยัดแบตที่สุดในกลุ่มการหมุน แต่การงัดที่ทำให้รถหมุนโดยไม่สั่นแรงจะหลุด",
            costTh = COST_CONTINUOUS_HIGH,
        )
        SensorSource.ROTATION_VECTOR -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "มุมที่ตัวรถเอียงหรือหันไปจากตอนเริ่มเฝ้า คิดเป็นองศา รวมค่าจากไจโรกับเข็มทิศเข้าด้วยกัน",
            asPrimaryTh = "เปิดเหตุการณ์ได้เองเมื่อมุมต่างจากตอนจอดเกินเกณฑ์ จับการยกหรือหันที่ค้างอยู่ได้ " +
                "ไม่ใช่แค่ตอนกำลังขยับ",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ แต่บอกได้ว่าตอนเกิดเหตุ รถเอียงไปกี่องศาจากตอนจอด",
            ifOffTh = "การหันหรือยกที่ทำช้าจนไม่เกิดแรงสั่นจะหลุด",
            costTh = COST_CONTINUOUS,
        )
        SensorSource.GAME_ROTATION_VECTOR -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "มุมที่เอียงไปจากตอนเริ่มเฝ้าเช่นเดียวกัน แต่ไม่ใช้เข็มทิศ จึงไม่เพี้ยนเมื่ออยู่ใกล้โลหะ " +
                "หรือลำโพง แลกกับทิศที่ไหลไปเรื่อย ๆ เมื่อเฝ้านานหลายชั่วโมง",
            asPrimaryTh = "เปิดเหตุการณ์ได้เองเมื่อมุมต่างจากตอนจอดเกินเกณฑ์ และนิ่งกว่าตัวที่ใช้เข็มทิศ " +
                "เมื่อจอดใกล้โลหะ",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ แต่เติมมุมที่วัดได้เข้าไปในเหตุการณ์ที่ตัวหลักเปิดไว้",
            ifOffTh = "เหลือการวัดมุมจากตัวที่ใช้เข็มทิศ ซึ่งไหวตามโลหะรอบตัวมากกว่า",
            costTh = COST_CONTINUOUS,
        )
        SensorSource.MAGNETIC_FIELD -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "ความแรงของสนามแม่เหล็กรอบจุดติดตั้งที่ต่างไปจากตอนเริ่มเฝ้า เช่น มีโลหะขนาดใหญ่เข้ามาใกล้ " +
                "หรือรถถูกยกขึ้นรถบรรทุก",
            asPrimaryTh = "เปิดเหตุการณ์ได้เอง แต่จุดจอดที่มีรถคันอื่นเข้าออกตลอดจะทำให้เตือนผิดได้ง่าย",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ แต่ช่วยยืนยันว่าสิ่งแวดล้อมรอบรถเปลี่ยนไปจริง ไม่ใช่แค่แรงสั่นผ่าน",
            ifOffTh = "การถูกยกขึ้นรถบรรทุกอย่างนุ่มนวลจะเหลือให้ตัวอื่นจับอย่างเดียว",
            costTh = COST_CONTINUOUS_LOW,
        )
        SensorSource.GEOMAGNETIC_ROTATION_VECTOR -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "มุมที่ตัวรถหันไปจากตอนเริ่มเฝ้า โดยใช้เข็มทิศแทนไจโร",
            asPrimaryTh = "เปิดเหตุการณ์ได้เองเมื่อมุมต่างเกินเกณฑ์ และไม่ไหลสะสมเหมือนตัวที่ใช้ไจโรล้วน " +
                "แต่ตอบสนองช้ากว่า",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ แต่ช่วยตรึงทิศให้การวัดมุมของตัวอื่นไม่ไหลไปตามเวลา",
            ifOffTh = "ประหยัดแบต แต่การวัดมุมที่เหลือจะไหลมากขึ้นเมื่อเฝ้าต่อเนื่องหลายชั่วโมง",
            costTh = COST_CONTINUOUS_LOW,
        )
        SensorSource.AMBIENT_LIGHT -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "แสงที่จุดติดตั้งเปลี่ยนไปจากตอนเริ่มเฝ้า เช่น เปิดเบาะ เปิดฝาครอบ ส่องไฟเข้ามา — " +
                "เมื่อแสงเปลี่ยนแล้วตามด้วยการสั่นในช่วงเวลาใกล้กัน เหตุการณ์จะถูกยกจาก " +
                "\"รถอาจถูกเคลื่อนย้าย\" เป็น \"พบการงัดแงะหรือเปิดเบาะ\" ระดับวิกฤต",
            asPrimaryTh = "แสงที่เปลี่ยนไม่เปิดเหตุการณ์ด้วยตัวเองไม่ว่าตั้งบทบาทใด แต่เมื่อเป็นตัวหลัก " +
                "การสั่นที่ตามมาจะเปิดเหตุการณ์ได้ แม้ตัวที่จับการสั่นจะเป็นแค่ตัวประกอบ",
            asSupportingTh = "ต้องมีตัวหลักตัวอื่นจับการสั่นในช่วงเวลาเดียวกัน เหตุการณ์จึงจะเปิด " +
                "ส่วนการยกระดับเป็น \"พบการงัดแงะหรือเปิดเบาะ\" ยังทำงานเหมือนเดิม",
            ifOffTh = "การเปิดเบาะเงียบ ๆ จะไม่ถูกยกระดับ เหลือเป็นเหตุการณ์สั่นธรรมดา",
            costTh = COST_ON_CHANGE,
        )
        SensorSource.PROXIMITY -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "สิ่งที่เข้ามาบังหน้าจอโทรศัพท์ เช่น มือที่เอื้อมมาหยิบ หรือฝาที่ถูกปิดทับ",
            asPrimaryTh = "เปิดเหตุการณ์ได้เอง แต่ถ้าติดตั้งไว้ในที่แคบหรือมีอะไรบังอยู่แล้ว จะเตือนผิดง่ายมาก",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ แต่ช่วยบอกว่ามีอะไรเข้ามาใกล้ตัวเครื่องตอนเกิดเหตุ",
            ifOffTh = "มือที่เอื้อมเข้ามาโดยยังไม่ทำให้รถสั่นจะไม่ถูกบันทึก",
            costTh = COST_ON_CHANGE,
        )
    }

    private fun entry(source: SensorSource, role: SensorRole): SensorContribution = when (source) {
        SensorSource.SIGNIFICANT_MOTION -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "การเคลื่อนไหวที่ชัดเจนพอจนตัวเครื่องปลุกระบบขึ้นมาเอง เช่น ประตูถูกกระแทก " +
                "หรือโทรศัพท์ถูกปลดออกจากบาน",
            asPrimaryTh = "เปิดเหตุการณ์ได้เอง และพร้อมทันทีโดยไม่ต้องรอปรับเทียบ แต่รายงานเป็นครั้ง ๆ " +
                "บอกไม่ได้ว่าประตูเปิดไปกี่องศา",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ แต่ยืนยันว่ามีการเคลื่อนไหวจริงตอนที่มุมประตูเปลี่ยน",
            ifOffTh = "ไม่ถูกลงทะเบียนเลย การเปิดประตูช้า ๆ จะเหลือให้เซ็นเซอร์การหมุนจับอย่างเดียว",
            costTh = COST_TRIGGER,
        )
        SensorSource.ACCELEROMETER -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "แรงกระแทกที่บานประตู เช่น ถูกเคาะ ถูกงัด หรือถูกกระแทกปิด",
            asPrimaryTh = "เปิดเหตุการณ์ได้เองเมื่อพบแรงกระแทก เหมาะถ้าห่วงการงัดมากกว่าการเปิดประตูช้า ๆ",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ แต่แยกการเปิดประตูตามปกติ ออกจากการงัดที่มีแรงกระแทกร่วมด้วย",
            ifOffTh = "การเคาะหรืองัดที่ยังไม่ทำให้บานประตูหมุนจะไม่ถูกตรวจ",
            costTh = COST_CONTINUOUS,
        )
        SensorSource.LINEAR_ACCELERATION -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "การเคลื่อนที่ของบานประตูโดยตัดแรงโน้มถ่วงออก จึงไม่นับการสั่นสะเทือนจากพื้นหรือลม",
            asPrimaryTh = "เปิดเหตุการณ์ได้เองเมื่อบานประตูเคลื่อนที่จริงเกินเกณฑ์",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ แต่ช่วยยืนยันว่าบานประตูขยับจริง ไม่ใช่แค่สั่นอยู่กับที่",
            ifOffTh = "การผลักประตูเบา ๆ จะเหลือให้การวัดมุมจับอย่างเดียว",
            costTh = COST_CONTINUOUS,
        )
        SensorSource.GYROSCOPE -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "ความเร็วในการหมุนของบานประตูขณะกำลังเปิด",
            asPrimaryTh = "เปิดเหตุการณ์ได้เองเมื่อพบการหมุนเร็วเกินเกณฑ์ แม้ยังไม่มีแรงกระแทก",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ ต้องมีตัวหลักตัวอื่นจับได้ในช่วงเวลาใกล้กัน $LAST_PRIMARY_WARNING",
            ifOffTh = "การรวมสัญญาณจะเหลือมุมจากเวกเตอร์การหมุนอย่างเดียว",
            costTh = COST_CONTINUOUS_HIGH,
            caveatTh = ENTRY_ROTATION_CAVEAT,
        )
        SensorSource.ROTATION_VECTOR -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "มุมที่บานประตูหมุนไปจากตอนปิด คิดเป็นองศา รวมค่าจากไจโรกับเข็มทิศเข้าด้วยกัน",
            asPrimaryTh = "เปิดเหตุการณ์ได้เองเมื่อมุมเกินเกณฑ์ที่ตั้งไว้ จับประตูที่ถูกเปิดค้างไว้ได้ " +
                "ไม่ใช่แค่จังหวะที่กำลังเปิด",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ แต่บอกได้ว่าตอนเกิดเหตุ ประตูเปิดไปกี่องศา",
            ifOffTh = "การรวมสัญญาณจะเหลือความเร็วการหมุนอย่างเดียว ซึ่งบอกไม่ได้ว่าประตูค้างอยู่ที่มุมใด",
            costTh = COST_CONTINUOUS,
            caveatTh = ENTRY_ROTATION_CAVEAT,
        )
        SensorSource.GAME_ROTATION_VECTOR -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "มุมที่บานประตูหมุนไปจากตอนปิด โดยไม่ใช้เข็มทิศ จึงไม่เพี้ยนเมื่อประตูเป็นโลหะ " +
                "หรือมีกลอนแม่เหล็ก",
            asPrimaryTh = "เปิดเหตุการณ์ได้เองเมื่อมุมเกินเกณฑ์ และนิ่งกว่าตัวที่ใช้เข็มทิศเมื่อติดตั้งกับประตูโลหะ",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ แต่เติมมุมที่วัดได้เข้าไปในเหตุการณ์ที่ตัวหลักเปิดไว้",
            ifOffTh = "การรวมสัญญาณจะเหลือมุมจากตัวที่ใช้เข็มทิศ ซึ่งไหวตามโลหะของบานประตูมากกว่า",
            costTh = COST_CONTINUOUS,
            caveatTh = ENTRY_ROTATION_CAVEAT,
        )
        SensorSource.MAGNETIC_FIELD -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "สนามแม่เหล็กรอบวงกบที่ต่างไปจากตอนปิด เช่น กลอนโลหะที่เลื่อนออก " +
                "หรือบานประตูโลหะที่แยกจากวงกบ",
            asPrimaryTh = "เปิดเหตุการณ์ได้เอง แต่ประตูที่มีคนเดินผ่านถือของโลหะบ่อย ๆ จะทำให้เตือนผิดได้",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ แต่ช่วยยืนยันว่าบานประตูแยกออกจากวงกบจริง",
            ifOffTh = "การแง้มประตูโดยยังไม่หมุนถึงมุมที่ตั้งไว้จะไม่มีหลักฐานประกอบ",
            costTh = COST_CONTINUOUS_LOW,
        )
        SensorSource.GEOMAGNETIC_ROTATION_VECTOR -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "มุมที่บานประตูหันไปจากตอนปิด โดยใช้เข็มทิศแทนไจโร",
            asPrimaryTh = "เปิดเหตุการณ์ได้เองเมื่อมุมเกินเกณฑ์ และไม่ไหลสะสมตามเวลาเหมือนตัวที่ใช้ไจโรล้วน " +
                "แต่ตอบสนองช้ากว่าและไหวตามโลหะของบานประตู",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ แต่ช่วยตรึงทิศให้การวัดมุมของตัวอื่นไม่ไหลเมื่อเฝ้าข้ามคืน",
            ifOffTh = "ประหยัดแบต แต่การวัดมุมที่เหลือจะไหลมากขึ้นเมื่อเฝ้าต่อเนื่องหลายชั่วโมง",
            costTh = COST_CONTINUOUS_LOW,
        )
        SensorSource.AMBIENT_LIGHT -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "แสงที่เปลี่ยนตรงจุดติดตั้ง เช่น ไฟจากอีกฝั่งที่ลอดเข้ามาเมื่อประตูถูกเปิด — " +
                "เมื่อตามด้วยการสั่นในช่วงเวลาใกล้กัน เหตุการณ์จะถูกยกเป็น " +
                "\"พบการงัดแงะหรือเปิดเบาะ\" ระดับวิกฤต",
            asPrimaryTh = "แสงที่เปลี่ยนไม่เปิดเหตุการณ์ด้วยตัวเองไม่ว่าตั้งบทบาทใด แต่เมื่อเป็นตัวหลัก " +
                "การสั่นที่ตามมาจะเปิดเหตุการณ์ได้ แม้ตัวที่จับการสั่นจะเป็นแค่ตัวประกอบ",
            asSupportingTh = "ต้องมีตัวหลักตัวอื่นจับได้ในช่วงเวลาเดียวกัน เหตุการณ์จึงจะเปิด " +
                "ส่วนการยกระดับยังทำงานเหมือนเดิม",
            ifOffTh = "ประตูที่ถูกเปิดในที่มืดสนิทจะไม่เสียอะไร แต่การเปิดตอนกลางวันจะเสียหลักฐานหนึ่งแหล่ง",
            costTh = COST_ON_CHANGE,
        )
        SensorSource.PROXIMITY -> SensorContribution(
            source = source,
            recommendedRole = role,
            detectsTh = "สิ่งที่เข้ามาบังหน้าจอโทรศัพท์ เช่น มือที่เอื้อมมาปลดเครื่องออกจากบานประตู",
            asPrimaryTh = "เปิดเหตุการณ์ได้เอง แต่ถ้าติดตั้งไว้ในซอกที่มีอะไรบังอยู่แล้ว จะเตือนผิดง่ายมาก",
            asSupportingTh = "เปิดเหตุการณ์เองไม่ได้ แต่ช่วยบอกว่ามีคนเข้ามาใกล้ตัวเครื่องตอนประตูถูกเปิด",
            ifOffTh = "การเอื้อมมาปลดเครื่องโดยยังไม่หมุนบานประตูจะไม่ถูกบันทึก",
            costTh = COST_ON_CHANGE,
        )
    }

    private fun power(source: SensorSource, role: SensorRole): SensorContribution =
        if (source == SensorSource.AMBIENT_LIGHT) {
            SensorContribution(
                source = source,
                recommendedRole = role,
                detectsTh = "ไฟยืนยันที่จุดติดตั้ง — เทียบระดับแสงกับค่าที่ปรับเทียบไว้ตอนปิดและเปิดไฟ " +
                    "เพื่อแยก \"ไฟดับทั้งจุด\" ออกจาก \"สายชาร์จหลุดอย่างเดียว\"",
                asPrimaryTh = "เป็นค่าที่แนะนำของโหมดนี้ และเป็นสัญญาณที่สองคู่กับสถานะการชาร์จ",
                asSupportingTh = "ลดบทบาทลงจะไม่ทำให้การเฝ้าไฟยืนยันหยุด เพราะโหมดนี้อ่านเซ็นเซอร์แสงโดยตรง " +
                    "สิ่งที่เปลี่ยนคือการรวมสัญญาณที่ทำงานคู่ขนานเท่านั้น",
                ifOffTh = "การรวมสัญญาณจะไม่ได้ค่าแสง แต่การเฝ้าไฟยืนยันของโหมดนี้ยังทำงานต่อไป",
                costTh = COST_ON_CHANGE,
                caveatTh = POWER_LIGHT_CAVEAT,
            )
        } else {
            SensorContribution(
                source = source,
                recommendedRole = role,
                detectsTh = "โหมดไฟเลี้ยงไม่ใช้เซ็นเซอร์นี้ตรวจจับ",
                asPrimaryTh = "ตั้งไม่ได้ในโหมดนี้",
                asSupportingTh = "ตั้งไม่ได้ในโหมดนี้",
                ifOffTh = "โหมดนี้บังคับปิดไว้ตลอด — การขยับตอนถอดหรือเสียบสายชาร์จ " +
                    "จะเปิดเหตุการณ์ซ้อนกับเหตุการณ์ไฟเลี้ยง",
                costTh = COST_LOCKED,
            )
        }
}

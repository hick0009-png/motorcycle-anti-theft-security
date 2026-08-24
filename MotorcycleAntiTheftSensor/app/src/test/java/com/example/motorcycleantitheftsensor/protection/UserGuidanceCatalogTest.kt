package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.*
import org.junit.Test

class UserGuidanceCatalogTest {

    @Test
    fun `Verify catalog negative cases for secrets`() {
        val forbiddenStrings = listOf(
            "otpauth://",
            "secret=",
            "TOTP_DEBUG",
            "chat_id",
            "JBSWY3DPEHPK3PXP",
            "123456",
            "Authenticator",
            "TOTP",
        )
        GuidanceCode.values().forEach { code ->
            val content = UserGuidanceCatalog.content(code, GuidanceDetail.None)
            val allText = "${content.titleTh} ${content.bodyTh} ${content.telegramTh ?: ""}"
            forbiddenStrings.forEach { forbidden ->
                assertFalse("Code $code contains forbidden string '$forbidden'", allText.contains(forbidden))
            }
        }
    }

    @Test
    fun `Verify exhaustive mapping`() {
        val c_SETUP_REQUIRED = UserGuidanceCatalog.content(GuidanceCode.SETUP_REQUIRED, GuidanceDetail.None)
        assertEquals("ต้องตั้งค่าระบบก่อนเปิดการป้องกัน", c_SETUP_REQUIRED.titleTh)
        assertEquals("ตั้งค่า Bot และจับคู่เจ้าของให้ครบ", c_SETUP_REQUIRED.bodyTh)
        assertEquals("⚠️ ระบบยังตั้งค่าไม่ครบ ดูหน้าการตั้งค่าบนมือถือรถ", c_SETUP_REQUIRED.telegramTh)
        val c_DISARMED = UserGuidanceCatalog.content(GuidanceCode.DISARMED, GuidanceDetail.None)
        assertEquals("การป้องกันปิดอยู่", c_DISARMED.titleTh)
        assertEquals("ระบบออนไลน์และพร้อมเปิดการป้องกัน", c_DISARMED.bodyTh)
        assertEquals("ℹ️ ปิดการป้องกันแล้ว", c_DISARMED.telegramTh)
        val c_ARMING = UserGuidanceCatalog.content(GuidanceCode.ARMING, GuidanceDetail.None)
        assertEquals("กำลังเปิดการป้องกัน", c_ARMING.titleTh)
        assertEquals("กำลังปรับเทียบเซนเซอร์ เหลือ 0 วินาที", c_ARMING.bodyTh)
        assertEquals("ℹ️ กำลังเปิดการป้องกัน รอการปรับเทียบเซนเซอร์", c_ARMING.telegramTh)
        val c_ARMED_HEALTHY = UserGuidanceCatalog.content(GuidanceCode.ARMED_HEALTHY, GuidanceDetail.None)
        assertEquals("การป้องกันทำงานปกติ", c_ARMED_HEALTHY.titleTh)
        assertEquals("ระบบหลักพร้อมทำงาน", c_ARMED_HEALTHY.bodyTh)
        assertEquals("✅ การป้องกันทำงานปกติ", c_ARMED_HEALTHY.telegramTh)
        val c_ARMED_DEGRADED = UserGuidanceCatalog.content(GuidanceCode.ARMED_DEGRADED, GuidanceDetail.None)
        assertEquals("การป้องกันทำงานแบบจำกัด", c_ARMED_DEGRADED.titleTh)
        assertEquals("", c_ARMED_DEGRADED.bodyTh)
        assertEquals("⚠️ การป้องกันทำงานแบบจำกัด: ", c_ARMED_DEGRADED.telegramTh)
        val c_ALERT_ACTIVE = UserGuidanceCatalog.content(GuidanceCode.ALERT_ACTIVE, GuidanceDetail.None)
        assertEquals("กำลังส่งสัญญาณเตือนภัย", c_ALERT_ACTIVE.titleTh)
        assertEquals("ตรวจพบความผิดปกติและกำลังส่งสัญญาณเตือนภัย", c_ALERT_ACTIVE.bodyTh)
        assertEquals("🚨 กำลังส่งสัญญาณเตือนภัย ตรวจพบความผิดปกติ", c_ALERT_ACTIVE.telegramTh)
        val c_OFFLINE = UserGuidanceCatalog.content(GuidanceCode.OFFLINE, GuidanceDetail.None)
        assertEquals("ระบบออฟไลน์", c_OFFLINE.titleTh)
        assertEquals("บริการหลักหยุดทำงาน", c_OFFLINE.bodyTh)
        assertEquals("⚠️ ระบบออฟไลน์ บริการหลักหยุดทำงาน", c_OFFLINE.telegramTh)
        val c_SERVICE_RECOVERED = UserGuidanceCatalog.content(GuidanceCode.SERVICE_RECOVERED, GuidanceDetail.None)
        assertEquals("บริการเริ่มใหม่สำเร็จ", c_SERVICE_RECOVERED.titleTh)
        assertEquals("ระบบกู้คืนสถานะเดิมแล้ว", c_SERVICE_RECOVERED.bodyTh)
        assertEquals("✅ บริการเริ่มใหม่สำเร็จ ระบบกู้คืนสถานะเดิมแล้ว", c_SERVICE_RECOVERED.telegramTh)
        val c_BOT_VERIFYING = UserGuidanceCatalog.content(GuidanceCode.BOT_VERIFYING, GuidanceDetail.None)
        assertEquals("กำลังทดสอบเชื่อมต่อ Bot…", c_BOT_VERIFYING.titleTh)
        assertEquals("โปรดรอสักครู่", c_BOT_VERIFYING.bodyTh)
        assertNull(c_BOT_VERIFYING.telegramTh)
        val c_BOT_CONNECTED = UserGuidanceCatalog.content(GuidanceCode.BOT_CONNECTED, GuidanceDetail.None)
        assertEquals("เชื่อมต่อ Telegram Bot สำเร็จ", c_BOT_CONNECTED.titleTh)
        assertEquals("พร้อมรับส่งข้อความแจ้งเตือน", c_BOT_CONNECTED.bodyTh)
        assertNull(c_BOT_CONNECTED.telegramTh)
        val c_BOT_TOKEN_INVALID = UserGuidanceCatalog.content(GuidanceCode.BOT_TOKEN_INVALID, GuidanceDetail.None)
        assertEquals("Token ไม่ถูกต้องหรือเชื่อมต่อไม่ได้", c_BOT_TOKEN_INVALID.titleTh)
        assertEquals("ตรวจสอบ Token จาก BotFather แล้วลองใหม่", c_BOT_TOKEN_INVALID.bodyTh)
        assertNull(c_BOT_TOKEN_INVALID.telegramTh)
        val c_TELEGRAM_UNREACHABLE = UserGuidanceCatalog.content(GuidanceCode.TELEGRAM_UNREACHABLE, GuidanceDetail.None)
        assertEquals("ติดต่อ Telegram ไม่ได้", c_TELEGRAM_UNREACHABLE.titleTh)
        assertEquals("ตรวจสอบอินเทอร์เน็ตของมือถือรถ", c_TELEGRAM_UNREACHABLE.bodyTh)
        assertEquals("⚠️ ติดต่อ Telegram ไม่ได้ ตรวจสอบอินเทอร์เน็ตของมือถือรถ", c_TELEGRAM_UNREACHABLE.telegramTh)
        val c_PAIRING_REQUIRED = UserGuidanceCatalog.content(GuidanceCode.PAIRING_REQUIRED, GuidanceDetail.None)
        assertEquals("ต้องจับคู่ Telegram ก่อน", c_PAIRING_REQUIRED.titleTh)
        assertEquals("พิมพ์ /pair <รหัส> จากแอปในมือถือรถ", c_PAIRING_REQUIRED.bodyTh)
        assertEquals("🔒 ต้องจับคู่ Telegram ก่อน พิมพ์ /pair <รหัส> จากแอปในมือถือรถ", c_PAIRING_REQUIRED.telegramTh)
        val c_PAIRING_ACCEPTED = UserGuidanceCatalog.content(GuidanceCode.PAIRING_ACCEPTED, GuidanceDetail.None)
        assertEquals("จับคู่ Telegram สำเร็จ", c_PAIRING_ACCEPTED.titleTh)
        assertEquals("บัญชีนี้สามารถสั่งงานรถได้", c_PAIRING_ACCEPTED.bodyTh)
        assertEquals("✅ จับคู่ Telegram สำเร็จ บัญชีนี้สามารถสั่งงานรถได้", c_PAIRING_ACCEPTED.telegramTh)
        val c_PAIRING_INVALID_OR_EXPIRED = UserGuidanceCatalog.content(GuidanceCode.PAIRING_INVALID_OR_EXPIRED, GuidanceDetail.None)
        assertEquals("รหัสจับคู่ไม่ถูกต้องหรือหมดอายุ", c_PAIRING_INVALID_OR_EXPIRED.titleTh)
        assertEquals("รหัสไม่ถูกต้องหรือหมดอายุ สร้างรหัสใหม่บนมือถือรถ", c_PAIRING_INVALID_OR_EXPIRED.bodyTh)
        assertEquals("⚠️ รหัสจับคู่ไม่ถูกต้องหรือหมดอายุ", c_PAIRING_INVALID_OR_EXPIRED.telegramTh)
        val c_UNAUTHORIZED_COMMAND = UserGuidanceCatalog.content(GuidanceCode.UNAUTHORIZED_COMMAND, GuidanceDetail.None)
        assertEquals("คำสั่งจากบัญชีที่ไม่ได้รับอนุญาต", c_UNAUTHORIZED_COMMAND.titleTh)
        assertEquals("ไม่มีการเปลี่ยนสถานะระบบ", c_UNAUTHORIZED_COMMAND.bodyTh)
        assertEquals("🔒 บัญชีนี้ไม่ได้รับอนุญาตให้สั่งงาน", c_UNAUTHORIZED_COMMAND.telegramTh)
        val c_COMMAND_STATUS_SUCCESS = UserGuidanceCatalog.content(GuidanceCode.COMMAND_STATUS_SUCCESS, GuidanceDetail.None)
        assertEquals("อัปเดตสถานะแล้ว", c_COMMAND_STATUS_SUCCESS.titleTh)
        assertEquals("อัปเดตสถานะแล้ว", c_COMMAND_STATUS_SUCCESS.bodyTh)
        assertEquals("ℹ️ สถานะระบบ: {protectionStatus}", c_COMMAND_STATUS_SUCCESS.telegramTh)
        val c_COMMAND_ARM_APPLIED = UserGuidanceCatalog.content(GuidanceCode.COMMAND_ARM_APPLIED, GuidanceDetail.None)
        assertEquals("เปิดการป้องกันแล้ว", c_COMMAND_ARM_APPLIED.titleTh)
        assertEquals("กำลังเปิดการป้องกัน…", c_COMMAND_ARM_APPLIED.bodyTh)
        assertEquals("✅ กำลังเปิดการป้องกัน โปรดรอการปรับเทียบเซนเซอร์", c_COMMAND_ARM_APPLIED.telegramTh)
        val c_COMMAND_ARM_REJECTED = UserGuidanceCatalog.content(GuidanceCode.COMMAND_ARM_REJECTED, GuidanceDetail.None)
        assertEquals("ไม่สามารถเปิดการป้องกันได้", c_COMMAND_ARM_REJECTED.titleTh)
        assertEquals("ไม่สามารถเปิดการป้องกันได้: ", c_COMMAND_ARM_REJECTED.bodyTh)
        assertEquals("⚠️ เปิดการป้องกันไม่ได้: ", c_COMMAND_ARM_REJECTED.telegramTh)
        val c_COMMAND_DISARM_APPLIED = UserGuidanceCatalog.content(GuidanceCode.COMMAND_DISARM_APPLIED, GuidanceDetail.None)
        assertEquals("ปลดการป้องกันสำเร็จ", c_COMMAND_DISARM_APPLIED.titleTh)
        assertEquals("ปลดการป้องกันสำเร็จ", c_COMMAND_DISARM_APPLIED.bodyTh)
        assertEquals("✅ ปลดการป้องกันสำเร็จ", c_COMMAND_DISARM_APPLIED.telegramTh)
        val c_COMMAND_DISARM_REJECTED = UserGuidanceCatalog.content(GuidanceCode.COMMAND_DISARM_REJECTED, GuidanceDetail.None)
        assertEquals("ไม่สามารถปลดการป้องกันได้", c_COMMAND_DISARM_REJECTED.titleTh)
        assertEquals("ไม่สามารถปลดการป้องกันได้: ", c_COMMAND_DISARM_REJECTED.bodyTh)
        assertEquals("⚠️ ปลดการป้องกันไม่ได้: ", c_COMMAND_DISARM_REJECTED.telegramTh)
        val c_COMMAND_SENSITIVITY_APPLIED = UserGuidanceCatalog.content(GuidanceCode.COMMAND_SENSITIVITY_APPLIED, GuidanceDetail.None)
        assertEquals("บันทึกระดับการตรวจจับแล้ว", c_COMMAND_SENSITIVITY_APPLIED.titleTh)
        assertEquals("บันทึกระดับการตรวจจับแล้ว", c_COMMAND_SENSITIVITY_APPLIED.bodyTh)
        assertEquals(
            "✅ บันทึกระดับการตรวจจับ 0/10 แล้ว (ใช้ได้เฉพาะเซ็นเซอร์ที่รองรับในโหมดยานพาหนะ)",
            c_COMMAND_SENSITIVITY_APPLIED.telegramTh,
        )
        val c_COMMAND_SENSITIVITY_INVALID = UserGuidanceCatalog.content(GuidanceCode.COMMAND_SENSITIVITY_INVALID, GuidanceDetail.None)
        assertEquals("ระดับการตรวจจับไม่ถูกต้อง", c_COMMAND_SENSITIVITY_INVALID.titleTh)
        assertEquals("ระดับการตรวจจับไม่ถูกต้อง", c_COMMAND_SENSITIVITY_INVALID.bodyTh)
        assertEquals("⚠️ ระดับการตรวจจับต้องอยู่ระหว่าง 1 ถึง 10", c_COMMAND_SENSITIVITY_INVALID.telegramTh)
        val c_COMMAND_HELP = UserGuidanceCatalog.content(GuidanceCode.COMMAND_HELP, GuidanceDetail.None)
        assertEquals("ไม่มี", c_COMMAND_HELP.titleTh)
        assertEquals("ไม่มี", c_COMMAND_HELP.bodyTh)
        assertEquals(
            "ℹ️ คำสั่ง: /status, /arm, /disarm, /sensitivity 1-10 ปรับระดับการตรวจจับ " +
                "(/sensitivity เป็นคำสั่งเดิม ใช้ได้เฉพาะเซ็นเซอร์ที่รองรับในโหมดยานพาหนะ)",
            c_COMMAND_HELP.telegramTh,
        )
        assertFalse(c_COMMAND_HELP.telegramTh!!.contains("<รหัส>"))
        val c_COMMAND_UNKNOWN = UserGuidanceCatalog.content(GuidanceCode.COMMAND_UNKNOWN, GuidanceDetail.None)
        assertEquals("คำสั่งไม่สำเร็จ", c_COMMAND_UNKNOWN.titleTh)
        assertEquals("ไม่มี", c_COMMAND_UNKNOWN.bodyTh)
        assertEquals("ℹ️ ไม่พบคำสั่ง พิมพ์ /help เพื่อดูคำสั่งที่ใช้ได้", c_COMMAND_UNKNOWN.telegramTh)
        val c_SENSOR_HEALTHY = UserGuidanceCatalog.content(GuidanceCode.SENSOR_HEALTHY, GuidanceDetail.None)
        assertEquals("เซนเซอร์พร้อมใช้งาน", c_SENSOR_HEALTHY.titleTh)
        assertEquals(" กำลังอ่านค่า", c_SENSOR_HEALTHY.bodyTh)
        assertNull(c_SENSOR_HEALTHY.telegramTh)
        val c_SENSOR_UNAVAILABLE = UserGuidanceCatalog.content(GuidanceCode.SENSOR_UNAVAILABLE, GuidanceDetail.None)
        assertEquals("เซนเซอร์ไม่พร้อมใช้งาน", c_SENSOR_UNAVAILABLE.titleTh)
        assertEquals(": ", c_SENSOR_UNAVAILABLE.bodyTh)
        assertEquals("รวมใน /status", c_SENSOR_UNAVAILABLE.telegramTh)
        val c_SENSOR_PERMISSION_MISSING = UserGuidanceCatalog.content(GuidanceCode.SENSOR_PERMISSION_MISSING, GuidanceDetail.None)
        assertEquals("ต้องอนุญาตสิทธิ์", c_SENSOR_PERMISSION_MISSING.titleTh)
        assertEquals("เปิดสิทธิ์ที่จำเป็นเพื่อใช้งานฟีเจอร์นี้", c_SENSOR_PERMISSION_MISSING.bodyTh)
        assertNull(c_SENSOR_PERMISSION_MISSING.telegramTh)
        val c_SENSOR_SAMPLE_FAILED = UserGuidanceCatalog.content(GuidanceCode.SENSOR_SAMPLE_FAILED, GuidanceDetail.None)
        assertEquals("อ่านค่าเซนเซอร์ไม่สำเร็จ", c_SENSOR_SAMPLE_FAILED.titleTh)
        assertEquals("ระบบยังทำงานด้วยเซนเซอร์ที่พร้อม", c_SENSOR_SAMPLE_FAILED.bodyTh)
        assertEquals("รวมใน /status", c_SENSOR_SAMPLE_FAILED.telegramTh)
        val c_INCIDENT_OPENED = UserGuidanceCatalog.content(GuidanceCode.INCIDENT_OPENED, GuidanceDetail.None)
        assertEquals("ตรวจพบ ", c_INCIDENT_OPENED.titleTh)
        assertEquals("กำลังส่งการแจ้งเตือน", c_INCIDENT_OPENED.bodyTh)
        assertEquals("🚨 ตรวจพบ ", c_INCIDENT_OPENED.telegramTh)
        val c_INCIDENT_UPDATED = UserGuidanceCatalog.content(GuidanceCode.INCIDENT_UPDATED, GuidanceDetail.None)
        assertEquals("เหตุการณ์เดิมกำลังอัปเดต", c_INCIDENT_UPDATED.titleTh)
        assertEquals("บันทึกหลักฐานเพิ่มโดยไม่ส่งข้อความซ้ำ", c_INCIDENT_UPDATED.bodyTh)
        assertNull(c_INCIDENT_UPDATED.telegramTh)
        val c_INCIDENT_ESCALATED = UserGuidanceCatalog.content(GuidanceCode.INCIDENT_ESCALATED, GuidanceDetail.None)
        assertEquals("เหตุการณ์รุนแรงขึ้น", c_INCIDENT_ESCALATED.titleTh)
        assertEquals("", c_INCIDENT_ESCALATED.bodyTh)
        assertEquals("🚨 เหตุยกระดับเป็นวิกฤต: ", c_INCIDENT_ESCALATED.telegramTh)
        val c_INCIDENT_CLOSED = UserGuidanceCatalog.content(GuidanceCode.INCIDENT_CLOSED, GuidanceDetail.None)
        assertEquals("เหตุการณ์สิ้นสุดแล้ว", c_INCIDENT_CLOSED.titleTh)
        assertEquals("ไม่มีความเคลื่อนไหวต่อเนื่อง 30 วินาที", c_INCIDENT_CLOSED.bodyTh)
        assertEquals("ℹ️ เหตุการณ์สิ้นสุดแล้ว", c_INCIDENT_CLOSED.telegramTh)
        val c_TELEGRAM_DELIVERY_SENDING = UserGuidanceCatalog.content(GuidanceCode.TELEGRAM_DELIVERY_SENDING, GuidanceDetail.None)
        assertEquals("กำลังส่ง Telegram…", c_TELEGRAM_DELIVERY_SENDING.titleTh)
        assertEquals("กำลังส่ง Telegram…", c_TELEGRAM_DELIVERY_SENDING.bodyTh)
        assertEquals("ไม่มีข้อความเพิ่ม", c_TELEGRAM_DELIVERY_SENDING.telegramTh)
        val c_TELEGRAM_DELIVERY_SENT = UserGuidanceCatalog.content(GuidanceCode.TELEGRAM_DELIVERY_SENT, GuidanceDetail.None)
        assertEquals("ส่ง Telegram สำเร็จ", c_TELEGRAM_DELIVERY_SENT.titleTh)
        assertEquals("ส่ง Telegram สำเร็จ", c_TELEGRAM_DELIVERY_SENT.bodyTh)
        assertEquals("ข้อความเหตุการณ์หลัก", c_TELEGRAM_DELIVERY_SENT.telegramTh)
        val c_TELEGRAM_DELIVERY_FAILED = UserGuidanceCatalog.content(GuidanceCode.TELEGRAM_DELIVERY_FAILED, GuidanceDetail.None)
        assertEquals("ส่ง Telegram ไม่สำเร็จ", c_TELEGRAM_DELIVERY_FAILED.titleTh)
        assertEquals("ระบบจะรายงานสถานะการเชื่อมต่อ", c_TELEGRAM_DELIVERY_FAILED.bodyTh)
        assertEquals("ไม่มีการตอบซ้ำ", c_TELEGRAM_DELIVERY_FAILED.telegramTh)
        val c_SMS_FALLBACK_USED = UserGuidanceCatalog.content(GuidanceCode.SMS_FALLBACK_USED, GuidanceDetail.None)
        assertEquals("ใช้ช่องทางสำรองสำหรับเหตุการณ์วิกฤต", c_SMS_FALLBACK_USED.titleTh)
        assertEquals("ใช้ช่องทางสำรองสำหรับเหตุการณ์วิกฤต", c_SMS_FALLBACK_USED.bodyTh)
        assertEquals("ไม่เปิดเผยปลายทางหรือ key", c_SMS_FALLBACK_USED.telegramTh)
        val c_NOTIFICATION_PERMISSION_MISSING = UserGuidanceCatalog.content(GuidanceCode.NOTIFICATION_PERMISSION_MISSING, GuidanceDetail.None)
        assertEquals("ยังไม่ได้อนุญาตการแจ้งเตือน", c_NOTIFICATION_PERMISSION_MISSING.titleTh)
        assertEquals("เปิดสิทธิ์เพื่อเห็นสถานะสำคัญบนมือถือรถ", c_NOTIFICATION_PERMISSION_MISSING.bodyTh)
        assertNull(c_NOTIFICATION_PERMISSION_MISSING.telegramTh)
        val c_MICROPHONE_PERMISSION_MISSING = UserGuidanceCatalog.content(GuidanceCode.MICROPHONE_PERMISSION_MISSING, GuidanceDetail.None)
        assertEquals("ไมโครโฟนยังไม่พร้อม", c_MICROPHONE_PERMISSION_MISSING.titleTh)
        assertEquals("การตรวจจับเสียงจะไม่ทำงาน แต่ระบบส่วนอื่นยังทำงาน", c_MICROPHONE_PERMISSION_MISSING.bodyTh)
        assertNull(c_MICROPHONE_PERMISSION_MISSING.telegramTh)
        val c_LOCATION_PERMISSION_MISSING = UserGuidanceCatalog.content(GuidanceCode.LOCATION_PERMISSION_MISSING, GuidanceDetail.None)
        assertEquals("ตำแหน่งยังไม่พร้อม", c_LOCATION_PERMISSION_MISSING.titleTh)
        assertEquals("ข้อมูลตำแหน่งจะไม่ถูกรวมในเหตุการณ์", c_LOCATION_PERMISSION_MISSING.bodyTh)
        assertNull(c_LOCATION_PERMISSION_MISSING.telegramTh)
        val c_SMS_PERMISSION_DENIED = UserGuidanceCatalog.content(GuidanceCode.SMS_PERMISSION_DENIED, GuidanceDetail.None)
        assertEquals("SMS ถูกปิดเพื่อความปลอดภัย", c_SMS_PERMISSION_DENIED.titleTh)
        assertEquals("ไม่มีการส่ง SMS จนกว่าผู้ใช้จะอนุญาตตาม flow ที่กำหนด", c_SMS_PERMISSION_DENIED.bodyTh)
        assertNull(c_SMS_PERMISSION_DENIED.telegramTh)
        val c_SETTINGS_SAVE_SUCCESS = UserGuidanceCatalog.content(GuidanceCode.SETTINGS_SAVE_SUCCESS, GuidanceDetail.None)
        assertEquals("บันทึกการตั้งค่าสำเร็จ", c_SETTINGS_SAVE_SUCCESS.titleTh)
        assertEquals("บันทึกการตั้งค่าสำเร็จ", c_SETTINGS_SAVE_SUCCESS.bodyTh)
        assertNull(c_SETTINGS_SAVE_SUCCESS.telegramTh)
        val c_SETTINGS_SAVE_FAILED = UserGuidanceCatalog.content(GuidanceCode.SETTINGS_SAVE_FAILED, GuidanceDetail.None)
        assertEquals("บันทึกการตั้งค่าไม่สำเร็จ", c_SETTINGS_SAVE_FAILED.titleTh)
        assertEquals("ตรวจสอบข้อมูลแล้วลองใหม่", c_SETTINGS_SAVE_FAILED.bodyTh)
        assertNull(c_SETTINGS_SAVE_FAILED.telegramTh)
    }

    @Test
    fun `Verify legacy sensitivity and vehicle-only wording is retired`() {
        GuidanceCode.values().forEach { code ->
            val content = UserGuidanceCatalog.content(code, GuidanceDetail.None)
            val allText = "${content.titleTh} ${content.bodyTh} ${content.telegramTh ?: ""}"
            assertFalse("Code $code still uses legacy 'ระดับความไว'", allText.contains("ระดับความไว"))
            assertFalse("Code $code still uses vehicle-only 'สถานะรถ'", allText.contains("สถานะรถ"))
            assertFalse("Code $code leaks English placeholder copy", allText.contains("update status card only"))
        }
    }

    @Test
    fun `Verify sensitivity command explains Vehicle-detector compatibility scope`() {
        val applied = UserGuidanceCatalog.content(GuidanceCode.COMMAND_SENSITIVITY_APPLIED, GuidanceDetail.None)
        val invalid = UserGuidanceCatalog.content(GuidanceCode.COMMAND_SENSITIVITY_INVALID, GuidanceDetail.None)
        val help = UserGuidanceCatalog.content(GuidanceCode.COMMAND_HELP, GuidanceDetail.None)

        listOf(applied, invalid, help).forEach { content ->
            val text = "${content.titleTh} ${content.bodyTh} ${content.telegramTh ?: ""}"
            assertTrue(
                "sensitivity copy must use 'ระดับการตรวจจับ': $text",
                text.contains("ระดับการตรวจจับ"),
            )
        }
        assertTrue(applied.telegramTh!!.contains("โหมดยานพาหนะ"))
        assertTrue(help.telegramTh!!.contains("คำสั่งเดิม"))
    }
}

package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.location.LocationAnswer
import com.example.motorcycleantitheftsensor.sensor.LocationStartFailure
import java.util.Locale

/**
 * Turns a lookup into the message the owner reads while their vehicle is somewhere else.
 *
 * Written for somebody standing in a car park who has just realised the bike is gone. That
 * rules out a bare pair of coordinates: they are unreadable under stress and unusable without
 * retyping them somewhere. A map link is one tap to navigation, and works in every Telegram
 * client without the app needing a second way to send messages.
 *
 * The age of the fix is never left out and never buried. A ten-minute-old position sent as if
 * it were current is the one thing here that could send somebody to the wrong place, and the
 * difference between "it is here" and "it was here ten minutes ago" is the difference between
 * a search and a wild goose chase.
 */
object LocationAnswerFormatter {

    fun format(answer: LocationAnswer): String = when (answer) {
        is LocationAnswer.Fresh -> buildString {
            append("📍 ตำแหน่งล่าสุด (เพิ่งวัดได้)\n")
            append(coordinates(answer.fix.latitude, answer.fix.longitude))
            append("\nความแม่นยำ ~").append(answer.fix.accuracyMeters.toInt()).append(" เมตร")
            append("\n\n").append(mapLink(answer.fix.latitude, answer.fix.longitude))
        }

        is LocationAnswer.LastKnown -> buildString {
            append("📍 ตำแหน่งที่เครื่องจำไว้ล่าสุด\n")
            append(coordinates(answer.fix.latitude, answer.fix.longitude))
            append("\nความแม่นยำ ~").append(answer.fix.accuracyMeters.toInt()).append(" เมตร")
            append("\n⏱ ข้อมูลนี้เก่า ").append(age(answer.ageMs)).append(" — ตอนนี้อาจไม่ได้อยู่ตรงนี้แล้ว")
            append("\n\n").append(mapLink(answer.fix.latitude, answer.fix.longitude))
            append("\n\nจับสัญญาณใหม่ไม่ได้ในตอนนี้ อาจอยู่ในที่อับสัญญาณ ลองสั่ง /where ซ้ำอีกครั้ง")
        }

        is LocationAnswer.Unavailable -> when (answer.reason) {
            LocationStartFailure.PERMISSION_DENIED ->
                "❌ ระบุตำแหน่งไม่ได้: แอปไม่ได้รับสิทธิ์เข้าถึงตำแหน่ง\nต้องเปิดสิทธิ์ที่ตัวเครื่อง ซึ่งทำจากระยะไกลไม่ได้"

            LocationStartFailure.NO_PROVIDERS_AVAILABLE ->
                "❌ ระบุตำแหน่งไม่ได้: ระบบระบุตำแหน่งของเครื่องถูกปิดอยู่\nต้องเปิดที่ตัวเครื่อง ซึ่งทำจากระยะไกลไม่ได้"

            else ->
                "❌ ยังจับตำแหน่งไม่ได้ และเครื่องไม่มีตำแหน่งเก่าเก็บไว้เลย\n" +
                    "มักเกิดเมื่ออยู่ในอาคารหรือที่อับสัญญาณ ลองสั่ง /where ซ้ำอีกครั้งใน 1–2 นาที"
        }
    }

    private fun coordinates(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.6f, %.6f", latitude, longitude)

    /**
     * A plain `google.com/maps` link rather than a `geo:` URI: the owner may well be reading
     * this on a desktop, and a `geo:` link opens nothing there.
     */
    private fun mapLink(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", latitude, longitude)

    private fun age(ageMs: Long): String {
        val minutes = ageMs / 60_000L
        return when {
            minutes < 1L -> "ไม่ถึง 1 นาที"
            minutes < 60L -> "$minutes นาที"
            minutes < 60L * 24L -> "${minutes / 60L} ชั่วโมง ${minutes % 60L} นาที"
            else -> "${minutes / (60L * 24L)} วัน"
        }
    }
}

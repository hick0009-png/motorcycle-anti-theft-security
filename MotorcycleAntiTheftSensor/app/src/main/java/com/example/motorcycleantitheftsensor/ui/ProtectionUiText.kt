package com.example.motorcycleantitheftsensor.ui

/**
 * Thai owner-facing permission wording (profile-aware Thai UX, Task 2). Unknown
 * permissions fall back to a neutral Thai phrase and never expose the raw package
 * string to the owner.
 */
internal fun friendlyPermissionName(permission: String): String = when (permission.permissionKey()) {
    "RECORD_AUDIO" -> "ไมโครโฟน"
    "POST_NOTIFICATIONS" -> "การแจ้งเตือน"
    "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION" -> "ตำแหน่ง"
    "SEND_SMS", "RECEIVE_SMS" -> "SMS"
    else -> "สิทธิ์อื่น"
}

internal fun friendlyPermissionExplanation(permission: String): String =
    when (permission.permissionKey()) {
        "RECORD_AUDIO" ->
            "ยังไม่ได้ให้สิทธิ์ไมโครโฟน การตรวจจับเสียงผิดปกติจะใช้ไม่ได้"
        "POST_NOTIFICATIONS" ->
            "ยังไม่ได้ให้สิทธิ์การแจ้งเตือน การแจ้งเหตุอาจไม่แสดง"
        "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION" ->
            "ยังไม่ได้ให้สิทธิ์ตำแหน่ง การติดตามตำแหน่งจะใช้ไม่ได้"
        "SEND_SMS", "RECEIVE_SMS" ->
            "ยังไม่ได้ให้สิทธิ์ SMS ช่องทางสำรองผ่าน SMS จะใช้ไม่ได้"
        else -> "ยังไม่ได้ให้สิทธิ์นี้ ฟีเจอร์บางอย่างอาจใช้ไม่ได้"
    }

private fun String.permissionKey(): String = substringAfterLast('.')

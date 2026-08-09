package com.example.motorcycleantitheftsensor.ui

internal fun friendlyPermissionName(permission: String): String = when (permission.permissionKey()) {
    "RECORD_AUDIO" -> "Microphone"
    "POST_NOTIFICATIONS" -> "Notifications"
    else -> permission.permissionKey()
        .lowercase()
        .replace('_', ' ')
        .replaceFirstChar(Char::uppercase)
}

internal fun friendlyPermissionExplanation(permission: String): String =
    when (permission.permissionKey()) {
        "RECORD_AUDIO" ->
            "Microphone access is missing. Vibration detection will be unavailable."
        "POST_NOTIFICATIONS" ->
            "Notification access is missing. Protection alerts may not appear."
        else -> "${friendlyPermissionName(permission)} access is missing."
    }

private fun String.permissionKey(): String = substringAfterLast('.')

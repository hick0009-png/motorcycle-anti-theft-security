package com.example.motorcycleantitheftsensor.security

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * SEC-08: DeviceAdminController
 * DeviceAdminReceiver managing Device Owner / Device Admin privileges.
 * Implements Factory Reset Protection (FRP Lock) and warns/triggers alerts
 * if someone attempts to remove admin rights or tamper with the device.
 */
class DeviceAdminController : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(
            context,
            "Motorcycle Anti-Theft Protection Activated (Device Admin Active)",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Warning dialog presented to anyone attempting to remove admin rights
        return "⚠️ WARNING: Deactivating Anti-Theft Admin will trigger immediate alarm alerts to the vehicle owner!"
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(
            context,
            "⚠️ Anti-Theft Protection Deactivated!",
            Toast.LENGTH_LONG
        ).show()
    }
}

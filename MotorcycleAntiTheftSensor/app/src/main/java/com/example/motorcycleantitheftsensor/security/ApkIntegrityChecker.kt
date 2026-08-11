package com.example.motorcycleantitheftsensor.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.security.MessageDigest

/**
 * SEC-07: ApkIntegrityChecker
 * Validates APK signature SHA-256 checksum and checks for attached debuggers or tampered builds.
 */
class ApkIntegrityChecker(private val context: Context) {

    /**
     * Checks if the app is currently running under a debugger.
     */
    fun isDebuggerAttached(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }

    /**
     * Computes the SHA-256 fingerprint of the installed APK's signing certificate.
     */
    fun getApkSignatureSha256(): String? {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            }

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (signatures.isNullOrEmpty()) return null

            val certBytes = signatures[0].toByteArray()
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(certBytes)
            digest.joinToString(":") { "%02X".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compares installed signature fingerprint against an expected release fingerprint.
     */
    fun verifySignature(expectedSha256: String): Boolean {
        val currentSignature = getApkSignatureSha256() ?: return false
        return currentSignature.equals(expectedSha256.trim(), ignoreCase = true)
    }
}

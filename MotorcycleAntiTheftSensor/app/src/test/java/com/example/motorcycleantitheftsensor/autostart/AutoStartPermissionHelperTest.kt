package com.example.motorcycleantitheftsensor.autostart

import android.content.Context
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AutoStartPermissionHelperTest {

    @Test
    fun getInstanceReturnsNonNullSingleton() {
        val helper = AutoStartPermissionHelper.getInstance()
        assertNotNull(helper)
    }

    @Test
    fun getOemTargetsGeneratesAccurateTargetsForMajorOems() {
        val helper = AutoStartPermissionHelper.getInstance()

        // Huawei & Honor
        val huaweiTargets = helper.getOemTargets(manufacturer = "huawei", brand = "huawei")
        assertTrue(huaweiTargets.isNotEmpty())
        assertEquals("com.huawei.systemmanager", huaweiTargets.first().packageName)
        assertEquals("com.huawei.systemmanager.optimize.process.ProtectActivity", huaweiTargets.first().className)

        val honorTargets = helper.getOemTargets(manufacturer = "honor", brand = "honor")
        assertTrue(honorTargets.any { it.packageName == "com.hihonor.systemmanager" })

        // Xiaomi / Redmi / Poco
        val xiaomiTargets = helper.getOemTargets(manufacturer = "xiaomi", brand = "redmi")
        assertTrue(xiaomiTargets.isNotEmpty())
        assertEquals("com.miui.securitycenter", xiaomiTargets.first().packageName)

        // Oppo / Realme
        val oppoTargets = helper.getOemTargets(manufacturer = "oppo", brand = "realme")
        assertTrue(oppoTargets.isNotEmpty())
        assertEquals("com.coloros.safecenter", oppoTargets.first().packageName)

        // OnePlus (OxygenOS / ColorOS unified)
        val onePlusTargets = helper.getOemTargets(manufacturer = "oneplus", brand = "oneplus")
        assertTrue(onePlusTargets.any { it.packageName == "com.oplus.safecenter" || it.packageName == "com.oneplus.security" })

        // Vivo / iQOO
        val vivoTargets = helper.getOemTargets(manufacturer = "vivo", brand = "iqoo")
        assertTrue(vivoTargets.isNotEmpty())
        assertEquals("com.vivo.permissionmanager", vivoTargets.first().packageName)

        // Samsung
        val samsungTargets = helper.getOemTargets(manufacturer = "samsung", brand = "samsung")
        assertTrue(samsungTargets.isNotEmpty())
        assertEquals("com.samsung.android.lool", samsungTargets.first().packageName)
        assertTrue(samsungTargets.any { it.packageName == "com.samsung.android.sm_cn" })

        // Asus
        val asusTargets = helper.getOemTargets(manufacturer = "asus", brand = "asus")
        assertTrue(asusTargets.isNotEmpty())
        assertEquals("com.asus.mobilemanager", asusTargets.first().packageName)

        // Transsion
        val transsionTargets = helper.getOemTargets(manufacturer = "infinix", brand = "infinix")
        assertTrue(transsionTargets.isNotEmpty())
        assertEquals("com.transsion.phonemanager", transsionTargets.first().packageName)

        // Unknown standard Android
        val unknownTargets = helper.getOemTargets(manufacturer = "google", brand = "pixel")
        assertTrue(unknownTargets.isEmpty())
    }

    @Test
    fun backgroundKeepAliveManagerCheckStatusReturnsNonNull() {
        val mockContext = mock(Context::class.java)
        val mockPowerManager = mock(PowerManager::class.java)

        `when`(mockContext.packageName).thenReturn("com.example.motorcycleantitheftsensor")
        `when`(mockContext.getSystemService(Context.POWER_SERVICE)).thenReturn(mockPowerManager)
        `when`(mockPowerManager.isIgnoringBatteryOptimizations("com.example.motorcycleantitheftsensor"))
            .thenReturn(true)

        val status = BackgroundKeepAliveManager.checkStatus(mockContext)
        assertNotNull(status)
        assertTrue(status.isBatteryOptimizedIgnored)
        assertTrue(status.isFullyConfigured)
    }

    @Test
    fun keepAliveStatusIsFullyConfiguredLogic() {
        val fullyConfigured = KeepAliveStatus(
            isBatteryOptimizedIgnored = true,
            isOemAutoStartAvailable = false,
            manufacturer = "Google",
        )
        assertTrue(fullyConfigured.isFullyConfigured)

        val notIgnored = KeepAliveStatus(
            isBatteryOptimizedIgnored = false,
            isOemAutoStartAvailable = false,
            manufacturer = "Google",
        )
        assertFalse(notIgnored.isFullyConfigured)

        val oemConfigured = KeepAliveStatus(
            isBatteryOptimizedIgnored = true,
            isOemAutoStartAvailable = true,
            manufacturer = "Huawei",
        )
        assertTrue(oemConfigured.isFullyConfigured)
    }
}

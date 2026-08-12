package com.example.motorcycleantitheftsensor.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramNetworkSecurityConfigTest {
    private val source = File("src/main/res/xml/network_security_config.xml").readText()

    @Test
    fun telegramUsesHttpsOnlyPrimaryAndBackupPins() {
        val pins = Regex("""<pin digest="SHA-256">([^<]+)</pin>""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()

        assertTrue(source.contains("cleartextTrafficPermitted=\"false\""))
        assertTrue(source.contains("<domain includeSubdomains=\"true\">api.telegram.org</domain>"))
        assertTrue(source.contains("<pin-set expiration=\"2027-12-01\">"))
        assertEquals(
            listOf(
                "AgyCmTysFOI6aQCSyQJ+QIXpnGn0v7n+D+mv6jWAtQc=",
                "8Rw90Ej3Ttt8RRkrg+WYDS9n7IS03bk5bjP/UXPtaY8=",
            ),
            pins,
        )
        assertFalse(source.contains("Y9mvm0exBk1JoQ557r9S1aB2cBZn6EesEARfNSc8dOf="))
    }
}

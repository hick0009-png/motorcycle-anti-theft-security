package com.example.motorcycleantitheftsensor.telephony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncryptedSmsCodecTest {
    @Test
    fun eachEncryptionUsesDistinctPayloadAndDecrypts() {
        val first = EncryptedSmsCodec.encryptSmsPayload("alert", "secret")
        val second = EncryptedSmsCodec.encryptSmsPayload("alert", "secret")
        assertNotEquals(first, second)
        assertEquals("alert", EncryptedSmsCodec.decryptSmsPayload(first, "secret"))
    }

    @Test
    fun modifiedCiphertextIsRejected() {
        val encrypted = EncryptedSmsCodec.encryptSmsPayload("alert", "secret")
        assertNull(EncryptedSmsCodec.decryptSmsPayload(encrypted.dropLast(1) + "A", "secret"))
    }
}

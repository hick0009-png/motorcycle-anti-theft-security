package com.example.motorcycleantitheftsensor.telephony

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedSmsCodecTest {
    private val key = EncryptedSmsCodec.generateKeyBase64()

    @Test
    fun eachEncryptionUsesDistinctPayloadAndDecrypts() {
        val first = EncryptedSmsCodec.encryptSmsPayload("alert", key)
        val second = EncryptedSmsCodec.encryptSmsPayload("alert", key)
        assertNotEquals(first, second)
        assertEquals("alert", EncryptedSmsCodec.decryptSmsPayload(first, key))
    }

    @Test
    fun modifiedCiphertextIsRejected() {
        val encrypted = EncryptedSmsCodec.encryptSmsPayload("alert", key)
        val replacement = if (encrypted.last() == 'A') 'B' else 'A'
        assertNull(EncryptedSmsCodec.decryptSmsPayload(encrypted.dropLast(1) + replacement, key))
    }

    @Test
    fun generatedKeysAre256BitAndDistinct() {
        val other = EncryptedSmsCodec.generateKeyBase64()
        assertNotEquals(key, other)
        assertTrue(EncryptedSmsCodec.isValidKeyBase64(key))
        assertTrue(EncryptedSmsCodec.isValidKeyBase64(other))
    }

    @Test
    fun passphrasesAreNotAcceptedAsKeyMaterial() {
        assertFalse(EncryptedSmsCodec.isValidKeyBase64("moto1234"))
        assertFalse(EncryptedSmsCodec.isValidKeyBase64(""))
        // Correctly padded Base64, but only 30 bytes — one byte short of an AES-256 key.
        assertFalse(EncryptedSmsCodec.isValidKeyBase64("A".repeat(40)))
    }

    @Test
    fun encryptingUnderAPassphraseFailsLoudlyInsteadOfProducingAWeakMessage() {
        val failure = runCatching { EncryptedSmsCodec.encryptSmsPayload("alert", "moto1234") }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun messagesSentUnderTheOldPassphraseSchemeStillOpen() {
        val legacyMessage = legacyEncrypt("ALERT:VIBRATION|TIME:1787245200000", "moto1234")
        assertTrue(legacyMessage.startsWith("[ENC_ALARM_V2]"))
        assertEquals(
            "ALERT:VIBRATION|TIME:1787245200000",
            EncryptedSmsCodec.decryptSmsPayload(legacyMessage, "moto1234"),
        )
        // The device key must not open a legacy message, so /decode falls through to the old one.
        assertNull(EncryptedSmsCodec.decryptSmsPayload(legacyMessage, key))
    }

    @Test
    fun unknownPrefixIsRejectedRatherThanGuessedAt() {
        val encrypted = EncryptedSmsCodec.encryptSmsPayload("alert", key)
        val reprefixed = "[ENC_ALARM_V9]" + encrypted.removePrefix("[ENC_ALARM_V3]")
        assertNull(EncryptedSmsCodec.decryptSmsPayload(reprefixed, key))
        assertNull(EncryptedSmsCodec.decryptSmsPayload("plain text alert", key))
    }

    /** Reproduces exactly what a pre-randomised-key build put on the wire. */
    private fun legacyEncrypt(plainText: String, passphrase: String): String {
        val keySpec = SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(passphrase.toByteArray(Charsets.UTF_8)),
            "AES",
        )
        val nonce = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
        val combined = nonce + cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return "[ENC_ALARM_V2]" + java.util.Base64.getEncoder().encodeToString(combined)
    }
}

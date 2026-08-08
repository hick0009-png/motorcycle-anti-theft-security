package com.example.motorcycleantitheftsensor.telephony

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec

/**
 * SEC-04: EncryptedSmsCodec
 * Encrypts SMS payload (GPS coordinates + timestamp + alert type) using AES-256-GCM + Base64.
 * Prevents cleartext SMS interception. The Telegram Bot server decodes incoming forwarded SMS.
 */
object EncryptedSmsCodec {

    private const val PREFIX = "[ENC_ALARM_V2]"
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val NONCE_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128

    /**
     * Encrypts plain payload into an SMS-friendly string prefixed with [ENC_ALARM].
     */
    fun encryptSmsPayload(plainText: String, secretKeyPass: String): String {
        val keySpec = deriveKey(secretKeyPass)
        val nonce = ByteArray(NONCE_SIZE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = nonce + encryptedBytes
        val base64Text = Base64Codec.encode(combined)
        return "$PREFIX$base64Text"
    }

    /**
     * Decrypts an incoming SMS payload starting with [ENC_ALARM].
     */
    fun decryptSmsPayload(smsContent: String, secretKeyPass: String): String? {
        if (!smsContent.startsWith(PREFIX)) return null
        val base64Data = smsContent.substring(PREFIX.length).trim()
        return try {
            val combined = Base64Codec.decode(base64Data)
            if (combined.size <= NONCE_SIZE_BYTES) return null
            val nonce = combined.copyOfRange(0, NONCE_SIZE_BYTES)
            val encryptedBytes = combined.copyOfRange(NONCE_SIZE_BYTES, combined.size)
            val keySpec = deriveKey(secretKeyPass)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_SIZE_BITS, nonce))
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun deriveKey(password: String): SecretKeySpec = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8)),
        "AES"
    )

    private object Base64Codec {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        fun encode(bytes: ByteArray): String {
            val out = StringBuilder((bytes.size + 2) / 3 * 4)
            var index = 0
            while (index < bytes.size) {
                val first = bytes[index++].toInt() and 0xff
                val second = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
                val third = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
                out.append(ALPHABET[first ushr 2])
                out.append(ALPHABET[((first and 3) shl 4) or if (second >= 0) second ushr 4 else 0])
                out.append(if (second >= 0) ALPHABET[((second and 15) shl 2) or if (third >= 0) third ushr 6 else 0] else '=')
                out.append(if (third >= 0) ALPHABET[third and 63] else '=')
            }
            return out.toString()
        }

        fun decode(value: String): ByteArray {
            require(value.length % 4 == 0)
            val out = ArrayList<Byte>(value.length * 3 / 4)
            var index = 0
            while (index < value.length) {
                val a = value[index++].digit()
                val b = value[index++].digit()
                val c = value[index++]
                val d = value[index++]
                require(a >= 0 && b >= 0)
                val cValue = if (c == '=') 0 else c.digit().also { require(it >= 0) }
                val dValue = if (d == '=') 0 else d.digit().also { require(it >= 0) }
                out.add(((a shl 2) or (b ushr 4)).toByte())
                if (c != '=') out.add((((b and 15) shl 4) or (cValue ushr 2)).toByte())
                if (d != '=') out.add((((cValue and 3) shl 6) or dValue).toByte())
            }
            return out.toByteArray()
        }

        private fun Char.digit(): Int = ALPHABET.indexOf(this)
    }
}

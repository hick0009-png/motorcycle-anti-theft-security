package com.example.motorcycleantitheftsensor.telephony

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec

/**
 * SEC-04: EncryptedSmsCodec
 * Encrypts the SMS fallback payload (incident copy + coordinates + timestamp) using
 * AES-256-GCM + Base64, so an intercepted alert reveals nothing.
 *
 * Two on-the-wire schemes exist, told apart by the prefix:
 *
 *  - [PREFIX_V3] carries a payload sealed under a **random 256-bit device key**. This is
 *    the only scheme this codec will produce. The key never leaves the device: the sensor
 *    phone encrypts, and the same phone decrypts when the owner forwards the ciphertext
 *    back through `/decode`, so there is nothing for a human to type or remember.
 *  - [PREFIX_V2] is the legacy scheme, whose key was `SHA-256(passphrase)` over a
 *    passphrase the owner typed. That is a single unsalted hash iteration, so a short
 *    passphrase fell to offline brute force from one intercepted message. It is kept
 *    **decrypt-only** so alerts already sitting in the owner's inbox stay readable.
 */
object EncryptedSmsCodec {

    private const val PREFIX_V2 = "[ENC_ALARM_V2]"
    private const val PREFIX_V3 = "[ENC_ALARM_V3]"
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val NONCE_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128
    private const val KEY_SIZE_BYTES = 32

    /** A fresh random 256-bit key, Base64 encoded for storage in encrypted preferences. */
    fun generateKeyBase64(): String =
        Base64Codec.encode(ByteArray(KEY_SIZE_BYTES).also(SecureRandom()::nextBytes))

    /** True when [keyBase64] decodes to exactly the 32 raw bytes an AES-256 key needs. */
    fun isValidKeyBase64(keyBase64: String): Boolean = rawKeyOrNull(keyBase64) != null

    /**
     * Seals [plainText] under the random device key [keyBase64] and returns an
     * SMS-friendly string prefixed with [PREFIX_V3].
     *
     * Requires a key from [generateKeyBase64]; a passphrase is rejected rather than
     * quietly stretched into a weak key.
     */
    fun encryptSmsPayload(plainText: String, keyBase64: String): String {
        val rawKey = requireNotNull(rawKeyOrNull(keyBase64)) {
            "SMS key must be a Base64 256-bit key from generateKeyBase64()"
        }
        val keySpec = SecretKeySpec(rawKey, "AES")
        val nonce = ByteArray(NONCE_SIZE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return "$PREFIX_V3${Base64Codec.encode(nonce + encryptedBytes)}"
    }

    /**
     * Opens an incoming payload of either scheme, picking the key derivation from the
     * prefix. Returns null when the prefix is unknown, the Base64 is malformed, or the
     * GCM tag does not authenticate under [key].
     */
    fun decryptSmsPayload(smsContent: String, key: String): String? {
        val keySpec = when {
            smsContent.startsWith(PREFIX_V3) -> rawKeyOrNull(key)?.let { SecretKeySpec(it, "AES") }
            smsContent.startsWith(PREFIX_V2) -> legacyKeySpec(key)
            else -> null
        } ?: return null
        val prefixLength = if (smsContent.startsWith(PREFIX_V3)) PREFIX_V3.length else PREFIX_V2.length
        val base64Data = smsContent.substring(prefixLength).trim()
        return try {
            val combined = Base64Codec.decode(base64Data)
            if (combined.size <= NONCE_SIZE_BYTES) return null
            val nonce = combined.copyOfRange(0, NONCE_SIZE_BYTES)
            val encryptedBytes = combined.copyOfRange(NONCE_SIZE_BYTES, combined.size)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_SIZE_BITS, nonce))
            String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun rawKeyOrNull(keyBase64: String): ByteArray? = try {
        Base64Codec.decode(keyBase64.trim()).takeIf { it.size == KEY_SIZE_BYTES }
    } catch (_: Exception) {
        null
    }

    /** Legacy derivation, retained only to open messages sent before the key was randomised. */
    private fun legacyKeySpec(password: String): SecretKeySpec = SecretKeySpec(
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

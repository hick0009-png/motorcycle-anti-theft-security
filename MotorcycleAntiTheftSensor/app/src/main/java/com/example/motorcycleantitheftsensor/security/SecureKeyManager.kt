package com.example.motorcycleantitheftsensor.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SEC-01: SecureKeyManager
 * Hardware-backed AES-256-GCM Master Key Manager using Android Keystore System.
 * Ensures keys cannot be extracted from the device hardware.
 */
class SecureKeyManager(private val context: Context) {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TINK_MASTER_KEY_ALIAS = "motorcycle_anti_theft_tink_master_key"
        private const val RAW_CIPHER_KEY_ALIAS = "motorcycle_anti_theft_raw_cipher_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val MIN_COMBINED_SIZE = 28 // 12-byte IV + 16-byte GCM Tag
    }

    val masterKey: MasterKey by lazy {
        MasterKey.Builder(context, TINK_MASTER_KEY_ALIAS)
            .setKeyGenParameterSpec(
                KeyGenParameterSpec.Builder(
                    TINK_MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false) // Continuous background operation
                    .build()
            )
            .build()
    }

    /**
     * Encrypts raw data using AES-256-GCM hardware key.
     * Returns IV concatenated with Ciphertext.
     */
    fun encrypt(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = getSecretKey()
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encryptedData = cipher.doFinal(plainText)
        
        // Combine IV (12 bytes) + Encrypted Payload
        val combined = ByteArray(iv.size + encryptedData.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encryptedData, 0, combined, iv.size, encryptedData.size)
        return combined
    }

    /**
     * Decrypts combined IV + Ciphertext using AES-256-GCM hardware key.
     */
    fun decrypt(combinedData: ByteArray): ByteArray {
        require(combinedData.size >= MIN_COMBINED_SIZE) { "Invalid encrypted payload size" }
        val iv = ByteArray(12)
        val ciphertext = ByteArray(combinedData.size - 12)
        System.arraycopy(combinedData, 0, iv, 0, 12)
        System.arraycopy(combinedData, 12, ciphertext, 0, ciphertext.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = getSecretKey()
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(ciphertext)
    }

    @Synchronized
    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(RAW_CIPHER_KEY_ALIAS, null) as? SecretKey
            ?: generateSecretKey()
    }

    private fun generateSecretKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        val keyGenSpec = KeyGenParameterSpec.Builder(
            RAW_CIPHER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }
}

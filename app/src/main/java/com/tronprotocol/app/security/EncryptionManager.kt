package com.tronprotocol.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed encryption manager using Android KeyStore
 * Provides AES-256-GCM encryption for sensitive data
 *
 * Inspired by ToolNeuron's Memory Vault encryption architecture
 */
class EncryptionManager constructor() {

    private val keyStore: KeyStore?
    private var softwareKey: SecretKey? = null

    init {
        keyStore = try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        } catch (e: Exception) {
            Log.w(TAG, "AndroidKeyStore not available, using software fallback: ${e.message}")
            null
        }
        ensureMasterKey()
    }

    private fun ensureMasterKey() {
        if (keyStore != null) {
            try {
                if (!keyStore.containsAlias(KEY_ALIAS)) {
                    val keyGenerator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        "AndroidKeyStore"
                    )

                    val spec = KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build()

                    keyGenerator.init(spec)
                    keyGenerator.generateKey()

                    Log.d(TAG, "Master key generated in hardware-backed KeyStore")
                }
                return
            } catch (e: Exception) {
                Log.w(TAG, "Hardware key generation failed, using software fallback: ${e.message}")
            }
        }
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        softwareKey = keyGen.generateKey()
    }

    private fun getMasterKey(): SecretKey {
        if (keyStore != null) {
            try {
                val key = keyStore.getKey(KEY_ALIAS, null)
                if (key != null) return key as SecretKey
            } catch (_: Exception) { }
        }
        return softwareKey ?: throw IllegalStateException("No encryption key available")
    }

    /**
     * Encrypt data using AES-256-GCM
     * @param data Plain data to encrypt
     * @return Encrypted data with IV prepended
     */
    @Throws(Exception::class)
    fun encrypt(data: ByteArray): ByteArray {
        val startTime = System.currentTimeMillis()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getMasterKey())

        val iv = cipher.iv
        val encrypted = cipher.doFinal(data)

        // Prepend IV to encrypted data
        val result = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(encrypted, 0, result, iv.size, encrypted.size)

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Encrypted ${data.size} bytes in ${duration}ms")

        return result
    }

    /**
     * Decrypt data using AES-256-GCM
     * @param encryptedData Encrypted data with IV prepended
     * @return Decrypted plain data
     */
    @Throws(Exception::class)
    fun decrypt(encryptedData: ByteArray): ByteArray {
        require(encryptedData.size > GCM_IV_LENGTH) {
            "Invalid encrypted data: size ${encryptedData.size} is too small (minimum ${GCM_IV_LENGTH + 1})"
        }

        val startTime = System.currentTimeMillis()

        // Extract IV from beginning
        val iv = encryptedData.copyOfRange(0, GCM_IV_LENGTH)

        // Extract encrypted data
        val ciphertext = encryptedData.copyOfRange(GCM_IV_LENGTH, encryptedData.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), spec)

        val decrypted = try {
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            Log.e(TAG, "SECURITY: GCM authentication tag verification failed — data may have been tampered with")
            throw SecurityException("Decryption authentication failed: data integrity compromised", e)
        }

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Decrypted ${encryptedData.size} bytes in ${duration}ms")

        return decrypted
    }

    /**
     * Encrypt a string using UTF-8 encoding
     */
    @Throws(Exception::class)
    fun encryptString(plaintext: String): ByteArray =
        encrypt(plaintext.toByteArray(Charsets.UTF_8))

    /**
     * Decrypt to a string using UTF-8 encoding
     */
    @Throws(Exception::class)
    fun decryptString(encryptedData: ByteArray): String =
        String(decrypt(encryptedData), Charsets.UTF_8)

    companion object {
        private const val TAG = "EncryptionManager"
        private const val KEY_ALIAS = "TronProtocolMasterKey"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
    }
}

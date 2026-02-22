package com.tronprotocol.app.security

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Secure storage for sensitive data using hardware-backed encryption
 *
 * Inspired by ToolNeuron's Memory Vault secure storage architecture
 */
class SecureStorage @Throws(Exception::class) constructor(private val context: Context) {

    private val encryptionManager: EncryptionManager = EncryptionManager()
    private val storageDir: File = File(context.filesDir, STORAGE_DIR).apply {
        if (!exists()) {
            mkdirs()
        }
    }

    /**
     * Store encrypted data with a key
     */
    @Throws(Exception::class)
    fun store(key: String, data: String) {
        val encryptedData = encryptionManager.encryptString(data)
        val file = File(storageDir, sanitizeKey(key))

        FileOutputStream(file).use { fos ->
            fos.write(encryptedData)
        }

        Log.d(TAG, "Stored encrypted data for key: $key")
    }

    /**
     * Store encrypted binary data with a key
     */
    @Throws(Exception::class)
    fun storeBytes(key: String, data: ByteArray) {
        val encryptedData = encryptionManager.encrypt(data)
        val file = File(storageDir, sanitizeKey(key))

        FileOutputStream(file).use { fos ->
            fos.write(encryptedData)
        }

        Log.d(TAG, "Stored encrypted bytes for key: $key")
    }

    /**
     * Retrieve and decrypt data by key
     */
    @Throws(Exception::class)
    fun retrieve(key: String): String? {
        val file = File(storageDir, sanitizeKey(key))

        if (!file.exists()) {
            return null
        }

        val encryptedData = readFile(file)
        return encryptionManager.decryptString(encryptedData)
    }

    /**
     * Retrieve and decrypt binary data by key
     */
    @Throws(Exception::class)
    fun retrieveBytes(key: String): ByteArray? {
        val file = File(storageDir, sanitizeKey(key))

        if (!file.exists()) {
            return null
        }

        val encryptedData = readFile(file)
        return encryptionManager.decrypt(encryptedData)
    }

    /**
     * Check if a key exists
     */
    fun exists(key: String): Boolean {
        val file = File(storageDir, sanitizeKey(key))
        return file.exists()
    }

    /**
     * Delete stored data by key
     */
    fun delete(key: String): Boolean {
        val file = File(storageDir, sanitizeKey(key))
        return if (file.exists()) {
            val deleted = file.delete()
            Log.d(TAG, "Deleted data for key: $key")
            deleted
        } else {
            false
        }
    }

    /**
     * Clear all stored data
     */
    fun clearAll() {
        storageDir.listFiles()?.forEach { file ->
            file.delete()
        }
        Log.d(TAG, "Cleared all secure storage")
    }

    private fun sanitizeKey(key: String): String =
        // Replace special characters that aren't safe for filenames
        key.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    @Throws(IOException::class)
    private fun readFile(file: File): ByteArray {
        val fileSize = file.length()
        if (fileSize > MAX_FILE_SIZE) {
            throw IOException("File exceeds maximum allowed size: ${fileSize} bytes (max $MAX_FILE_SIZE)")
        }
        if (fileSize == 0L) {
            return ByteArray(0)
        }
        FileInputStream(file).use { fis ->
            val data = ByteArray(fileSize.toInt())
            var offset = 0
            while (offset < data.size) {
                val read = fis.read(data, offset, data.size - offset)
                if (read < 0) break
                offset += read
            }
            return data
        }
    }

    companion object {
        private const val TAG = "SecureStorage"
        private const val STORAGE_DIR = "secure_data"
        /** Maximum file size for secure storage reads (50 MB). Prevents OOM on corrupted files. */
        private const val MAX_FILE_SIZE = 50L * 1024 * 1024
    }
}

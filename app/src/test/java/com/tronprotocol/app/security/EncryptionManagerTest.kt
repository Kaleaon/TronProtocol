package com.tronprotocol.app.security

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EncryptionManagerTest {

    private lateinit var manager: EncryptionManager

    @Before
    fun setUp() {
        manager = EncryptionManager()
    }

    // --- Byte array round-trip ---

    @Test
    fun encryptDecrypt_roundTrip_preservesData() {
        val original = "Hello, TronProtocol!".toByteArray(Charsets.UTF_8)
        val encrypted = manager.encrypt(original)
        val decrypted = manager.decrypt(encrypted)
        assertArrayEquals(original, decrypted)
    }

    // --- String round-trip ---

    @Test
    fun encryptDecryptString_roundTrip_preservesText() {
        val original = "Sensitive user data with unicode: \u00e9\u00e8\u00ea \u2603"
        val encrypted = manager.encryptString(original)
        val decrypted = manager.decryptString(encrypted)
        assertEquals(original, decrypted)
    }

    // --- Encrypted output differs from plaintext ---

    @Test
    fun encrypt_outputDiffersFromPlaintext() {
        val original = "plaintext data".toByteArray(Charsets.UTF_8)
        val encrypted = manager.encrypt(original)
        assertFalse(
            "Encrypted output should differ from plaintext",
            original.contentEquals(encrypted)
        )
    }

    // --- Each encryption produces different ciphertext (unique IV) ---

    @Test
    fun encrypt_producesDifferentCiphertextEachTime() {
        val original = "same data every time".toByteArray(Charsets.UTF_8)
        val encrypted1 = manager.encrypt(original)
        val encrypted2 = manager.encrypt(original)
        assertFalse(
            "Two encryptions of the same data should produce different ciphertext due to unique IV",
            encrypted1.contentEquals(encrypted2)
        )
    }

    // --- Decrypt with corrupted data throws SecurityException ---

    @Test(expected = SecurityException::class)
    fun decrypt_withCorruptedData_throwsSecurityException() {
        val original = "data to corrupt".toByteArray(Charsets.UTF_8)
        val encrypted = manager.encrypt(original)

        // Corrupt the ciphertext portion (after the 12-byte IV)
        // Flip several bytes to ensure the GCM authentication tag fails
        for (i in 12 until minOf(encrypted.size, 20)) {
            encrypted[i] = (encrypted[i].toInt() xor 0xFF).toByte()
        }

        manager.decrypt(encrypted)
    }

    // --- Decrypt with too-short data throws IllegalArgumentException ---

    @Test(expected = IllegalArgumentException::class)
    fun decrypt_withTooShortData_throwsIllegalArgumentException() {
        // Data must be > 12 bytes (GCM_IV_LENGTH); provide exactly 12 bytes
        val tooShort = ByteArray(12) { it.toByte() }
        manager.decrypt(tooShort)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decrypt_withEmptyData_throwsIllegalArgumentException() {
        manager.decrypt(ByteArray(0))
    }

    // --- Empty data encrypt/decrypt round-trip ---

    @Test
    fun encryptDecrypt_emptyData_roundTrip() {
        val original = ByteArray(0)
        val encrypted = manager.encrypt(original)
        val decrypted = manager.decrypt(encrypted)
        assertArrayEquals(original, decrypted)
    }

    // --- Large data (10KB) encrypt/decrypt round-trip ---

    @Test
    fun encryptDecrypt_largeData_roundTrip() {
        val original = ByteArray(10 * 1024) { (it % 256).toByte() }
        val encrypted = manager.encrypt(original)
        val decrypted = manager.decrypt(encrypted)
        assertArrayEquals(original, decrypted)
    }
}

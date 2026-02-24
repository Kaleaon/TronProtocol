package com.tronprotocol.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecureStorageTest {

    private lateinit var storage: SecureStorage
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        storage = SecureStorage(context)
        storage.clearAll()
    }

    // --- Store and retrieve round-trip ---

    @Test
    fun storeAndRetrieve_roundTrip() {
        storage.store("test_key", "test_value")
        val retrieved = storage.retrieve("test_key")
        assertEquals("test_value", retrieved)
    }

    // --- Retrieve missing key returns null ---

    @Test
    fun retrieve_missingKey_returnsNull() {
        val result = storage.retrieve("nonexistent_key")
        assertNull(result)
    }

    // --- Exists returns true for stored key, false for missing ---

    @Test
    fun exists_returnsTrueForStoredKey() {
        storage.store("exists_key", "some_data")
        assertTrue(storage.exists("exists_key"))
    }

    @Test
    fun exists_returnsFalseForMissingKey() {
        assertFalse(storage.exists("missing_key"))
    }

    // --- Delete removes stored data ---

    @Test
    fun delete_removesStoredData() {
        storage.store("delete_me", "value")
        assertTrue(storage.exists("delete_me"))

        val deleted = storage.delete("delete_me")

        assertTrue(deleted)
        assertFalse(storage.exists("delete_me"))
        assertNull(storage.retrieve("delete_me"))
    }

    // --- Delete returns false for non-existent key ---

    @Test
    fun delete_returnsFalseForNonExistentKey() {
        val result = storage.delete("no_such_key")
        assertFalse(result)
    }

    // --- ClearAll removes all data ---

    @Test
    fun clearAll_removesAllData() {
        storage.store("key1", "value1")
        storage.store("key2", "value2")
        storage.store("key3", "value3")

        storage.clearAll()

        assertNull(storage.retrieve("key1"))
        assertNull(storage.retrieve("key2"))
        assertNull(storage.retrieve("key3"))
        assertFalse(storage.exists("key1"))
    }

    // --- Overwrite existing key with new value ---

    @Test
    fun store_overwritesExistingKey() {
        storage.store("overwrite_key", "original_value")
        assertEquals("original_value", storage.retrieve("overwrite_key"))

        storage.store("overwrite_key", "updated_value")
        assertEquals("updated_value", storage.retrieve("overwrite_key"))
    }

    // --- Store and retrieve bytes round-trip ---

    @Test
    fun storeBytesAndRetrieveBytes_roundTrip() {
        val original = byteArrayOf(0x00, 0x01, 0x02, 0x7F, 0x42, 0xFF.toByte())
        storage.storeBytes("bytes_key", original)

        val retrieved = storage.retrieveBytes("bytes_key")
        assertNotNull(retrieved)
        assertArrayEquals(original, retrieved)
    }

    // --- Sanitized key handles special characters ---

    @Test
    fun sanitizedKey_handlesSpecialCharacters() {
        val specialKey = "path/to\\my key with spaces"
        storage.store(specialKey, "special_value")

        val retrieved = storage.retrieve(specialKey)
        assertEquals("special_value", retrieved)
        assertTrue(storage.exists(specialKey))
    }
}

package com.tronprotocol.app.rag

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
class EncryptedCloudSyncManagerTest {

    private lateinit var manager: EncryptedCloudSyncManager
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = EncryptedCloudSyncManager(context, "test_sync_ai")
    }

    @Test
    fun `initialization does not throw`() {
        assertNotNull(manager)
    }

    @Test
    fun `getStatus returns initial status`() {
        val status = manager.getStatus()
        assertNotNull(status)
        assertTrue(status.containsKey("endpoint_configured"))
        assertTrue(status.containsKey("auto_backup"))
        assertTrue(status.containsKey("last_backup"))
        assertTrue(status.containsKey("last_status"))
    }

    @Test
    fun `getStatus shows no endpoint configured initially`() {
        val status = manager.getStatus()
        assertEquals(false, status["endpoint_configured"])
    }

    @Test
    fun `getStatus shows auto backup disabled initially`() {
        val status = manager.getStatus()
        assertEquals(false, status["auto_backup"])
    }

    @Test
    fun `last backup time is 0 before first sync`() {
        val status = manager.getStatus()
        assertEquals(0L, status["last_backup"])
    }

    @Test
    fun `last status is none before first sync`() {
        val status = manager.getStatus()
        assertEquals("none", status["last_status"])
    }

    @Test
    fun `configure sets the endpoint`() {
        manager.configure("https://example.com/sync")
        val status = manager.getStatus()
        assertEquals(true, status["endpoint_configured"])
    }

    @Test
    fun `setAutoBackup enables auto backup`() {
        manager.setAutoBackup(true)
        val status = manager.getStatus()
        assertEquals(true, status["auto_backup"])
    }

    @Test
    fun `setAutoBackup can disable auto backup`() {
        manager.setAutoBackup(true)
        manager.setAutoBackup(false)
        val status = manager.getStatus()
        assertEquals(false, status["auto_backup"])
    }

    @Test
    fun `shouldAutoBackup returns false without endpoint`() {
        manager.setAutoBackup(true)
        // No endpoint configured, so should return false even if auto backup is enabled
        assertFalse(manager.shouldAutoBackup())
    }

    @Test
    fun `shouldAutoBackup returns false when disabled`() {
        manager.configure("https://example.com/sync")
        manager.setAutoBackup(false)
        assertFalse(manager.shouldAutoBackup())
    }

    @Test
    fun `shouldAutoBackup returns true when enabled with endpoint and no recent backup`() {
        manager.configure("https://example.com/sync")
        manager.setAutoBackup(true)
        // lastBackupTimestamp is 0, so > 24 hours ago
        assertTrue(manager.shouldAutoBackup())
    }

    @Test
    fun `backup without endpoint returns failure`() {
        val ragStore = RAGStore(context, "test_sync_backup")
        val result = manager.backup(ragStore)
        assertFalse(result.success)
        assertTrue(result.message.contains("No sync endpoint configured"))
    }

    @Test
    fun `restore without endpoint returns failure`() {
        val result = manager.restore()
        assertFalse(result.success)
        assertTrue(result.message.contains("No sync endpoint configured"))
    }

    @Test
    fun `backupResult data class fields are correct`() {
        val result = EncryptedCloudSyncManager.BackupResult(true, "OK")
        assertTrue(result.success)
        assertEquals("OK", result.message)
    }

    @Test
    fun `restoreResult data class fields are correct`() {
        val result = EncryptedCloudSyncManager.RestoreResult(false, "Failed")
        assertFalse(result.success)
        assertEquals("Failed", result.message)
    }
}

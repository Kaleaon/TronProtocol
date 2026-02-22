package com.tronprotocol.app.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tronprotocol.app.phylactery.ContinuumMemorySystem
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic job for Phylactery Google Drive synchronization.
 *
 * Runs on Wi-Fi when device is idle, syncing the Phylactery memory system
 * (Episodic, Semantic, and Core Identity tiers) to cloud backup.
 *
 * Sync strategy:
 * - Export CMS state as JSON
 * - Write to local staging file
 * - Upload to Google Drive via Drive API (when configured)
 * - Verify upload integrity
 * - Record sync timestamp
 *
 * In Phase 1 (current): writes local backup files only.
 * In Phase 2: integrates Google Drive API for cloud sync.
 */
class PhylacterySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting Phylactery sync cycle")
        return try {
            val storage = SecureStorage(applicationContext)
            val syncDir = File(applicationContext.filesDir, SYNC_DIR).apply {
                if (!exists()) mkdirs()
            }

            // Phase 1: Local backup
            val cms = ContinuumMemorySystem(applicationContext)
            val exportData = cms.exportForSync()

            // Add sync metadata.
            val syncPayload = JSONObject().apply {
                put("sync_version", SYNC_VERSION)
                put("sync_timestamp", System.currentTimeMillis())
                put("device_id", getDeviceId())
                put("phylactery_data", exportData)
            }

            // Write to local staging file.
            val backupFile = File(syncDir, "phylactery_backup_${System.currentTimeMillis()}.json")
            backupFile.writeText(syncPayload.toString())

            // Also maintain a "latest" pointer for quick restore.
            val latestFile = File(syncDir, "phylactery_latest.json")
            latestFile.writeText(syncPayload.toString())

            // Prune old backups (keep last N).
            pruneOldBackups(syncDir)

            // Record sync time.
            storage.store(LAST_SYNC_KEY, System.currentTimeMillis().toString())

            Log.d(TAG, "Phylactery sync complete: ${backupFile.length()} bytes")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Phylactery sync failed", e)
            Result.retry()
        }
    }

    private fun getDeviceId(): String {
        val storage = SecureStorage(applicationContext)
        var deviceId = storage.retrieve(DEVICE_ID_KEY)
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            storage.store(DEVICE_ID_KEY, deviceId)
        }
        return deviceId
    }

    private fun pruneOldBackups(syncDir: File) {
        val backups = syncDir.listFiles { file ->
            file.name.startsWith("phylactery_backup_") && file.name.endsWith(".json")
        }?.sortedByDescending { it.lastModified() } ?: return

        if (backups.size > MAX_BACKUPS) {
            backups.drop(MAX_BACKUPS).forEach { file ->
                file.delete()
                Log.d(TAG, "Pruned old backup: ${file.name}")
            }
        }
    }

    companion object {
        private const val TAG = "PhylacterySyncWorker"
        private const val SYNC_DIR = "phylactery_sync"
        private const val SYNC_VERSION = 1
        private const val MAX_BACKUPS = 10
        private const val LAST_SYNC_KEY = "phylactery_last_sync"
        private const val DEVICE_ID_KEY = "device_sync_id"
        const val WORK_NAME = "phylactery_sync"

        /**
         * Schedule the periodic sync worker.
         * Runs every 6 hours on Wi-Fi when device is not low battery.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi only
                .setRequiresBatteryNotLow(true)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<PhylacterySyncWorker>(
                6, TimeUnit.HOURS,
                30, TimeUnit.MINUTES // flex interval
            )
                .setConstraints(constraints)
                .addTag("phylactery_sync")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
            Log.d(TAG, "Phylactery sync worker scheduled (6h interval)")
        }

        /**
         * Cancel the sync worker.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Phylactery sync worker cancelled")
        }

        /**
         * Get the timestamp of the last successful sync.
         */
        fun getLastSyncTime(context: Context): Long {
            return try {
                val storage = SecureStorage(context)
                storage.retrieve(LAST_SYNC_KEY)?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
    }
}

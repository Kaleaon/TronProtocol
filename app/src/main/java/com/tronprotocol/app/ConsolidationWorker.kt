package com.tronprotocol.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tronprotocol.app.rag.MemoryConsolidationManager
import com.tronprotocol.app.rag.RAGStore

/**
 * Periodic WorkManager task for memory consolidation.
 *
 * Replaces the long-running sleeping thread in [TronProtocolService] so
 * consolidation respects Android background execution constraints.
 */
class ConsolidationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val manager = MemoryConsolidationManager(applicationContext)
            if (!manager.isConsolidationTime()) {
                Log.d(TAG, "Skipping consolidation; conditions not met")
                return Result.success()
            }

            val ragStore = RAGStore(applicationContext, AI_ID)
            val result = manager.consolidate(ragStore)
            Log.d(TAG, "Consolidation result: $result")

            if (result.success) {
                ragStore.addMemory("Memory consolidation completed: $result", 0.8f)
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Consolidation worker failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ConsolidationWorker"
        private const val AI_ID = "tronprotocol_ai"
        const val WORK_NAME = "memory_consolidation"
    }
}

package com.tronprotocol.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        StartupDiagnostics.recordMilestone(context, "boot_receiver_invoked")

        try {
            val request = OneTimeWorkRequestBuilder<BootStartWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(BOOT_START_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            StartupDiagnostics.recordMilestone(context, "service_scheduled", "BootStartWorker enqueued")
        } catch (t: Throwable) {
            StartupDiagnostics.recordError(context, "boot_receiver_schedule_failed", t)
            Log.e(TAG, "Failed to schedule boot startup worker", t)
        }
    }

    companion object {
        const val PREFS_NAME = "tron_protocol_prefs"
        const val DEFERRED_SERVICE_START_KEY = "deferred_service_start"

        private const val TAG = "BootReceiver"
        private const val BOOT_START_WORK_NAME = "boot_start_tron_protocol"
    }
}

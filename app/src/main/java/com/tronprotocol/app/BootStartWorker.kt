package com.tronprotocol.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class BootStartWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        StartupDiagnostics.recordMilestone(applicationContext, "boot_worker_started")
        val canStartImmediately = canStartServiceImmediately(applicationContext)

        return if (canStartImmediately) {
            try {
                startTronProtocolService(applicationContext)
                StartupDiagnostics.recordMilestone(applicationContext, "service_scheduled", "Started from BootStartWorker")
                clearDeferredStartFlag(applicationContext)
                Result.success()
            } catch (t: Throwable) {
                if (isForegroundStartBlockedException(t) || isSecurityException(t)) {
                    persistDeferredStartFlag(applicationContext)
                    StartupDiagnostics.recordError(applicationContext, "boot_worker_foreground_start_blocked", t)
                    Log.w(TAG, "Foreground start blocked at boot; deferring to next app launch", t)
                    Result.success()
                } else {
                    persistDeferredStartFlag(applicationContext)
                    StartupDiagnostics.recordError(applicationContext, "boot_worker_start_failed", t)
                    Log.e(TAG, "Failed to start TronProtocolService from worker", t)
                    Result.failure()
                }
            }
        } else {
            persistDeferredStartFlag(applicationContext)
            StartupDiagnostics.recordMilestone(applicationContext, "service_scheduled", "Deferred by device state")
            Log.i(TAG, "Device state does not allow immediate service start; deferred")
            Result.success()
        }
    }

    private fun canStartServiceImmediately(context: Context): Boolean {
        // Android 13+: cannot start foreground service without POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "POST_NOTIFICATIONS not granted — deferring service start to app launch")
                return false
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isInteractive) {
                return false
            }
        }

        return true
    }

    private fun isForegroundStartBlockedException(t: Throwable): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            t.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
    }

    private fun isSecurityException(t: Throwable): Boolean {
        return t is SecurityException ||
            t.javaClass.name == "java.lang.SecurityException"
    }

    private fun startTronProtocolService(context: Context) {
        val serviceIntent = Intent(context, TronProtocolService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun persistDeferredStartFlag(context: Context) {
        context.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(BootReceiver.DEFERRED_SERVICE_START_KEY, true)
            .apply()
    }

    private fun clearDeferredStartFlag(context: Context) {
        context.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(BootReceiver.DEFERRED_SERVICE_START_KEY, false)
            .apply()
    }

    companion object {
        private const val TAG = "BootStartWorker"
    }
}

package com.tronprotocol.app

import android.content.Context
import android.content.Intent
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
        val canStartImmediately = canStartServiceImmediately(applicationContext)

        return if (canStartImmediately) {
            try {
                startTronProtocolService(applicationContext)
                clearDeferredStartFlag(applicationContext)
                Result.success()
            } catch (t: Throwable) {
                if (isForegroundStartBlockedException(t)) {
                    persistDeferredStartFlag(applicationContext)
                    Log.w(TAG, "Foreground start blocked at boot; deferring to next app launch", t)
                    Result.success()
                } else {
                    persistDeferredStartFlag(applicationContext)
                    Log.e(TAG, "Failed to start TronProtocolService from worker", t)
                    Result.failure()
                }
            }
        } else {
            persistDeferredStartFlag(applicationContext)
            Log.i(TAG, "Device state does not allow immediate service start; deferred")
            Result.success()
        }
    }

    private fun canStartServiceImmediately(context: Context): Boolean {
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

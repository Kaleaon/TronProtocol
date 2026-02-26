package com.tronprotocol.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

/**
 * Utility for managing permission groups and checking permission status.
 * Extracted from MainActivity so fragments can query permission state
 * while the Activity owns the ActivityResultLauncher contracts.
 */
object PermissionCoordinator {

    private fun buildStoragePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    private fun buildNotificationPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }
    }

    enum class PermissionGroup(val displayName: String, val permissions: List<String>) {
        TELEPHONY("Phone", listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG
        )),
        SMS("SMS", listOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS
        )),
        CONTACTS("Contacts", listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
        )),
        LOCATION("Location", listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )),
        STORAGE("Storage", buildStoragePermissions()),
        NOTIFICATIONS("Notifications", buildNotificationPermissions());
    }

    /**
     * Check if all permissions in a group are granted.
     */
    fun isGroupGranted(context: Context, group: PermissionGroup): Boolean {
        return group.permissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if "all files access" is granted (Android 11+).
     */
    fun isAllFilesAccessGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /**
     * Get the permissions that still need to be requested for a group.
     */
    fun getMissingPermissions(context: Context, group: PermissionGroup): List<String> {
        return group.permissions.filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Build a summary string of permission status across all groups.
     */
    fun buildPermissionSummary(context: Context): String {
        val granted = PermissionGroup.entries.count { isGroupGranted(context, it) }
        val total = PermissionGroup.entries.size
        return "$granted / $total permission groups granted"
    }

    /**
     * Get all permissions across all groups as a flat array (for bulk request).
     */
    fun getAllPermissions(): Array<String> {
        return PermissionGroup.entries.flatMap { it.permissions }.toTypedArray()
    }
}

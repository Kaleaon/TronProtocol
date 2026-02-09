package com.tronprotocol.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.tronprotocol.app.plugins.CalculatorPlugin
import com.tronprotocol.app.plugins.CommunicationHubPlugin
import com.tronprotocol.app.plugins.DateTimePlugin
import com.tronprotocol.app.plugins.DeviceInfoPlugin
import com.tronprotocol.app.plugins.FileManagerPlugin
import com.tronprotocol.app.plugins.GuidanceRouterPlugin
import com.tronprotocol.app.plugins.NotesPlugin
import com.tronprotocol.app.plugins.PersonalizationPlugin
import com.tronprotocol.app.plugins.Plugin
import com.tronprotocol.app.plugins.PluginManager
import com.tronprotocol.app.plugins.PolicyGuardrailPlugin
import com.tronprotocol.app.plugins.SandboxedCodeExecutionPlugin
import com.tronprotocol.app.plugins.TaskAutomationPlugin
import com.tronprotocol.app.plugins.TelegramBridgePlugin
import com.tronprotocol.app.plugins.TextAnalysisPlugin
import com.tronprotocol.app.plugins.WebSearchPlugin

class MainActivity : AppCompatActivity() {

    private lateinit var pluginCountText: TextView
    private lateinit var permissionStatusText: TextView
    private lateinit var startupStateBadgeText: TextView
    private lateinit var diagnosticsText: TextView
    private lateinit var permissionRationaleText: TextView
    private lateinit var prefs: SharedPreferences

    private var activePermissionRequest: PermissionFeature? = null

    private enum class PermissionFeature(
        val title: String,
        val permissions: Array<String>,
        val rationale: String,
        val deniedGuidance: String
    ) {
        TELEPHONY(
            title = "Telephony",
            permissions = arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.WRITE_CALL_LOG
            ),
            rationale = "Telephony tools need phone and call-log access so Tron Protocol can place calls and summarize call history.",
            deniedGuidance = "Telephony is unavailable. Enable Phone + Call Log permissions in Settings > Apps > Tron Protocol > Permissions."
        ),
        SMS(
            title = "SMS",
            permissions = arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
            ),
            rationale = "SMS tools need message access to send, read, and monitor incoming SMS as requested.",
            deniedGuidance = "SMS actions are disabled. Grant SMS permissions from app settings to continue."
        ),
        CONTACTS(
            title = "Contacts",
            permissions = arrayOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS
            ),
            rationale = "Contacts access is required to find people and update contact records.",
            deniedGuidance = "Contact features are disabled. Grant Contacts permission in app settings."
        ),
        LOCATION(
            title = "Location",
            permissions = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            rationale = "Location access helps route and nearby-place features respond with accurate context.",
            deniedGuidance = "Location features are unavailable. Turn on Location permissions in app settings."
        ),
        STORAGE(
            title = "Storage",
            permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            rationale = "Storage permission is required to read, save, and organize files requested by the user.",
            deniedGuidance = "File operations are unavailable. Grant Storage permissions (and All Files access on Android 11+)."
        ),
        NOTIFICATIONS(
            title = "Notifications",
            permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            },
            rationale = "Notification permission keeps important status alerts visible while background tasks run.",
            deniedGuidance = "Notifications are off. Enable notification permission in app settings."
        )
    }

    private val allRuntimePermissions by lazy {
        PermissionFeature.entries
            .flatMap { it.permissions.toList() }
            .distinct()
            .toTypedArray()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val feature = activePermissionRequest
            val denied = result.filterValues { granted -> !granted }.keys

            if (feature != null) {
                if (denied.isEmpty()) {
                    showPermissionMessage("${feature.title} permission granted. You can continue.")
                } else {
                    onPermissionDenied(feature, denied.toList())
                }
            }

            activePermissionRequest = null
            updatePermissionUi()
            refreshStartupStateBadge()
            refreshDiagnosticsPanel()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(BootReceiver.PREFS_NAME, MODE_PRIVATE)
        pluginCountText = findViewById(R.id.pluginCountText)
        permissionStatusText = findViewById(R.id.permissionStatusText)
        startupStateBadgeText = findViewById(R.id.startupStateBadgeText)
        permissionRationaleText = findViewById(R.id.permissionRationaleText)

        runStartupBlock("initialize_plugins") { initializePlugins() }
        runStartupBlock("wire_ui_actions") { wireUiActions() }

        if (prefs.getBoolean(FIRST_LAUNCH_KEY, true)) {
            runStartupBlock("request_initial_access") {
                requestInitialAccess()
                prefs.edit().putBoolean(FIRST_LAUNCH_KEY, false).apply()
            }
        } else {
            runStartupBlock("update_permission_ui") { updatePermissionUi() }
        }

        requestBatteryOptimizationExemption()
        startTronProtocolService()
        refreshStartupStateBadge()
        runStartupBlock("request_battery_optimization_exemption") { requestBatteryOptimizationExemption() }
        runStartupBlock("start_service_from_main_oncreate") { startTronProtocolService() }
        refreshDiagnosticsPanel()
    }

    override fun onStart() {
        super.onStart()
        startServiceIfDeferredFromBoot()
        refreshStartupStateBadge()
        runStartupBlock("start_service_if_deferred_from_boot") { startServiceIfDeferredFromBoot() }
        refreshDiagnosticsPanel()
    }

    private fun runStartupBlock(name: String, block: () -> Unit) {
        try {
            block()
            StartupDiagnostics.recordMilestone(this, "$name_success")
        } catch (t: Throwable) {
            StartupDiagnostics.recordError(this, name, t)
            Log.e(TAG, "Startup block failed: $name", t)
        }
    }

    private fun wireUiActions() {
        findViewById<MaterialButton>(R.id.btnTelephonyFeature).setOnClickListener {
            executeFeatureWithPermissions(PermissionFeature.TELEPHONY) {
                showPermissionMessage("Telephony plugin is ready to execute call operations.")
            }
        }

        findViewById<MaterialButton>(R.id.btnSmsFeature).setOnClickListener {
            executeFeatureWithPermissions(PermissionFeature.SMS) {
                showPermissionMessage("SMS plugin is ready to send/read messages.")
            }
        }

        findViewById<MaterialButton>(R.id.btnContactsFeature).setOnClickListener {
            executeFeatureWithPermissions(PermissionFeature.CONTACTS) {
                showPermissionMessage("Contacts plugin is ready to read/update contacts.")
            }
        }

        findViewById<MaterialButton>(R.id.btnLocationFeature).setOnClickListener {
            executeFeatureWithPermissions(PermissionFeature.LOCATION) {
                showPermissionMessage("Location plugin is ready to resolve nearby context.")
            }
        }

        findViewById<MaterialButton>(R.id.btnStorageFeature).setOnClickListener {
            executeFeatureWithPermissions(PermissionFeature.STORAGE) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    showPermissionMessage("Grant All Files access to complete advanced file management.")
                    requestAllFilesAccess()
                } else {
                    showPermissionMessage("File manager plugin is ready for storage operations.")
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnNotificationsFeature).setOnClickListener {
            executeFeatureWithPermissions(PermissionFeature.NOTIFICATIONS) {
                showPermissionMessage("Notifications are enabled for task and service updates.")
            }
        }

        findViewById<MaterialButton>(R.id.btnGrantAllFiles).setOnClickListener {
            requestAllFilesAccess()
            refreshDiagnosticsPanel()
        }

        findViewById<MaterialButton>(R.id.btnStartService).setOnClickListener {
            startTronProtocolService()
            refreshStartupStateBadge()
            runStartupBlock("start_service_from_button") { startTronProtocolService() }
            Toast.makeText(this, "Service start requested", Toast.LENGTH_SHORT).show()
            refreshDiagnosticsPanel()
        }
    }

    private fun initializePlugins() {
        val pluginManager = PluginManager.getInstance()
        pluginManager.initialize(this)
        val failedPlugins = mutableListOf<String>()

        fun register(name: String, creator: () -> Plugin) {
            try {
                val plugin = creator()
                val isRegistered = pluginManager.registerPlugin(plugin)
                if (!isRegistered) {
                    failedPlugins.add(name)
                    Log.w(TAG, "Plugin registration failed: $name")
                }
            } catch (t: Throwable) {
                failedPlugins.add(name)
                Log.w(TAG, "Plugin initialization failed: $name", t)
            }
        }

        // Register guardrail first so policies apply to subsequent plugin invocations
        register("PolicyGuardrailPlugin") { PolicyGuardrailPlugin() }

        register("DeviceInfoPlugin") { DeviceInfoPlugin() }
        register("WebSearchPlugin") { WebSearchPlugin() }
        register("CalculatorPlugin") { CalculatorPlugin() }
        register("DateTimePlugin") { DateTimePlugin() }
        register("TextAnalysisPlugin") { TextAnalysisPlugin() }
        register("FileManagerPlugin") { FileManagerPlugin() }
        register("NotesPlugin") { NotesPlugin() }
        register("TelegramBridgePlugin") { TelegramBridgePlugin() }
        register("TaskAutomationPlugin") { TaskAutomationPlugin() }
        register("SandboxedCodeExecutionPlugin") { SandboxedCodeExecutionPlugin() }
        register("PersonalizationPlugin") { PersonalizationPlugin() }
        register("CommunicationHubPlugin") { CommunicationHubPlugin() }
        register("GuidanceRouterPlugin") { GuidanceRouterPlugin() }

        val activePluginCount = pluginManager.getAllPlugins().size
        pluginCountText.text = getString(R.string.active_plugins_count, activePluginCount)

        val pluginCount = pluginManager.getAllPlugins().size
        pluginCountText.text = "Active plugins: $pluginCount"
        StartupDiagnostics.recordMilestone(this, "plugin_init_summary", "Registered plugins: $pluginCount")
        if (failedPlugins.isNotEmpty()) {
            val skippedPlugins = failedPlugins.joinToString()
            Toast.makeText(
                this,
                getString(R.string.skipped_plugins_message, skippedPlugins),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestInitialAccess() {
        showPermissionMessage("Permissions are requested on demand when you use telephony, SMS, contacts, location, storage, or notification features.")
        updatePermissionUi()
    }

    private fun executeFeatureWithPermissions(feature: PermissionFeature, onGranted: () -> Unit) {
        val missingPermissions = missingPermissionsFor(feature)
        if (missingPermissions.isEmpty() || feature.permissions.isEmpty()) {
            onGranted()
            updatePermissionUi()
            return
        }

        if (missingPermissions.any { shouldShowRequestPermissionRationale(it) }) {
            showRationaleDialog(feature, missingPermissions.toTypedArray())
        } else {
            activePermissionRequest = feature
            permissionLauncher.launch(missingPermissions.toTypedArray())
            showPermissionMessage("Requesting ${feature.title.lowercase()} permissions...")
        }
    }

    private fun showRationaleDialog(feature: PermissionFeature, missingPermissions: Array<String>) {
        AlertDialog.Builder(this)
            .setTitle("${feature.title} permission required")
            .setMessage(feature.rationale)
            .setPositiveButton("Continue") { _, _ ->
                activePermissionRequest = feature
                permissionLauncher.launch(missingPermissions)
            }
            .setNegativeButton("Not now") { _, _ ->
                showPermissionMessage(feature.deniedGuidance)
                openAppPermissionSettingsPrompt(feature)
            }
            .show()
    }

    private fun onPermissionDenied(feature: PermissionFeature, denied: List<String>) {
        val permanentlyDenied = denied.any { !shouldShowRequestPermissionRationale(it) }
        val message = if (permanentlyDenied) {
            "${feature.deniedGuidance} Tap \"Open settings\" to enable it."
        } else {
            feature.deniedGuidance
        }

        showPermissionMessage(message)
        openAppPermissionSettingsPrompt(feature)
    }

    private fun openAppPermissionSettingsPrompt(feature: PermissionFeature) {
        AlertDialog.Builder(this)
            .setTitle("${feature.title} permission denied")
            .setMessage(feature.deniedGuidance)
            .setPositiveButton("Open settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun missingPermissionsFor(feature: PermissionFeature): List<String> {
        return feature.permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
        updatePermissionUi()
    }

    private fun updatePermissionUi() {
        val granted = allRuntimePermissions.count {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val allFilesGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

        permissionStatusText.text =
            "Runtime permissions: $granted/${allRuntimePermissions.size}\nAll files access: ${if (allFilesGranted) "Granted" else "Pending"}"
    }

    private fun showPermissionMessage(message: String) {
        permissionRationaleText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun refreshStartupStateBadge() {
        val state = prefs.getString(
            TronProtocolService.SERVICE_STARTUP_STATE_KEY,
            TronProtocolService.STATE_DEFERRED
        ) ?: TronProtocolService.STATE_DEFERRED
        val reason = prefs.getString(
            TronProtocolService.SERVICE_STARTUP_REASON_KEY,
            getString(R.string.service_status_waiting_reason)
        ) ?: getString(R.string.service_status_waiting_reason)

        val (colorRes, label) = when (state) {
            TronProtocolService.STATE_RUNNING -> R.color.service_status_running_background to "RUNNING"
            TronProtocolService.STATE_BLOCKED_BY_PERMISSION -> R.color.service_status_blocked_background to "BLOCKED-BY-PERMISSION"
            TronProtocolService.STATE_DEGRADED -> R.color.service_status_degraded_background to "DEGRADED"
            else -> R.color.service_status_deferred_background to "DEFERRED"
        }

        startupStateBadgeText.setBackgroundColor(ContextCompat.getColor(this, colorRes))
        startupStateBadgeText.text = getString(R.string.service_status_format, label, reason)
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = packageName
            val pm = getSystemService(POWER_SERVICE) as? PowerManager

            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun startServiceIfDeferredFromBoot() {
        if (!prefs.getBoolean(BootReceiver.DEFERRED_SERVICE_START_KEY, false)) {
            return
        }

        try {
            startTronProtocolService()
            prefs.edit().putBoolean(BootReceiver.DEFERRED_SERVICE_START_KEY, false).apply()
            StartupDiagnostics.recordMilestone(this, "service_scheduled", "Deferred service launch requested")
        } catch (t: Throwable) {
            StartupDiagnostics.recordError(this, "deferred_service_start_failed", t)
            Log.w(TAG, "Deferred service start failed", t)
        }
    }

    private fun startTronProtocolService() {
        val serviceIntent = Intent(this, TronProtocolService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun refreshDiagnosticsPanel() {
        diagnosticsText.text = StartupDiagnostics.getEventsForDisplay(this)
    }

    companion object {
        private const val FIRST_LAUNCH_KEY = "is_first_launch"
        private const val TAG = "MainActivity"
    }
}

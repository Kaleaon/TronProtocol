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
    private lateinit var prefs: SharedPreferences

    private val runtimePermissions by lazy {
        buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.WRITE_CALL_LOG)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.WRITE_CONTACTS)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updatePermissionUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(BootReceiver.PREFS_NAME, MODE_PRIVATE)
        pluginCountText = findViewById(R.id.pluginCountText)
        permissionStatusText = findViewById(R.id.permissionStatusText)

        initializePlugins()
        wireUiActions()

        if (prefs.getBoolean(FIRST_LAUNCH_KEY, true)) {
            requestInitialAccess()
            prefs.edit().putBoolean(FIRST_LAUNCH_KEY, false).apply()
        } else {
            updatePermissionUi()
        }

        requestBatteryOptimizationExemption()
        startTronProtocolService()
    }

    override fun onStart() {
        super.onStart()
        startServiceIfDeferredFromBoot()
    }

    private fun wireUiActions() {
        findViewById<MaterialButton>(R.id.btnRequestPermissions).setOnClickListener {
            permissionLauncher.launch(runtimePermissions)
        }

        findViewById<MaterialButton>(R.id.btnGrantAllFiles).setOnClickListener {
            requestAllFilesAccess()
        }

        findViewById<MaterialButton>(R.id.btnStartService).setOnClickListener {
            startTronProtocolService()
            Toast.makeText(this, "Service start requested", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initializePlugins() {
        val pluginManager = PluginManager.getInstance()
        pluginManager.initialize(this)
        val failedPlugins = mutableListOf<String>()

        fun register(plugin: Plugin) {
            val isRegistered = pluginManager.registerPlugin(plugin)
            if (!isRegistered) {
                failedPlugins.add(plugin.name)
            }
        }

        // Register guardrail first so policies apply to subsequent plugin invocations
        register(PolicyGuardrailPlugin())

        register(DeviceInfoPlugin())
        register(WebSearchPlugin())
        register(CalculatorPlugin())
        register(DateTimePlugin())
        register(TextAnalysisPlugin())
        register(FileManagerPlugin())
        register(NotesPlugin())
        register(TelegramBridgePlugin())
        register(TaskAutomationPlugin())
        register(SandboxedCodeExecutionPlugin())
        register(PersonalizationPlugin())
        register(CommunicationHubPlugin())
        register(GuidanceRouterPlugin())

        val activePluginCount = pluginManager.getAllPlugins().size
pluginCountText.text = getString(R.string.active_plugins_count, activePluginCount)

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
        permissionLauncher.launch(runtimePermissions)
        requestAllFilesAccess()
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
        val granted = runtimePermissions.count {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val allFilesGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

        permissionStatusText.text =
            "Runtime permissions: $granted/${runtimePermissions.size}\nAll files access: ${if (allFilesGranted) "Granted" else "Pending"}"
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
        } catch (t: Throwable) {
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

    companion object {
        private const val FIRST_LAUNCH_KEY = "is_first_launch"
        private const val TAG = "MainActivity"
    }
}

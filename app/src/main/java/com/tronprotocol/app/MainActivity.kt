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
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tronprotocol.app.plugins.PluginManager
import com.tronprotocol.app.plugins.PluginRegistry

class MainActivity : AppCompatActivity() {

    private lateinit var pluginCountText: TextView
    private lateinit var permissionStatusText: TextView
    private lateinit var pluginStatusText: TextView
    private lateinit var pluginToggleContainer: LinearLayout
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
        pluginStatusText = findViewById(R.id.pluginStatusText)
        pluginToggleContainer = findViewById(R.id.pluginToggleContainer)

        initializePlugins()
        renderPluginManagementUi()
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
        pluginManager.destroy()
        pluginManager.initialize(this)

        for (config in PluginRegistry.sortedConfigs) {
            if (!isPluginEnabled(config.id, config.defaultEnabled)) {
                continue
            }
            val plugin = config.factory.invoke()
            plugin.setEnabled(true)
            pluginManager.registerPlugin(plugin)
        }

        updatePluginUiState()
    }

    private fun renderPluginManagementUi() {
        pluginToggleContainer.removeAllViews()
        val pluginManager = PluginManager.getInstance()

        for (config in PluginRegistry.sortedConfigs) {
            val toggle = SwitchMaterial(this).apply {
                val enabled = isPluginEnabled(config.id, config.defaultEnabled)
                isChecked = enabled
                text = "${config.id} · ${config.pluginClass.simpleName} (P${config.startupPriority})"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }

            toggle.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(pluginPreferenceKey(config.id), isChecked).apply()

                if (isChecked) {
                    val plugin = config.factory.invoke()
                    plugin.setEnabled(true)
                    pluginManager.registerPlugin(plugin)
                } else {
                    pluginManager.unregisterPlugin(config.id)
                }

                updatePluginUiState()
            }

            pluginToggleContainer.addView(toggle)
        }

        updatePluginUiState()
    }

    private fun updatePluginUiState() {
        val pluginManager = PluginManager.getInstance()
        val enabledConfigs = PluginRegistry.sortedConfigs.filter { isPluginEnabled(it.id, it.defaultEnabled) }
        val loadedPluginIds = pluginManager.getAllPlugins().map { it.id }.toSet()

        pluginCountText.text = "Active plugins: ${pluginManager.getAllPlugins().size}"
        pluginStatusText.text = buildString {
            append("Configured enabled: ${enabledConfigs.size}/${PluginRegistry.sortedConfigs.size}\n")
            append("Loaded: ${pluginManager.getAllPlugins().size}\n")
            append("Status:\n")
            PluginRegistry.sortedConfigs.forEach { config ->
                val configuredEnabled = enabledConfigs.any { it.id == config.id }
                val loaded = loadedPluginIds.contains(config.id)
                append("• ${config.id} -> ${if (configuredEnabled) "enabled" else "disabled"}")
                append(", loaded=${if (loaded) "yes" else "no"}\n")
            }
        }
    }

    private fun isPluginEnabled(pluginId: String, defaultEnabled: Boolean): Boolean {
        return prefs.getBoolean(pluginPreferenceKey(pluginId), defaultEnabled)
    }

    private fun pluginPreferenceKey(pluginId: String): String = "plugin_enabled_$pluginId"

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

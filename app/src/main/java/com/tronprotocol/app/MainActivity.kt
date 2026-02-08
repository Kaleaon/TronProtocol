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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.tronprotocol.app.plugins.CalculatorPlugin
import com.tronprotocol.app.plugins.TaskAutomationPlugin
import com.tronprotocol.app.plugins.SandboxedCodeExecutionPlugin
import com.tronprotocol.app.plugins.PolicyGuardrailPlugin
import com.tronprotocol.app.plugins.PersonalizationPlugin
import com.tronprotocol.app.plugins.CommunicationHubPlugin
import com.tronprotocol.app.plugins.DateTimePlugin
import com.tronprotocol.app.plugins.DeviceInfoPlugin
import com.tronprotocol.app.plugins.FileManagerPlugin
import com.tronprotocol.app.plugins.NotesPlugin
import com.tronprotocol.app.plugins.PluginManager
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

        prefs = getSharedPreferences("tron_protocol_prefs", MODE_PRIVATE)
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

        // Register guardrail first so policies apply to subsequent plugin invocations
        pluginManager.registerPlugin(PolicyGuardrailPlugin())

        pluginManager.registerPlugin(DeviceInfoPlugin())
        pluginManager.registerPlugin(WebSearchPlugin())
        pluginManager.registerPlugin(CalculatorPlugin())
        pluginManager.registerPlugin(DateTimePlugin())
        pluginManager.registerPlugin(TextAnalysisPlugin())
        pluginManager.registerPlugin(FileManagerPlugin())
        pluginManager.registerPlugin(NotesPlugin())
        pluginManager.registerPlugin(TelegramBridgePlugin())
        pluginManager.registerPlugin(TaskAutomationPlugin())
        pluginManager.registerPlugin(SandboxedCodeExecutionPlugin())
        pluginManager.registerPlugin(PersonalizationPlugin())
        pluginManager.registerPlugin(CommunicationHubPlugin())

        pluginCountText.text = "Active plugins: ${pluginManager.getAllPlugins().size}"
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
    }
}

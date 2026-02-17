package com.tronprotocol.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.children
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.ktheme.core.ThemeEngine
import com.ktheme.utils.ColorUtils
import com.tronprotocol.app.llm.OnDeviceLLMManager
import com.tronprotocol.app.plugins.PluginManager
import com.tronprotocol.app.plugins.PluginRegistry
import java.time.Instant
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var pluginCountText: TextView
    private lateinit var permissionStatusText: TextView
    private lateinit var pluginStatusText: TextView
    private lateinit var pluginToggleContainer: LinearLayout
    private lateinit var startupStateBadgeText: TextView
    private lateinit var diagnosticsText: TextView
    private lateinit var permissionRationaleText: TextView
    private lateinit var conversationTranscriptText: TextView
    private lateinit var conversationInput: TextInputEditText
    private lateinit var messageFormatText: TextView
    private lateinit var messageShareStatusText: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var llmManager: OnDeviceLLMManager
    private val llmSetupExecutor = Executors.newSingleThreadExecutor()

    private var activePermissionRequest: PermissionFeature? = null
    private var pendingShareType: ShareType? = null
    private val conversationTurns = mutableListOf<ConversationTurn>()

    private enum class ShareType {
        DOC,
        IMAGE,
        AUDIO
    }

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

    private val shareDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val type = pendingShareType
            pendingShareType = null
            if (uri == null || type == null) {
                return@registerForActivityResult
            }
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val displayName = resolveDisplayName(uri) ?: "File ($mimeType)"
            val formatted = formatShareMessage(type.name, displayName, uri.toString(), NOTE_SHARED_FROM_DEVICE_PICKER)
            messageShareStatusText.text = formatted
            showPermissionMessage("Shared ${type.name.lowercase()} context with AI message format.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(BootReceiver.PREFS_NAME, MODE_PRIVATE)
        llmManager = OnDeviceLLMManager(this)
        pluginCountText = findViewById(R.id.pluginCountText)
        permissionStatusText = findViewById(R.id.permissionStatusText)
        pluginStatusText = findViewById(R.id.pluginStatusText)
        pluginToggleContainer = findViewById(R.id.pluginToggleContainer)
        startupStateBadgeText = findViewById(R.id.startupStateBadgeText)
        permissionRationaleText = findViewById(R.id.permissionRationaleText)
        diagnosticsText = findViewById(R.id.diagnosticsText)
        conversationTranscriptText = findViewById(R.id.conversationTranscriptText)
        conversationInput = findViewById(R.id.conversationInput)
        messageFormatText = findViewById(R.id.messageFormatText)
        messageShareStatusText = findViewById(R.id.messageShareStatusText)
        messageShareStatusText.text = "Ready to share docs, pictures, music, and links with AI."

        runStartupBlock("apply_ktheme") { applyKtheme() }
        runStartupBlock("initialize_plugins") { initializePlugins() }
        renderPluginManagementUi()
        runStartupBlock("wire_ui_actions") { wireUiActions() }

        if (prefs.getBoolean(FIRST_LAUNCH_KEY, true)) {
            runStartupBlock("request_initial_access") {
                requestInitialAccess()
                prefs.edit().putBoolean(FIRST_LAUNCH_KEY, false).apply()
            }
        } else {
            runStartupBlock("update_permission_ui") { updatePermissionUi() }
        }

        runStartupBlock("request_battery_optimization_exemption") { requestBatteryOptimizationExemption() }
        runStartupBlock("start_service_from_main_oncreate") { startTronProtocolService() }
        refreshStartupStateBadge()
        refreshDiagnosticsPanel()
    }

    override fun onStart() {
        super.onStart()
        runStartupBlock("start_service_if_deferred_from_boot") { startServiceIfDeferredFromBoot() }
        refreshStartupStateBadge()
        refreshDiagnosticsPanel()
    }

    private fun runStartupBlock(name: String, block: () -> Unit) {
        try {
            block()
            StartupDiagnostics.recordMilestone(this, "${name}_success")
        } catch (t: Throwable) {
            StartupDiagnostics.recordError(this, name, t)
            Log.e(TAG, "Startup block failed: $name", t)
        }
    }

    private fun applyKtheme() {
        try {
            val engine = ThemeEngine()
            val themeFile = java.io.File.createTempFile("navy-gold", ".json", cacheDir).apply {
                outputStream().use { output ->
                    assets.open("themes/navy-gold.json").use { input -> input.copyTo(output) }
                }
                deleteOnExit()
            }

            val theme = engine.loadThemeFromFile(themeFile)
            engine.setActiveTheme(theme.metadata.id)
            val activeTheme = engine.getActiveTheme() ?: return
            val colors = activeTheme.colorScheme

            val background = ColorUtils.hexToColorInt(colors.background)
            val surface = ColorUtils.hexToColorInt(colors.surface)
            val onSurface = ColorUtils.hexToColorInt(colors.onSurface)
            val primary = ColorUtils.hexToColorInt(colors.primary)
            val onPrimary = ColorUtils.hexToColorInt(colors.onPrimary)

            findViewById<android.widget.ScrollView>(R.id.rootScrollView).setBackgroundColor(background)

            listOf(
                R.id.headerCard,
                R.id.conversationCard,
                R.id.actionCard,
                R.id.messageCard,
                R.id.pluginManagementCard,
                R.id.diagnosticsCard,
            ).forEach { cardId ->
                findViewById<MaterialCardView>(cardId).setCardBackgroundColor(surface)
            }

            listOf(
                R.id.pluginCountText,
                R.id.permissionStatusText,
                R.id.startupStateBadgeText,
                R.id.permissionRationaleText,
                R.id.pluginStatusText,
                R.id.diagnosticsText,
                R.id.conversationTranscriptText,
                R.id.messageFormatText,
                R.id.messageShareStatusText,
            ).forEach { textId ->
                findViewById<TextView>(textId).setTextColor(onSurface)
            }

            listOf(
                R.id.btnTelephonyFeature,
                R.id.btnSmsFeature,
                R.id.btnContactsFeature,
                R.id.btnLocationFeature,
                R.id.btnStorageFeature,
                R.id.btnNotificationsFeature,
                R.id.btnOpenBotFather,
                R.id.btnSendConversation,
                R.id.btnShareDocument,
                R.id.btnShareImage,
                R.id.btnShareMusic,
            ).forEach { buttonId ->
                findViewById<MaterialButton>(buttonId).apply {
                    backgroundTintList = ColorStateList.valueOf(primary)
                    setTextColor(onPrimary)
                }
            }

            findViewById<MaterialButton>(R.id.btnGrantAllFiles).apply {
                strokeColor = ColorStateList.valueOf(primary)
                setTextColor(primary)
            }

            findViewById<MaterialButton>(R.id.btnStartService).setTextColor(primary)
            findViewById<MaterialButton>(R.id.btnOpenPluginGuide).setTextColor(primary)
            findViewById<MaterialButton>(R.id.btnOpenKthemeRepo).setTextColor(primary)
            findViewById<MaterialButton>(R.id.btnRuntimeSelfCheck).setTextColor(primary)
            findViewById<MaterialButton>(R.id.btnDownloadInitModel).setTextColor(primary)
            findViewById<MaterialButton>(R.id.btnExportDebugLog).setTextColor(primary)
            findViewById<MaterialButton>(R.id.btnClearConversation).apply {
                strokeColor = ColorStateList.valueOf(primary)
                setTextColor(primary)
            }

            pluginToggleContainer.children.forEach { child ->
                if (child is SwitchMaterial) {
                    child.setTextColor(onSurface)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to apply ktheme styling", t)
        }
    }

    private fun wireUiActions() {
        findViewById<MaterialButton>(R.id.btnSendConversation).setOnClickListener {
            sendConversationMessage()
        }

        findViewById<MaterialButton>(R.id.btnClearConversation).setOnClickListener {
            conversationTurns.clear()
            conversationTranscriptText.text = ConversationTranscriptFormatter.format(conversationTurns)
            showInlineStatusMessage("Conversation history cleared.")
        }

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
            runStartupBlock("start_service_from_button") { startTronProtocolService() }
            refreshStartupStateBadge()
            Toast.makeText(this, "Service start requested", Toast.LENGTH_SHORT).show()
            refreshDiagnosticsPanel()
        }

        findViewById<MaterialButton>(R.id.btnOpenBotFather).setOnClickListener {
            openExternalLink(BOTFATHER_URL, "Opening BotFather setup in Telegram/browser.")
        }

        findViewById<MaterialButton>(R.id.btnOpenPluginGuide).setOnClickListener {
            openExternalLink(PLUGIN_GUIDE_URL, "Opening plugin expansion and maintenance guide.")
        }

        findViewById<MaterialButton>(R.id.btnOpenKthemeRepo).setOnClickListener {
            openExternalLink(KTHEME_REPO_URL, "Opening Ktheme so AI/user can design and preview themes.")
        }

        findViewById<MaterialButton>(R.id.btnShareDocument).setOnClickListener {
            startSharePicker(ShareType.DOC, arrayOf("application/pdf", "text/plain", "application/msword"))
        }

        findViewById<MaterialButton>(R.id.btnShareImage).setOnClickListener {
            startSharePicker(ShareType.IMAGE, arrayOf("image/*"))
        }

        findViewById<MaterialButton>(R.id.btnShareMusic).setOnClickListener {
            startSharePicker(ShareType.AUDIO, arrayOf("audio/*"))
        }

        findViewById<MaterialButton>(R.id.btnShareLink).setOnClickListener {
            promptAndShareLink()
        }

        findViewById<MaterialButton>(R.id.btnRuntimeSelfCheck).setOnClickListener {
            val manager = PluginManager.getInstance()
            val message = buildString {
                append(manager.runRuntimeSelfCheck())
                append("\n")
                append("Policy: ${manager.getRuntimePolicyStatus()}")
            }
            messageShareStatusText.text = message
            showPermissionMessage("Runtime self-check completed.")
        }

        findViewById<MaterialButton>(R.id.btnDownloadInitModel).setOnClickListener {
            promptModelDownloadAndInit()
        }

        findViewById<MaterialButton>(R.id.btnExportDebugLog).setOnClickListener {
            exportDebugLog()
        }
    }

    private fun initializePlugins() {
        val pluginManager = PluginManager.getInstance()
        pluginManager.initialize(this)

        for (config in PluginRegistry.sortedConfigs) {
            if (!isPluginEnabled(config.id, config.defaultEnabled)) {
                continue
            }
            val plugin = config.factory.invoke()
            plugin.isEnabled = true
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
                setTextColor(pluginStatusText.currentTextColor)
            }

            toggle.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(pluginPreferenceKey(config.id), isChecked).apply()

                if (isChecked) {
                    val plugin = config.factory.invoke()
                    plugin.isEnabled = true
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

    private fun showInlineStatusMessage(message: String) {
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

    private fun startSharePicker(type: ShareType, mimeTypes: Array<String>) {
        pendingShareType = type
        shareDocumentLauncher.launch(mimeTypes)
    }


    private fun promptModelDownloadAndInit() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
        }

        val modelNameInput = EditText(this).apply {
            hint = "Qwen2.5-1.5B-Instruct"
            setText("Qwen2.5-1.5B-Instruct")
        }

        val modelUrlInput = EditText(this).apply {
            hint = "https://example.com/model.zip"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        layout.addView(modelNameInput)
        layout.addView(modelUrlInput)

        AlertDialog.Builder(this)
            .setTitle("Download & initialize LLM")
            .setView(layout)
            .setPositiveButton("Start") { _, _ ->
                val modelName = modelNameInput.text?.toString()?.trim().orEmpty()
                val modelUrl = modelUrlInput.text?.toString()?.trim().orEmpty()
                if (modelName.isBlank() || modelUrl.isBlank()) {
                    showPermissionMessage("Provide both model name and URL.")
                    return@setPositiveButton
                }

                showPermissionMessage("Downloading model package. This can take a while...")
                llmSetupExecutor.execute {
                    val result = llmManager.downloadAndInitializeModel(modelName, modelUrl)
                    runOnUiThread {
                        if (result.success) {
                            val sizeMb = result.downloadedBytes / (1024f * 1024f)
                            showPermissionMessage(
                                "Model ready: ${result.config?.modelName} (${String.format("%.1f", sizeMb)} MB downloaded)"
                            )
                        } else {
                            showPermissionMessage("Model setup failed: ${result.error}")
                        }
                        refreshDiagnosticsPanel()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportDebugLog() {
        llmSetupExecutor.execute {
            val llmStats = llmManager.getStats().entries.joinToString(separator = "\n") { (key, value) ->
                "$key=$value"
            }
            StartupDiagnostics.setDebugSection("llm_manager", llmStats)

            val file = StartupDiagnostics.exportDebugLog(this)
            runOnUiThread {
                showPermissionMessage("Debug log exported: ${file.absolutePath}")
                messageShareStatusText.text = "Debug log file: ${file.absolutePath}"
            }
        }
    }

    private fun promptAndShareLink() {
        val input = EditText(this).apply {
            hint = "https://example.com/context"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Share web link with AI")
            .setView(input)
            .setPositiveButton("Share") { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                if (url.isBlank()) {
                    showPermissionMessage("Link was empty, nothing shared.")
                    return@setPositiveButton
                }
                val formatted = formatShareMessage("LINK", "Web context", url, NOTE_SHARED_BY_USER)
                messageShareStatusText.text = formatted
                showPermissionMessage("Shared web link context with AI message format.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendConversationMessage() {
        val userText = conversationInput.text?.toString()?.trim().orEmpty()
        if (userText.isBlank()) {
            showInlineStatusMessage("Type a message before sending.")
            return
        }

        conversationTurns.add(ConversationTurn("You", userText))

        val pluginManager = PluginManager.getInstance()
        val guidanceResult = pluginManager.executePlugin(
            GUIDANCE_ROUTER_PLUGIN_ID,
            "$GUIDANCE_ROUTER_GUIDE_COMMAND_PREFIX$userText"
        )
        val aiMessage = if (guidanceResult.isSuccess) {
            guidanceResult.data ?: "No response received from AI. Please try again."
        } else {
            "I'm having trouble responding right now. Please try again."
        }

        conversationTurns.add(ConversationTurn("Tron AI", aiMessage))
        conversationTranscriptText.text = ConversationTranscriptFormatter.format(conversationTurns)
        conversationInput.setText("")
    }

    private fun formatShareMessage(type: String, title: String, uriOrLink: String, note: String): String {
        return "[$type] $title$MESSAGE_SEPARATOR$uriOrLink$MESSAGE_SEPARATOR$note$MESSAGE_SEPARATOR" +
            Instant.now().toString()
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            val cursor: Cursor? = contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) it.getString(index) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openExternalLink(url: String, feedbackMessage: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            showPermissionMessage(feedbackMessage)
        } catch (t: Throwable) {
            showPermissionMessage("Unable to open link. Please copy and paste this URL into your browser: $url")
            StartupDiagnostics.recordError(this, "open_external_link_failed", t)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        llmSetupExecutor.shutdownNow()
        llmManager.shutdown()
    }

    companion object {
        private const val FIRST_LAUNCH_KEY = "is_first_launch"
        private const val TAG = "MainActivity"
        private const val BOTFATHER_URL = "https://t.me/BotFather"
        private const val PLUGIN_GUIDE_URL = "https://github.com/Kaleaon/TronProtocol#plugin-system-inspired-by-toolneuron"
        private const val KTHEME_REPO_URL = "https://github.com/kaleaon/Ktheme"
        private const val MESSAGE_SEPARATOR = " · "
        private const val NOTE_SHARED_FROM_DEVICE_PICKER = "shared from device picker"
        private const val NOTE_SHARED_BY_USER = "shared by user"
        private const val GUIDANCE_ROUTER_PLUGIN_ID = "guidance_router"
        private const val GUIDANCE_ROUTER_GUIDE_COMMAND_PREFIX = "guide|"
    }
}

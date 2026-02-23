package com.tronprotocol.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import java.io.File
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.ktheme.core.ThemeEngine
import com.ktheme.utils.ColorUtils
import com.tronprotocol.app.llm.ModelCatalog
import com.tronprotocol.app.llm.ModelDownloadManager
import com.tronprotocol.app.llm.ModelRepository
import com.tronprotocol.app.llm.OnDeviceLLMManager
import com.tronprotocol.app.plugins.PluginManager
import com.tronprotocol.app.plugins.PluginRegistry
import java.time.Instant
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // --- Views ---
    private lateinit var pluginCountText: TextView
    private lateinit var permissionStatusText: TextView
    private lateinit var startupStateBadgeText: TextView
    private lateinit var diagnosticsText: TextView
    private lateinit var permissionRationaleText: TextView
    private lateinit var conversationTranscriptText: TextView
    private lateinit var conversationInput: TextInputEditText
    private lateinit var messageShareStatusText: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var llmManager: OnDeviceLLMManager
    private var downloadManager: ModelDownloadManager? = null
    private var modelRepository: ModelRepository? = null
    private lateinit var modelHubStatusText: TextView
    private lateinit var modelSelectedDetailsText: TextView
    private lateinit var apiKeyListContainer: LinearLayout
    private lateinit var apiKeyEmptyText: TextView
    private lateinit var pluginSummaryText: TextView
    private lateinit var serviceStatusDetailText: TextView
    private lateinit var deviceCapText: TextView
    private val llmSetupExecutor = Executors.newSingleThreadExecutor()

    // --- Tab views ---
    private lateinit var tabChat: View
    private lateinit var tabModels: View
    private lateinit var tabPlugins: View
    private lateinit var tabSettings: View
    private lateinit var bottomNav: BottomNavigationView

    // --- Plugin category containers ---
    private lateinit var pluginCoreContainer: LinearLayout
    private lateinit var pluginAiContainer: LinearLayout
    private lateinit var pluginCommContainer: LinearLayout
    private lateinit var pluginToolsContainer: LinearLayout
    private lateinit var pluginSafetyContainer: LinearLayout

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
            permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
            } else {
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            },
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
                    showPermissionMessage("${feature.title} permission granted.")
                } else {
                    onPermissionDenied(feature, denied.toList())
                }
            }

            activePermissionRequest = null
            updatePermissionUi()
            refreshStartupStateBadge()
            refreshDiagnosticsPanel()

            if (feature == PermissionFeature.NOTIFICATIONS && denied.isEmpty()) {
                runStartupBlock("start_service_after_notification_grant") {
                    startTronProtocolService()
                }
                refreshStartupStateBadge()
            }
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
            showPermissionMessage("Shared ${type.name.lowercase()} context with AI.")
        }

    private val personalityImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            handleBulkPersonalityImport(uris)
        }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(BootReceiver.PREFS_NAME, MODE_PRIVATE)
        llmManager = OnDeviceLLMManager(this)
        downloadManager = ModelDownloadManager(this)
        modelRepository = ModelRepository(this)

        bindViews()
        setupBottomNavigation()

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

        if (canStartForegroundService()) {
            runStartupBlock("start_service_from_main_oncreate") { startTronProtocolService() }
        } else {
            Log.i(TAG, "Deferring service start until POST_NOTIFICATIONS is granted")
        }
        refreshStartupStateBadge()
        refreshDiagnosticsPanel()
        runStartupBlock("refresh_model_hub") { refreshModelHubCard() }
        runStartupBlock("refresh_api_keys") { refreshApiKeyList() }
        runStartupBlock("refresh_device_cap") { refreshDeviceCapabilities() }
    }

    override fun onStart() {
        super.onStart()
        runStartupBlock("start_service_if_deferred_from_boot") { startServiceIfDeferredFromBoot() }
        refreshStartupStateBadge()
        refreshDiagnosticsPanel()
    }

    override fun onDestroy() {
        super.onDestroy()
        llmSetupExecutor.shutdownNow()
        llmManager.shutdown()
        downloadManager?.shutdown()
    }

    // ========================================================================
    // View binding & tab navigation
    // ========================================================================

    private fun bindViews() {
        pluginCountText = findViewById(R.id.pluginCountText)
        permissionStatusText = findViewById(R.id.permissionStatusText)
        startupStateBadgeText = findViewById(R.id.startupStateBadgeText)
        permissionRationaleText = findViewById(R.id.permissionRationaleText)
        diagnosticsText = findViewById(R.id.diagnosticsText)
        conversationTranscriptText = findViewById(R.id.conversationTranscriptText)
        conversationInput = findViewById(R.id.conversationInput)
        messageShareStatusText = findViewById(R.id.messageShareStatusText)
        messageShareStatusText.text = ""
        modelHubStatusText = findViewById(R.id.modelHubStatusText)
        modelSelectedDetailsText = findViewById(R.id.modelSelectedDetailsText)
        apiKeyListContainer = findViewById(R.id.apiKeyListContainer)
        apiKeyEmptyText = findViewById(R.id.apiKeyEmptyText)
        pluginSummaryText = findViewById(R.id.pluginSummaryText)
        serviceStatusDetailText = findViewById(R.id.serviceStatusDetailText)
        deviceCapText = findViewById(R.id.deviceCapText)

        tabChat = findViewById(R.id.tabChat)
        tabModels = findViewById(R.id.tabModels)
        tabPlugins = findViewById(R.id.tabPlugins)
        tabSettings = findViewById(R.id.tabSettings)
        bottomNav = findViewById(R.id.bottomNav)

        pluginCoreContainer = findViewById(R.id.pluginCoreContainer)
        pluginAiContainer = findViewById(R.id.pluginAiContainer)
        pluginCommContainer = findViewById(R.id.pluginCommContainer)
        pluginToolsContainer = findViewById(R.id.pluginToolsContainer)
        pluginSafetyContainer = findViewById(R.id.pluginSafetyContainer)
    }

    private fun setupBottomNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chat -> showTab(tabChat)
                R.id.nav_models -> showTab(tabModels)
                R.id.nav_plugins -> showTab(tabPlugins)
                R.id.nav_settings -> showTab(tabSettings)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        // Default to chat tab
        showTab(tabChat)
    }

    private fun showTab(tab: View) {
        tabChat.visibility = View.GONE
        tabModels.visibility = View.GONE
        tabPlugins.visibility = View.GONE
        tabSettings.visibility = View.GONE
        tab.visibility = View.VISIBLE
    }

    // ========================================================================
    // Startup helpers
    // ========================================================================

    private fun runStartupBlock(name: String, block: () -> Unit) {
        try {
            block()
            StartupDiagnostics.recordMilestone(this, "${name}_success")
        } catch (t: Throwable) {
            StartupDiagnostics.recordError(this, name, t)
            Log.e(TAG, "Startup block failed: $name", t)
        }
    }

    // ========================================================================
    // Ktheme
    // ========================================================================

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

            // Root background
            findViewById<LinearLayout>(R.id.statusBar).setBackgroundColor(background)
            tabChat.setBackgroundColor(background)
            tabModels.setBackgroundColor(background)
            tabPlugins.setBackgroundColor(background)
            tabSettings.setBackgroundColor(background)

            // Bottom nav styling
            bottomNav.setBackgroundColor(surface)
            bottomNav.itemIconTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(primary, onSurface)
            )
            bottomNav.itemTextColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(primary, onSurface)
            )

            // Status bar area
            findViewById<TextView>(R.id.pluginCountText).setTextColor(onSurface)

            // All cards
            val cardIds = listOf(
                R.id.modelActiveCard, R.id.deviceCapCard,
                R.id.pluginSummaryCard, R.id.pluginCoreCard, R.id.pluginAiCard,
                R.id.pluginCommCard, R.id.pluginToolsCard, R.id.pluginSafetyCard,
                R.id.apiKeysCard, R.id.serviceCard, R.id.permissionsCard,
                R.id.integrationsCard, R.id.toolsCard, R.id.diagnosticsCard,
            )
            cardIds.forEach { id ->
                try {
                    findViewById<MaterialCardView>(id).setCardBackgroundColor(surface)
                } catch (_: Exception) {}
            }

            // Text colors
            conversationTranscriptText.setTextColor(onSurface)
            modelHubStatusText.setTextColor(onSurface)
            modelSelectedDetailsText.setTextColor(onSurface)
            permissionStatusText.setTextColor(onSurface)
            diagnosticsText.setTextColor(onSurface)
            pluginSummaryText.setTextColor(onSurface)
            serviceStatusDetailText.setTextColor(onSurface)
            deviceCapText.setTextColor(onSurface)
            apiKeyEmptyText.setTextColor(onSurface)
            messageShareStatusText.setTextColor(onSurface)

            // Send button
            findViewById<MaterialButton>(R.id.btnSendConversation).apply {
                backgroundTintList = ColorStateList.valueOf(primary)
                iconTint = ColorStateList.valueOf(onPrimary)
            }

            // Filled buttons
            listOf(R.id.btnModelBrowse, R.id.btnStartService).forEach { id ->
                findViewById<MaterialButton>(id).apply {
                    backgroundTintList = ColorStateList.valueOf(primary)
                    setTextColor(onPrimary)
                }
            }

            // Outlined buttons
            listOf(
                R.id.btnModelSelect, R.id.btnModelRecommend, R.id.btnModelStatus,
                R.id.btnDownloadInitModel,
                R.id.btnTelephonyFeature, R.id.btnSmsFeature, R.id.btnContactsFeature,
                R.id.btnLocationFeature, R.id.btnStorageFeature, R.id.btnNotificationsFeature,
                R.id.btnOpenBotFather, R.id.btnOpenPluginGuide, R.id.btnOpenKthemeRepo,
                R.id.btnImportPersonality,
                R.id.btnShareDocument, R.id.btnShareImage, R.id.btnShareMusic, R.id.btnShareLink,
            ).forEach { id ->
                try {
                    findViewById<MaterialButton>(id).apply {
                        strokeColor = ColorStateList.valueOf(primary)
                        setTextColor(primary)
                    }
                } catch (_: Exception) {}
            }

            // Text buttons
            listOf(
                R.id.btnAddApiKey, R.id.btnGrantAllFiles,
                R.id.btnExportDebugLog, R.id.btnRuntimeSelfCheck,
            ).forEach { id ->
                try {
                    findViewById<MaterialButton>(id).setTextColor(primary)
                } catch (_: Exception) {}
            }

            try {
                applyKthemeToAllTextViews(tabModels as ViewGroup, onSurface)
                applyKthemeToAllTextViews(tabPlugins as ViewGroup, onSurface)
                applyKthemeToAllTextViews(tabSettings as ViewGroup, onSurface)
            } catch (_: Exception) {}

        } catch (t: Throwable) {
            Log.w(TAG, "Unable to apply ktheme styling", t)
        }
    }

    private fun applyKthemeToAllTextViews(viewGroup: ViewGroup, color: Int) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is TextView && child !is EditText) {
                child.setTextColor(color)
            } else if (child is ViewGroup) {
                applyKthemeToAllTextViews(child, color)
            }
        }
    }

    // ========================================================================
    // UI wiring
    // ========================================================================

    private fun wireUiActions() {
        // Chat
        findViewById<MaterialButton>(R.id.btnSendConversation).setOnClickListener {
            sendConversationMessage()
        }

        // Permissions
        findViewById<MaterialButton>(R.id.btnTelephonyFeature).setOnClickListener {
            executeFeatureWithPermissions(PermissionFeature.TELEPHONY) {
                showPermissionMessage("Telephony plugin ready.")
            }
        }
        findViewById<MaterialButton>(R.id.btnSmsFeature).setOnClickListener {
            executeFeatureWithPermissions(PermissionFeature.SMS) {
                showPermissionMessage("SMS plugin ready.")
            }
        }
        findViewById<MaterialButton>(R.id.btnContactsFeature).setOnClickListener {
            executeFeatureWithPermissions(PermissionFeature.CONTACTS) {
                showPermissionMessage("Contacts plugin ready.")
            }
        }
        findViewById<MaterialButton>(R.id.btnLocationFeature).setOnClickListener {
            executeFeatureWithPermissions(PermissionFeature.LOCATION) {
                showPermissionMessage("Location plugin ready.")
            }
        }
        findViewById<MaterialButton>(R.id.btnStorageFeature).setOnClickListener {
            executeFeatureWithPermissions(PermissionFeature.STORAGE) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    showPermissionMessage("Grant All Files access to complete advanced file management.")
                    requestAllFilesAccess()
                } else {
                    showPermissionMessage("File manager plugin ready.")
                }
            }
        }
        findViewById<MaterialButton>(R.id.btnNotificationsFeature).setOnClickListener {
            executeFeatureWithPermissions(PermissionFeature.NOTIFICATIONS) {
                showPermissionMessage("Notifications enabled.")
            }
        }
        findViewById<MaterialButton>(R.id.btnGrantAllFiles).setOnClickListener {
            requestAllFilesAccess()
            refreshDiagnosticsPanel()
        }

        // Service
        findViewById<MaterialButton>(R.id.btnStartService).setOnClickListener {
            runStartupBlock("start_service_from_button") { startTronProtocolService() }
            refreshStartupStateBadge()
            Toast.makeText(this, "Service start requested", Toast.LENGTH_SHORT).show()
            refreshDiagnosticsPanel()
        }

        // Integrations
        findViewById<MaterialButton>(R.id.btnOpenBotFather).setOnClickListener {
            openExternalLink(BOTFATHER_URL, "Opening BotFather setup.")
        }
        findViewById<MaterialButton>(R.id.btnOpenPluginGuide).setOnClickListener {
            openExternalLink(PLUGIN_GUIDE_URL, "Opening plugin guide.")
        }
        findViewById<MaterialButton>(R.id.btnOpenKthemeRepo).setOnClickListener {
            openExternalLink(KTHEME_REPO_URL, "Opening Ktheme designer.")
        }

        // Sharing
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

        // Self-check
        findViewById<MaterialButton>(R.id.btnRuntimeSelfCheck).setOnClickListener {
            val manager = PluginManager.getInstance()
            val message = buildString {
                append(manager.runRuntimeSelfCheck())
                append("\n")
                append("Policy: ${manager.getRuntimePolicyStatus()}")
            }
            pluginSummaryText.text = message
            showPermissionMessage("Runtime self-check completed.")
        }

        // Model hub
        findViewById<MaterialButton>(R.id.btnModelBrowse).setOnClickListener {
            showModelCatalogDialog()
        }
        findViewById<MaterialButton>(R.id.btnModelSelect).setOnClickListener {
            showDownloadedModelsDialog()
        }
        findViewById<MaterialButton>(R.id.btnModelRecommend).setOnClickListener {
            showModelRecommendation()
        }
        findViewById<MaterialButton>(R.id.btnModelStatus).setOnClickListener {
            showModelStatus()
        }
        findViewById<MaterialButton>(R.id.btnDownloadInitModel).setOnClickListener {
            promptModelDownloadAndInit()
        }

        // Tools
        findViewById<MaterialButton>(R.id.btnImportPersonality).setOnClickListener {
            personalityImportLauncher.launch(arrayOf(
                "application/json",
                "text/plain",
                "text/markdown",
                "text/csv",
                "*/*"
            ))
        }
        findViewById<MaterialButton>(R.id.btnExportDebugLog).setOnClickListener {
            exportDebugLog()
        }

        // API Keys
        findViewById<MaterialButton>(R.id.btnAddApiKey).setOnClickListener {
            showAddApiKeyDialog()
        }
    }

    // ========================================================================
    // API Key Management UI
    // ========================================================================

    private fun showAddApiKeyDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val serviceInput = EditText(this).apply {
            hint = "Service name (e.g. anthropic, huggingface)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        layout.addView(serviceInput)

        val keyInput = EditText(this).apply {
            hint = "API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(keyInput)

        AlertDialog.Builder(this)
            .setTitle("Add API Key")
            .setMessage("Keys are encrypted with hardware-backed AES-256-GCM and stored on-device only.")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val service = serviceInput.text?.toString()?.trim().orEmpty()
                val key = keyInput.text?.toString()?.trim().orEmpty()
                if (service.isBlank() || key.isBlank()) {
                    showToast("Both service name and key are required.")
                    return@setPositiveButton
                }
                storeApiKey(service, key)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun storeApiKey(service: String, key: String) {
        val pluginManager = PluginManager.getInstance()
        val result = pluginManager.executePlugin("api_key_vault", "store|$service|$key")
        if (result.isSuccess) {
            showToast("Key saved for $service")
            // If this is the Anthropic key, also set it on guidance router
            if (service.lowercase() == "anthropic") {
                pluginManager.executePlugin("guidance_router", "set_api_key|$key")
            }
            // If this is HuggingFace token, set it on download manager
            if (service.lowercase() == "huggingface" || service.lowercase() == "hf") {
                downloadManager?.setHuggingFaceToken(key)
            }
            refreshApiKeyList()
        } else {
            showToast("Failed to save key: ${result.errorMessage}")
        }
    }

    private fun refreshApiKeyList() {
        apiKeyListContainer.removeAllViews()
        val pluginManager = PluginManager.getInstance()
        val result = pluginManager.executePlugin("api_key_vault", "list")

        if (!result.isSuccess) {
            apiKeyEmptyText.visibility = View.VISIBLE
            return
        }

        val servicesStr = result.data?.removePrefix("Stored services: ")?.trim().orEmpty()
        if (servicesStr.isBlank()) {
            apiKeyEmptyText.visibility = View.VISIBLE
            return
        }

        apiKeyEmptyText.visibility = View.GONE
        val services = servicesStr.split(",").map { it.trim() }.filter { it.isNotBlank() }

        for (service in services) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val nameText = TextView(this).apply {
                text = service
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(nameText)

            // Masked key preview
            val maskedResult = pluginManager.executePlugin("api_key_vault", "get|$service")
            val preview = TextView(this).apply {
                text = if (maskedResult.isSuccess) {
                    maskedResult.data?.substringAfter(": ")?.take(16) ?: "****"
                } else "****"
                textSize = 12f
                setTypeface(android.graphics.Typeface.MONOSPACE)
            }
            row.addView(preview)

            val deleteBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Del"
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 8 }
                minimumWidth = 0
                minWidth = 0
                setPadding(16, 0, 16, 0)
            }
            deleteBtn.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Delete API Key")
                    .setMessage("Remove the key for '$service'?")
                    .setPositiveButton("Delete") { _, _ ->
                        pluginManager.executePlugin("api_key_vault", "delete|$service")
                        showToast("Deleted key for $service")
                        refreshApiKeyList()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            row.addView(deleteBtn)

            apiKeyListContainer.addView(row)
        }
    }

    // ========================================================================
    // Plugin management (categorized)
    // ========================================================================

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

    /**
     * Plugin categories for the UI. Plugins are assigned based on their ID patterns.
     */
    private fun getPluginCategory(pluginId: String): String {
        return when {
            pluginId in CORE_PLUGINS -> "core"
            pluginId in AI_PLUGINS -> "ai"
            pluginId in COMM_PLUGINS -> "comm"
            pluginId in SAFETY_PLUGINS -> "safety"
            else -> "tools"
        }
    }

    private fun getCategoryContainer(category: String): LinearLayout {
        return when (category) {
            "core" -> pluginCoreContainer
            "ai" -> pluginAiContainer
            "comm" -> pluginCommContainer
            "safety" -> pluginSafetyContainer
            else -> pluginToolsContainer
        }
    }

    private fun renderPluginManagementUi() {
        pluginCoreContainer.removeAllViews()
        pluginAiContainer.removeAllViews()
        pluginCommContainer.removeAllViews()
        pluginToolsContainer.removeAllViews()
        pluginSafetyContainer.removeAllViews()

        val pluginManager = PluginManager.getInstance()

        for (config in PluginRegistry.sortedConfigs) {
            val category = getPluginCategory(config.id)
            val container = getCategoryContainer(category)

            val toggle = SwitchMaterial(this).apply {
                val enabled = isPluginEnabled(config.id, config.defaultEnabled)
                isChecked = enabled
                text = config.pluginClass.simpleName?.replace("Plugin", "") ?: config.id
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = 12
                    marginEnd = 12
                }
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

            container.addView(toggle)
        }

        updatePluginUiState()
    }

    private fun updatePluginUiState() {
        val pluginManager = PluginManager.getInstance()
        val enabledCount = PluginRegistry.sortedConfigs.count { isPluginEnabled(it.id, it.defaultEnabled) }
        val loadedCount = pluginManager.getAllPlugins().size
        val totalCount = PluginRegistry.sortedConfigs.size

        pluginCountText.text = "$loadedCount active"
        pluginSummaryText.text = "$enabledCount enabled / $totalCount total, $loadedCount loaded"
    }

    private fun isPluginEnabled(pluginId: String, defaultEnabled: Boolean): Boolean {
        return prefs.getBoolean(pluginPreferenceKey(pluginId), defaultEnabled)
    }

    private fun pluginPreferenceKey(pluginId: String): String = "plugin_enabled_$pluginId"

    // ========================================================================
    // Device capabilities
    // ========================================================================

    private fun refreshDeviceCapabilities() {
        val cap = llmManager.assessDevice()
        deviceCapText.text = buildString {
            append("RAM: ${cap.availableRamMb} MB free / ${cap.totalRamMb} MB total\n")
            append("CPU: ${cap.cpuArch} | ARM64: ${cap.supportsArm64}\n")
            append("GPU: ${if (cap.hasGpu) "Available" else "None"} | FP16: ${cap.supportsFp16}\n")
            append("Threads: ${cap.recommendedThreads} | LLM capable: ${if (cap.canRunLLM) "Yes" else "No"}\n")
            append("MNN: ${if (OnDeviceLLMManager.isNativeAvailable()) "Installed" else "Not installed"}")
        }
    }

    // ========================================================================
    // Permissions
    // ========================================================================

    private fun requestInitialAccess() {
        updatePermissionUi()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifPerms = PermissionFeature.NOTIFICATIONS.permissions
            val missing = notifPerms.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                activePermissionRequest = PermissionFeature.NOTIFICATIONS
                permissionLauncher.launch(missing.toTypedArray())
                showPermissionMessage("Notification permission is required for the background service.")
                return
            }
        }

        showPermissionMessage("Notifications enabled. Other permissions are requested on demand.")
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
            "Runtime: $granted/${allRuntimePermissions.size} | All files: ${if (allFilesGranted) "Yes" else "No"}"
    }

    private fun showPermissionMessage(message: String) {
        permissionRationaleText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ========================================================================
    // Service
    // ========================================================================

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
            TronProtocolService.STATE_BLOCKED_BY_PERMISSION -> R.color.service_status_blocked_background to "BLOCKED"
            TronProtocolService.STATE_DEGRADED -> R.color.service_status_degraded_background to "DEGRADED"
            else -> R.color.service_status_deferred_background to "DEFERRED"
        }

        startupStateBadgeText.setBackgroundColor(ContextCompat.getColor(this, colorRes))
        startupStateBadgeText.text = label
        serviceStatusDetailText.text = reason
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

        if (!canStartForegroundService()) {
            Log.i(TAG, "Deferred service start skipped — POST_NOTIFICATIONS not granted yet")
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

    private fun canStartForegroundService(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun startTronProtocolService() {
        val serviceIntent = Intent(this, TronProtocolService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    // ========================================================================
    // Diagnostics
    // ========================================================================

    private fun refreshDiagnosticsPanel() {
        val startupSummary = StartupDiagnostics.getEventsForDisplay(this)
        val retrievalSummary = StartupDiagnostics.getRetrievalDiagnosticsSummary(this, RAG_DIAGNOSTICS_AI_ID)
        diagnosticsText.text = "$startupSummary\n\n[RAG Telemetry]\n$retrievalSummary"
    }

    // ========================================================================
    // Conversation
    // ========================================================================

    private fun sendConversationMessage() {
        val userText = conversationInput.text?.toString()?.trim().orEmpty()
        if (userText.isBlank()) {
            showToast("Type a message before sending.")
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

        // Auto-scroll to bottom
        findViewById<ScrollView>(R.id.chatScrollView).post {
            findViewById<ScrollView>(R.id.chatScrollView).fullScroll(View.FOCUS_DOWN)
        }
    }

    // ========================================================================
    // Sharing
    // ========================================================================

    private fun startSharePicker(type: ShareType, mimeTypes: Array<String>) {
        pendingShareType = type
        shareDocumentLauncher.launch(mimeTypes)
    }

    private fun promptAndShareLink() {
        val input = EditText(this).apply {
            hint = "https://example.com/context"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(48, 24, 48, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("Share web link with AI")
            .setView(input)
            .setPositiveButton("Share") { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                if (url.isBlank()) {
                    showToast("Link was empty, nothing shared.")
                    return@setPositiveButton
                }
                val formatted = formatShareMessage("LINK", "Web context", url, NOTE_SHARED_BY_USER)
                messageShareStatusText.text = formatted
                showToast("Shared web link with AI.")
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    // ========================================================================
    // Model Hub
    // ========================================================================

    private fun refreshModelHubCard() {
        val repo = modelRepository ?: return
        val downloaded = repo.getAvailableModels()
        val selectedModel = repo.getSelectedModel()

        if (selectedModel != null) {
            modelHubStatusText.text = selectedModel.name
            modelSelectedDetailsText.visibility = View.VISIBLE
            modelSelectedDetailsText.text = buildString {
                append("${selectedModel.family} | ${selectedModel.parameterCount} | ${selectedModel.quantization}")
                append(" | ${selectedModel.diskUsageMb} MB")
            }
        } else if (downloaded.isNotEmpty()) {
            modelHubStatusText.text = "${downloaded.size} model(s) available"
            modelSelectedDetailsText.visibility = View.VISIBLE
            modelSelectedDetailsText.text = "Select one for inference"
        } else {
            modelHubStatusText.text = "No models downloaded"
            modelSelectedDetailsText.visibility = View.VISIBLE
            modelSelectedDetailsText.text = "Browse the catalog to get started"
        }
    }

    private fun showModelCatalogDialog() {
        val dm = downloadManager ?: return
        val entries = ModelCatalog.entries
        val cap = llmManager.assessDevice()

        val items = entries.map { entry ->
            val downloaded = dm.isModelDownloaded(entry.id)
            val fits = entry.ramRequirement.minRamMb <= cap.totalRamMb
            val status = when {
                downloaded -> "[Downloaded]"
                !fits -> "[Needs more RAM]"
                else -> "[${entry.sizeMb} MB]"
            }
            "$status ${entry.name} (${entry.parameterCount}, ${entry.quantization})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Model Catalog (${entries.size} models)")
            .setItems(items) { _, which ->
                val entry = entries[which]
                showCatalogEntryDialog(entry)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showCatalogEntryDialog(entry: ModelCatalog.CatalogEntry) {
        val dm = downloadManager ?: return
        val downloaded = dm.isModelDownloaded(entry.id)

        val message = buildString {
            append("${entry.description}\n\n")
            append("Family: ${entry.family}\n")
            append("Parameters: ${entry.parameterCount}\n")
            append("Quantization: ${entry.quantization}\n")
            append("Size: ${entry.sizeMb} MB\n")
            append("Context: ${entry.contextWindow} tokens\n")
            append("RAM: ${entry.ramRequirement.minRamMb} MB min, ${entry.ramRequirement.recommendedRamMb} MB recommended\n")
            append("GPU: ${if (entry.supportsGpu) "Yes" else "No"}\n")
            append("Source: ${entry.source}\n")
            if (downloaded) append("\nStatus: Downloaded")
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage(message)

        if (downloaded) {
            builder.setPositiveButton("Select") { _, _ ->
                selectAndLoadModel(entry.id)
            }
            builder.setNeutralButton("Delete") { _, _ ->
                confirmDeleteModel(entry.id, entry.name)
            }
        } else {
            builder.setPositiveButton("Download") { _, _ ->
                startModelDownload(entry)
            }
        }

        builder.setNegativeButton("Close", null)
        builder.show()
    }

    private fun showDownloadedModelsDialog() {
        val repo = modelRepository ?: return
        val models = repo.getAvailableModels()
        val selectedId = repo.getSelectedModelId()

        if (models.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("My Models")
                .setMessage("No models downloaded yet. Use 'Browse' to download one.")
                .setPositiveButton("Browse") { _, _ -> showModelCatalogDialog() }
                .setNegativeButton("Close", null)
                .show()
            return
        }

        val items = models.map { model ->
            val isSelected = model.id == selectedId
            val marker = if (isSelected) " [Active]" else ""
            "${model.name}$marker (${model.parameterCount}, ${model.diskUsageMb} MB)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("My Models (${models.size})")
            .setItems(items) { _, which ->
                val model = models[which]
                showDownloadedModelDialog(model)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showDownloadedModelDialog(model: ModelRepository.AvailableModel) {
        val repo = modelRepository ?: return
        val isSelected = model.id == repo.getSelectedModelId()
        val config = repo.getModelConfig(model.id)

        val message = buildString {
            append("Family: ${model.family}\n")
            append("Parameters: ${model.parameterCount}\n")
            append("Quantization: ${model.quantization}\n")
            append("Disk usage: ${model.diskUsageMb} MB\n")
            append("Context: ${model.contextWindow} tokens\n")
            append("Source: ${model.source}\n")
            append("Path: ${model.directory.absolutePath}\n")
            if (isSelected) append("\nCurrently selected for inference")
            if (config != null) {
                append("\n\nCustom config: temp=${config.temperature}, " +
                        "tokens=${config.maxTokens}, threads=${config.threadCount}")
            }
        }

        val items = mutableListOf<String>()
        if (!isSelected) items.add("Select for inference")
        items.add("Configure")
        items.add("Delete")

        AlertDialog.Builder(this)
            .setTitle(model.name)
            .setMessage(message)
            .setItems(items.toTypedArray()) { _, which ->
                val adjustedIndex = if (isSelected) which + 1 else which
                when (adjustedIndex) {
                    0 -> selectAndLoadModel(model.id)
                    1 -> showModelConfigDialog(model)
                    2 -> confirmDeleteModel(model.id, model.name)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showModelRecommendation() {
        val cap = llmManager.assessDevice()
        val recommended = ModelCatalog.recommendForDevice(cap.totalRamMb)
        val dm = downloadManager

        val message = buildString {
            append("Device RAM: ${cap.availableRamMb} MB available / ${cap.totalRamMb} MB total\n")
            append("SoC: ${ModelCatalog.DeviceSocInfo.getDeviceSoc()}\n\n")

            if (recommended != null) {
                val downloaded = dm?.isModelDownloaded(recommended.id) == true
                append("Recommended: ${recommended.name}\n")
                append("${recommended.parameterCount} params, ${recommended.quantization} quantization\n")
                append("${recommended.sizeMb} MB download\n")
                append("${recommended.description}\n")
                if (downloaded) {
                    append("\nThis model is already downloaded.")
                }
            } else {
                append("No model recommended for this device (insufficient RAM).\n")
                append("Minimum requirement: 2 GB available RAM.")
            }
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Model Recommendation")
            .setMessage(message)

        if (recommended != null) {
            val downloaded = dm?.isModelDownloaded(recommended.id) == true
            if (downloaded) {
                builder.setPositiveButton("Select") { _, _ ->
                    selectAndLoadModel(recommended.id)
                }
            } else {
                builder.setPositiveButton("Download") { _, _ ->
                    startModelDownload(recommended)
                }
            }
        }

        builder.setNegativeButton("Close", null)
        builder.show()
    }

    private fun showModelStatus() {
        val repo = modelRepository
        val dm = downloadManager
        val cap = llmManager.assessDevice()
        val hfToken = dm?.getHuggingFaceToken()

        val message = buildString {
            append("=== On-Device LLM Status ===\n\n")
            append("MNN Libraries: ${if (OnDeviceLLMManager.isNativeAvailable()) "Installed" else "Not installed"}\n")
            append("Catalog: ${ModelCatalog.entries.size} models\n")

            if (repo != null) {
                val downloaded = repo.getAvailableModels()
                append("Downloaded: ${downloaded.size} model(s)\n")
                val selected = repo.getSelectedModel()
                if (selected != null) {
                    append("Selected: ${selected.name}\n")
                }
                val totalDiskMb = downloaded.sumOf { it.diskUsageBytes } / (1024 * 1024)
                append("Disk usage: $totalDiskMb MB\n")
            }

            append("HF Token: ${if (hfToken != null) "Set (${hfToken.take(8)}...)" else "Not set"}\n")

            append("\n=== Device ===\n")
            append("RAM: ${cap.availableRamMb}/${cap.totalRamMb} MB\n")
            append("CPU: ${cap.cpuArch}\n")
            append("ARM64: ${cap.supportsArm64}\n")
            append("FP16: ${cap.supportsFp16}\n")
            append("GPU: ${cap.hasGpu}\n")
            append("Threads: ${cap.recommendedThreads}\n")
            append("Can run LLM: ${cap.canRunLLM}\n")
        }

        AlertDialog.Builder(this)
            .setTitle("Model Hub Status")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("HF Token") { _, _ ->
                showHfTokenDialog()
            }
            .show()
    }

    private fun showHfTokenDialog() {
        val dm = downloadManager ?: return
        val currentToken = dm.getHuggingFaceToken()

        val input = EditText(this).apply {
            hint = "hf_xxxxxxxxxxxx"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (currentToken != null) setText(currentToken)
            setPadding(48, 24, 48, 0)
        }

        AlertDialog.Builder(this)
            .setTitle("HuggingFace API Token")
            .setMessage("Set your HuggingFace token to download gated models.\n" +
                    "Get one at: huggingface.co/settings/tokens")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val token = input.text?.toString()?.trim()
                dm.setHuggingFaceToken(if (token.isNullOrBlank()) null else token)
                showToast(if (token.isNullOrBlank()) "HF token cleared" else "HF token saved")
            }
            .setNeutralButton("Clear") { _, _ ->
                dm.setHuggingFaceToken(null)
                showToast("HF token cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showModelConfigDialog(model: ModelRepository.AvailableModel) {
        val repo = modelRepository ?: return
        val config = repo.getModelConfig(model.id) ?: ModelRepository.ModelConfigOverrides()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val maxTokensInput = EditText(this).apply {
            hint = "Max tokens (default: 512)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(config.maxTokens.toString())
        }
        layout.addView(TextView(this).apply { text = "Max Tokens:" })
        layout.addView(maxTokensInput)

        val tempInput = EditText(this).apply {
            hint = "Temperature (default: 0.7)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(config.temperature.toString())
        }
        layout.addView(TextView(this).apply { text = "Temperature:" })
        layout.addView(tempInput)

        val topPInput = EditText(this).apply {
            hint = "Top-P (default: 0.9)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(config.topP.toString())
        }
        layout.addView(TextView(this).apply { text = "Top-P:" })
        layout.addView(topPInput)

        val threadsInput = EditText(this).apply {
            hint = "Thread count (default: 4)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(config.threadCount.toString())
        }
        layout.addView(TextView(this).apply { text = "Threads:" })
        layout.addView(threadsInput)

        AlertDialog.Builder(this)
            .setTitle("Configure: ${model.name}")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newConfig = ModelRepository.ModelConfigOverrides(
                    maxTokens = (maxTokensInput.text.toString().toIntOrNull() ?: 512).coerceIn(1, 8192),
                    temperature = (tempInput.text.toString().toFloatOrNull() ?: 0.7f).coerceIn(0.0f, 2.0f),
                    topP = (topPInput.text.toString().toFloatOrNull() ?: 0.9f).coerceIn(0.0f, 1.0f),
                    threadCount = (threadsInput.text.toString().toIntOrNull() ?: 4).coerceIn(1, 16),
                    backend = config.backend,
                    useMmap = config.useMmap
                )
                repo.setModelConfig(model.id, newConfig)
                showToast("Configuration saved for ${model.name}")
            }
            .setNeutralButton("Reset") { _, _ ->
                repo.removeModelConfig(model.id)
                showToast("Configuration reset for ${model.name}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startModelDownload(entry: ModelCatalog.CatalogEntry) {
        val dm = downloadManager ?: return

        val progressContainer = findViewById<MaterialCardView>(R.id.modelDownloadProgressContainer)
        val progressBar = findViewById<android.widget.ProgressBar>(R.id.modelDownloadProgressBar)
        val downloadStatusText = findViewById<TextView>(R.id.modelDownloadStatusText)

        progressContainer.visibility = View.VISIBLE
        downloadStatusText.text = "Starting download: ${entry.name}..."
        progressBar.progress = 0

        showToast("Downloading ${entry.name} (${entry.sizeMb} MB)...")

        val started = dm.downloadModel(entry) { progress ->
            runOnUiThread {
                when (progress.state) {
                    ModelDownloadManager.DownloadState.DOWNLOADING -> {
                        progressBar.progress = progress.progressPercent
                        val speedMb = progress.speedBytesPerSec / (1024f * 1024f)
                        val downloadedMb = progress.downloadedBytes / (1024f * 1024f)
                        val totalMb = progress.totalBytes / (1024f * 1024f)
                        downloadStatusText.text = String.format(
                            "%.1f / %.1f MB (%.1f MB/s)",
                            downloadedMb, totalMb, speedMb
                        )
                    }
                    ModelDownloadManager.DownloadState.EXTRACTING -> {
                        downloadStatusText.text = "Extracting ${entry.name}..."
                        progressBar.isIndeterminate = true
                    }
                    ModelDownloadManager.DownloadState.COMPLETED -> {
                        progressContainer.visibility = View.GONE
                        progressBar.isIndeterminate = false
                        showToast("Download complete: ${entry.name}")
                        refreshModelHubCard()
                    }
                    ModelDownloadManager.DownloadState.ERROR -> {
                        progressContainer.visibility = View.GONE
                        progressBar.isIndeterminate = false
                        showToast("Download failed: ${progress.errorMessage ?: "Unknown error"}")
                    }
                    ModelDownloadManager.DownloadState.CANCELLED -> {
                        progressContainer.visibility = View.GONE
                        progressBar.isIndeterminate = false
                    }
                    else -> { }
                }
            }
        }

        if (!started) {
            progressContainer.visibility = View.GONE
            showToast("Download already in progress for ${entry.name}")
        }
    }

    private fun selectAndLoadModel(modelId: String) {
        val repo = modelRepository ?: return
        repo.setSelectedModelId(modelId)
        refreshModelHubCard()

        val model = repo.getSelectedModel()
        if (model != null) {
            showToast("Selected: ${model.name}")

            if (OnDeviceLLMManager.isNativeAvailable()) {
                llmSetupExecutor.execute {
                    try {
                        val config = llmManager.createConfigFromDirectory(model.directory)
                        val loaded = llmManager.loadModel(config)
                        runOnUiThread {
                            if (loaded) {
                                showToast("Loaded: ${model.name} (${config.backendName})")
                            } else {
                                showToast("Selected ${model.name} but failed to load model")
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            showToast("Selected ${model.name} but error loading: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun confirmDeleteModel(modelId: String, modelName: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete model")
            .setMessage("Delete '$modelName' from device? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                downloadManager?.deleteModel(modelId)
                modelRepository?.removeImportedModel(modelId)
                val repo = modelRepository
                if (repo?.getSelectedModelId() == modelId) {
                    repo.setSelectedModelId(null)
                }
                refreshModelHubCard()
                showToast("Deleted: $modelName")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptModelDownloadAndInit() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val modelNameInput = EditText(this).apply {
            hint = "Qwen2.5-1.5B-Instruct"
            setText("Qwen2.5-1.5B-Instruct")
        }

        val modelUrlInput = EditText(this).apply {
            hint = "https://example.com/model.zip"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        layout.addView(TextView(this).apply { text = "Model name:" })
        layout.addView(modelNameInput)
        layout.addView(TextView(this).apply { text = "Download URL:" })
        layout.addView(modelUrlInput)

        AlertDialog.Builder(this)
            .setTitle("Download & initialize LLM")
            .setView(layout)
            .setPositiveButton("Start") { _, _ ->
                val modelName = modelNameInput.text?.toString()?.trim().orEmpty()
                val modelUrl = modelUrlInput.text?.toString()?.trim().orEmpty()
                if (modelName.isBlank() || modelUrl.isBlank()) {
                    showToast("Provide both model name and URL.")
                    return@setPositiveButton
                }

                val uri = android.net.Uri.parse(modelUrl)
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    showToast("Invalid URL scheme: only http and https are allowed.")
                    return@setPositiveButton
                }

                showToast("Downloading model package...")
                llmSetupExecutor.execute {
                    val result = llmManager.downloadAndInitializeModel(modelName, modelUrl)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (result.success) {
                            val sizeMb = result.downloadedBytes / (1024f * 1024f)
                            showToast(
                                "Model ready: ${result.config?.modelName} (${String.format("%.1f", sizeMb)} MB)"
                            )
                        } else {
                            showToast("Model setup failed: ${result.error}")
                        }
                        refreshDiagnosticsPanel()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ========================================================================
    // Personality import
    // ========================================================================

    private fun handleBulkPersonalityImport(uris: List<Uri>) {
        llmSetupExecutor.execute {
            try {
                val manager = PluginManager.getInstance()
                val plugin = manager.getPlugin("personality_import")
                if (plugin == null) {
                    runOnUiThread { showToast("Personality Import plugin not available.") }
                    return@execute
                }

                val fileCount = uris.size
                runOnUiThread { showToast("Importing $fileCount file(s)…") }

                // Copy all selected files to temp storage
                val tempFiles = mutableListOf<File>()
                for (uri in uris) {
                    val displayName = resolveDisplayName(uri) ?: "import_file_${tempFiles.size}"
                    val tempFile = File(cacheDir, "personality_import_$displayName")
                    val copied = contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                        true
                    } ?: false

                    if (copied) {
                        tempFiles.add(tempFile)
                    } else {
                        Log.w("MainActivity", "Failed to read file: $displayName")
                    }
                }

                if (tempFiles.isEmpty()) {
                    runOnUiThread { showToast("Failed to read any selected files.") }
                    return@execute
                }

                // Use import_bulk for multiple files, import for single file
                val result = if (tempFiles.size == 1) {
                    plugin.execute("import|${tempFiles[0].absolutePath}")
                } else {
                    val paths = tempFiles.joinToString("\n") { it.absolutePath }
                    plugin.execute("import_bulk|$paths")
                }

                // Clean up temp files
                tempFiles.forEach { it.delete() }

                runOnUiThread {
                    if (result.isSuccess) {
                        val msg = if (fileCount == 1) "Import successful!" else "Imported $fileCount files successfully!"
                        showToast(msg)
                    } else {
                        showToast("Import failed: ${result.errorMessage}")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showToast("Import error: ${e.message}")
                }
            }
        }
    }

    // ========================================================================
    // Debug log
    // ========================================================================

    private fun exportDebugLog() {
        llmSetupExecutor.execute {
            val llmStats = llmManager.getStats().entries.joinToString(separator = "\n") { (key, value) ->
                "$key=$value"
            }
            StartupDiagnostics.setDebugSection("llm_manager", llmStats)

            val file = StartupDiagnostics.exportDebugLog(this)
            runOnUiThread {
                showToast("Debug log exported: ${file.absolutePath}")
                messageShareStatusText.text = "Debug log: ${file.absolutePath}"
            }
        }
    }

    // ========================================================================
    // External links
    // ========================================================================

    private fun openExternalLink(url: String, feedbackMessage: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            showToast(feedbackMessage)
        } catch (t: Throwable) {
            showToast("Unable to open link.")
            StartupDiagnostics.recordError(this, "open_external_link_failed", t)
        }
    }

    // ========================================================================
    // Constants
    // ========================================================================

    companion object {
        private const val FIRST_LAUNCH_KEY = "is_first_launch"
        private const val TAG = "MainActivity"
        private const val BOTFATHER_URL = "https://t.me/BotFather"
        private const val PLUGIN_GUIDE_URL = "https://github.com/Kaleaon/TronProtocol#plugin-system-inspired-by-toolneuron"
        private const val KTHEME_REPO_URL = "https://github.com/kaleaon/Ktheme"
        private const val MESSAGE_SEPARATOR = " \u00B7 "
        private const val NOTE_SHARED_FROM_DEVICE_PICKER = "shared from device picker"
        private const val NOTE_SHARED_BY_USER = "shared by user"
        private const val GUIDANCE_ROUTER_PLUGIN_ID = "guidance_router"
        private const val GUIDANCE_ROUTER_GUIDE_COMMAND_PREFIX = "guide|"
        private const val RAG_DIAGNOSTICS_AI_ID = "tronprotocol_ai"

        // Plugin categorization
        private val CORE_PLUGINS = setOf(
            "policy_guardrail", "phylactery", "on_device_llm", "inference_router",
            "device_info", "api_key_vault"
        )
        private val AI_PLUGINS = setOf(
            "guidance_router", "frontier_dynamics", "personalization", "personality_import",
            "takens_training", "continuity_bridge", "drift_detector", "wisdom_log",
            "hedonic", "nct", "react_planner", "prompt_strategy_evolution",
            "ab_testing", "goal_hierarchy", "preference_crystallization",
            "identity_narrative", "relationship_model", "mood_decision_router",
            "error_pattern_learning", "conversation_summary", "tone_adaptation",
        )
        private val COMM_PLUGINS = setOf(
            "telegram_bridge", "communication_hub", "proactive_messaging",
            "sms_send", "email", "voice_synthesis", "communication_scheduler",
            "multi_party_conversation", "contact_manager",
        )
        private val SAFETY_PLUGINS = setOf(
            "kill_switch", "privacy_budget", "user_override_history",
            "audit_dashboard", "granular_consent", "action_explanation",
            "value_alignment",
        )
    }
}

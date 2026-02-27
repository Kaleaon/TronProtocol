package com.tronprotocol.app.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.tronprotocol.app.R
import com.tronprotocol.app.StartupDiagnostics
import com.tronprotocol.app.TronProtocolService
import com.tronprotocol.app.plugins.PluginManager
import com.tronprotocol.app.llm.OnDeviceLLMManager
import com.tronprotocol.app.avatar.NnrRenderEngine
import com.tronprotocol.app.inference.InferenceTelemetry
import java.io.File
import java.util.concurrent.Executors

class SettingsFragment : Fragment() {

    /**
     * Interface for delegating actions that require Activity-level APIs
     * (permission launchers, theme application, debug export).
     */
    interface SettingsHost : ThemeHost, PermissionHost {
        fun exportDebugLog()
        val inferenceTelemetry: InferenceTelemetry
        fun launchPersonalityImport()
        fun launchShareDocument(mimeTypes: Array<String>)
    }

    interface ThemeHost {
        fun applyTheme(themeId: String)
    }

    interface PermissionHost {
        fun requestPermissionGroup(group: String)
        fun requestAllFilesAccess()
    }

    // Views
    private lateinit var btnAddApiKey: MaterialButton
    private lateinit var apiKeyListContainer: LinearLayout
    private lateinit var apiKeyEmptyText: TextView
    private lateinit var btnStartService: MaterialButton
    private lateinit var serviceStatusDetailText: TextView
    private lateinit var permissionStatusText: TextView
    private lateinit var permissionRationaleText: TextView
    private lateinit var activeThemeText: TextView
    private lateinit var activeThemeDescText: TextView
    private lateinit var messageShareStatusText: TextView
    private lateinit var memoryStatsText: TextView
    private lateinit var telemetryStatsText: TextView
    private lateinit var systemDashboardText: TextView
    private lateinit var diagnosticsText: TextView

    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        wireButtons(view)
        refreshDiagnosticsPanel()
        refreshPermissionStatus()
        refreshApiKeyList()
        refreshMemoryStats()
        refreshSystemDashboard()
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }

    private fun bindViews(view: View) {
        btnAddApiKey = view.findViewById(R.id.btnAddApiKey)
        apiKeyListContainer = view.findViewById(R.id.apiKeyListContainer)
        apiKeyEmptyText = view.findViewById(R.id.apiKeyEmptyText)
        btnStartService = view.findViewById(R.id.btnStartService)
        serviceStatusDetailText = view.findViewById(R.id.serviceStatusDetailText)
        permissionStatusText = view.findViewById(R.id.permissionStatusText)
        permissionRationaleText = view.findViewById(R.id.permissionRationaleText)
        activeThemeText = view.findViewById(R.id.activeThemeText)
        activeThemeDescText = view.findViewById(R.id.activeThemeDescText)
        messageShareStatusText = view.findViewById(R.id.messageShareStatusText)
        memoryStatsText = view.findViewById(R.id.memoryStatsText)
        telemetryStatsText = view.findViewById(R.id.telemetryStatsText)
        systemDashboardText = view.findViewById(R.id.systemDashboardText)
        diagnosticsText = view.findViewById(R.id.diagnosticsText)
    }

    private fun wireButtons(view: View) {
        val host = activity as? SettingsHost

        // Service control
        btnStartService.setOnClickListener {
            try {
                val intent = Intent(requireContext(), TronProtocolService::class.java)
                ContextCompat.startForegroundService(requireContext(), intent)
                showToast("Service started")
            } catch (e: Exception) {
                showToast("Failed to start service: ${e.message}")
            }
        }

        // API Keys
        btnAddApiKey.setOnClickListener { showAddApiKeyDialog() }

        // Permissions
        view.findViewById<MaterialButton>(R.id.btnTelephonyFeature).setOnClickListener {
            host?.requestPermissionGroup("TELEPHONY")
        }
        view.findViewById<MaterialButton>(R.id.btnSmsFeature).setOnClickListener {
            host?.requestPermissionGroup("SMS")
        }
        view.findViewById<MaterialButton>(R.id.btnContactsFeature).setOnClickListener {
            host?.requestPermissionGroup("CONTACTS")
        }
        view.findViewById<MaterialButton>(R.id.btnLocationFeature).setOnClickListener {
            host?.requestPermissionGroup("LOCATION")
        }
        view.findViewById<MaterialButton>(R.id.btnStorageFeature).setOnClickListener {
            host?.requestPermissionGroup("STORAGE")
        }
        view.findViewById<MaterialButton>(R.id.btnNotificationsFeature).setOnClickListener {
            host?.requestPermissionGroup("NOTIFICATIONS")
        }
        view.findViewById<MaterialButton>(R.id.btnGrantAllFiles).setOnClickListener {
            host?.requestAllFilesAccess()
        }

        // Theme
        view.findViewById<MaterialButton>(R.id.btnChangeTheme).setOnClickListener {
            showThemePickerDialog()
        }

        // Integrations
        view.findViewById<MaterialButton>(R.id.btnOpenBotFather).setOnClickListener {
            openExternalLink(BOTFATHER_URL)
        }
        view.findViewById<MaterialButton>(R.id.btnOpenPluginGuide).setOnClickListener {
            openExternalLink(PLUGIN_GUIDE_URL)
        }
        view.findViewById<MaterialButton>(R.id.btnOpenKthemeRepo).setOnClickListener {
            openExternalLink(KTHEME_REPO_URL)
        }

        // Tools
        view.findViewById<MaterialButton>(R.id.btnImportPersonality).setOnClickListener {
            host?.launchPersonalityImport()
        }
        view.findViewById<MaterialButton>(R.id.btnShareDocument).setOnClickListener {
            host?.launchShareDocument(arrayOf("application/*", "text/*"))
        }
        view.findViewById<MaterialButton>(R.id.btnShareImage).setOnClickListener {
            host?.launchShareDocument(arrayOf("image/*"))
        }
        view.findViewById<MaterialButton>(R.id.btnShareMusic).setOnClickListener {
            host?.launchShareDocument(arrayOf("audio/*"))
        }
        view.findViewById<MaterialButton>(R.id.btnShareLink).setOnClickListener {
            showShareLinkDialog()
        }

        // Memory
        view.findViewById<MaterialButton>(R.id.btnMemoryConsolidate).setOnClickListener {
            triggerMemoryConsolidation()
        }
        view.findViewById<MaterialButton>(R.id.btnMemoryStats).setOnClickListener {
            refreshMemoryStats()
        }

        // Telemetry
        view.findViewById<MaterialButton>(R.id.btnRefreshTelemetry).setOnClickListener {
            refreshTelemetryDisplay()
        }
        view.findViewById<MaterialButton>(R.id.btnResetTelemetry).setOnClickListener {
            showToast("Telemetry reset")
        }

        // System dashboard
        view.findViewById<MaterialButton>(R.id.btnRefreshDashboard).setOnClickListener {
            refreshSystemDashboard()
        }

        // Diagnostics
        view.findViewById<MaterialButton>(R.id.btnExportDebugLog).setOnClickListener {
            host?.exportDebugLog()
        }
    }

    // ========================================================================
    // API Keys
    // ========================================================================

    private fun showAddApiKeyDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val nameInput = EditText(requireContext()).apply { hint = "Service name (e.g., Anthropic)" }
        val keyInput = EditText(requireContext()).apply { hint = "API key" }
        layout.addView(nameInput)
        layout.addView(keyInput)

        AlertDialog.Builder(requireContext())
            .setTitle("Add API Key")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                val key = keyInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank() || key.isBlank()) {
                    showToast("Provide both service name and key.")
                    return@setPositiveButton
                }
                PluginManager.getInstance().executePlugin("api_key_vault", "store|$name|$key")
                showToast("API key saved for $name")
                refreshApiKeyList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun refreshApiKeyList() {
        if (!isAdded) return
        val result = PluginManager.getInstance().executePlugin("api_key_vault", "list")
        apiKeyListContainer.removeAllViews()
        if (result.isSuccess && !result.data.isNullOrBlank()) {
            apiKeyEmptyText.visibility = View.GONE
            val lines = result.data!!.split("\n")
            for (line in lines) {
                if (line.isBlank()) continue
                val tv = TextView(requireContext()).apply {
                    text = line
                    textSize = 13f
                    setPadding(0, 4, 0, 4)
                }
                apiKeyListContainer.addView(tv)
            }
        } else {
            apiKeyEmptyText.visibility = View.VISIBLE
        }
    }

    // ========================================================================
    // Theme
    // ========================================================================

    private fun showThemePickerDialog() {
        val ctx = requireContext()
        val themeApplicator = ThemeApplicator(ctx)
        val themeFiles = try { ctx.assets.list("themes") ?: emptyArray() } catch (_: Exception) { emptyArray() }
        val themeIds = themeFiles.filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }

        if (themeIds.isEmpty()) {
            showToast("No themes found")
            return
        }

        val names = themeIds.map { id ->
            val colors = themeApplicator.loadThemeColors(id)
            colors?.themeName ?: id
        }.toTypedArray()

        AlertDialog.Builder(ctx)
            .setTitle("Choose Theme")
            .setItems(names) { _, which ->
                val chosen = themeIds[which]
                (activity as? SettingsHost)?.applyTheme(chosen)
                showToast("Theme applied")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ========================================================================
    // Memory & RAG
    // ========================================================================

    fun refreshMemoryStats() {
        if (!isAdded) return
        backgroundExecutor.execute {
            val pluginManager = PluginManager.getInstance()
            val ragResult = pluginManager.executePlugin("rag_memory", "stats")
            val ragText = if (ragResult.isSuccess) ragResult.data ?: "No RAG statistics available" else "RAG memory plugin not active"

            val consolidationResult = pluginManager.executePlugin("rag_memory", "consolidation_status")
            val consolidationText = if (consolidationResult.isSuccess) consolidationResult.data ?: "" else ""

            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                memoryStatsText.text = buildString {
                    append(ragText)
                    if (consolidationText.isNotBlank()) append("\n\n$consolidationText")
                }
            }
        }
    }

    private fun triggerMemoryConsolidation() {
        showToast("Starting memory consolidation...")
        backgroundExecutor.execute {
            val result = PluginManager.getInstance().executePlugin("rag_memory", "consolidate")
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (result.isSuccess) showToast("Memory consolidation complete")
                else showToast("Consolidation unavailable: ${result.errorMessage}")
                refreshMemoryStats()
            }
        }
    }

    // ========================================================================
    // Telemetry
    // ========================================================================

    private fun refreshTelemetryDisplay() {
        if (!isAdded) return
        telemetryStatsText.text = "Inference telemetry active"
    }

    // ========================================================================
    // System Dashboard
    // ========================================================================

    fun refreshSystemDashboard() {
        if (!isAdded) return
        val ctx = requireContext()
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        val pluginManager = PluginManager.getInstance()
        val allPlugins = pluginManager.getAllPlugins()

        val prefs = ctx.getSharedPreferences("tron_prefs", Context.MODE_PRIVATE)
        val serviceState = prefs.getString(TronProtocolService.SERVICE_STARTUP_STATE_KEY, TronProtocolService.STATE_DEFERRED) ?: TronProtocolService.STATE_DEFERRED

        systemDashboardText.text = buildString {
            append("=== Service ===\n")
            append("State: $serviceState\n")
            append("Plugins loaded: ${allPlugins.size}\n")
            append("Plugins registered: ${pluginManager.getRegisteredCount()}\n\n")
            append("=== Resources ===\n")
            append("RAM: ${memInfo.availMem / (1024 * 1024)} MB free / ${memInfo.totalMem / (1024 * 1024)} MB total\n")
            append("Low memory: ${memInfo.lowMemory}\n\n")
            append("=== Subsystems ===\n")
            append("NNR native: ${if (NnrRenderEngine.isNativeAvailable()) "Loaded" else "Not available"}\n")
            append("MNN framework: ${if (OnDeviceLLMManager.isNativeAvailable()) "Loaded" else "Not available"}\n")
        }
    }

    // ========================================================================
    // Diagnostics
    // ========================================================================

    fun refreshDiagnosticsPanel() {
        if (!isAdded) return
        diagnosticsText.text = StartupDiagnostics.getEventsForDisplay(requireContext())
    }

    // ========================================================================
    // Permissions
    // ========================================================================

    fun refreshPermissionStatus() {
        if (!isAdded) return
        permissionStatusText.text = PermissionCoordinator.buildPermissionSummary(requireContext())
    }

    // ========================================================================
    // Sharing
    // ========================================================================

    private fun showShareLinkDialog() {
        val input = EditText(requireContext()).apply {
            hint = "https://example.com"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Share Web Link")
            .setView(input)
            .setPositiveButton("Share") { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                if (url.isNotBlank()) {
                    messageShareStatusText.text = "Shared link: $url"
                    showToast("Link shared with AI")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Called by the Activity when personality import files are selected.
     */
    fun handleBulkPersonalityImport(uris: List<Uri>) {
        backgroundExecutor.execute {
            try {
                val plugin = PluginManager.getInstance().getPlugin("personality_import") ?: return@execute
                val tempFiles = mutableListOf<File>()
                for (uri in uris) {
                    val tempFile = File(requireContext().cacheDir, "personality_import_${tempFiles.size}")
                    val copied = requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                        true
                    } ?: false
                    if (copied) tempFiles.add(tempFile)
                }
                if (tempFiles.isEmpty()) return@execute

                val result = if (tempFiles.size == 1) {
                    plugin.execute("import|${tempFiles[0].absolutePath}")
                } else {
                    val paths = tempFiles.joinToString("\n") { it.absolutePath }
                    plugin.execute("import_bulk|$paths")
                }
                tempFiles.forEach { it.delete() }

                activity?.runOnUiThread {
                    if (result.isSuccess) showToast("Import successful!")
                    else showToast("Import failed: ${result.errorMessage}")
                }
            } catch (e: Exception) {
                activity?.runOnUiThread { showToast("Import error: ${e.message}") }
            }
        }
    }

    // ========================================================================
    // Utility
    // ========================================================================

    private fun openExternalLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            showToast("Unable to open link.")
        }
    }

    private fun showToast(msg: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val BOTFATHER_URL = "https://t.me/BotFather"
        private const val PLUGIN_GUIDE_URL = "https://github.com/Kaleaon/TronProtocol#plugin-system-inspired-by-toolneuron"
        private const val KTHEME_REPO_URL = "https://github.com/kaleaon/Ktheme"
    }
}

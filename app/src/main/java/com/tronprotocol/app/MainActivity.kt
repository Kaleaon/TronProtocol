package com.tronprotocol.app

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.tronprotocol.app.affect.AffectOrchestrator
import com.tronprotocol.app.inference.AIContextManager
import com.tronprotocol.app.inference.InferenceTelemetry
import com.tronprotocol.app.inference.PromptTemplateEngine
import com.tronprotocol.app.inference.ResponseQualityScorer
import com.tronprotocol.app.plugins.PluginManager
import com.tronprotocol.app.plugins.PluginRegistry
import com.tronprotocol.app.ui.ChatFragment
import com.tronprotocol.app.ui.AvatarFragment
import com.tronprotocol.app.ui.ModelHubFragment
import com.tronprotocol.app.ui.PluginManagementFragment
import com.tronprotocol.app.ui.SettingsFragment
import com.tronprotocol.app.ui.ThemeApplicator

/**
 * Thin-shell Activity that hosts 5 tab fragments and owns permission launchers + service lifecycle.
 *
 * After refactoring (Phase 2), tab-specific logic lives in:
 * - [ChatFragment]
 * - [AvatarFragment]
 * - [ModelHubFragment]
 * - [PluginManagementFragment]
 * - [SettingsFragment]
 */
class MainActivity : AppCompatActivity(), SettingsFragment.SettingsHost {

    // --- Shell views ---
    private lateinit var pluginCountText: TextView
    private lateinit var startupStateBadgeText: TextView
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var prefs: SharedPreferences
    private lateinit var themeApplicator: ThemeApplicator

    // --- Subsystems owned by the Activity (shared across fragments) ---
    lateinit var aiContextManager: AIContextManager
        private set
    lateinit var promptTemplateEngine: PromptTemplateEngine
        private set
    lateinit var responseQualityScorer: ResponseQualityScorer
        private set
    lateinit var inferenceTelemetry: InferenceTelemetry
        private set
    var affectOrchestrator: AffectOrchestrator? = null
        private set

    private val uiHandler = Handler(Looper.getMainLooper())
    private var affectUpdateRunnable: Runnable? = null

    // --- Fragments (cached) ---
    private val chatFragment by lazy { ChatFragment() }
    private val avatarFragment by lazy { AvatarFragment() }
    private val modelHubFragment by lazy { ModelHubFragment() }
    private val pluginManagementFragment by lazy { PluginManagementFragment() }
    private val settingsFragment by lazy { SettingsFragment() }
    private var activeFragment: Fragment? = null

    // --- Permission handling ---
    private var activePermissionGroup: String? = null

    val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { granted -> !granted }.keys
            if (denied.isEmpty()) {
                showToast("Permission granted.")
            } else {
                showToast("Some permissions were denied.")
            }
            activePermissionGroup = null
            refreshStartupStateBadge()

            if (denied.isEmpty() && canStartForegroundService()) {
                runStartupBlock("start_service_after_permission_grant") {
                    startTronProtocolService()
                }
            }
        }

    private val personalityImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            // Delegate to settings fragment
            settingsFragment.handleBulkPersonalityImport(uris)
        }

    private val shareDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val displayName = resolveDisplayName(uri) ?: "File ($mimeType)"
            showToast("Shared: $displayName")
        }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(BootReceiver.PREFS_NAME, MODE_PRIVATE)
        themeApplicator = ThemeApplicator(this)

        runStartupBlock("init_affect_orchestrator") {
            affectOrchestrator = AffectOrchestrator(this)
        }
        runStartupBlock("init_ai_inference_subsystems") {
            aiContextManager = AIContextManager()
            promptTemplateEngine = PromptTemplateEngine()
            responseQualityScorer = ResponseQualityScorer()
            inferenceTelemetry = InferenceTelemetry(this)
        }

        bindShellViews()
        setupBottomNavigation()

        runStartupBlock("apply_ktheme") { applyKthemeToShell() }
        runStartupBlock("initialize_plugins") { initializePlugins() }

        if (prefs.getBoolean(FIRST_LAUNCH_KEY, true)) {
            runStartupBlock("request_initial_access") {
                permissionLauncher.launch(
                    com.tronprotocol.app.ui.PermissionCoordinator.getAllPermissions()
                )
                prefs.edit().putBoolean(FIRST_LAUNCH_KEY, false).apply()
            }
        }

        runStartupBlock("request_battery_optimization_exemption") { requestBatteryOptimizationExemption() }

        if (canStartForegroundService()) {
            runStartupBlock("start_service_from_main_oncreate") { startTronProtocolService() }
        } else {
            Log.i(TAG, "Deferring service start until POST_NOTIFICATIONS is granted")
        }

        refreshStartupStateBadge()
        runStartupBlock("start_affect_system") { startAffectSystem() }

        handleIncomingShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        runStartupBlock("start_service_if_deferred_from_boot") { startServiceIfDeferredFromBoot() }
        refreshStartupStateBadge()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAffectUpdates()
        affectOrchestrator?.stop()
    }

    // ========================================================================
    // View binding & tab navigation
    // ========================================================================

    private fun bindShellViews() {
        pluginCountText = findViewById(R.id.pluginCountText)
        startupStateBadgeText = findViewById(R.id.startupStateBadgeText)
        bottomNav = findViewById(R.id.bottomNav)
    }

    private fun setupBottomNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chat -> showFragment(chatFragment)
                R.id.nav_avatar -> showFragment(avatarFragment)
                R.id.nav_models -> showFragment(modelHubFragment)
                R.id.nav_plugins -> showFragment(pluginManagementFragment)
                R.id.nav_settings -> showFragment(settingsFragment)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        // Default to chat tab
        showFragment(chatFragment)
    }

    private fun showFragment(fragment: Fragment) {
        if (fragment === activeFragment) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
        activeFragment = fragment
    }

    // ========================================================================
    // Plugin initialization (lazy)
    // ========================================================================

    private fun initializePlugins() {
        val pluginManager = PluginManager.getInstance()
        pluginManager.initialize(this)

        for (config in PluginRegistry.sortedConfigs) {
            val enabled = prefs.getBoolean("plugin_enabled_${config.id}", config.defaultEnabled)
            if (enabled) {
                pluginManager.registerLazy(config)
            }
        }

        // Eagerly init critical safety plugin
        pluginManager.ensureInitialized("policy_guardrail")
        pluginManager.ensureInitialized("rag_memory")

        val count = pluginManager.getRegisteredCount()
        pluginCountText.text = "$count plugins"
        Log.d(TAG, "Registered $count plugins (lazy)")
    }

    // ========================================================================
    // Service lifecycle
    // ========================================================================

    fun startTronProtocolService() {
        try {
            val intent = Intent(this, TronProtocolService::class.java)
            ContextCompat.startForegroundService(this, intent)
            updateServiceState(TronProtocolService.STATE_RUNNING, "foreground_launch")
        } catch (e: Exception) {
            updateServiceState(TronProtocolService.STATE_DEGRADED, "start_failed: ${e.message}")
            Log.e(TAG, "Failed to start service", e)
        }
    }

    private fun startServiceIfDeferredFromBoot() {
        val state = prefs.getString(TronProtocolService.SERVICE_STARTUP_STATE_KEY, TronProtocolService.STATE_DEFERRED)
        if (state == TronProtocolService.STATE_DEFERRED && canStartForegroundService()) {
            startTronProtocolService()
        }
    }

    private fun canStartForegroundService(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun updateServiceState(state: String, reason: String) {
        prefs.edit()
            .putString(TronProtocolService.SERVICE_STARTUP_STATE_KEY, state)
            .putString(TronProtocolService.SERVICE_STARTUP_REASON_KEY, reason)
            .apply()
    }

    fun refreshStartupStateBadge() {
        val state = prefs.getString(TronProtocolService.SERVICE_STARTUP_STATE_KEY, TronProtocolService.STATE_DEFERRED) ?: TronProtocolService.STATE_DEFERRED
        startupStateBadgeText.text = state.uppercase()
        val colorRes = when (state) {
            TronProtocolService.STATE_RUNNING -> R.color.service_status_running_background
            TronProtocolService.STATE_DEGRADED -> R.color.service_status_degraded_background
            TronProtocolService.STATE_BLOCKED_BY_PERMISSION -> R.color.service_status_blocked_background
            else -> R.color.service_status_deferred_background
        }
        startupStateBadgeText.setBackgroundColor(ContextCompat.getColor(this, colorRes))
    }

    // ========================================================================
    // SettingsHost implementation
    // ========================================================================

    override fun applyTheme(themeId: String) {
        prefs.edit().putString(ThemeApplicator.PREF_SELECTED_THEME, themeId).apply()
        applyKthemeToShell()
    }

    override fun requestPermissionGroup(group: String) {
        val perms = com.tronprotocol.app.ui.PermissionCoordinator.PermissionGroup.entries
            .firstOrNull { it.name.equals(group, ignoreCase = true) }
            ?.permissions ?: return

        activePermissionGroup = group
        permissionLauncher.launch(perms.toTypedArray())
    }

    override fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e: Exception) {
                showToast("Unable to open All Files settings.")
            }
        }
    }

    override fun exportDebugLog() {
        val file = StartupDiagnostics.exportDebugLog(this)
        showToast("Debug log exported: ${file.absolutePath}")
    }

    override fun getInferenceTelemetry(): InferenceTelemetry = inferenceTelemetry

    override fun launchPersonalityImport() {
        personalityImportLauncher.launch(arrayOf("*/*"))
    }

    override fun launchShareDocument(mimeTypes: Array<String>) {
        shareDocumentLauncher.launch(mimeTypes)
    }

    // ========================================================================
    // Affect system
    // ========================================================================

    private fun startAffectSystem() {
        val orchestrator = affectOrchestrator ?: return
        orchestrator.start()
        startAffectUpdates()
    }

    private fun startAffectUpdates() {
        affectUpdateRunnable = object : Runnable {
            override fun run() {
                // Update chat emotion strip if chat fragment is visible
                val chat = activeFragment as? ChatFragment
                val orchestrator = affectOrchestrator
                if (chat != null && orchestrator != null && orchestrator.isRunning()) {
                    val state = orchestrator.getCurrentState()
                    val expression = orchestrator.getLastExpression()
                    chat.updateEmotionStrip(
                        state.hedonicTone(),
                        expression?.let { "${it.earPosition} | ${it.posture}" },
                        state.coherence
                    )
                }
                // Update avatar affect if avatar fragment is visible
                val avatar = activeFragment as? AvatarFragment
                if (avatar != null && orchestrator != null) {
                    avatar.refreshAffectDisplay(orchestrator)
                    avatar.updateAvatarFps()
                }
                uiHandler.postDelayed(this, AFFECT_UI_UPDATE_INTERVAL_MS)
            }
        }
        uiHandler.postDelayed(affectUpdateRunnable!!, AFFECT_UI_UPDATE_INTERVAL_MS)
    }

    private fun stopAffectUpdates() {
        affectUpdateRunnable?.let { uiHandler.removeCallbacks(it) }
        affectUpdateRunnable = null
    }

    // ========================================================================
    // Ktheme (shell-level only)
    // ========================================================================

    private fun applyKthemeToShell() {
        val selectedId = prefs.getString(ThemeApplicator.PREF_SELECTED_THEME, ThemeApplicator.DEFAULT_THEME_ID) ?: ThemeApplicator.DEFAULT_THEME_ID
        val colors = themeApplicator.loadThemeColors(selectedId) ?: return

        // Status bar + system bars
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.surface

        // Status bar header
        findViewById<LinearLayout>(R.id.statusBar).setBackgroundColor(colors.background)
        pluginCountText.setTextColor(colors.onSurface)

        // Bottom nav
        bottomNav.setBackgroundColor(colors.surface)
        bottomNav.itemIconTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(colors.primary, colors.onSurfaceVariant)
        )
        bottomNav.itemTextColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(colors.primary, colors.onSurfaceVariant)
        )

        // Apply theme to active fragment's view tree
        activeFragment?.view?.let { fragmentView ->
            themeApplicator.applyToViewTree(fragmentView, colors)
        }
    }

    // ========================================================================
    // Incoming share intent
    // ========================================================================

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        // Route shared text to the chat fragment
        bottomNav.selectedItemId = R.id.nav_chat
    }

    // ========================================================================
    // Battery optimization
    // ========================================================================

    @Suppress("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Battery optimization request failed", e)
            }
        }
    }

    // ========================================================================
    // Utility
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

    fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun resolveDisplayName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }

    // ========================================================================
    // Constants
    // ========================================================================

    companion object {
        private const val FIRST_LAUNCH_KEY = "is_first_launch"
        private const val TAG = "MainActivity"
        private const val AFFECT_UI_UPDATE_INTERVAL_MS = 2000L
    }
}

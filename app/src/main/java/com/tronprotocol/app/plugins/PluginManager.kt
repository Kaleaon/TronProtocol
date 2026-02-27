package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.AuditLogger
import com.tronprotocol.app.security.ExternalContentSanitizer

/**
 * Manages all plugins in the TronProtocol system.
 *
 * Enhanced with OpenClaw-inspired patterns:
 * - PluginSafetyScanner for multi-signal threat detection
 * - ToolPolicyEngine for layered permission enforcement
 * - AuditLogger for comprehensive activity tracking
 * - LaneQueueExecutor integration for concurrency control
 */
class PluginManager private constructor() {

    private val plugins = mutableMapOf<String, Plugin>()
    private val lazyConfigs = mutableMapOf<String, PluginRegistry.PluginConfig>()
    private var context: Context? = null

    // OpenClaw-inspired subsystems
    private var safetyScanner: PluginSafetyScanner? = null
    private var toolPolicyEngine: ToolPolicyEngine? = null
    private var auditLogger: AuditLogger? = null
    private val runtimeAutonomyPolicy = RuntimeAutonomyPolicy()

    // OpenClaw v2026.2.24 compatibility subsystems
    private var contentSanitizer: ExternalContentSanitizer? = null
    private var dangerousToolClassifier: DangerousToolClassifier? = null
    private var sendPolicy: SendPolicy? = null

    fun initialize(context: Context) {
        this.context = context.applicationContext
        Log.d(TAG, "PluginManager initialized")
    }

    /**
     * Attach the OpenClaw-inspired safety scanner for multi-signal threat detection.
     */
    fun attachSafetyScanner(scanner: PluginSafetyScanner) {
        this.safetyScanner = scanner
        Log.d(TAG, "PluginSafetyScanner attached")
    }

    /**
     * Attach the OpenClaw-inspired tool policy engine for layered permission enforcement.
     */
    fun attachToolPolicyEngine(engine: ToolPolicyEngine) {
        this.toolPolicyEngine = engine
        Log.d(TAG, "ToolPolicyEngine attached")
    }

    /**
     * Attach the audit logger for comprehensive activity tracking.
     */
    fun attachAuditLogger(logger: AuditLogger) {
        this.auditLogger = logger
        Log.d(TAG, "AuditLogger attached")
    }

    /**
     * Attach the external content sanitizer (OpenClaw external-content.ts).
     */
    fun attachContentSanitizer(sanitizer: ExternalContentSanitizer) {
        this.contentSanitizer = sanitizer
        Log.d(TAG, "ExternalContentSanitizer attached")
    }

    /**
     * Attach the dangerous tool classifier (OpenClaw dangerous-tools.ts).
     */
    fun attachDangerousToolClassifier(classifier: DangerousToolClassifier) {
        this.dangerousToolClassifier = classifier
        Log.d(TAG, "DangerousToolClassifier attached")
    }

    /**
     * Attach the outbound send policy (OpenClaw send-policy.ts).
     */
    fun attachSendPolicy(policy: SendPolicy) {
        this.sendPolicy = policy
        Log.d(TAG, "SendPolicy attached")
    }

    /** Get the attached content sanitizer (for use by channel plugins). */
    fun getContentSanitizer(): ExternalContentSanitizer? = contentSanitizer

    /** Get the attached dangerous tool classifier. */
    fun getDangerousToolClassifier(): DangerousToolClassifier? = dangerousToolClassifier

    /** Get the attached send policy (for use by communication plugins). */
    fun getSendPolicy(): SendPolicy? = sendPolicy

    /**
     * Register a plugin eagerly (creates and initializes immediately).
     */
    fun registerPlugin(plugin: Plugin?): Boolean {
        if (plugin == null) {
            Log.w(TAG, "Skipping plugin registration: plugin is null")
            return false
        }

        val ctx = context
        if (ctx == null) {
            Log.w(TAG, "Skipping plugin registration for ${plugin.javaClass.name}: context is null")
            return false
        }

        return try {
            plugin.initialize(ctx)
            plugins[plugin.id] = plugin
            lazyConfigs.remove(plugin.id)
            Log.d(TAG, "Registered plugin: ${plugin.name}")
            true
        } catch (e: Exception) {
            val pluginId = try {
                plugin.id
            } catch (_: Exception) {
                "<unavailable>"
            }
            Log.e(TAG, "Failed to initialize plugin. id=$pluginId, class=${plugin.javaClass.name}", e)
            false
        }
    }

    /**
     * Register a plugin config for lazy initialization.
     * The plugin will not be created/initialized until first access via [getPlugin] or [executePlugin].
     */
    fun registerLazy(config: PluginRegistry.PluginConfig) {
        if (plugins.containsKey(config.id)) return // already eagerly registered
        lazyConfigs[config.id] = config
        Log.d(TAG, "Registered lazy plugin config: ${config.id}")
    }

    /**
     * Eagerly initialize a plugin by ID.
     * Used for critical plugins (e.g. policy_guardrail) that must be ready immediately.
     */
    fun ensureInitialized(pluginId: String): Boolean {
        if (plugins.containsKey(pluginId)) return true
        return materializeLazy(pluginId)
    }

    /**
     * Materialize a lazily-registered plugin: create, initialize, and move from lazyConfigs to plugins.
     * Returns true if the plugin is now available.
     */
    private fun materializeLazy(pluginId: String): Boolean {
        val config = lazyConfigs[pluginId] ?: return false
        val ctx = context ?: return false

        return try {
            val plugin = config.factory()
            plugin.initialize(ctx)
            plugins[plugin.id] = plugin
            lazyConfigs.remove(pluginId)
            Log.d(TAG, "Lazily initialized plugin: ${plugin.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lazily initialize plugin: $pluginId", e)
            false
        }
    }

    /**
     * Unregister a plugin
     */
    fun unregisterPlugin(pluginId: String) {
        val plugin = plugins.remove(pluginId)
        lazyConfigs.remove(pluginId)
        if (plugin != null) {
            plugin.destroy()
            Log.d(TAG, "Unregistered plugin: ${plugin.name}")
        }
    }

    /**
     * Get a plugin by ID. Lazily initializes the plugin if it was registered via [registerLazy].
     */
    fun getPlugin(pluginId: String): Plugin? {
        plugins[pluginId]?.let { return it }
        // Try lazy materialization
        if (lazyConfigs.containsKey(pluginId)) {
            materializeLazy(pluginId)
        }
        return plugins[pluginId]
    }

    /**
     * Get all registered plugins (both eagerly and lazily registered).
     * Note: lazily-registered plugins that have not been accessed are returned as stubs.
     * Call [materializeAll] first if you need fully initialized instances.
     */
    fun getAllPlugins(): List<Plugin> = ArrayList(plugins.values)

    /**
     * Returns the total count of registered plugins (eager + lazy).
     */
    fun getRegisteredCount(): Int = plugins.size + lazyConfigs.size

    /**
     * Returns IDs of all registered plugins (both eager and lazy).
     */
    fun getRegisteredIds(): Set<String> = plugins.keys + lazyConfigs.keys

    /**
     * Materialize all lazily-registered plugins. Useful when listing all plugins in the UI.
     */
    fun materializeAll() {
        val ids = ArrayList(lazyConfigs.keys)
        for (id in ids) {
            materializeLazy(id)
        }
    }

    /**
     * Get all enabled plugins
     */
    fun getEnabledPlugins(): List<Plugin> = plugins.values.filter { it.isEnabled }

    /**
     * Execute a plugin by ID.
     *
     * Enhanced execution pipeline (OpenClaw-inspired):
     * 1. Tool Policy Engine evaluation (layered permissions)
     * 2. Safety Scanner analysis (multi-signal threat detection)
     * 3. Legacy PolicyGuardrailPlugin check (backward compatibility)
     * 4. Plugin execution
     * 5. Audit logging
     */
    fun executePlugin(pluginId: String, input: String): PluginResult {
        return executePlugin(pluginId, input, isSubAgent = false, isSandboxed = false)
    }

    /**
     * Execute a plugin with full context for policy evaluation.
     */
    fun executePlugin(
        pluginId: String,
        input: String,
        isSubAgent: Boolean = false,
        isSandboxed: Boolean = false,
        sessionId: String? = null
    ): PluginResult {
        // Lazily materialize if needed
        if (!plugins.containsKey(pluginId) && lazyConfigs.containsKey(pluginId)) {
            materializeLazy(pluginId)
        }

        val plugin = plugins[pluginId]
            ?: return PluginResult.error("Plugin not found: $pluginId", 0)

        if (!plugin.isEnabled) {
            return PluginResult.error("Plugin is disabled: $pluginId", 0)
        }

        val startTime = System.currentTimeMillis()

        // Layer 0.5: Dangerous tool classification (OpenClaw dangerous-tools.ts)
        dangerousToolClassifier?.let { classifier ->
            val classification = classifier.classify(pluginId)
            when (classification.tier) {
                DangerousToolClassifier.DangerTier.BLOCKED -> {
                    auditLogger?.logSecurityEvent(
                        pluginId, "dangerous_tool_blocked", "blocked",
                        mapOf("tier" to "BLOCKED", "reason" to classification.reason)
                    )
                    return PluginResult.error(
                        "Plugin $pluginId is BLOCKED: ${classification.reason}",
                        System.currentTimeMillis() - startTime
                    )
                }
                DangerousToolClassifier.DangerTier.OWNER_ONLY -> {
                    if (isSubAgent) {
                        auditLogger?.logSecurityEvent(
                            pluginId, "dangerous_tool_denied_subagent", "blocked",
                            mapOf("tier" to "OWNER_ONLY", "reason" to "Sub-agents cannot use OWNER_ONLY tools")
                        )
                        return PluginResult.error(
                            "Sub-agent denied: $pluginId is OWNER_ONLY",
                            System.currentTimeMillis() - startTime
                        )
                    }
                }
                DangerousToolClassifier.DangerTier.APPROVAL_REQUIRED -> {
                    if (isSubAgent) {
                        auditLogger?.logSecurityEvent(
                            pluginId, "dangerous_tool_denied_subagent", "blocked",
                            mapOf("tier" to "APPROVAL_REQUIRED", "reason" to "Sub-agents cannot use APPROVAL_REQUIRED tools")
                        )
                        return PluginResult.error(
                            "Sub-agent denied: $pluginId requires approval",
                            System.currentTimeMillis() - startTime
                        )
                    }
                }
                DangerousToolClassifier.DangerTier.SAFE -> { /* proceed */ }
            }
        }

        // Layer 1: Tool Policy Engine evaluation (OpenClaw cumulative pipeline)
        toolPolicyEngine?.let { engine ->
            val decision = engine.evaluatePipeline(pluginId, isSubAgent, isSandboxed, sessionId)
            if (!decision.allowed) {
                auditLogger?.logSecurityEvent(
                    pluginId, "policy_denied",
                    "blocked",
                    mapOf("layer" to decision.decidingLayer.name, "reason" to decision.reason)
                )
                return PluginResult.error(
                    "Denied by ${decision.decidingLayer.name} policy: ${decision.reason}",
                    System.currentTimeMillis() - startTime
                )
            }

            val declaredCapabilities = plugin.requiredCapabilities().ifEmpty {
                PluginRegistry.defaultCapabilitiesByPluginId[pluginId] ?: emptySet()
            }
            val capabilityDecision = engine.evaluateCapabilities(pluginId, declaredCapabilities)
            if (!capabilityDecision.allowed) {
                val missing = capabilityDecision.missingCapabilities.joinToString(",") { it.name }
                auditLogger?.logCapabilityDenied(pluginId, missing)
                return PluginResult.error(
                    "Denied by capability policy. Missing: $missing",
                    System.currentTimeMillis() - startTime
                )
            }
        }

        // Layer 2: Safety Scanner analysis (OpenClaw skill scanner)
        safetyScanner?.let { scanner ->
            val scanResult = scanner.scan(pluginId, input)
            if (!scanResult.allowed) {
                auditLogger?.logSecurityEvent(
                    pluginId, "safety_blocked",
                    "blocked",
                    mapOf(
                        "risk_level" to scanResult.riskLevel.name,
                        "findings" to scanResult.findings.size,
                        "recommendation" to scanResult.recommendation
                    )
                )
                return PluginResult.error(
                    "Blocked by safety scanner: ${scanResult.recommendation}",
                    System.currentTimeMillis() - startTime
                )
            }
        }

        // Layer 3: Legacy PolicyGuardrailPlugin check (backward compatibility)
        val guardrail = getGuardrailPlugin()
        if (guardrail != null && PolicyGuardrailPlugin::class.java.name != plugin.javaClass.name) {
            val policy = guardrail.evaluate(pluginId, input)
            if (!policy.isSuccess) {
                return PluginResult.error(
                    policy.errorMessage,
                    System.currentTimeMillis() - startTime
                )
            }
        }

        // Layer 3.5: Runtime autonomy + tamper safety policy
        val autonomyDecision = runtimeAutonomyPolicy.evaluate(pluginId)
        if (!autonomyDecision.allowed) {
            auditLogger?.logSecurityEvent(
                pluginId,
                "autonomy_policy_denied",
                "blocked",
                mapOf("reason" to autonomyDecision.reason)
            )
            return PluginResult.error(
                "Blocked by runtime autonomy policy: ${autonomyDecision.reason}",
                System.currentTimeMillis() - startTime
            )
        }

        // Layer 4: Execute plugin
        return try {
            val result = plugin.execute(input)
            val duration = System.currentTimeMillis() - startTime

            // Layer 5: Audit logging
            auditLogger?.logPluginExecution(pluginId, input, result.isSuccess, duration)

            Log.d(TAG, "Executed plugin ${plugin.name}: $result")
            result
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "Plugin execution failed: ${plugin.name}", e)

            auditLogger?.logPluginExecution(pluginId, input, false, duration)

            PluginResult.error("Execution failed: ${e.message}", duration)
        }
    }

    private fun getGuardrailPlugin(): PolicyGuardrailPlugin? {
        return plugins["policy_guardrail"] as? PolicyGuardrailPlugin
    }

    fun reportPluginIntegrity(pluginId: String, trusted: Boolean) {
        runtimeAutonomyPolicy.reportIntegritySignal(pluginId, trusted)
    }

    fun runRuntimeSelfCheck(): String {
        return runtimeAutonomyPolicy.runSelfCheck(plugins.keys)
    }

    fun getRuntimePolicyStatus(): String = runtimeAutonomyPolicy.summary()

    /**
     * Clean up all plugins
     */
    fun destroy() {
        for (plugin in plugins.values) {
            plugin.destroy()
        }
        plugins.clear()
        lazyConfigs.clear()

        // Reset optional attached subsystems so lifecycle destroy acts as a full manager reset.
        safetyScanner = null
        toolPolicyEngine = null
        auditLogger = null
        contentSanitizer = null
        dangerousToolClassifier = null
        sendPolicy = null
        Log.d(TAG, "PluginManager destroyed")
    }

    companion object {
        private const val TAG = "PluginManager"

        @Volatile
        private var instance: PluginManager? = null

        @JvmStatic
        fun getInstance(): PluginManager {
            return instance ?: synchronized(this) {
                instance ?: PluginManager().also { instance = it }
            }
        }
    }
}

package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.security.AuditLogger
import com.tronprotocol.app.security.ExternalContentSanitizer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
    private val executionExecutor = Executors.newCachedThreadPool()
    private val circuitBreakerState = ConcurrentHashMap<String, CircuitBreakerState>()

    private data class CircuitBreakerState(
        var failures: Int = 0,
        var openUntilMs: Long = 0
    )

    private enum class PolicyAction { ALLOW, DENY, CONFIRM, DRY_RUN }

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
        sessionId: String? = null,
        requestId: String = UUID.randomUUID().toString(),
        confirmed: Boolean = false,
        dryRun: Boolean = false
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
        val manifest = PluginCapabilityManifests.get(pluginId)
        val argsHash = hashArgs(input)

        val policyAction = evaluateExecutionPolicy(
            plugin = plugin,
            pluginId = pluginId,
            input = input,
            isSubAgent = isSubAgent,
            isSandboxed = isSandboxed,
            sessionId = sessionId,
            manifest = manifest,
            confirmed = confirmed,
            dryRun = dryRun,
            requestId = requestId,
            argsHash = argsHash,
            startTime = startTime
        )
        when (policyAction) {
            PolicyAction.DENY -> return PluginResult.error("Denied by execution policy", System.currentTimeMillis() - startTime)
            PolicyAction.CONFIRM -> return PluginResult.error("Confirmation required for high-impact plugin: $pluginId", System.currentTimeMillis() - startTime)
            PolicyAction.DRY_RUN -> {
                val duration = System.currentTimeMillis() - startTime
                auditHighImpactProvenance(
                    manifest, requestId, pluginId, argsHash,
                    decision = "dry-run", result = "simulated-success", duration = duration
                )
                return PluginResult.success("Dry-run policy accepted for $pluginId; no side effects were executed", duration)
            }
            PolicyAction.ALLOW -> Unit
        }

        if (!canExecuteByCircuitBreaker(pluginId, manifest, requestId, argsHash)) {
            return PluginResult.error("Circuit breaker is open for $pluginId", System.currentTimeMillis() - startTime)
        }

        // Layer 0.5: Dangerous tool classification (OpenClaw dangerous-tools.ts)
        dangerousToolClassifier?.let { classifier ->
            val classification = classifier.classify(pluginId)
            when (classification.tier) {
                DangerousToolClassifier.DangerTier.BLOCKED -> {
                    auditLogger?.logSecurityEvent(
                        pluginId, "dangerous_tool_blocked", "blocked",
                        mapOf("tier" to "BLOCKED", "reason" to classification.reason, "request_id" to requestId)
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
                            mapOf("tier" to "OWNER_ONLY", "reason" to "Sub-agents cannot use OWNER_ONLY tools", "request_id" to requestId)
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
                            mapOf("tier" to "APPROVAL_REQUIRED", "reason" to "Sub-agents cannot use APPROVAL_REQUIRED tools", "request_id" to requestId)
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
                    mapOf("layer" to decision.decidingLayer.name, "reason" to decision.reason, "request_id" to requestId)
                )
                return PluginResult.error(
                    "Denied by ${decision.decidingLayer.name} policy: ${decision.reason}",
                    System.currentTimeMillis() - startTime
                )
            }

            val declaredCapabilities = plugin.requiredCapabilities().ifEmpty { manifest.permissions }
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
        return executeWithBudget(plugin, pluginId, input, manifest, startTime).also { result ->
            val duration = System.currentTimeMillis() - startTime
            if (result.isSuccess) {
                resetCircuitBreaker(pluginId)
            } else {
                recordCircuitFailure(pluginId)
            }

            // Layer 5: Audit logging
            auditLogger?.logPluginExecution(pluginId, input, result.isSuccess, duration)
            auditHighImpactProvenance(
                manifest = manifest,
                requestId = requestId,
                pluginId = pluginId,
                argsHash = argsHash,
                decision = "allow",
                result = if (result.isSuccess) "success" else "failure",
                duration = duration
            )

            Log.d(TAG, "Executed plugin ${plugin.name}: $result")
        }
    }

    private fun evaluateExecutionPolicy(
        plugin: Plugin,
        pluginId: String,
        input: String,
        isSubAgent: Boolean,
        isSandboxed: Boolean,
        sessionId: String?,
        manifest: PluginCapabilityManifest,
        confirmed: Boolean,
        dryRun: Boolean,
        requestId: String,
        argsHash: String,
        startTime: Long
    ): PolicyAction {
        if (dryRun) return PolicyAction.DRY_RUN
        if (manifest.riskClass >= PluginRiskClass.HIGH && !confirmed) {
            auditHighImpactProvenance(
                manifest, requestId, pluginId, argsHash,
                decision = "confirm", result = "confirmation-required", duration = System.currentTimeMillis() - startTime
            )
            return PolicyAction.CONFIRM
        }
        return PolicyAction.ALLOW
    }

    private fun executeWithBudget(
        plugin: Plugin,
        pluginId: String,
        input: String,
        manifest: PluginCapabilityManifest,
        startTime: Long
    ): PluginResult {
        val budget = manifest.executionBudget
        var lastError: String? = null
        for (attempt in 0..budget.maxRetries) {
            try {
                val future = executionExecutor.submit<PluginResult> { plugin.execute(input) }
                return future.get(budget.timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                lastError = "Execution timed out (${budget.timeoutMs}ms)"
                Log.w(TAG, "Plugin timeout plugin=$pluginId attempt=${attempt + 1}")
            } catch (e: Exception) {
                lastError = e.message ?: "unknown error"
                Log.e(TAG, "Plugin execution failed plugin=$pluginId attempt=${attempt + 1}", e)
            }
        }
        return PluginResult.error(lastError ?: "execution failed", System.currentTimeMillis() - startTime)
    }

    private fun canExecuteByCircuitBreaker(
        pluginId: String,
        manifest: PluginCapabilityManifest,
        requestId: String,
        argsHash: String
    ): Boolean {
        val now = System.currentTimeMillis()
        val state = circuitBreakerState.getOrPut(pluginId) { CircuitBreakerState() }
        if (state.openUntilMs > now) {
            auditHighImpactProvenance(
                manifest, requestId, pluginId, argsHash,
                decision = "deny", result = "circuit-open", duration = 0
            )
            return false
        }
        return true
    }

    private fun recordCircuitFailure(pluginId: String) {
        val manifest = PluginCapabilityManifests.get(pluginId)
        val state = circuitBreakerState.getOrPut(pluginId) { CircuitBreakerState() }
        state.failures += 1
        if (state.failures >= manifest.executionBudget.circuitBreakerThreshold) {
            state.openUntilMs = System.currentTimeMillis() + manifest.executionBudget.circuitBreakerCooldownMs
            state.failures = 0
        }
    }

    private fun resetCircuitBreaker(pluginId: String) {
        circuitBreakerState[pluginId]?.let {
            it.failures = 0
            it.openUntilMs = 0
        }
    }

    private fun auditHighImpactProvenance(
        manifest: PluginCapabilityManifest,
        requestId: String,
        pluginId: String,
        argsHash: String,
        decision: String,
        result: String,
        duration: Long
    ) {
        if (manifest.riskClass < PluginRiskClass.HIGH) return
        auditLogger?.logSync(
            severity = if (result == "success") AuditLogger.Severity.INFO else AuditLogger.Severity.WARNING,
            category = AuditLogger.AuditCategory.POLICY_DECISION,
            actor = "plugin_manager",
            action = "high_impact_provenance",
            target = pluginId,
            outcome = result,
            details = mapOf(
                "request_id" to requestId,
                "plugin_id" to pluginId,
                "args_hash" to argsHash,
                "decision" to decision,
                "result" to result,
                "risk_class" to manifest.riskClass.name,
                "duration_ms" to duration
            )
        )
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
        executionExecutor.shutdownNow()
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

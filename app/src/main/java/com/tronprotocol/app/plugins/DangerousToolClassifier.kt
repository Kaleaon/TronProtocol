package com.tronprotocol.app.plugins

import android.util.Log

/**
 * Dangerous Tool Classifier — two-tier classification of plugin risk levels.
 *
 * Inspired by OpenClaw's dangerous-tools.ts which classifies tools into:
 * - Gateway HTTP restrictions (blocked from certain surfaces)
 * - ACP restrictions (require explicit approval: exec, spawn, shell, fs_write, etc.)
 *
 * TronProtocol's SubAgentManager had per-isolation-level deny lists, but there was
 * no first-class system-wide classification. This classifier provides a single source
 * of truth for tool danger tiers, consulted by PluginManager, SubAgentManager, and
 * the ToolPolicyEngine.
 */
class DangerousToolClassifier {

    /**
     * Danger tier for a tool. Higher tiers require stricter authorization.
     */
    enum class DangerTier {
        /** No restrictions beyond standard capability checks. */
        SAFE,

        /** Requires explicit user approval each invocation. */
        APPROVAL_REQUIRED,

        /** Only the device owner (via primary Telegram allowed_chats) may trigger. */
        OWNER_ONLY,

        /** Never allowed programmatically — only via direct user UI interaction. */
        BLOCKED
    }

    /** Classification result for a single tool. */
    data class ToolClassification(
        val pluginId: String,
        val tier: DangerTier,
        val reason: String,
        val requiredCapabilities: Set<Capability>
    )

    // Mutable overrides — operator can promote/demote tools at runtime
    private val overrides = mutableMapOf<String, DangerTier>()

    /**
     * Classify a plugin by its danger tier.
     *
     * Resolution order:
     * 1. Runtime override (if set)
     * 2. Static default classification
     * 3. Fallback to SAFE
     */
    fun classify(pluginId: String): ToolClassification {
        val tier = overrides[pluginId]
            ?: DEFAULT_CLASSIFICATIONS[pluginId]
            ?: DangerTier.SAFE

        val reason = DEFAULT_REASONS[pluginId] ?: when (tier) {
            DangerTier.SAFE -> "Plugin has no dangerous capabilities"
            DangerTier.APPROVAL_REQUIRED -> "Plugin requires user approval"
            DangerTier.OWNER_ONLY -> "Plugin restricted to device owner"
            DangerTier.BLOCKED -> "Plugin blocked from programmatic access"
        }

        val capabilities = PluginRegistry.defaultCapabilitiesByPluginId[pluginId] ?: emptySet()

        return ToolClassification(
            pluginId = pluginId,
            tier = tier,
            reason = reason,
            requiredCapabilities = capabilities
        )
    }

    /** Quick check: is this plugin classified as non-SAFE? */
    fun isDangerous(pluginId: String): Boolean {
        return classify(pluginId).tier != DangerTier.SAFE
    }

    /** Get all plugins that require explicit user approval. */
    fun getApprovalRequiredTools(): Set<String> {
        return getAllClassifiedTools()
            .filter { it.value == DangerTier.APPROVAL_REQUIRED }
            .keys
    }

    /** Get all plugins restricted to the device owner. */
    fun getOwnerOnlyTools(): Set<String> {
        return getAllClassifiedTools()
            .filter { it.value == DangerTier.OWNER_ONLY }
            .keys
    }

    /** Get all blocked plugins. */
    fun getBlockedTools(): Set<String> {
        return getAllClassifiedTools()
            .filter { it.value == DangerTier.BLOCKED }
            .keys
    }

    /**
     * Override the classification for a plugin at runtime.
     * Useful for operator configuration.
     */
    fun setOverride(pluginId: String, tier: DangerTier) {
        overrides[pluginId] = tier
        Log.d(TAG, "Classification override: $pluginId -> ${tier.name}")
    }

    /** Remove a runtime override, reverting to the default classification. */
    fun removeOverride(pluginId: String) {
        overrides.remove(pluginId)
    }

    /** Get a combined view of all known classifications (defaults + overrides). */
    private fun getAllClassifiedTools(): Map<String, DangerTier> {
        val result = mutableMapOf<String, DangerTier>()
        result.putAll(DEFAULT_CLASSIFICATIONS)
        result.putAll(overrides)
        return result
    }

    /** Summary for diagnostics. */
    fun getSummary(): Map<String, Any> = mapOf(
        "safe_count" to DEFAULT_CLASSIFICATIONS.count { it.value == DangerTier.SAFE },
        "approval_required" to getApprovalRequiredTools(),
        "owner_only" to getOwnerOnlyTools(),
        "blocked" to getBlockedTools(),
        "overrides" to overrides.mapValues { it.value.name }
    )

    companion object {
        private const val TAG = "DangerousToolClassifier"

        /**
         * Default danger classifications.
         *
         * Aligned with OpenClaw's two-tier model:
         * - OWNER_ONLY ≈ OpenClaw gateway HTTP restrictions
         * - APPROVAL_REQUIRED ≈ OpenClaw ACP restrictions (exec, spawn, fs_write)
         */
        private val DEFAULT_CLASSIFICATIONS = mapOf(
            // OWNER_ONLY: plugins that can send data externally or modify the filesystem
            "telegram_bridge" to DangerTier.OWNER_ONLY,
            "communication_hub" to DangerTier.OWNER_ONLY,
            "sandbox_exec" to DangerTier.OWNER_ONLY,
            "file_manager" to DangerTier.OWNER_ONLY,
            "scripting_runtime" to DangerTier.OWNER_ONLY,

            // APPROVAL_REQUIRED: plugins that perform significant autonomous actions
            "task_automation" to DangerTier.APPROVAL_REQUIRED,
            "intent_automation" to DangerTier.APPROVAL_REQUIRED,
            "sms_send" to DangerTier.APPROVAL_REQUIRED,
            "email" to DangerTier.APPROVAL_REQUIRED,
            "proactive_messaging" to DangerTier.APPROVAL_REQUIRED,
            "scheduled_actions" to DangerTier.APPROVAL_REQUIRED,
            "contact_manager" to DangerTier.APPROVAL_REQUIRED,

            // SAFE: stateless or read-only plugins
            "calculator" to DangerTier.SAFE,
            "datetime" to DangerTier.SAFE,
            "text_analysis" to DangerTier.SAFE,
            "device_info" to DangerTier.SAFE,
            "notes" to DangerTier.SAFE,
            "web_search" to DangerTier.SAFE,
            "personalization" to DangerTier.SAFE,
            "on_device_llm" to DangerTier.SAFE,
            "guidance_router" to DangerTier.SAFE,
            "policy_guardrail" to DangerTier.SAFE,
            "rag_memory" to DangerTier.SAFE
        )

        private val DEFAULT_REASONS = mapOf(
            "telegram_bridge" to "Can send messages to external Telegram chats",
            "communication_hub" to "Can send messages across communication channels",
            "sandbox_exec" to "Can execute arbitrary code in sandboxed environment",
            "file_manager" to "Can read/write files on the device filesystem",
            "scripting_runtime" to "Can execute scripts with system access",
            "task_automation" to "Can trigger autonomous multi-step tasks",
            "intent_automation" to "Can fire Android intents to other apps",
            "sms_send" to "Can send SMS messages (carrier charges may apply)",
            "email" to "Can send emails on behalf of the user",
            "proactive_messaging" to "Can initiate unsolicited outbound messages",
            "scheduled_actions" to "Can schedule future autonomous actions",
            "contact_manager" to "Can modify the user's contact list"
        )
    }
}

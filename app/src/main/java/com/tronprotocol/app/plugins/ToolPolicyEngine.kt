package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool Policy Engine — multi-layered permission enforcement for plugin access.
 *
 * Inspired by OpenClaw's 6-level tool policy precedence system:
 * 1. Sub-agent restrictions (most restrictive)
 * 2. Sandbox policy (per-session sandbox rules)
 * 3. Group policy (group-level rules for plugin categories)
 * 4. Session policy (per-session overrides)
 * 5. Plugin profile (per-plugin default permissions)
 * 6. Global policy (system-wide defaults, least restrictive)
 *
 * Each layer can ALLOW or DENY access to specific plugins. Higher-priority
 * layers override lower ones. This replaces the flat deny/allow list in
 * PolicyGuardrailPlugin with a structured, defense-in-depth approach.
 */
class ToolPolicyEngine {

    data class CapabilityDecision(
        val allowed: Boolean,
        val missingCapabilities: Set<Capability>
    )

    /** A policy rule at a specific layer. */
    data class PolicyRule(
        val layer: PolicyLayer,
        val pluginId: String,   // "*" for wildcard
        val action: Action,
        val reason: String = ""
    )

    enum class PolicyLayer(val priority: Int) {
        SUB_AGENT(1),       // Highest priority — sub-agent restrictions
        SANDBOX(2),         // Sandbox-mode restrictions
        GROUP(3),           // Plugin group policies
        SESSION(4),         // Per-session overrides
        PLUGIN_PROFILE(5),  // Per-plugin defaults
        GLOBAL(6)           // System-wide defaults (lowest priority)
    }

    enum class Action { ALLOW, DENY }

    /** Result of a policy evaluation. */
    data class PolicyDecision(
        val allowed: Boolean,
        val decidingLayer: PolicyLayer,
        val decidingRule: PolicyRule?,
        val evaluatedLayers: Int,
        val reason: String
    )

    // Plugin group definitions (like OpenClaw's group:fs, group:runtime, etc.)
    private val pluginGroups = mutableMapOf<String, MutableSet<String>>()

    // Policy rules organized by layer
    private val rules = mutableListOf<PolicyRule>()

    // Persistence
    private var preferences: SharedPreferences? = null
    private val grantedCapabilitiesByPlugin = mutableMapOf<String, MutableSet<Capability>>()

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadDefaultGroups()
        loadDefaultPolicies()
        loadDefaultCapabilities()
        loadPersistedPolicies()
    }

    fun evaluateCapabilities(
        pluginId: String,
        requiredCapabilities: Set<Capability>
    ): CapabilityDecision {
        if (requiredCapabilities.isEmpty()) {
            return CapabilityDecision(allowed = true, missingCapabilities = emptySet())
        }

        val granted = grantedCapabilitiesByPlugin[pluginId] ?: mutableSetOf()
        val missing = requiredCapabilities.filterNot { granted.contains(it) }.toSet()

        if (missing.isNotEmpty()) {
            Log.d(TAG, "DENIED plugin=$pluginId missing_capabilities=$missing")
        }

        return CapabilityDecision(
            allowed = missing.isEmpty(),
            missingCapabilities = missing
        )
    }

    fun setGrantedCapabilities(pluginId: String, capabilities: Set<Capability>) {
        grantedCapabilitiesByPlugin[pluginId] = capabilities.toMutableSet()
    }

    fun getGrantedCapabilities(pluginId: String): Set<Capability> {
        return grantedCapabilitiesByPlugin[pluginId]?.toSet() ?: emptySet()
    }

    /**
     * Evaluate whether a plugin can be executed in the current context.
     */
    fun evaluate(
        pluginId: String,
        isSubAgent: Boolean = false,
        isSandboxed: Boolean = false,
        sessionId: String? = null
    ): PolicyDecision {
        var evaluatedLayers = 0

        // Evaluate layers in priority order (most restrictive first)
        val layerOrder = listOf(
            PolicyLayer.SUB_AGENT to isSubAgent,
            PolicyLayer.SANDBOX to isSandboxed,
            PolicyLayer.GROUP to true,
            PolicyLayer.SESSION to (sessionId != null),
            PolicyLayer.PLUGIN_PROFILE to true,
            PolicyLayer.GLOBAL to true
        )

        for ((layer, applies) in layerOrder) {
            if (!applies) continue
            evaluatedLayers++

            // Get rules for this layer that match the plugin
            val matchingRules = rules.filter { rule ->
                rule.layer == layer && (rule.pluginId == pluginId ||
                        rule.pluginId == "*" ||
                        isPluginInGroup(pluginId, rule.pluginId))
            }.sortedBy {
                // Specific rules before wildcards
                if (it.pluginId == "*") 1 else 0
            }

            // First matching rule at this layer decides
            if (matchingRules.isNotEmpty()) {
                val decidingRule = matchingRules.first()
                val allowed = decidingRule.action == Action.ALLOW

                if (!allowed) {
                    Log.d(TAG, "DENIED plugin=$pluginId at layer=${layer.name}: ${decidingRule.reason}")
                }

                return PolicyDecision(
                    allowed = allowed,
                    decidingLayer = layer,
                    decidingRule = decidingRule,
                    evaluatedLayers = evaluatedLayers,
                    reason = decidingRule.reason.ifEmpty {
                        "${decidingRule.action.name} by ${layer.name} policy"
                    }
                )
            }
        }

        // No rule matched — default allow
        return PolicyDecision(
            allowed = true,
            decidingLayer = PolicyLayer.GLOBAL,
            decidingRule = null,
            evaluatedLayers = evaluatedLayers,
            reason = "No matching policy rule; default allow"
        )
    }

    /**
     * Add a policy rule.
     */
    fun addRule(rule: PolicyRule) {
        // Remove any existing rule at same layer for same plugin
        rules.removeIf { it.layer == rule.layer && it.pluginId == rule.pluginId }
        rules.add(rule)
        persistPolicies()
        Log.d(TAG, "Added rule: ${rule.action.name} ${rule.pluginId} at ${rule.layer.name}")
    }

    /**
     * Remove a policy rule.
     */
    fun removeRule(layer: PolicyLayer, pluginId: String): Boolean {
        val removed = rules.removeIf { it.layer == layer && it.pluginId == pluginId }
        if (removed) persistPolicies()
        return removed
    }

    /**
     * Define a plugin group (e.g., "group:stateful" -> [notes, personalization, task_automation]).
     */
    fun defineGroup(groupName: String, pluginIds: Set<String>) {
        pluginGroups[groupName] = pluginIds.toMutableSet()
        persistPolicies()
    }

    /**
     * Add a plugin to a group.
     */
    fun addToGroup(groupName: String, pluginId: String) {
        pluginGroups.getOrPut(groupName) { mutableSetOf() }.add(pluginId)
    }

    /**
     * Get all rules, optionally filtered by layer.
     */
    fun getRules(layer: PolicyLayer? = null): List<PolicyRule> {
        return if (layer != null) {
            rules.filter { it.layer == layer }
        } else {
            rules.sortedBy { it.layer.priority }
        }
    }

    /**
     * Get all defined groups.
     */
    fun getGroups(): Map<String, Set<String>> = pluginGroups.toMap()

    /**
     * Check if a plugin belongs to a group reference (e.g., "group:stateful").
     */
    private fun isPluginInGroup(pluginId: String, groupRef: String): Boolean {
        if (!groupRef.startsWith("group:")) return false
        val groupName = groupRef.removePrefix("group:")
        return pluginGroups[groupName]?.contains(pluginId) == true
    }

    // -- Default configuration --

    private fun loadDefaultGroups() {
        pluginGroups["group:stateless"] = mutableSetOf(
            "calculator", "datetime", "text_analysis", "device_info"
        )
        pluginGroups["group:stateful"] = mutableSetOf(
            "notes", "personalization", "task_automation"
        )
        pluginGroups["group:network"] = mutableSetOf(
            "web_search", "telegram_bridge", "communication_hub", "guidance_router"
        )
        pluginGroups["group:system"] = mutableSetOf(
            "file_manager", "sandbox_exec", "policy_guardrail"
        )
        pluginGroups["group:safe"] = mutableSetOf(
            "calculator", "datetime", "text_analysis", "device_info"
        )
    }

    private fun loadDefaultPolicies() {
        // Sub-agent restrictions (no network, no file, no system by default)
        rules.add(PolicyRule(PolicyLayer.SUB_AGENT, "group:network", Action.DENY,
            "Sub-agents cannot access network plugins"))
        rules.add(PolicyRule(PolicyLayer.SUB_AGENT, "group:system", Action.DENY,
            "Sub-agents cannot access system plugins"))
        rules.add(PolicyRule(PolicyLayer.SUB_AGENT, "group:safe", Action.ALLOW,
            "Sub-agents can use safe/stateless plugins"))

        // Sandbox restrictions
        rules.add(PolicyRule(PolicyLayer.SANDBOX, "file_manager", Action.DENY,
            "File access denied in sandbox mode"))
        rules.add(PolicyRule(PolicyLayer.SANDBOX, "sandbox_exec", Action.DENY,
            "Code execution denied in sandbox mode"))
        rules.add(PolicyRule(PolicyLayer.SANDBOX, "telegram_bridge", Action.DENY,
            "External messaging denied in sandbox mode"))

        // Global defaults
        rules.add(PolicyRule(PolicyLayer.GLOBAL, "*", Action.ALLOW,
            "Global default: all plugins allowed"))
        rules.add(PolicyRule(PolicyLayer.GLOBAL, "policy_guardrail", Action.ALLOW,
            "Policy guardrail always allowed"))
    }

    private fun loadDefaultCapabilities() {
        grantedCapabilitiesByPlugin.clear()
        for ((pluginId, capabilities) in PluginRegistry.defaultCapabilitiesByPluginId) {
            grantedCapabilitiesByPlugin[pluginId] = capabilities.toMutableSet()
        }
    }

    // -- Persistence --

    private fun persistPolicies() {
        try {
            val json = JSONObject()

            // Persist groups
            val groupsJson = JSONObject()
            for ((name, plugins) in pluginGroups) {
                groupsJson.put(name, JSONArray(plugins.toList()))
            }
            json.put("groups", groupsJson)

            // Persist user-added rules (skip default rules)
            val rulesArr = JSONArray()
            for (rule in rules) {
                rulesArr.put(JSONObject().apply {
                    put("layer", rule.layer.name)
                    put("pluginId", rule.pluginId)
                    put("action", rule.action.name)
                    put("reason", rule.reason)
                })
            }
            json.put("rules", rulesArr)

            preferences?.edit()?.putString(KEY_POLICIES, json.toString())?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist policies", e)
        }
    }

    private fun loadPersistedPolicies() {
        try {
            val data = preferences?.getString(KEY_POLICIES, null) ?: return
            val json = JSONObject(data)

            // Load persisted groups
            val groupsJson = json.optJSONObject("groups")
            if (groupsJson != null) {
                val keys = groupsJson.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val arr = groupsJson.getJSONArray(name)
                    val plugins = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        plugins.add(arr.getString(i))
                    }
                    pluginGroups[name] = plugins
                }
            }

            // Load persisted rules (merge with defaults)
            val rulesArr = json.optJSONArray("rules")
            if (rulesArr != null) {
                for (i in 0 until rulesArr.length()) {
                    val obj = rulesArr.getJSONObject(i)
                    val rule = PolicyRule(
                        layer = PolicyLayer.valueOf(obj.getString("layer")),
                        pluginId = obj.getString("pluginId"),
                        action = Action.valueOf(obj.getString("action")),
                        reason = obj.optString("reason", "")
                    )
                    // Only add if not already present (defaults take precedence)
                    if (rules.none { it.layer == rule.layer && it.pluginId == rule.pluginId }) {
                        rules.add(rule)
                    }
                }
            }

            Log.d(TAG, "Loaded persisted policies: ${pluginGroups.size} groups, ${rules.size} rules")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted policies", e)
        }
    }

    companion object {
        private const val TAG = "ToolPolicyEngine"
        private const val PREFS_NAME = "tool_policy_engine"
        private const val KEY_POLICIES = "policies_v1"
    }
}

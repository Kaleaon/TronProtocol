package com.tronprotocol.app.plugins

import java.security.MessageDigest

/**
 * Security/operations manifest per plugin.
 */
data class PluginCapabilityManifest(
    val pluginId: String,
    val permissions: Set<Capability>,
    val sideEffects: Set<PluginSideEffect>,
    val riskClass: PluginRiskClass,
    val executionBudget: PluginExecutionBudget
)

enum class PluginSideEffect {
    NONE,
    NETWORK,
    FILESYSTEM,
    MEMORY_WRITE,
    EXTERNAL_MESSAGE,
    CODE_EXECUTION,
    SYSTEM_AUTOMATION
}

enum class PluginRiskClass { LOW, MEDIUM, HIGH, CRITICAL }

data class PluginExecutionBudget(
    val timeoutMs: Long,
    val maxRetries: Int,
    val circuitBreakerThreshold: Int,
    val circuitBreakerCooldownMs: Long
)

object PluginCapabilityManifests {
    private val explicitHighRisk = setOf(
        "sms_send", "communication_hub", "sandbox_exec", "scripting_runtime", "file_manager", "intent_automation"
    )

    val manifestsByPluginId: Map<String, PluginCapabilityManifest> by lazy {
        PluginRegistry.configs.associate { config ->
            val permissions = config.defaultCapabilities
            val sideEffects = inferSideEffects(permissions)
            val riskClass = inferRiskClass(config.id, permissions, sideEffects)
            val budget = defaultBudgetForRisk(riskClass)
            config.id to PluginCapabilityManifest(
                pluginId = config.id,
                permissions = permissions,
                sideEffects = sideEffects,
                riskClass = riskClass,
                executionBudget = budget
            )
        }
    }

    fun get(pluginId: String): PluginCapabilityManifest {
        return manifestsByPluginId[pluginId]
            ?: PluginCapabilityManifest(
                pluginId,
                emptySet(),
                setOf(PluginSideEffect.NONE),
                PluginRiskClass.LOW,
                defaultBudgetForRisk(PluginRiskClass.LOW)
            )
    }

    private fun inferSideEffects(capabilities: Set<Capability>): Set<PluginSideEffect> {
        val effects = mutableSetOf<PluginSideEffect>()
        if (capabilities.any { it == Capability.NETWORK_OUTBOUND || it == Capability.HTTP_REQUEST }) {
            effects += PluginSideEffect.NETWORK
        }
        if (capabilities.any { it == Capability.FILESYSTEM_WRITE || it == Capability.FILESYSTEM_READ }) {
            effects += PluginSideEffect.FILESYSTEM
        }
        if (capabilities.contains(Capability.MEMORY_WRITE)) effects += PluginSideEffect.MEMORY_WRITE
        if (capabilities.contains(Capability.SMS_SEND)) effects += PluginSideEffect.EXTERNAL_MESSAGE
        if (capabilities.any { it == Capability.CODE_EXECUTION || it == Capability.SCRIPT_EXECUTE }) {
            effects += PluginSideEffect.CODE_EXECUTION
        }
        if (capabilities.any { it == Capability.TASK_AUTOMATION || it == Capability.INTENT_FIRE }) {
            effects += PluginSideEffect.SYSTEM_AUTOMATION
        }
        if (effects.isEmpty()) effects += PluginSideEffect.NONE
        return effects
    }

    private fun inferRiskClass(
        pluginId: String,
        capabilities: Set<Capability>,
        sideEffects: Set<PluginSideEffect>
    ): PluginRiskClass {
        if (pluginId in explicitHighRisk || sideEffects.contains(PluginSideEffect.CODE_EXECUTION)) {
            return PluginRiskClass.CRITICAL
        }
        if (capabilities.any { it == Capability.SMS_SEND || it == Capability.FILESYSTEM_WRITE || it == Capability.CODE_EXECUTION }) {
            return PluginRiskClass.HIGH
        }
        if (sideEffects.any { it == PluginSideEffect.NETWORK || it == PluginSideEffect.SYSTEM_AUTOMATION || it == PluginSideEffect.MEMORY_WRITE }) {
            return PluginRiskClass.MEDIUM
        }
        return PluginRiskClass.LOW
    }

    private fun defaultBudgetForRisk(riskClass: PluginRiskClass): PluginExecutionBudget {
        return when (riskClass) {
            PluginRiskClass.CRITICAL -> PluginExecutionBudget(4_000, 0, 2, 60_000)
            PluginRiskClass.HIGH -> PluginExecutionBudget(6_000, 1, 3, 45_000)
            PluginRiskClass.MEDIUM -> PluginExecutionBudget(8_000, 1, 4, 30_000)
            PluginRiskClass.LOW -> PluginExecutionBudget(10_000, 2, 5, 15_000)
        }
    }
}

internal fun hashArgs(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

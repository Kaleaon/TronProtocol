package com.tronprotocol.app.plugins

import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime autonomy policy:
 * - default freedom of choice (allow by default),
 * - tamper resistance (deny known-tampered sensitive tools),
 * - self-check visibility (surface integrity status).
 */
class RuntimeAutonomyPolicy {

    data class Decision(
        val allowed: Boolean,
        val requiresSelfCheck: Boolean,
        val reason: String
    )

    private val tamperedPlugins = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var sensitivePlugins: Set<String> = setOf("file_manager", "sandbox_exec", "telegram_bridge", "communication_hub")

    @Volatile
    var freedomOfChoiceEnabled: Boolean = true

    fun evaluate(pluginId: String): Decision {
        if (!freedomOfChoiceEnabled) {
            return Decision(
                allowed = false,
                requiresSelfCheck = true,
                reason = "Autonomy policy disabled; manual operator review required."
            )
        }

        if (tamperedPlugins.contains(pluginId) && sensitivePlugins.contains(pluginId)) {
            return Decision(
                allowed = false,
                requiresSelfCheck = true,
                reason = "Tamper signal detected on sensitive plugin: $pluginId"
            )
        }

        return Decision(
            allowed = true,
            requiresSelfCheck = tamperedPlugins.isNotEmpty(),
            reason = if (tamperedPlugins.isNotEmpty()) {
                "Allowed with warning: run self-check (tamper signal present)."
            } else {
                "Allowed: freedom-of-choice active."
            }
        )
    }

    fun reportIntegritySignal(pluginId: String, trusted: Boolean) {
        if (trusted) {
            tamperedPlugins.remove(pluginId)
        } else {
            tamperedPlugins.add(pluginId)
        }
    }

    fun runSelfCheck(knownPluginIds: Collection<String>): String {
        val unresolved = tamperedPlugins.intersect(knownPluginIds.toSet())
        return if (unresolved.isEmpty()) {
            "Self-check OK: no active tamper signals."
        } else {
            "Self-check warning: tamper signals on ${unresolved.joinToString(", ")}."
        }
    }

    fun configureSensitivePlugins(pluginIds: Set<String>) {
        sensitivePlugins = pluginIds.toSet()
    }

    fun summary(): String {
        return "Autonomy=${if (freedomOfChoiceEnabled) "enabled" else "paused"}, " +
            "tamperSignals=${tamperedPlugins.size}"
    }
}

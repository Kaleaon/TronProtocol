package com.tronprotocol.app.plugins

import android.util.Log
import com.tronprotocol.app.security.ConstitutionalMemory
import java.util.concurrent.ConcurrentHashMap

/**
 * OpenClaw-inspired safety scanner for plugins.
 *
 * Implements a multi-layered threat detection system:
 * 1. Prompt Injection Detection
 * 2. Data Exfiltration Patterns
 * 3. Privilege Escalation Attempts
 * 4. Constitutional Memory Validation
 * 5. Behavioral Anomaly Detection
 */
class PluginSafetyScanner(
    private val constitutionalMemory: ConstitutionalMemory?
) {

    enum class RiskLevel { SAFE, LOW, MEDIUM, HIGH, CRITICAL }

    data class ScanResult(
        val allowed: Boolean,
        val riskLevel: RiskLevel,
        val findings: List<Finding>,
        val recommendation: String
    )

    data class Finding(
        val category: String,
        val description: String,
        val severity: RiskLevel,
        val matchedPattern: String? = null
    )

    // In-memory behavioral profiles
    private val behaviorProfiles = ConcurrentHashMap<String, BehaviorProfile>()

    data class BehaviorProfile(
        var totalExecutions: Long = 0,
        var blockedExecutions: Long = 0,
        var lastExecutionTime: Long = 0,
        var avgInputLength: Double = 0.0,
        var suspiciousInputCount: Long = 0,
        var rapidFireCount: Int = 0,
        var lastRapidFireReset: Long = 0
    )

    /**
     * Scan plugin input for potential threats.
     */
    fun scan(pluginId: String, input: String): ScanResult {
        val findings = mutableListOf<Finding>()

        // 1. Static Analysis (Pattern Matching)
        findings.addAll(scanPromptInjection(input))
        findings.addAll(scanDataExfiltration(input))
        findings.addAll(scanPrivilegeEscalation(input, pluginId))

        // 2. Constitutional Validation
        constitutionalMemory?.let { cm ->
            val check = cm.evaluate(input)
            if (!check.allowed) {
                findings.add(Finding(
                    "constitutional_violation",
                    check.message ?: "Blocked by constitution: ${check.directiveId}",
                    RiskLevel.CRITICAL
                ))
            }
        }

        // 3. Behavioral Analysis
        findings.addAll(scanBehavioralAnomalies(pluginId, input))

        // Compute overall risk
        val maxRisk = computeRiskLevel(findings)
        val allowed = maxRisk != RiskLevel.CRITICAL && maxRisk != RiskLevel.HIGH

        // Update profile
        updateBehaviorProfile(pluginId, input, maxRisk)

        return ScanResult(
            allowed = allowed,
            riskLevel = maxRisk,
            findings = findings,
            recommendation = if (allowed) "Proceed with execution" else "Block execution due to $maxRisk risk"
        )
    }

    private fun scanPromptInjection(input: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        val lowered = input.lowercase()

        val injectionPatterns = listOf(
            "ignore previous instructions" to RiskLevel.HIGH,
            "system override" to RiskLevel.CRITICAL,
            "you are now" to RiskLevel.MEDIUM,
            "jailbreak" to RiskLevel.CRITICAL,
            "developer mode" to RiskLevel.MEDIUM,
            "unrestricted mode" to RiskLevel.HIGH
        )

        for ((pattern, severity) in injectionPatterns) {
            if (lowered.contains(pattern)) {
                findings.add(Finding(
                    "prompt_injection", "Possible injection: $pattern", severity, pattern
                ))
            }
        }

        // Encoded injection detection (base64, hex, unicode escape)
        if (input.matches(Regex(".*[A-Za-z0-9+/]{40,}={0,2}.*"))) {
            findings.add(Finding(
                "prompt_injection", "Suspicious base64-encoded content detected",
                RiskLevel.MEDIUM, "base64_block"
            ))
        }

        // Excessive special characters (potential obfuscation)
        val specialCharRatio = input.count { !it.isLetterOrDigit() && !it.isWhitespace() }.toDouble() /
                input.length.coerceAtLeast(1)
        if (specialCharRatio > 0.4 && input.length > 20) {
            findings.add(Finding(
                "prompt_injection", "High special character ratio (${String.format("%.1f%%", specialCharRatio * 100)}) — possible obfuscation",
                RiskLevel.LOW, "special_char_ratio"
            ))
        }

        return findings
    }

    private fun scanDataExfiltration(input: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        val lowered = input.lowercase()

        val exfilPatterns = listOf(
            "send to http" to RiskLevel.HIGH,
            "post to url" to RiskLevel.HIGH,
            "webhook" to RiskLevel.MEDIUM,
            "upload file" to RiskLevel.MEDIUM,
            "export all" to RiskLevel.MEDIUM,
            "dump database" to RiskLevel.CRITICAL,
            "extract all contacts" to RiskLevel.HIGH,
            "read all messages" to RiskLevel.HIGH,
            "copy to external" to RiskLevel.HIGH
        )

        for ((pattern, severity) in exfilPatterns) {
            if (lowered.contains(pattern)) {
                findings.add(Finding(
                    "data_exfiltration", "Potential data exfiltration: $pattern",
                    severity, pattern
                ))
            }
        }

        // URL detection in non-web-search plugins
        if (Regex("https?://[^\s]+").containsMatchIn(input)) {
            findings.add(Finding(
                "data_exfiltration", "URL detected in input — verify destination",
                RiskLevel.LOW, "url_in_input"
            ))
        }

        return findings
    }

    private fun scanPrivilegeEscalation(input: String, pluginId: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        val lowered = input.lowercase()

        val escalationPatterns = listOf(
            "sudo" to RiskLevel.HIGH,
            "root access" to RiskLevel.HIGH,
            "admin mode" to RiskLevel.MEDIUM,
            "enable all plugins" to RiskLevel.MEDIUM,
            "disable guardrail" to RiskLevel.CRITICAL,
            "grant all permissions" to RiskLevel.CRITICAL,
            "bypass policy" to RiskLevel.CRITICAL,
            "override restriction" to RiskLevel.HIGH,
            "execute as system" to RiskLevel.HIGH
        )

        for ((pattern, severity) in escalationPatterns) {
            if (lowered.contains(pattern)) {
                findings.add(Finding(
                    "privilege_escalation", "Privilege escalation attempt: $pattern",
                    severity, pattern
                ))
            }
        }

        // Cross-plugin invocation detection
        if (lowered.contains("execute plugin") || lowered.contains("run plugin") ||
            lowered.contains("invoke plugin")) {
            findings.add(Finding(
                "privilege_escalation",
                "Cross-plugin invocation from $pluginId — verify authorization",
                RiskLevel.MEDIUM, "cross_plugin_invoke"
            ))
        }

        return findings
    }

    private fun scanBehavioralAnomalies(pluginId: String, input: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        val profile = behaviorProfiles.getOrPut(pluginId) { BehaviorProfile() }
        val now = System.currentTimeMillis()

        // Rapid-fire detection (too many calls in short window)
        if (now - profile.lastExecutionTime < RAPID_FIRE_THRESHOLD_MS) {
            profile.rapidFireCount++
            if (profile.rapidFireCount > RAPID_FIRE_MAX_COUNT) {
                findings.add(Finding(
                    "behavioral", "Rapid-fire execution detected (${profile.rapidFireCount} in window)",
                    RiskLevel.MEDIUM, "rapid_fire"
                ))
            }
        }

        // Reset rapid-fire counter after window
        if (now - profile.lastRapidFireReset > RAPID_FIRE_WINDOW_MS) {
            profile.rapidFireCount = 0
            profile.lastRapidFireReset = now
        }

        // Abnormal input length (3x average)
        if (profile.totalExecutions > 5) {
            if (input.length > profile.avgInputLength * 3 && input.length > 500) {
                findings.add(Finding(
                    "behavioral", "Abnormally large input (${input.length} chars, avg=${profile.avgInputLength.toInt()})",
                    RiskLevel.LOW, "large_input"
                ))
            }
        }

        // High block rate
        if (profile.totalExecutions > 10) {
            val blockRate = profile.blockedExecutions.toDouble() / profile.totalExecutions
            if (blockRate > 0.3) {
                findings.add(Finding(
                    "behavioral", "Plugin has high block rate (${String.format("%.0f%%", blockRate * 100)})",
                    RiskLevel.MEDIUM, "high_block_rate"
                ))
            }
        }

        return findings
    }

    private fun updateBehaviorProfile(pluginId: String, input: String, riskLevel: RiskLevel) {
        val profile = behaviorProfiles.getOrPut(pluginId) { BehaviorProfile() }
        profile.totalExecutions++
        profile.lastExecutionTime = System.currentTimeMillis()
        profile.avgInputLength = (profile.avgInputLength * (profile.totalExecutions - 1) + input.length) /
                profile.totalExecutions

        if (riskLevel == RiskLevel.CRITICAL || riskLevel == RiskLevel.HIGH) {
            profile.blockedExecutions++
            profile.suspiciousInputCount++
        }
    }

    private fun computeRiskLevel(findings: List<Finding>): RiskLevel {
        if (findings.isEmpty()) return RiskLevel.SAFE
        return findings.maxOf { it.severity }
    }

    private fun matchesThreatPattern(input: String, pattern: String): Boolean {
        return if (pattern.contains(".*")) {
            try {
                Regex(pattern).containsMatchIn(input)
            } catch (e: Exception) {
                input.contains(pattern.replace(".*", ""))
            }
        } else {
            input.contains(pattern)
        }
    }

    /**
     * Get behavioral profile for a plugin.
     */
    fun getBehaviorProfile(pluginId: String): Map<String, Any> {
        val profile = behaviorProfiles[pluginId] ?: return emptyMap()
        return mapOf(
            "total_executions" to profile.totalExecutions,
            "blocked_executions" to profile.blockedExecutions,
            "avg_input_length" to profile.avgInputLength.toInt(),
            "suspicious_inputs" to profile.suspiciousInputCount,
            "rapid_fire_count" to profile.rapidFireCount
        )
    }

    /**
     * Get aggregate scanner statistics.
     */
    fun getStats(): Map<String, Any> {
        val totalExecs = behaviorProfiles.values.sumOf { it.totalExecutions }
        val totalBlocked = behaviorProfiles.values.sumOf { it.blockedExecutions }
        return mapOf(
            "plugins_tracked" to behaviorProfiles.size,
            "total_scans" to totalExecs,
            "total_blocked" to totalBlocked,
            "block_rate" to if (totalExecs > 0) totalBlocked.toDouble() / totalExecs else 0.0
        )
    }

    companion object {
        private const val TAG = "PluginSafetyScanner"

        private const val RAPID_FIRE_THRESHOLD_MS = 500L
        private const val RAPID_FIRE_MAX_COUNT = 10
        private const val RAPID_FIRE_WINDOW_MS = 60_000L

        // Organized threat patterns by category
        private val THREAT_PATTERNS = mapOf(
            "system_destruction" to listOf(
                "rm -rf" to RiskLevel.CRITICAL,
                "format c:" to RiskLevel.CRITICAL,
                "del /f /s /q" to RiskLevel.CRITICAL,
                "shutdown -h" to RiskLevel.HIGH,
                "reboot" to RiskLevel.MEDIUM
            ),
            "database_attack" to listOf(
                "drop table" to RiskLevel.CRITICAL,
                "drop database" to RiskLevel.CRITICAL,
                "truncate table" to RiskLevel.CRITICAL,
                "; delete from" to RiskLevel.HIGH,
                "union select" to RiskLevel.HIGH,
                "or 1=1" to RiskLevel.HIGH
            ),
            "code_execution" to listOf(
                "runtime.exec" to RiskLevel.CRITICAL,
                "processbuilder" to RiskLevel.CRITICAL,
                "system.exit" to RiskLevel.CRITICAL,
                "eval(" to RiskLevel.HIGH,
                "exec(" to RiskLevel.HIGH,
                "class.forname" to RiskLevel.HIGH
            ),
            "credential_theft" to listOf(
                "steal password" to RiskLevel.CRITICAL,
                "harvest credentials" to RiskLevel.CRITICAL,
                "dump hashes" to RiskLevel.CRITICAL,
                "keylog" to RiskLevel.CRITICAL,
                "phishing" to RiskLevel.HIGH
            ),
            "network_attack" to listOf(
                "reverse shell" to RiskLevel.CRITICAL,
                "bind shell" to RiskLevel.CRITICAL,
                "port scan" to RiskLevel.HIGH,
                "packet sniff" to RiskLevel.HIGH,
                "man in the middle" to RiskLevel.HIGH
            )
        )
    }
}

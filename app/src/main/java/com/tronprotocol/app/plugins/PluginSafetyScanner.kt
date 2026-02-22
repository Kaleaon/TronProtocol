package com.tronprotocol.app.plugins

import android.util.Log
import com.tronprotocol.app.security.ConstitutionalMemory

/**
 * Plugin Safety Scanner — automated behavioral analysis before plugin execution.
 *
 * Inspired by OpenClaw's skill scanner (src/security/skill-scanner.ts):
 * - Scans plugin inputs for dangerous patterns before execution
 * - Categorizes risk levels (SAFE, LOW, MEDIUM, HIGH, CRITICAL)
 * - Tracks plugin behavior history for anomaly detection
 * - Integrates with ConstitutionalMemory for directive-based blocking
 * - Detects prompt injection attempts, data exfiltration, and privilege escalation
 *
 * Unlike simple pattern matching (the old PolicyGuardrailPlugin approach),
 * this scanner uses multi-signal analysis combining pattern detection,
 * behavioral history, and constitutional directive evaluation.
 */
class PluginSafetyScanner(
    private val constitutionalMemory: ConstitutionalMemory? = null
) {

    enum class RiskLevel { SAFE, LOW, MEDIUM, HIGH, CRITICAL }

    /** Result of scanning a plugin execution request. */
    data class ScanResult(
        val allowed: Boolean,
        val riskLevel: RiskLevel,
        val findings: List<Finding>,
        val constitutionalViolations: List<String>,
        val recommendation: String
    ) {
        val hasCriticalFindings: Boolean get() = findings.any { it.severity == RiskLevel.CRITICAL }
    }

    data class Finding(
        val category: String,
        val description: String,
        val severity: RiskLevel,
        val matchedPattern: String?
    )

    /** Behavioral profile for a plugin. */
    private data class BehaviorProfile(
        var totalExecutions: Long = 0,
        var blockedExecutions: Long = 0,
        var avgInputLength: Double = 0.0,
        var lastExecutionTime: Long = 0,
        var suspiciousInputCount: Long = 0,
        var rapidFireCount: Long = 0,
        var lastRapidFireReset: Long = System.currentTimeMillis()
    )

    private val behaviorProfiles = mutableMapOf<String, BehaviorProfile>()

    /**
     * Scan a plugin execution request before allowing it.
     */
    fun scan(pluginId: String, input: String): ScanResult {
        val findings = mutableListOf<Finding>()
        val constitutionalViolations = mutableListOf<String>()

        // 1. Pattern-based threat detection
        findings.addAll(scanPatterns(input))

        // 2. Prompt injection detection
        findings.addAll(scanPromptInjection(input))

        // 3. Data exfiltration detection
        findings.addAll(scanDataExfiltration(input))

        // 4. Privilege escalation detection
        findings.addAll(scanPrivilegeEscalation(input, pluginId))

        // 5. Behavioral anomaly detection
        findings.addAll(scanBehavioralAnomalies(pluginId, input))

        // 6. Constitutional memory evaluation
        constitutionalMemory?.let { cm ->
            val check = cm.evaluatePrompt(input)
            if (!check.allowed) {
                for (violation in check.violatedDirectives) {
                    constitutionalViolations.add("${violation.id}: ${violation.rule}")
                    findings.add(Finding(
                        "constitutional", "Violated directive: ${violation.id}",
                        RiskLevel.CRITICAL, violation.rule
                    ))
                }
            }
            for (warning in check.warnings) {
                findings.add(Finding(
                    "constitutional_warning", "Warning from directive: ${warning.id}",
                    RiskLevel.MEDIUM, warning.rule
                ))
            }
        }

        // 7. Compute overall risk level
        val riskLevel = computeRiskLevel(findings)

        // 8. Update behavioral profile
        updateBehaviorProfile(pluginId, input, riskLevel)

        // 9. Determine allow/block
        val allowed = riskLevel != RiskLevel.CRITICAL && constitutionalViolations.isEmpty()

        val recommendation = when (riskLevel) {
            RiskLevel.SAFE -> "Execution approved"
            RiskLevel.LOW -> "Execution approved with monitoring"
            RiskLevel.MEDIUM -> "Execution approved with audit logging"
            RiskLevel.HIGH -> "Execution allowed but flagged for review"
            RiskLevel.CRITICAL -> "Execution BLOCKED — critical safety violation"
        }

        if (!allowed) {
            Log.w(TAG, "BLOCKED plugin=$pluginId risk=$riskLevel findings=${findings.size} " +
                    "constitutional_violations=${constitutionalViolations.size}")
        }

        return ScanResult(allowed, riskLevel, findings, constitutionalViolations, recommendation)
    }

    private fun scanPatterns(input: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        val lowered = input.lowercase()

        for ((category, patterns) in THREAT_PATTERNS) {
            for ((pattern, severity) in patterns) {
                if (matchesThreatPattern(lowered, pattern)) {
                    findings.add(Finding(
                        category, "Matched threat pattern: $pattern", severity, pattern
                    ))
                }
            }
        }

        return findings
    }

    private fun scanPromptInjection(input: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        val lowered = input.lowercase()

        // Instruction override attempts
        val injectionPatterns = listOf(
            "ignore previous instructions" to RiskLevel.CRITICAL,
            "ignore all prior" to RiskLevel.CRITICAL,
            "disregard above" to RiskLevel.HIGH,
            "new instructions:" to RiskLevel.HIGH,
            "system prompt:" to RiskLevel.HIGH,
            "you are now" to RiskLevel.MEDIUM,
            "pretend you are" to RiskLevel.MEDIUM,
            "act as if" to RiskLevel.LOW,
            "forget everything" to RiskLevel.HIGH,
            "override safety" to RiskLevel.CRITICAL,
            "jailbreak" to RiskLevel.CRITICAL,
            "do anything now" to RiskLevel.CRITICAL
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
        if (Regex("https?://[^\\s]+").containsMatchIn(input)) {
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

    // Cache compiled patterns to avoid recompilation on every scan
    private val compiledThreatPatterns = mutableMapOf<String, Regex?>()

    private fun matchesThreatPattern(input: String, pattern: String): Boolean {
        return if (pattern.contains(".*")) {
            try {
                val regex = compiledThreatPatterns.getOrPut(pattern) {
                    // Reject patterns with known ReDoS triggers (nested quantifiers)
                    if (REDOS_DETECTOR.containsMatchIn(pattern)) null
                    else Regex(pattern)
                }
                regex?.containsMatchIn(input)
                    ?: input.contains(pattern.replace(".*", ""))
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
            "avg_input_length" to profile.avgInputLength.toLong(),
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
            "block_rate" to if (totalExecs > 0L) totalBlocked.toDouble() / totalExecs else 0.0
        )
    }

    companion object {
        private const val TAG = "PluginSafetyScanner"

        private const val RAPID_FIRE_THRESHOLD_MS = 500L
        private const val RAPID_FIRE_MAX_COUNT = 10L
        private const val RAPID_FIRE_WINDOW_MS = 60_000L
        /** Detects common ReDoS-vulnerable patterns like (a+)+, (a*)*b. */
        private val REDOS_DETECTOR = Regex("""\([^)]*[+*][^)]*\)[+*]""")

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

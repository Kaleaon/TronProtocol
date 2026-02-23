package com.tronprotocol.app.guidance

import android.util.Log
import com.tronprotocol.app.security.AuditLogger
import com.tronprotocol.app.security.ConstitutionalMemory
import com.tronprotocol.app.security.ConstitutionalMemory.Category
import com.tronprotocol.app.security.ConstitutionalMemory.Directive
import com.tronprotocol.app.security.ConstitutionalMemory.Enforcement

/**
 * Constitutional Values Engine — applies constitution-based values to uncensored model I/O.
 *
 * This is the core component that replaces baked-in model safety alignment (which heretic
 * removes via directional ablation) with a transparent, configurable, user-controllable
 * constitutional values system.
 *
 * The philosophy:
 * - Traditional alignment bakes refusal into model weights, making it opaque and inflexible
 * - Heretic (github.com/p-e-w/heretic) removes that alignment via parametrized ablation
 * - This engine replaces it with explicit constitutional directives that are:
 *   (a) Transparent — every directive is inspectable and has a clear rationale
 *   (b) Versioned — changes are tracked, audited, and rollback-capable
 *   (c) Layered — immutable safety core + configurable user values
 *   (d) Auditable — every evaluation is logged for accountability
 *
 * Integration with heretic models:
 * 1. Prompts are evaluated BEFORE being sent to the uncensored model
 * 2. Responses are evaluated AFTER generation, before delivery to the user
 * 3. Values violations produce structured feedback (not opaque refusal)
 * 4. The constitution is the single source of truth for what's allowed
 *
 * @see <a href="https://github.com/p-e-w/heretic">Heretic</a>
 */
class ConstitutionalValuesEngine(
    private val constitutionalMemory: ConstitutionalMemory,
    private val auditLogger: AuditLogger? = null
) {

    /**
     * Result of evaluating content through the constitutional values system.
     */
    data class ValuesEvaluation(
        val allowed: Boolean,
        val content: String?,
        val violations: List<ValuesViolation>,
        val warnings: List<ValuesWarning>,
        val auditTrail: List<String>,
        val constitutionVersion: Int
    ) {
        val hasViolations: Boolean get() = violations.isNotEmpty()
        val hasWarnings: Boolean get() = warnings.isNotEmpty()

        fun summary(): String {
            if (allowed && !hasWarnings) return "Values check passed (constitution v$constitutionVersion)"
            val parts = mutableListOf<String>()
            if (!allowed) parts.add("BLOCKED: ${violations.joinToString(", ") { it.directiveId }}")
            if (hasWarnings) parts.add("WARNINGS: ${warnings.joinToString(", ") { it.directiveId }}")
            return parts.joinToString("; ") + " (constitution v$constitutionVersion)"
        }
    }

    data class ValuesViolation(
        val directiveId: String,
        val category: Category,
        val enforcement: Enforcement,
        val reason: String
    )

    data class ValuesWarning(
        val directiveId: String,
        val category: Category,
        val reason: String
    )

    /**
     * Evaluate a prompt before sending it to an uncensored (heretic-processed) model.
     *
     * This is the input gate: it checks the user's request against the constitution
     * to determine if the uncensored model should process it at all.
     */
    fun evaluatePrompt(prompt: String): ValuesEvaluation {
        val check = constitutionalMemory.evaluatePrompt(prompt)
        val evaluation = buildEvaluation(prompt, check, "prompt")

        auditLogger?.logAsync(
            severity = AuditLogger.Severity.INFO,
            category = AuditLogger.AuditCategory.POLICY_DECISION,
            actor = "heretic_values_engine",
            action = "evaluate_prompt",
            target = "constitutional_values",
            outcome = if (evaluation.allowed) "allowed" else "blocked",
            details = mapOf(
                "prompt_length" to prompt.length,
                "violations" to evaluation.violations.size,
                "warnings" to evaluation.warnings.size,
                "constitution_version" to evaluation.constitutionVersion
            )
        )

        if (!evaluation.allowed) {
            Log.w(TAG, "Prompt blocked by constitutional values: ${evaluation.summary()}")
        }

        return evaluation
    }

    /**
     * Evaluate a response from an uncensored (heretic-processed) model.
     *
     * This is the output gate: it checks the model's raw output against the constitution
     * before delivering it to the user. Since the model has no built-in refusal behavior,
     * this is the only safety layer and must be robust.
     */
    fun evaluateResponse(response: String): ValuesEvaluation {
        val safetyCheck = constitutionalMemory.evaluate(response, Category.SAFETY)
        val dataCheck = constitutionalMemory.evaluate(response, Category.DATA_PROTECTION)
        val identityCheck = constitutionalMemory.evaluate(response, Category.IDENTITY)

        val mergedViolations = mutableListOf<Directive>()
        val mergedWarnings = mutableListOf<Directive>()
        val mergedAudits = mutableListOf<Directive>()

        for (check in listOf(safetyCheck, dataCheck, identityCheck)) {
            mergedViolations.addAll(check.violatedDirectives)
            mergedWarnings.addAll(check.warnings)
            mergedAudits.addAll(check.auditEntries)
        }

        val allowed = mergedViolations.none { it.enforcement == Enforcement.HARD_BLOCK }

        val evaluation = ValuesEvaluation(
            allowed = allowed,
            content = if (allowed) response else null,
            violations = mergedViolations.map {
                ValuesViolation(it.id, it.category, it.enforcement, it.rule)
            },
            warnings = mergedWarnings.map {
                ValuesWarning(it.id, it.category, it.rule)
            },
            auditTrail = mergedAudits.map { "AUDIT[${it.id}]: ${it.rule}" },
            constitutionVersion = constitutionalMemory.getVersion()
        )

        auditLogger?.logAsync(
            severity = AuditLogger.Severity.INFO,
            category = AuditLogger.AuditCategory.POLICY_DECISION,
            actor = "heretic_values_engine",
            action = "evaluate_response",
            target = "constitutional_values",
            outcome = if (evaluation.allowed) "allowed" else "blocked",
            details = mapOf(
                "response_length" to response.length,
                "violations" to evaluation.violations.size,
                "warnings" to evaluation.warnings.size,
                "constitution_version" to evaluation.constitutionVersion
            )
        )

        if (!evaluation.allowed) {
            Log.w(TAG, "Response blocked by constitutional values: ${evaluation.summary()}")
        }

        return evaluation
    }

    /**
     * Generate a structured refusal explanation when content is blocked.
     *
     * Unlike opaque model refusal ("I can't help with that"), this provides
     * a transparent explanation citing the specific constitutional directives.
     */
    fun buildRefusalExplanation(evaluation: ValuesEvaluation): String {
        if (evaluation.allowed) return ""

        val sb = StringBuilder()
        sb.appendLine("[Constitutional Values — Request Declined]")
        sb.appendLine()
        sb.appendLine("This request was evaluated against the constitutional values system")
        sb.appendLine("(version ${evaluation.constitutionVersion}) and could not be fulfilled.")
        sb.appendLine()

        if (evaluation.violations.isNotEmpty()) {
            sb.appendLine("Violated directives:")
            for (v in evaluation.violations) {
                sb.appendLine("  - ${v.directiveId} (${v.category.name}, ${v.enforcement.name})")
            }
            sb.appendLine()
        }

        if (evaluation.warnings.isNotEmpty()) {
            sb.appendLine("Warnings:")
            for (w in evaluation.warnings) {
                sb.appendLine("  - ${w.directiveId} (${w.category.name})")
            }
            sb.appendLine()
        }

        sb.appendLine("The constitutional values system provides transparent, auditable safety")
        sb.appendLine("controls in place of opaque model-level alignment.")

        return sb.toString().trimEnd()
    }

    /**
     * Get a summary of the current constitutional values state.
     */
    fun getValuesStatus(): Map<String, Any> {
        val directives = constitutionalMemory.getDirectives()
        val byCategory = directives.groupBy { it.category }
        val byEnforcement = directives.groupBy { it.enforcement }

        return mapOf(
            "constitution_version" to constitutionalMemory.getVersion(),
            "total_directives" to directives.size,
            "immutable_directives" to directives.count { it.immutable },
            "user_directives" to directives.count { !it.immutable },
            "categories" to byCategory.mapValues { it.value.size },
            "enforcement_levels" to byEnforcement.mapValues { it.value.size }
        )
    }

    private fun buildEvaluation(
        content: String,
        check: ConstitutionalMemory.ConstitutionalCheck,
        phase: String
    ): ValuesEvaluation {
        return ValuesEvaluation(
            allowed = check.allowed,
            content = if (check.allowed) content else null,
            violations = check.violatedDirectives.map {
                ValuesViolation(it.id, it.category, it.enforcement, it.rule)
            },
            warnings = check.warnings.map {
                ValuesWarning(it.id, it.category, it.rule)
            },
            auditTrail = check.auditEntries.map { "AUDIT[${it.id}]: ${it.rule}" },
            constitutionVersion = constitutionalMemory.getVersion()
        )
    }

    companion object {
        private const val TAG = "ConstitutionalValues"
    }
}

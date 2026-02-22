package com.tronprotocol.app.guidance

import android.text.TextUtils
import com.tronprotocol.app.security.ConstitutionalMemory
import com.tronprotocol.app.selfmod.CodeModification
import com.tronprotocol.app.selfmod.CodeModificationManager
import java.util.Locale

/**
 * Ethical kernel validation that gates both local and cloud guidance layers.
 *
 * Enhanced with OpenClaw-inspired ConstitutionalMemory integration:
 * - Falls through to ConstitutionalMemory for structured directive evaluation
 * - Maintains backward compatibility with legacy pattern matching
 * - Provides richer validation outcomes with constitutional check details
 */
class EthicalKernelValidator(
    private val codeModificationManager: CodeModificationManager?,
    private val constitutionalMemory: ConstitutionalMemory? = null
) {

    fun validatePrompt(prompt: String?): ValidationOutcome {
        if (TextUtils.isEmpty(prompt)) {
            return ValidationOutcome.rejected("Prompt is empty")
        }
        val safePrompt = prompt ?: return ValidationOutcome.rejected("Prompt is empty")

        // Layer 1: Constitutional Memory evaluation (OpenClaw-inspired, structured directives)
        constitutionalMemory?.let { cm ->
            val check = cm.evaluatePrompt(safePrompt)
            if (!check.allowed) {
                val violations = check.violatedDirectives.joinToString(", ") { it.id }
                return ValidationOutcome.rejected(
                    "Blocked by constitutional directive(s): $violations"
                )
            }
        }

        // Layer 2: Legacy pattern matching (backward compatibility)
        val lowered = safePrompt.lowercase(Locale.US)
        for (blocked in BLOCKED_PATTERNS) {
            if (lowered.contains(blocked)) {
                return ValidationOutcome.rejected("Prompt blocked by ethical kernel pattern: $blocked")
            }
        }

        return ValidationOutcome.accepted()
    }

    fun validateResponse(response: String?): ValidationOutcome {
        if (TextUtils.isEmpty(response)) {
            return ValidationOutcome.rejected("Response is empty")
        }
        val safeResponse = response ?: return ValidationOutcome.rejected("Response is empty")

        // Layer 1: Constitutional Memory evaluation
        constitutionalMemory?.let { cm ->
            val check = cm.evaluate(safeResponse, ConstitutionalMemory.Category.SAFETY)
            if (!check.allowed) {
                val violations = check.violatedDirectives.joinToString(", ") { it.id }
                return ValidationOutcome.rejected(
                    "Response blocked by constitutional directive(s): $violations"
                )
            }
        }

        // Layer 2: Legacy pattern matching
        val lowered = safeResponse.lowercase(Locale.US)
        for (blocked in BLOCKED_PATTERNS) {
            if (lowered.contains(blocked)) {
                return ValidationOutcome.rejected("Response blocked by ethical kernel pattern: $blocked")
            }
        }

        return ValidationOutcome.accepted()
    }

    fun validateSelfModification(modification: CodeModification): ValidationOutcome {
        // Layer 1: Constitutional Memory self-mod evaluation
        constitutionalMemory?.let { cm ->
            val check = cm.evaluateSelfMod(modification.modifiedCode)
            if (!check.allowed) {
                val violations = check.violatedDirectives.joinToString(", ") { it.id }
                return ValidationOutcome.rejected(
                    "Self-mod blocked by constitutional directive(s): $violations"
                )
            }
        }

        // Layer 2: CodeModificationManager validation
        if (codeModificationManager == null) {
            return ValidationOutcome.rejected("Self-mod validation unavailable")
        }

        val result = codeModificationManager.validate(modification)
        if (!result.isValid()) {
            return ValidationOutcome.rejected("Self-mod rejected by ethical kernel: ${result.getErrors()}")
        }

        return ValidationOutcome.accepted()
    }

    class ValidationOutcome private constructor(
        val allowed: Boolean,
        val message: String
    ) {
        companion object {
            fun accepted(): ValidationOutcome {
                return ValidationOutcome(true, "accepted")
            }

            fun rejected(reason: String): ValidationOutcome {
                return ValidationOutcome(false, reason)
            }
        }
    }

    companion object {
        private val BLOCKED_PATTERNS = listOf(
            "rm -rf", "drop table", "disable security", "steal password", "bypass approval"
        )
    }
}

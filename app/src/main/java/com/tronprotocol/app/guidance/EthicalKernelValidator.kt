package com.tronprotocol.app.guidance

import android.text.TextUtils
import com.tronprotocol.app.selfmod.CodeModification
import com.tronprotocol.app.selfmod.CodeModificationManager
import java.util.Locale

/**
 * Ethical kernel validation that gates both local and cloud guidance layers.
 */
class EthicalKernelValidator(
    private val codeModificationManager: CodeModificationManager?
) {

    fun validatePrompt(prompt: String?): ValidationOutcome {
        if (TextUtils.isEmpty(prompt)) {
            return ValidationOutcome.rejected("Prompt is empty")
        }

        val lowered = prompt!!.lowercase(Locale.US)
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

        val lowered = response!!.lowercase(Locale.US)
        for (blocked in BLOCKED_PATTERNS) {
            if (lowered.contains(blocked)) {
                return ValidationOutcome.rejected("Response blocked by ethical kernel pattern: $blocked")
            }
        }

        return ValidationOutcome.accepted()
    }

    fun validateSelfModification(modification: CodeModification): ValidationOutcome {
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

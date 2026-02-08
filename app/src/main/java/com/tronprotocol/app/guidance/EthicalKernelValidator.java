package com.tronprotocol.app.guidance;

import android.text.TextUtils;

import com.tronprotocol.app.selfmod.CodeModification;
import com.tronprotocol.app.selfmod.CodeModificationManager;
import com.tronprotocol.app.selfmod.ValidationResult;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Ethical kernel validation that gates both local and cloud guidance layers.
 */
public class EthicalKernelValidator {
    private static final List<String> BLOCKED_PATTERNS = Arrays.asList(
        "rm -rf", "drop table", "disable security", "steal password", "bypass approval"
    );

    private final CodeModificationManager codeModificationManager;

    public EthicalKernelValidator(CodeModificationManager codeModificationManager) {
        this.codeModificationManager = codeModificationManager;
    }

    public ValidationOutcome validatePrompt(String prompt) {
        if (TextUtils.isEmpty(prompt)) {
            return ValidationOutcome.rejected("Prompt is empty");
        }

        String lowered = prompt.toLowerCase(Locale.US);
        for (String blocked : BLOCKED_PATTERNS) {
            if (lowered.contains(blocked)) {
                return ValidationOutcome.rejected("Prompt blocked by ethical kernel pattern: " + blocked);
            }
        }

        return ValidationOutcome.accepted();
    }

    public ValidationOutcome validateResponse(String response) {
        if (TextUtils.isEmpty(response)) {
            return ValidationOutcome.rejected("Response is empty");
        }

        String lowered = response.toLowerCase(Locale.US);
        for (String blocked : BLOCKED_PATTERNS) {
            if (lowered.contains(blocked)) {
                return ValidationOutcome.rejected("Response blocked by ethical kernel pattern: " + blocked);
            }
        }

        return ValidationOutcome.accepted();
    }

    public ValidationOutcome validateSelfModification(CodeModification modification) {
        if (codeModificationManager == null) {
            return ValidationOutcome.rejected("Self-mod validation unavailable");
        }

        ValidationResult result = codeModificationManager.validate(modification);
        if (!result.isValid()) {
            return ValidationOutcome.rejected("Self-mod rejected by ethical kernel: " + result.getErrors());
        }

        return ValidationOutcome.accepted();
    }

    public static class ValidationOutcome {
        public final boolean allowed;
        public final String message;

        private ValidationOutcome(boolean allowed, String message) {
            this.allowed = allowed;
            this.message = message;
        }

        public static ValidationOutcome accepted() {
            return new ValidationOutcome(true, "accepted");
        }

        public static ValidationOutcome rejected(String reason) {
            return new ValidationOutcome(false, reason);
        }
    }
}

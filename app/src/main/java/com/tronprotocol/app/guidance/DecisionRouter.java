package com.tronprotocol.app.guidance;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Local decision router that picks local handling vs cloud model tier.
 */
public class DecisionRouter {

    private static final Set<String> HIGH_STAKES_TERMS = new HashSet<>(Arrays.asList(
        "identity", "persona", "self-mod", "self mod", "self modification",
        "ethical kernel", "ethics", "policy override", "approval", "governance"
    ));

    public RouteDecision decide(String prompt) {
        if (prompt == null) {
            return RouteDecision.local("Empty prompt");
        }

        String lowered = prompt.toLowerCase(Locale.US);

        for (String term : HIGH_STAKES_TERMS) {
            if (lowered.contains(term)) {
                return RouteDecision.cloud(AnthropicApiClient.MODEL_OPUS,
                    "High-stakes decision requires Opus tier");
            }
        }

        if (canHandleLocally(lowered)) {
            return RouteDecision.local("Simple prompt handled locally");
        }

        return RouteDecision.cloud(AnthropicApiClient.MODEL_SONNET,
            "General guidance delegated to Sonnet tier");
    }

    private boolean canHandleLocally(String lowered) {
        boolean shortPrompt = lowered.length() < 180;
        boolean simpleIntent = lowered.contains("hello") || lowered.contains("summary") ||
            lowered.contains("todo") || lowered.contains("status") || lowered.contains("help");
        boolean noCodeMutation = !lowered.contains("modify code") && !lowered.contains("patch") &&
            !lowered.contains("rewrite") && !lowered.contains("approve");

        return shortPrompt && simpleIntent && noCodeMutation;
    }

    public static class RouteDecision {
        public final boolean useLocal;
        public final String cloudModel;
        public final String reason;

        private RouteDecision(boolean useLocal, String cloudModel, String reason) {
            this.useLocal = useLocal;
            this.cloudModel = cloudModel;
            this.reason = reason;
        }

        static RouteDecision local(String reason) {
            return new RouteDecision(true, null, reason);
        }

        static RouteDecision cloud(String model, String reason) {
            return new RouteDecision(false, model, reason);
        }
    }
}

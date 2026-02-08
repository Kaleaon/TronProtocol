package com.tronprotocol.app.guidance;

import android.text.TextUtils;

import com.tronprotocol.app.rag.RAGStore;
import com.tronprotocol.app.rag.RetrievalResult;
import com.tronprotocol.app.rag.RetrievalStrategy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Orchestrates local-vs-cloud guidance, caching, and ethical-kernel validation.
 */
public class GuidanceOrchestrator {
    private static final String CACHE_PREFIX = "GUIDANCE_CACHE";

    private final AnthropicApiClient anthropicApiClient;
    private final DecisionRouter decisionRouter;
    private final EthicalKernelValidator ethicalKernelValidator;
    private final RAGStore ragStore;

    public GuidanceOrchestrator(AnthropicApiClient anthropicApiClient,
                                DecisionRouter decisionRouter,
                                EthicalKernelValidator ethicalKernelValidator,
                                RAGStore ragStore) {
        this.anthropicApiClient = anthropicApiClient;
        this.decisionRouter = decisionRouter;
        this.ethicalKernelValidator = ethicalKernelValidator;
        this.ragStore = ragStore;
    }

    public GuidanceResponse guide(String apiKey, String prompt) throws Exception {
        EthicalKernelValidator.ValidationOutcome promptCheck = ethicalKernelValidator.validatePrompt(prompt);
        if (!promptCheck.allowed) {
            return GuidanceResponse.error(promptCheck.message, "blocked");
        }

        DecisionRouter.RouteDecision decision = decisionRouter.decide(prompt);
        if (decision.useLocal) {
            String localAnswer = buildLocalResponse(prompt, decision.reason);
            EthicalKernelValidator.ValidationOutcome localCheck = ethicalKernelValidator.validateResponse(localAnswer);
            if (!localCheck.allowed) {
                return GuidanceResponse.error(localCheck.message, "local_blocked");
            }
            cache(prompt, localAnswer, "local");
            return GuidanceResponse.success(localAnswer, "local", "local-brain", true);
        }

        String cached = lookupCache(prompt);
        if (!TextUtils.isEmpty(cached)) {
            EthicalKernelValidator.ValidationOutcome cacheCheck = ethicalKernelValidator.validateResponse(cached);
            if (!cacheCheck.allowed) {
                return GuidanceResponse.error(cacheCheck.message, "cache_blocked");
            }
            return GuidanceResponse.success(cached, "cache", decision.cloudModel, true);
        }

        String cloudAnswer = anthropicApiClient.createGuidance(apiKey, decision.cloudModel, prompt, 600);
        EthicalKernelValidator.ValidationOutcome cloudCheck = ethicalKernelValidator.validateResponse(cloudAnswer);
        if (!cloudCheck.allowed) {
            return GuidanceResponse.error(cloudCheck.message, "cloud_blocked");
        }

        cache(prompt, cloudAnswer, decision.cloudModel);
        return GuidanceResponse.success(cloudAnswer, "cloud", decision.cloudModel, false);
    }

    private String buildLocalResponse(String prompt, String reason) {
        return "[Local Guidance]\n" +
            "Decision: " + reason + "\n" +
            "Prompt: " + prompt + "\n" +
            "Guidance: This request is within local capability. For high-stakes identity, self-mod approval, " +
            "or ethical-kernel work, routing will escalate to " + AnthropicApiClient.MODEL_OPUS + ".";
    }

    private void cache(String prompt, String answer, String sourceModel) {
        if (ragStore == null) {
            return;
        }

        try {
            String hash = hash(prompt);
            String payload = CACHE_PREFIX + "|" + hash + "|" + sourceModel + "|Q:" + prompt + "|A:" + answer;
            ragStore.addKnowledge(payload, "guidance_cache");
        } catch (Exception ignored) {
            // Best-effort cache.
        }
    }

    private String lookupCache(String prompt) {
        if (ragStore == null) {
            return null;
        }

        try {
            String hash = hash(prompt);
            List<RetrievalResult> results = ragStore.retrieve(hash, RetrievalStrategy.KEYWORD, 5);
            for (RetrievalResult result : results) {
                String content = result.getChunk().getContent();
                String marker = CACHE_PREFIX + "|" + hash + "|";
                if (content != null && content.startsWith(marker)) {
                    int idx = content.indexOf("|A:");
                    if (idx >= 0 && idx + 3 <= content.length()) {
                        return content.substring(idx + 3).trim();
                    }
                }
            }
        } catch (Exception ignored) {
            // No cache fallback.
        }
        return null;
    }

    private String hash(String text) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8 && i < digest.length; i++) {
            out.append(String.format("%02x", digest[i]));
        }
        return out.toString();
    }

    public static class GuidanceResponse {
        public final boolean success;
        public final String answer;
        public final String route;
        public final String model;
        public final boolean cacheHit;
        public final String error;

        private GuidanceResponse(boolean success, String answer, String route, String model, boolean cacheHit, String error) {
            this.success = success;
            this.answer = answer;
            this.route = route;
            this.model = model;
            this.cacheHit = cacheHit;
            this.error = error;
        }

        static GuidanceResponse success(String answer, String route, String model, boolean cacheHit) {
            return new GuidanceResponse(true, answer, route, model, cacheHit, null);
        }

        static GuidanceResponse error(String error, String route) {
            return new GuidanceResponse(false, null, route, null, false, error);
        }
    }
}

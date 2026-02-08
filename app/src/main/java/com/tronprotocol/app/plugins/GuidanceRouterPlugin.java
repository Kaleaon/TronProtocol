package com.tronprotocol.app.plugins;

import android.content.Context;

import com.tronprotocol.app.guidance.AnthropicApiClient;
import com.tronprotocol.app.guidance.EthicalKernelValidator;
import com.tronprotocol.app.guidance.GuidanceOrchestrator;
import com.tronprotocol.app.guidance.DecisionRouter;
import com.tronprotocol.app.rag.RAGStore;
import com.tronprotocol.app.security.SecureStorage;
import com.tronprotocol.app.selfmod.CodeModificationManager;

/**
 * Routes routine guidance through Sonnet and high-stakes guidance through Opus.
 */
public class GuidanceRouterPlugin implements Plugin {
    private static final String ID = "guidance_router";
    private static final String API_KEY = "anthropic_api_key";
    private static final String AI_ID = "tronprotocol_ai";

    private boolean enabled = true;

    private SecureStorage secureStorage;
    private GuidanceOrchestrator orchestrator;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Guidance Router";
    }

    @Override
    public String getDescription() {
        return "Anthropic-backed guidance with decision routing, cache, and ethical checks. " +
            "Commands: set_api_key|key, guide|question, stats";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public PluginResult execute(String input) {
        long start = System.currentTimeMillis();
        try {
            if (input == null || input.trim().isEmpty()) {
                return PluginResult.error("No command provided", elapsed(start));
            }

            String[] parts = input.split("\\|", 2);
            String command = parts[0].trim().toLowerCase();

            switch (command) {
                case "set_api_key":
                    if (parts.length < 2 || parts[1].trim().isEmpty()) {
                        return PluginResult.error("Usage: set_api_key|<anthropic_api_key>", elapsed(start));
                    }
                    secureStorage.store(API_KEY, parts[1].trim());
                    return PluginResult.success("Anthropic API key saved", elapsed(start));
                case "guide":
                    if (parts.length < 2 || parts[1].trim().isEmpty()) {
                        return PluginResult.error("Usage: guide|<question>", elapsed(start));
                    }
                    String key = secureStorage.retrieve(API_KEY);
                    GuidanceOrchestrator.GuidanceResponse response = orchestrator.guide(key, parts[1].trim());
                    if (!response.success) {
                        return PluginResult.error("Guidance failed: " + response.error, elapsed(start));
                    }
                    String payload = "route=" + response.route +
                        ", model=" + response.model +
                        ", cache_hit=" + response.cacheHit + "\n" + response.answer;
                    return PluginResult.success(payload, elapsed(start));
                case "stats":
                    return PluginResult.success(
                        "Configured models: routine=" + AnthropicApiClient.MODEL_SONNET +
                            ", high_stakes=" + AnthropicApiClient.MODEL_OPUS +
                            "; ethical kernel validation enabled on local+cloud layers",
                        elapsed(start)
                    );
                default:
                    return PluginResult.error("Unknown command: " + command, elapsed(start));
            }
        } catch (Exception e) {
            return PluginResult.error("Guidance router failed: " + e.getMessage(), elapsed(start));
        }
    }

    @Override
    public void initialize(Context context) {
        try {
            secureStorage = new SecureStorage(context);
            RAGStore ragStore = new RAGStore(context, AI_ID);
            CodeModificationManager codeModificationManager = new CodeModificationManager(context);

            AnthropicApiClient client = new AnthropicApiClient(20, 1500);
            DecisionRouter router = new DecisionRouter();
            EthicalKernelValidator validator = new EthicalKernelValidator(codeModificationManager);
            orchestrator = new GuidanceOrchestrator(client, router, validator, ragStore);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize guidance router", e);
        }
    }

    @Override
    public void destroy() {
        // No-op
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}

package com.tronprotocol.app.plugins;

import android.content.Context;
import android.util.Log;

import com.tronprotocol.app.llm.LLMModelConfig;
import com.tronprotocol.app.llm.OnDeviceLLMManager;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * On-Device LLM Plugin — runs small language models locally via MNN.
 *
 * Enables offline, low-latency AI inference using Alibaba's MNN framework
 * with quantized models (Qwen, Gemma, DeepSeek) up to ~4B parameters.
 *
 * Commands:
 *   status              - Show device capability and model state
 *   discover             - Scan for available model directories
 *   load|<model_path>    - Load a model from the specified directory
 *   generate|<prompt>    - Generate text using the loaded model
 *   benchmark            - Run inference benchmark
 *   unload               - Unload the current model
 *   stats                - Show inference statistics
 *
 * @see OnDeviceLLMManager
 * @see <a href="https://github.com/alibaba/MNN">Alibaba MNN</a>
 */
public class OnDeviceLLMPlugin implements Plugin {

    private static final String TAG = "OnDeviceLLMPlugin";
    private static final String ID = "on_device_llm";

    private OnDeviceLLMManager llmManager;
    private boolean enabled = true;

    @NotNull
    @Override
    public String getId() {
        return ID;
    }

    @NotNull
    @Override
    public String getName() {
        return "On-Device LLM";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Run small LLMs locally via MNN (Alibaba). Supports Qwen, Gemma, DeepSeek " +
                "models up to ~4B params with Q4 quantization. " +
                "Commands: status, discover, load|path, generate|prompt, benchmark, unload, stats";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @NotNull
    @Override
    public PluginResult execute(@NotNull String input) {
        long start = System.currentTimeMillis();

        if (input == null || input.trim().isEmpty()) {
            return PluginResult.error("No command provided. Use: status, discover, " +
                    "load|path, generate|prompt, benchmark, unload, stats", elapsed(start));
        }

        String[] parts = input.split("\\|", 2);
        String command = parts[0].trim().toLowerCase();

        try {
            switch (command) {
                case "status":
                    return executeStatus(start);
                case "discover":
                    return executeDiscover(start);
                case "load":
                    if (parts.length < 2 || parts[1].trim().isEmpty()) {
                        return PluginResult.error("Usage: load|<model_directory_path>", elapsed(start));
                    }
                    return executeLoad(parts[1].trim(), start);
                case "generate":
                    if (parts.length < 2 || parts[1].trim().isEmpty()) {
                        return PluginResult.error("Usage: generate|<prompt>", elapsed(start));
                    }
                    return executeGenerate(parts[1].trim(), start);
                case "benchmark":
                    return executeBenchmark(start);
                case "unload":
                    return executeUnload(start);
                case "stats":
                    return executeStats(start);
                default:
                    return PluginResult.error("Unknown command: " + command +
                            ". Use: status, discover, load|path, generate|prompt, " +
                            "benchmark, unload, stats", elapsed(start));
            }
        } catch (Exception e) {
            Log.e(TAG, "Command '" + command + "' failed: " + e.getMessage(), e);
            return PluginResult.error("Command failed: " + e.getMessage(), elapsed(start));
        }
    }

    @Override
    public void initialize(@NotNull Context context) {
        try {
            llmManager = new OnDeviceLLMManager(context);
            Log.d(TAG, "OnDeviceLLMPlugin initialized. MNN native available: "
                    + OnDeviceLLMManager.isNativeAvailable());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize OnDeviceLLMManager: " + e.getMessage(), e);
            throw new RuntimeException("Failed to initialize on-device LLM plugin", e);
        }
    }

    @Override
    public void destroy() {
        if (llmManager != null) {
            llmManager.shutdown();
            llmManager = null;
        }
    }

    /**
     * Provide access to the LLM manager for other components
     * (e.g., GuidanceOrchestrator for on-device fallback).
     */
    public OnDeviceLLMManager getLLMManager() {
        return llmManager;
    }

    // ---- Command implementations ----

    private PluginResult executeStatus(long start) {
        if (llmManager == null) {
            return PluginResult.error("LLM manager not initialized", elapsed(start));
        }

        OnDeviceLLMManager.DeviceCapability cap = llmManager.assessDevice();
        StringBuilder sb = new StringBuilder();
        sb.append("=== On-Device LLM Status ===\n");
        sb.append("MNN Native Libraries: ").append(OnDeviceLLMManager.isNativeAvailable()
                ? "AVAILABLE" : "NOT INSTALLED").append("\n");
        sb.append("Model State: ").append(llmManager.getModelState()).append("\n");
        sb.append("\n--- Device Capability ---\n");
        sb.append("Total RAM: ").append(cap.totalRamMb).append(" MB\n");
        sb.append("Available RAM: ").append(cap.availableRamMb).append(" MB\n");
        sb.append("CPU Architecture: ").append(cap.cpuArch).append("\n");
        sb.append("ARM64 Support: ").append(cap.supportsArm64).append("\n");
        sb.append("FP16 Support: ").append(cap.supportsFp16).append("\n");
        sb.append("GPU Available: ").append(cap.hasGpu).append("\n");
        sb.append("Recommended Threads: ").append(cap.recommendedThreads).append("\n");
        sb.append("Max Model Size: ").append(cap.maxModelSizeMb).append(" MB\n");
        sb.append("Can Run LLM: ").append(cap.canRunLLM).append("\n");
        sb.append("Recommended Model: ").append(cap.recommendedModel).append("\n");
        sb.append("Assessment: ").append(cap.reason);

        if (llmManager.isReady()) {
            LLMModelConfig config = llmManager.getActiveConfig();
            if (config != null) {
                sb.append("\n\n--- Active Model ---\n");
                sb.append("Name: ").append(config.getModelName()).append("\n");
                sb.append("Parameters: ").append(config.getParameterCount()).append("\n");
                sb.append("Quantization: ").append(config.getQuantization()).append("\n");
                sb.append("Backend: ").append(config.getBackendName()).append("\n");
                sb.append("Threads: ").append(config.getThreadCount()).append("\n");
                sb.append("Max Tokens: ").append(config.getMaxTokens()).append("\n");
                sb.append("Memory Mapped: ").append(config.isUseMmap());
            }
        }

        return PluginResult.success(sb.toString(), elapsed(start));
    }

    private PluginResult executeDiscover(long start) {
        if (llmManager == null) {
            return PluginResult.error("LLM manager not initialized", elapsed(start));
        }

        List<File> models = llmManager.discoverModels();
        if (models.isEmpty()) {
            return PluginResult.success(
                    "No MNN model directories found.\n" +
                    "Place exported models in one of:\n" +
                    "  - /sdcard/mnn_models/<model_name>/\n" +
                    "  - <app_files>/mnn_models/<model_name>/\n\n" +
                    "Each directory should contain: llm.mnn, config.json, tokenizer.txt\n\n" +
                    "Export models using MNN's llmexport.py:\n" +
                    "  python llmexport.py --path Qwen2.5-1.5B-Instruct " +
                    "--export mnn --quant_bit 4",
                    elapsed(start));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(models.size()).append(" model(s):\n");
        for (File modelDir : models) {
            sb.append("  - ").append(modelDir.getAbsolutePath());
            File weightFile = new File(modelDir, "llm.mnn.weight");
            if (weightFile.exists()) {
                long sizeMb = weightFile.length() / (1024 * 1024);
                sb.append(" (").append(sizeMb).append(" MB)");
            }
            sb.append("\n");
        }
        sb.append("\nUse: load|<path> to load a model");

        return PluginResult.success(sb.toString(), elapsed(start));
    }

    private PluginResult executeLoad(String modelPath, long start) {
        if (llmManager == null) {
            return PluginResult.error("LLM manager not initialized", elapsed(start));
        }

        if (!OnDeviceLLMManager.isNativeAvailable()) {
            return PluginResult.error(
                    "MNN native libraries not installed. To enable on-device LLM:\n" +
                    "1. Clone https://github.com/alibaba/MNN\n" +
                    "2. Build with: cmake -DMNN_BUILD_LLM=true " +
                    "-DMNN_SUPPORT_TRANSFORMER_FUSE=true ...\n" +
                    "3. Copy libMNN.so, libMNN_Express.so, libmnnllm.so to " +
                    "app/src/main/jniLibs/arm64-v8a/",
                    elapsed(start));
        }

        File modelDir = new File(modelPath);
        LLMModelConfig config = llmManager.createConfigFromDirectory(modelDir);
        Log.d(TAG, "Loading model: " + config);

        boolean loaded = llmManager.loadModel(config);
        if (loaded) {
            return PluginResult.success(
                    "Model loaded: " + config.getModelName() +
                    " (backend=" + config.getBackendName() +
                    ", threads=" + config.getThreadCount() + ")",
                    elapsed(start));
        } else {
            return PluginResult.error(
                    "Failed to load model from: " + modelPath +
                    ". State: " + llmManager.getModelState(),
                    elapsed(start));
        }
    }

    private PluginResult executeGenerate(String prompt, long start) {
        if (llmManager == null) {
            return PluginResult.error("LLM manager not initialized", elapsed(start));
        }

        if (!llmManager.isReady()) {
            return PluginResult.error(
                    "No model loaded. Use: discover (to find models) then load|<path>",
                    elapsed(start));
        }

        OnDeviceLLMManager.GenerationResult result = llmManager.generate(prompt);
        if (result.success) {
            String output = result.text + "\n\n" +
                    "[" + result.tokensGenerated + " tokens, " +
                    result.latencyMs + "ms, " +
                    String.format("%.1f", result.tokensPerSecond) + " tok/s, " +
                    "model=" + result.modelId + "]";
            return PluginResult.success(output, elapsed(start));
        } else {
            return PluginResult.error("Generation failed: " + result.error, elapsed(start));
        }
    }

    private PluginResult executeBenchmark(long start) {
        if (llmManager == null) {
            return PluginResult.error("LLM manager not initialized", elapsed(start));
        }

        if (!llmManager.isReady()) {
            return PluginResult.error("No model loaded for benchmarking", elapsed(start));
        }

        Map<String, Object> results = llmManager.benchmark();
        StringBuilder sb = new StringBuilder();
        sb.append("=== Benchmark Results ===\n");
        for (Map.Entry<String, Object> entry : results.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }

        return PluginResult.success(sb.toString(), elapsed(start));
    }

    private PluginResult executeUnload(long start) {
        if (llmManager == null) {
            return PluginResult.error("LLM manager not initialized", elapsed(start));
        }

        llmManager.unloadModel();
        return PluginResult.success("Model unloaded", elapsed(start));
    }

    private PluginResult executeStats(long start) {
        if (llmManager == null) {
            return PluginResult.error("LLM manager not initialized", elapsed(start));
        }

        Map<String, Object> llmStats = llmManager.getStats();
        StringBuilder sb = new StringBuilder();
        sb.append("=== On-Device LLM Stats ===\n");
        for (Map.Entry<String, Object> entry : llmStats.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }

        return PluginResult.success(sb.toString(), elapsed(start));
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}

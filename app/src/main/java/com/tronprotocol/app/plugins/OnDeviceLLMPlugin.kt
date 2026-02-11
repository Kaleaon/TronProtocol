package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.llm.OnDeviceLLMManager
import java.io.File

/**
 * On-Device LLM Plugin — runs small language models locally via MNN.
 *
 * Enables offline, low-latency AI inference using Alibaba's MNN framework
 * with quantized models (Qwen, Gemma, DeepSeek) up to ~4B parameters.
 *
 * Commands:
 *   status              - Show device capability and model state
 *   discover            - Scan for available model directories
 *   load|<model_path>   - Load a model from the specified directory
 *   generate|<prompt>   - Generate text using the loaded model
 *   benchmark           - Run inference benchmark
 *   unload              - Unload the current model
 *   stats               - Show inference statistics
 *
 * @see OnDeviceLLMManager
 * @see [Alibaba MNN](https://github.com/alibaba/MNN)
 */
class OnDeviceLLMPlugin : Plugin {

    private var llmManager: OnDeviceLLMManager? = null

    override val id: String = ID
    override val name: String = "On-Device LLM"
    override val description: String =
        "Run small LLMs locally via MNN (Alibaba). Supports Qwen, Gemma, DeepSeek " +
                "models up to ~4B params with Q4 quantization. " +
                "Commands: status, discover, load|path, generate|prompt, benchmark, unload, stats"
    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()

        if (input.isBlank()) {
            return PluginResult.error(
                "No command provided. Use: status, discover, " +
                        "load|path, generate|prompt, benchmark, unload, stats",
                elapsed(start)
            )
        }

        val parts = input.split("\\|".toRegex(), 2)
        val command = parts[0].trim().lowercase()

        return try {
            when (command) {
                "status" -> executeStatus(start)
                "discover" -> executeDiscover(start)
                "load" -> {
                    val path = parts.getOrNull(1)?.trim()
                    if (path.isNullOrBlank()) {
                        PluginResult.error("Usage: load|<model_directory_path>", elapsed(start))
                    } else {
                        executeLoad(path, start)
                    }
                }
                "generate" -> {
                    val prompt = parts.getOrNull(1)?.trim()
                    if (prompt.isNullOrBlank()) {
                        PluginResult.error("Usage: generate|<prompt>", elapsed(start))
                    } else {
                        executeGenerate(prompt, start)
                    }
                }
                "benchmark" -> executeBenchmark(start)
                "unload" -> executeUnload(start)
                "stats" -> executeStats(start)
                else -> PluginResult.error(
                    "Unknown command: $command. Use: status, discover, load|path, " +
                            "generate|prompt, benchmark, unload, stats",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command '$command' failed: ${e.message}", e)
            PluginResult.error("Command failed: ${e.message}", elapsed(start))
        }
    }

    override fun initialize(context: Context) {
        try {
            llmManager = OnDeviceLLMManager(context)
            Log.d(TAG, "OnDeviceLLMPlugin initialized. MNN native available: " +
                    OnDeviceLLMManager.isNativeAvailable())
        } catch (e: Exception) {
            Log.w(TAG, "OnDeviceLLMPlugin initialized without MNN backend: ${e.message}")
            // Don't throw — plugin registers successfully but operates in degraded mode.
            // Commands will report that MNN native libraries are not installed.
        }
    }

    override fun destroy() {
        llmManager?.shutdown()
        llmManager = null
    }

    /**
     * Provide access to the LLM manager for other components
     * (e.g., GuidanceOrchestrator for on-device fallback).
     */
    fun getLLMManager(): OnDeviceLLMManager? = llmManager

    // ---- Command implementations ----

    private fun executeStatus(start: Long): PluginResult {
        val manager = llmManager
            ?: return PluginResult.error("LLM manager not initialized", elapsed(start))

        val cap = manager.assessDevice()
        val sb = StringBuilder().apply {
            append("=== On-Device LLM Status ===\n")
            append("MNN Native Libraries: ")
            append(if (OnDeviceLLMManager.isNativeAvailable()) "AVAILABLE" else "NOT INSTALLED")
            append("\nModel State: ${manager.modelState}\n")
            append("\n--- Device Capability ---\n")
            append("Total RAM: ${cap.totalRamMb} MB\n")
            append("Available RAM: ${cap.availableRamMb} MB\n")
            append("CPU Architecture: ${cap.cpuArch}\n")
            append("ARM64 Support: ${cap.supportsArm64}\n")
            append("FP16 Support: ${cap.supportsFp16}\n")
            append("GPU Available: ${cap.hasGpu}\n")
            append("Recommended Threads: ${cap.recommendedThreads}\n")
            append("Max Model Size: ${cap.maxModelSizeMb} MB\n")
            append("Can Run LLM: ${cap.canRunLLM}\n")
            append("Recommended Model: ${cap.recommendedModel}\n")
            append("Assessment: ${cap.reason}")
        }

        if (manager.isReady) {
            manager.activeConfig?.let { config ->
                sb.append("\n\n--- Active Model ---\n")
                sb.append("Name: ${config.modelName}\n")
                sb.append("Parameters: ${config.parameterCount}\n")
                sb.append("Quantization: ${config.quantization}\n")
                sb.append("Backend: ${config.backendName}\n")
                sb.append("Threads: ${config.threadCount}\n")
                sb.append("Max Tokens: ${config.maxTokens}\n")
                sb.append("Memory Mapped: ${config.useMmap}")
            }
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun executeDiscover(start: Long): PluginResult {
        val manager = llmManager
            ?: return PluginResult.error("LLM manager not initialized", elapsed(start))

        val models = manager.discoverModels()
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
                elapsed(start)
            )
        }

        val sb = StringBuilder("Found ${models.size} model(s):\n")
        for (modelDir in models) {
            sb.append("  - ${modelDir.absolutePath}")
            val weightFile = File(modelDir, "llm.mnn.weight")
            if (weightFile.exists()) {
                val sizeMb = weightFile.length() / (1024 * 1024)
                sb.append(" ($sizeMb MB)")
            }
            sb.append("\n")
        }
        sb.append("\nUse: load|<path> to load a model")

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun executeLoad(modelPath: String, start: Long): PluginResult {
        val manager = llmManager
            ?: return PluginResult.error("LLM manager not initialized", elapsed(start))

        if (!OnDeviceLLMManager.isNativeAvailable()) {
            return PluginResult.error(
                "MNN native libraries not installed. To enable on-device LLM:\n" +
                        "1. Clone https://github.com/alibaba/MNN\n" +
                        "2. Build with: cmake -DMNN_BUILD_LLM=true " +
                        "-DMNN_SUPPORT_TRANSFORMER_FUSE=true ...\n" +
                        "3. Copy libMNN.so, libMNN_Express.so, libmnnllm.so to " +
                        "app/src/main/jniLibs/arm64-v8a/",
                elapsed(start)
            )
        }

        val modelDir = File(modelPath)
        val config = manager.createConfigFromDirectory(modelDir)
        Log.d(TAG, "Loading model: $config")

        return if (manager.loadModel(config)) {
            PluginResult.success(
                "Model loaded: ${config.modelName} " +
                        "(backend=${config.backendName}, threads=${config.threadCount})",
                elapsed(start)
            )
        } else {
            PluginResult.error(
                "Failed to load model from: $modelPath. State: ${manager.modelState}",
                elapsed(start)
            )
        }
    }

    private fun executeGenerate(prompt: String, start: Long): PluginResult {
        val manager = llmManager
            ?: return PluginResult.error("LLM manager not initialized", elapsed(start))

        if (!manager.isReady) {
            return PluginResult.error(
                "No model loaded. Use: discover (to find models) then load|<path>",
                elapsed(start)
            )
        }

        val result = manager.generate(prompt)
        return if (result.success) {
            val output = "${result.text}\n\n" +
                    "[${result.tokensGenerated} tokens, " +
                    "${result.latencyMs}ms, " +
                    "${String.format("%.1f", result.tokensPerSecond)} tok/s, " +
                    "model=${result.modelId}]"
            PluginResult.success(output, elapsed(start))
        } else {
            PluginResult.error("Generation failed: ${result.error}", elapsed(start))
        }
    }

    private fun executeBenchmark(start: Long): PluginResult {
        val manager = llmManager
            ?: return PluginResult.error("LLM manager not initialized", elapsed(start))

        if (!manager.isReady) {
            return PluginResult.error("No model loaded for benchmarking", elapsed(start))
        }

        val results = manager.benchmark()
        val sb = StringBuilder("=== Benchmark Results ===\n")
        for ((key, value) in results) {
            sb.append("$key: $value\n")
        }
        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun executeUnload(start: Long): PluginResult {
        val manager = llmManager
            ?: return PluginResult.error("LLM manager not initialized", elapsed(start))

        manager.unloadModel()
        return PluginResult.success("Model unloaded", elapsed(start))
    }

    private fun executeStats(start: Long): PluginResult {
        val manager = llmManager
            ?: return PluginResult.error("LLM manager not initialized", elapsed(start))

        val llmStats = manager.getStats()
        val sb = StringBuilder("=== On-Device LLM Stats ===\n")
        for ((key, value) in llmStats) {
            sb.append("$key: $value\n")
        }
        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    companion object {
        private const val TAG = "OnDeviceLLMPlugin"
        private const val ID = "on_device_llm"
    }
}

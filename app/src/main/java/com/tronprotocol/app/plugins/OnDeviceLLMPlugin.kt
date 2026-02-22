package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.llm.ModelCatalog
import com.tronprotocol.app.llm.ModelDownloadManager
import com.tronprotocol.app.llm.ModelRepository
import com.tronprotocol.app.llm.OnDeviceLLMManager
import java.io.File

/**
 * On-Device LLM Plugin — model hub with catalog, downloader, selector, and inference.
 *
 * Modernized with LLM-Hub-inspired model management features including a curated
 * model catalog, background download with progress, model selection, and
 * per-model configuration.
 *
 * Commands:
 *   status              - Show device capability and model state
 *   catalog             - List all available models in the catalog
 *   catalog|<family>    - Filter catalog by model family (Qwen, Llama, etc.)
 *   recommend           - Recommend best model for this device
 *   downloaded          - List all downloaded models on device
 *   download|<model_id> - Download a model from the catalog
 *   delete|<model_id>   - Delete a downloaded model
 *   select|<model_id>   - Select a model for inference (auto-loads it)
 *   selected            - Show the currently selected model
 *   discover            - Scan for locally available model directories
 *   load|<model_path>   - Load a model from a specific directory
 *   generate|<prompt>   - Generate text using the loaded model
 *   benchmark           - Run inference benchmark
 *   unload              - Unload the current model
 *   stats               - Show inference statistics
 *   families            - List all model families in the catalog
 *   config|<model_id>   - Show/edit per-model configuration
 *   hftoken|<token>     - Set HuggingFace API token for gated models
 *   hftoken             - Show current HF token status
 *   import|<path>       - Import a model from a local directory
 *   download_url|<name>|<url> - Download from a custom URL
 *
 * @see OnDeviceLLMManager
 * @see ModelCatalog
 * @see ModelDownloadManager
 * @see ModelRepository
 */
class OnDeviceLLMPlugin : Plugin {

    private var llmManager: OnDeviceLLMManager? = null
    private var downloadManager: ModelDownloadManager? = null
    private var modelRepository: ModelRepository? = null

    override val id: String = ID
    override val name: String = "On-Device LLM"
    override val description: String =
        "Model hub with catalog, downloader, and inference. " +
                "Commands: catalog, recommend, download|id, downloaded, select|id, " +
                "generate|prompt, status, benchmark, stats, delete|id, families"
    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()

        if (input.isBlank()) {
            return PluginResult.error(
                "No command provided. Use: catalog, recommend, download|id, downloaded, " +
                        "select|id, generate|prompt, status, benchmark, stats, delete|id, families",
                elapsed(start)
            )
        }

        val parts = input.split("\\|".toRegex(), 2)
        val command = parts[0].trim().lowercase()

        return try {
            when (command) {
                "status" -> executeStatus(start)
                "catalog" -> executeCatalog(parts.getOrNull(1)?.trim(), start)
                "recommend" -> executeRecommend(start)
                "downloaded" -> executeDownloaded(start)
                "download" -> executeDownload(parts.getOrNull(1)?.trim(), start)
                "delete" -> executeDelete(parts.getOrNull(1)?.trim(), start)
                "select" -> executeSelect(parts.getOrNull(1)?.trim(), start)
                "selected" -> executeSelected(start)
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
                "families" -> executeFamilies(start)
                "config" -> executeConfig(parts.getOrNull(1)?.trim(), start)
                "hftoken" -> executeHfToken(parts.getOrNull(1)?.trim(), start)
                "import" -> executeImport(parts.getOrNull(1)?.trim(), start)
                "download_url" -> {
                    val args = parts.getOrNull(1)?.trim()
                    if (args.isNullOrBlank()) {
                        PluginResult.error("Usage: download_url|<model_name>|<url>", elapsed(start))
                    } else {
                        executeDownloadUrl(args, start)
                    }
                }
                else -> PluginResult.error(
                    "Unknown command: $command. Use: catalog, recommend, download|id, " +
                            "downloaded, select|id, generate|prompt, status, benchmark, " +
                            "stats, delete|id, families, config|id, hftoken, import|path",
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
            downloadManager = ModelDownloadManager(context)
            modelRepository = ModelRepository(context)
            Log.d(TAG, "OnDeviceLLMPlugin initialized. MNN native: " +
                    "${OnDeviceLLMManager.isNativeAvailable()}, " +
                    "catalog: ${ModelCatalog.entries.size} models")
        } catch (e: Exception) {
            Log.w(TAG, "OnDeviceLLMPlugin initialized in degraded mode: ${e.message}")
        }
    }

    override fun destroy() {
        llmManager?.shutdown()
        downloadManager?.shutdown()
        llmManager = null
        downloadManager = null
        modelRepository = null
    }

    fun getLLMManager(): OnDeviceLLMManager? = llmManager
    fun getModelRepository(): ModelRepository? = modelRepository
    fun getDownloadManager(): ModelDownloadManager? = downloadManager

    // ---- Catalog commands ----

    private fun executeCatalog(familyFilter: String?, start: Long): PluginResult {
        val entries = if (familyFilter.isNullOrBlank()) {
            ModelCatalog.entries
        } else {
            ModelCatalog.byFamily(familyFilter)
        }

        if (entries.isEmpty()) {
            return PluginResult.success(
                "No models found" + if (familyFilter != null) " for family '$familyFilter'" else "" +
                        "\nAvailable families: ${ModelCatalog.families.joinToString(", ")}",
                elapsed(start)
            )
        }

        val dm = downloadManager
        val sb = StringBuilder()
        sb.append("=== Model Catalog")
        if (familyFilter != null) sb.append(" ($familyFilter)")
        sb.append(" ===\n")
        sb.append("${entries.size} model(s) available\n\n")

        for (entry in entries) {
            val downloaded = dm?.isModelDownloaded(entry.id) == true
            val statusIcon = if (downloaded) "[DOWNLOADED]" else "[AVAILABLE]"
            sb.append("$statusIcon ${entry.name}\n")
            sb.append("  ID: ${entry.id}\n")
            sb.append("  Family: ${entry.family} | Params: ${entry.parameterCount} | Quant: ${entry.quantization}\n")
            sb.append("  Size: ${entry.sizeMb} MB | Context: ${entry.contextWindow} tokens\n")
            sb.append("  RAM: ${entry.ramRequirement.minRamMb}MB min, ${entry.ramRequirement.recommendedRamMb}MB recommended\n")
            sb.append("  GPU: ${if (entry.supportsGpu) "Yes" else "No"} | Source: ${entry.source}\n")
            sb.append("  ${entry.description}\n\n")
        }

        sb.append("Use: download|<model_id> to download a model")

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun executeRecommend(start: Long): PluginResult {
        val manager = llmManager
            ?: return PluginResult.error("LLM manager not initialized", elapsed(start))

        val cap = manager.assessDevice()
        val recommended = ModelCatalog.recommendForDevice(cap.availableRamMb)
        val sorted = ModelCatalog.sortedForDevice(cap.availableRamMb)

        val sb = StringBuilder()
        sb.append("=== Model Recommendation ===\n")
        sb.append("Device RAM: ${cap.availableRamMb} MB available / ${cap.totalRamMb} MB total\n")
        sb.append("SoC: ${ModelCatalog.DeviceSocInfo.getDeviceSoc()}")
        val gen = ModelCatalog.DeviceSocInfo.getChipsetGeneration()
        if (gen != null) sb.append(" ($gen)")
        sb.append("\n\n")

        if (recommended != null) {
            sb.append("Best match: ${recommended.name}\n")
            sb.append("  ${recommended.parameterCount} params, ${recommended.quantization} quantization\n")
            sb.append("  ${recommended.sizeMb} MB download, ${recommended.contextWindow} token context\n")
            sb.append("  ${recommended.description}\n\n")
            sb.append("Download with: download|${recommended.id}\n\n")
        } else {
            sb.append("No models recommended for this device (insufficient RAM).\n\n")
        }

        sb.append("All models ranked for this device:\n")
        for ((i, entry) in sorted.withIndex()) {
            val fits = entry.ramRequirement.minRamMb <= cap.availableRamMb
            val marker = if (fits) "+" else "-"
            sb.append("  $marker ${i + 1}. ${entry.name} (${entry.parameterCount}, ${entry.sizeMb}MB")
            sb.append(", needs ${entry.ramRequirement.minRamMb}MB RAM)")
            if (!fits) sb.append(" [INSUFFICIENT RAM]")
            sb.append("\n")
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun executeFamilies(start: Long): PluginResult {
        val families = ModelCatalog.families
        val sb = StringBuilder("=== Model Families ===\n")
        for (family in families) {
            val count = ModelCatalog.byFamily(family).size
            sb.append("  $family ($count model${if (count != 1) "s" else ""})\n")
        }
        sb.append("\nUse: catalog|<family> to see models in a family")
        return PluginResult.success(sb.toString(), elapsed(start))
    }

    // ---- Download/Delete commands ----

    private fun executeDownload(modelId: String?, start: Long): PluginResult {
        if (modelId.isNullOrBlank()) {
            return PluginResult.error("Usage: download|<model_id>\nUse 'catalog' to see available model IDs.", elapsed(start))
        }

        val dm = downloadManager
            ?: return PluginResult.error("Download manager not initialized", elapsed(start))

        val entry = ModelCatalog.findById(modelId)
            ?: return PluginResult.error("Model '$modelId' not found in catalog.\nUse 'catalog' to see available IDs.", elapsed(start))

        if (dm.isModelDownloaded(entry.id)) {
            return PluginResult.success(
                "Model '${entry.name}' is already downloaded.\nUse: select|${entry.id} to select it for inference.",
                elapsed(start)
            )
        }

        val started = dm.downloadModel(entry) { progress ->
            Log.d(TAG, "Download ${entry.name}: ${progress.state} " +
                    "${progress.progressPercent}% (${progress.downloadedBytes}/${progress.totalBytes})")
        }

        return if (started) {
            PluginResult.success(
                "Download started: ${entry.name} (${entry.sizeMb} MB)\n" +
                        "Downloading in background. Use 'downloaded' to check status.",
                elapsed(start)
            )
        } else {
            PluginResult.error("Download already in progress for ${entry.name}", elapsed(start))
        }
    }

    private fun executeDelete(modelId: String?, start: Long): PluginResult {
        if (modelId.isNullOrBlank()) {
            return PluginResult.error("Usage: delete|<model_id>", elapsed(start))
        }

        val dm = downloadManager
            ?: return PluginResult.error("Download manager not initialized", elapsed(start))

        val repo = modelRepository

        // If this is the currently loaded model, unload first
        if (llmManager?.activeConfig?.modelId == modelId || llmManager?.activeConfig?.modelName == modelId) {
            llmManager?.unloadModel()
        }

        // If this is the selected model, clear selection
        if (repo?.getSelectedModelId() == modelId) {
            repo.setSelectedModelId(null)
        }

        val deleted = dm.deleteModel(modelId)
        repo?.removeImportedModel(modelId)

        return if (deleted) {
            PluginResult.success("Deleted model: $modelId", elapsed(start))
        } else {
            PluginResult.error("Model '$modelId' not found on disk", elapsed(start))
        }
    }

    private fun executeDownloaded(start: Long): PluginResult {
        val repo = modelRepository
            ?: return PluginResult.error("Model repository not initialized", elapsed(start))

        val dm = downloadManager
        val models = repo.getAvailableModels()
        val selectedId = repo.getSelectedModelId()

        if (models.isEmpty()) {
            return PluginResult.success(
                "No models downloaded yet.\n" +
                        "Use 'catalog' to browse available models and 'download|<id>' to download one.\n" +
                        "Or use 'recommend' to get a personalized recommendation.",
                elapsed(start)
            )
        }

        val sb = StringBuilder("=== Downloaded Models ===\n")
        sb.append("${models.size} model(s) on device\n\n")

        for (model in models) {
            val isSelected = model.id == selectedId
            val marker = if (isSelected) " [SELECTED]" else ""
            val isLoaded = llmManager?.activeConfig?.let { config ->
                config.modelPath == model.directory.absolutePath
            } == true
            val loadedMarker = if (isLoaded) " [LOADED]" else ""

            sb.append("${model.name}$marker$loadedMarker\n")
            sb.append("  ID: ${model.id}\n")
            sb.append("  Family: ${model.family} | Params: ${model.parameterCount} | Quant: ${model.quantization}\n")
            sb.append("  Size on disk: ${model.diskUsageMb} MB\n")
            sb.append("  Path: ${model.directory.absolutePath}\n")
            sb.append("  Source: ${model.source}\n\n")
        }

        // Show active downloads
        if (dm != null) {
            val downloading = ModelCatalog.entries.filter { dm.isDownloading(it.id) }
            if (downloading.isNotEmpty()) {
                sb.append("--- Active Downloads ---\n")
                for (entry in downloading) {
                    sb.append("  ${entry.name} (downloading...)\n")
                }
                sb.append("\n")
            }
        }

        sb.append("Use: select|<model_id> to select a model for inference\n")
        sb.append("Use: delete|<model_id> to remove a downloaded model")

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    // ---- Selection commands ----

    private fun executeSelect(modelId: String?, start: Long): PluginResult {
        if (modelId.isNullOrBlank()) {
            return PluginResult.error("Usage: select|<model_id>\nUse 'downloaded' to see available IDs.", elapsed(start))
        }

        val manager = llmManager
            ?: return PluginResult.error("LLM manager not initialized", elapsed(start))
        val repo = modelRepository
            ?: return PluginResult.error("Model repository not initialized", elapsed(start))

        val models = repo.getAvailableModels()
        val model = models.find { it.id == modelId }
            ?: return PluginResult.error("Model '$modelId' not found in downloaded models.\nUse 'downloaded' to see available IDs.", elapsed(start))

        repo.setSelectedModelId(model.id)

        // Auto-load the selected model
        if (!OnDeviceLLMManager.isNativeAvailable()) {
            return PluginResult.success(
                "Selected model: ${model.name}\n" +
                        "Note: MNN native libraries are not available. Model will be loaded when libraries are installed.",
                elapsed(start)
            )
        }

        val config = manager.createConfigFromDirectory(model.directory)
        val loaded = manager.loadModel(config)

        return if (loaded) {
            PluginResult.success(
                "Selected and loaded: ${model.name}\n" +
                        "Backend: ${config.backendName}, Threads: ${config.threadCount}\n" +
                        "Ready for inference. Use: generate|<prompt>",
                elapsed(start)
            )
        } else {
            PluginResult.success(
                "Selected: ${model.name}\nWarning: Failed to load model. State: ${manager.modelState}",
                elapsed(start)
            )
        }
    }

    private fun executeSelected(start: Long): PluginResult {
        val repo = modelRepository
            ?: return PluginResult.error("Model repository not initialized", elapsed(start))

        val selected = repo.getSelectedModel()
        if (selected == null) {
            return PluginResult.success(
                "No model selected.\nUse: select|<model_id> or 'recommend' to pick one.",
                elapsed(start)
            )
        }

        val isLoaded = llmManager?.isReady == true &&
                llmManager?.activeConfig?.modelPath == selected.directory.absolutePath

        val sb = StringBuilder("=== Selected Model ===\n")
        sb.append("Name: ${selected.name}\n")
        sb.append("ID: ${selected.id}\n")
        sb.append("Family: ${selected.family}\n")
        sb.append("Parameters: ${selected.parameterCount}\n")
        sb.append("Quantization: ${selected.quantization}\n")
        sb.append("Context Window: ${selected.contextWindow} tokens\n")
        sb.append("Disk Usage: ${selected.diskUsageMb} MB\n")
        sb.append("Path: ${selected.directory.absolutePath}\n")
        sb.append("Loaded: ${if (isLoaded) "Yes" else "No"}\n")

        if (isLoaded) {
            llmManager?.activeConfig?.let { config ->
                sb.append("\n--- Inference Config ---\n")
                sb.append("Backend: ${config.backendName}\n")
                sb.append("Threads: ${config.threadCount}\n")
                sb.append("Max Tokens: ${config.maxTokens}\n")
                sb.append("Temperature: ${config.temperature}\n")
                sb.append("Top-P: ${config.topP}\n")
                sb.append("Memory Mapped: ${config.useMmap}")
            }
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    // ---- Legacy commands (preserved) ----

    private fun executeStatus(start: Long): PluginResult {
        val manager = llmManager
            ?: return PluginResult.error("LLM manager not initialized", elapsed(start))

        val cap = manager.assessDevice()
        val repo = modelRepository
        val dm = downloadManager

        val sb = StringBuilder().apply {
            append("=== On-Device LLM Status ===\n")
            append("MNN Native Libraries: ")
            append(if (OnDeviceLLMManager.isNativeAvailable()) "AVAILABLE" else "NOT INSTALLED")
            append("\nModel State: ${manager.modelState}\n")
            append("Catalog: ${ModelCatalog.entries.size} models available\n")

            if (repo != null) {
                val downloaded = repo.getAvailableModels()
                append("Downloaded: ${downloaded.size} model(s)\n")
                val selectedId = repo.getSelectedModelId()
                if (selectedId != null) append("Selected: $selectedId\n")
            }

            append("\n--- Device Capability ---\n")
            append("Total RAM: ${cap.totalRamMb} MB\n")
            append("Available RAM: ${cap.availableRamMb} MB\n")
            append("CPU Architecture: ${cap.cpuArch}\n")
            append("ARM64 Support: ${cap.supportsArm64}\n")
            append("FP16 Support: ${cap.supportsFp16}\n")
            append("GPU Available: ${cap.hasGpu}\n")
            append("SoC: ${ModelCatalog.DeviceSocInfo.getDeviceSoc()}\n")
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
                        "Use 'catalog' to browse downloadable models or 'recommend' for suggestions.\n\n" +
                        "Manual placement paths:\n" +
                        "  - /sdcard/mnn_models/<model_name>/\n" +
                        "  - <app_files>/mnn_models/<model_name>/\n\n" +
                        "Each directory should contain: llm.mnn, config.json, tokenizer.txt",
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
        sb.append("\nUse: select|<model_id> to select and load a model")

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
            // Try auto-loading the selected model
            val repo = modelRepository
            if (repo != null) {
                val selected = repo.getSelectedModel()
                if (selected != null && OnDeviceLLMManager.isNativeAvailable()) {
                    val config = manager.createConfigFromDirectory(selected.directory)
                    if (manager.loadModel(config)) {
                        Log.d(TAG, "Auto-loaded selected model: ${selected.name}")
                    }
                }
            }

            if (!manager.isReady) {
                return PluginResult.error(
                    "No model loaded. Use: downloaded (to see models) then select|<id>.\n" +
                            "Or use: catalog -> download|<id> -> select|<id>",
                    elapsed(start)
                )
            }
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

        // Add repository stats
        val repo = modelRepository
        if (repo != null) {
            val downloaded = repo.getAvailableModels()
            sb.append("\n--- Repository ---\n")
            sb.append("Downloaded models: ${downloaded.size}\n")
            sb.append("Selected model: ${repo.getSelectedModelId() ?: "none"}\n")
            val totalDiskMb = downloaded.sumOf { it.diskUsageBytes } / (1024 * 1024)
            sb.append("Total disk usage: $totalDiskMb MB\n")
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    // ---- Config / Token / Import commands ----

    private fun executeConfig(modelId: String?, start: Long): PluginResult {
        val repo = modelRepository
            ?: return PluginResult.error("Model repository not initialized", elapsed(start))

        if (modelId.isNullOrBlank()) {
            // Show config for currently selected model
            val selectedId = repo.getSelectedModelId()
                ?: return PluginResult.error(
                    "No model selected. Usage: config|<model_id>", elapsed(start)
                )
            return showModelConfig(selectedId, repo, start)
        }

        return showModelConfig(modelId, repo, start)
    }

    private fun showModelConfig(modelId: String, repo: ModelRepository, start: Long): PluginResult {
        val config = repo.getModelConfig(modelId)
        val defaults = ModelRepository.ModelConfigOverrides()

        val sb = StringBuilder("=== Model Configuration: $modelId ===\n")
        if (config != null) {
            sb.append("Max Tokens: ${config.maxTokens}\n")
            sb.append("Temperature: ${config.temperature}\n")
            sb.append("Top-P: ${config.topP}\n")
            sb.append("Thread Count: ${config.threadCount}\n")
            sb.append("Backend: ${config.backend} (0=CPU, 3=OpenCL, 7=Vulkan)\n")
            sb.append("Memory Mapped: ${config.useMmap}\n")
        } else {
            sb.append("Using defaults:\n")
            sb.append("Max Tokens: ${defaults.maxTokens}\n")
            sb.append("Temperature: ${defaults.temperature}\n")
            sb.append("Top-P: ${defaults.topP}\n")
            sb.append("Thread Count: ${defaults.threadCount}\n")
            sb.append("Backend: ${defaults.backend} (CPU)\n")
            sb.append("Memory Mapped: ${defaults.useMmap}\n")
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun executeHfToken(token: String?, start: Long): PluginResult {
        val dm = downloadManager
            ?: return PluginResult.error("Download manager not initialized", elapsed(start))

        if (token.isNullOrBlank()) {
            val current = dm.getHuggingFaceToken()
            return if (current != null) {
                PluginResult.success(
                    "HuggingFace token is set: ${current.take(8)}...${current.takeLast(4)}\n" +
                            "Use: hftoken|<new_token> to change or hftoken|clear to remove.",
                    elapsed(start)
                )
            } else {
                PluginResult.success(
                    "No HuggingFace token set.\n" +
                            "Some models require authentication. Use: hftoken|<your_token>\n" +
                            "Get a token at: https://huggingface.co/settings/tokens",
                    elapsed(start)
                )
            }
        }

        if (token.equals("clear", ignoreCase = true)) {
            dm.setHuggingFaceToken(null)
            return PluginResult.success("HuggingFace token cleared.", elapsed(start))
        }

        dm.setHuggingFaceToken(token)
        return PluginResult.success(
            "HuggingFace token set: ${token.take(8)}...\nGated model downloads will now use this token.",
            elapsed(start)
        )
    }

    private fun executeImport(path: String?, start: Long): PluginResult {
        if (path.isNullOrBlank()) {
            return PluginResult.error(
                "Usage: import|<model_directory_path>\n" +
                        "The directory must contain: llm.mnn, config.json, tokenizer.txt",
                elapsed(start)
            )
        }

        val dm = downloadManager
            ?: return PluginResult.error("Download manager not initialized", elapsed(start))
        val repo = modelRepository
            ?: return PluginResult.error("Model repository not initialized", elapsed(start))

        val sourceDir = File(path)
        if (!sourceDir.exists()) {
            return PluginResult.error("Directory not found: $path", elapsed(start))
        }
        if (!sourceDir.isDirectory) {
            return PluginResult.error("Path is not a directory: $path", elapsed(start))
        }
        if (!File(sourceDir, "llm.mnn").exists()) {
            return PluginResult.error(
                "Directory missing required file: llm.mnn\n" +
                        "Expected MNN model directory with llm.mnn, config.json, tokenizer.txt",
                elapsed(start)
            )
        }

        val modelId = "import_${sourceDir.name}_${System.currentTimeMillis()}"
        val success = dm.importLocalModel(modelId, sourceDir)

        if (success) {
            repo.addImportedModel(
                ModelRepository.ImportedModelEntry(
                    id = modelId,
                    name = sourceDir.name,
                    directory = dm.getModelDir(modelId).absolutePath
                )
            )
            return PluginResult.success(
                "Imported model: ${sourceDir.name}\n" +
                        "ID: $modelId\n" +
                        "Use: select|$modelId to load it for inference.",
                elapsed(start)
            )
        } else {
            return PluginResult.error("Failed to import model from: $path", elapsed(start))
        }
    }

    private fun executeDownloadUrl(args: String, start: Long): PluginResult {
        val dm = downloadManager
            ?: return PluginResult.error("Download manager not initialized", elapsed(start))

        val urlParts = args.split("\\|".toRegex(), 2)
        if (urlParts.size < 2) {
            return PluginResult.error(
                "Usage: download_url|<model_name>|<download_url>",
                elapsed(start)
            )
        }

        val modelName = urlParts[0].trim()
        val downloadUrl = urlParts[1].trim()

        if (modelName.isBlank() || downloadUrl.isBlank()) {
            return PluginResult.error("Both model name and URL are required.", elapsed(start))
        }

        if (!downloadUrl.startsWith("http://") && !downloadUrl.startsWith("https://")) {
            return PluginResult.error("URL must start with http:// or https://", elapsed(start))
        }

        val modelId = "custom_${modelName.replace(Regex("[^a-zA-Z0-9]"), "_").lowercase()}_${System.currentTimeMillis()}"

        val started = dm.downloadFromUrl(modelId, modelName, downloadUrl) { progress ->
            Log.d(TAG, "Custom download $modelName: ${progress.state} ${progress.progressPercent}%")
        }

        return if (started) {
            // Track as imported model
            modelRepository?.addImportedModel(
                ModelRepository.ImportedModelEntry(
                    id = modelId,
                    name = modelName,
                    directory = dm.getModelDir(modelId).absolutePath
                )
            )
            PluginResult.success(
                "Download started: $modelName\n" +
                        "ID: $modelId\n" +
                        "Downloading in background. Use 'downloaded' to check status.",
                elapsed(start)
            )
        } else {
            PluginResult.error("Failed to start download for $modelName", elapsed(start))
        }
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    companion object {
        private const val TAG = "OnDeviceLLMPlugin"
        private const val ID = "on_device_llm"
    }
}

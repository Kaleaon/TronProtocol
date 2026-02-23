package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.guidance.ConstitutionalValuesEngine
import com.tronprotocol.app.llm.HereticModelManager
import com.tronprotocol.app.llm.LLMModelConfig
import com.tronprotocol.app.llm.ModelCatalog
import com.tronprotocol.app.llm.OnDeviceLLMManager
import com.tronprotocol.app.security.AuditLogger
import com.tronprotocol.app.security.ConstitutionalMemory

/**
 * Heretic Model Plugin — user-facing interface for managing uncensored models
 * with constitution-based values enforcement.
 *
 * This plugin integrates heretic-processed models (models whose safety alignment
 * has been removed via directional ablation) into TronProtocol's plugin system,
 * with all interactions gated by the [ConstitutionalValuesEngine].
 *
 * Commands:
 * - `status`                   — Show heretic model and constitutional values status
 * - `catalog`                  — List available heretic models from catalog
 * - `load|<model_id>`          — Load a heretic model for inference
 * - `unload`                   — Unload the current heretic model
 * - `generate|<prompt>`        — Generate text with constitutional values enforcement
 * - `values`                   — Show current constitutional values configuration
 * - `stats`                    — Show generation and values enforcement statistics
 * - `register|<id>|<source>`   — Register a model as heretic-processed
 * - `models`                   — List registered heretic models
 *
 * @see <a href="https://github.com/p-e-w/heretic">Heretic</a>
 */
class HereticModelPlugin : Plugin {

    override val id: String = "heretic_model"
    override val name: String = "Heretic Model (Constitution-Based Values)"
    override val description: String =
        "Manages uncensored heretic-processed models with transparent, " +
        "constitution-based values enforcement replacing opaque model-level alignment."
    override var isEnabled: Boolean = true

    private var context: Context? = null
    private var hereticManager: HereticModelManager? = null
    private var onDeviceLLMManager: OnDeviceLLMManager? = null
    private var valuesEngine: ConstitutionalValuesEngine? = null

    override fun initialize(context: Context) {
        this.context = context

        val constitutionalMemory = ConstitutionalMemory(context)
        val auditLogger = AuditLogger(context)

        valuesEngine = ConstitutionalValuesEngine(constitutionalMemory, auditLogger)

        onDeviceLLMManager = OnDeviceLLMManager(context, auditLogger)

        hereticManager = HereticModelManager(
            context = context,
            onDeviceLLMManager = onDeviceLLMManager!!,
            valuesEngine = valuesEngine!!,
            auditLogger = auditLogger
        )

        Log.d(TAG, "HereticModelPlugin initialized — constitutional values enforcement active")
    }

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        val manager = hereticManager
            ?: return PluginResult.error("Heretic model system not initialized", elapsed(start))

        val parts = input.split("|", limit = 2)
        val command = parts[0].trim().lowercase()
        val arg = if (parts.size > 1) parts[1].trim() else ""

        return try {
            when (command) {
                "status" -> handleStatus(manager, start)
                "catalog" -> handleCatalog(start)
                "load" -> handleLoad(manager, arg, start)
                "unload" -> handleUnload(manager, start)
                "generate" -> handleGenerate(manager, arg, start)
                "values" -> handleValues(start)
                "stats" -> handleStats(manager, start)
                "register" -> handleRegister(manager, arg, start)
                "models" -> handleModels(manager, start)
                else -> PluginResult.error(
                    "Unknown command: $command. " +
                    "Available: status, catalog, load, unload, generate, values, stats, register, models",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command '$command' failed: ${e.message}", e)
            PluginResult.error("Command failed: ${e.message}", elapsed(start))
        }
    }

    override fun destroy() {
        hereticManager?.shutdown()
        onDeviceLLMManager?.shutdown()
        hereticManager = null
        onDeviceLLMManager = null
        valuesEngine = null
        context = null
        Log.d(TAG, "HereticModelPlugin destroyed")
    }

    // -- Command handlers --

    private fun handleStatus(manager: HereticModelManager, start: Long): PluginResult {
        val sb = StringBuilder()
        sb.appendLine("=== Heretic Model System Status ===")
        sb.appendLine()
        sb.appendLine("Model Loaded: ${manager.isReady}")

        manager.activeConfig?.let { config ->
            sb.appendLine("Active Model: ${config.modelName}")
            sb.appendLine("  Parameters: ${config.parameterCount}")
            sb.appendLine("  Quantization: ${config.quantization}")
            sb.appendLine("  Backend: ${config.backendName}")

            val metadata = manager.getModelMetadata(config.modelId)
            if (metadata != null) {
                sb.appendLine("  Heretic Version: ${metadata.hereticVersion}")
                sb.appendLine("  Ablation Method: ${metadata.ablationMethod}")
                sb.appendLine("  Source Model: ${metadata.sourceModelName}")
                sb.appendLine("  KL Divergence: ${metadata.klDivergence ?: "unknown"}")
                sb.appendLine("  Refusal Rate: ${metadata.refusalRate ?: "unknown"}")
            }
        }

        sb.appendLine()
        val valuesStatus = valuesEngine?.getValuesStatus() ?: emptyMap()
        sb.appendLine("Constitutional Values:")
        sb.appendLine("  Version: ${valuesStatus["constitution_version"] ?: "unknown"}")
        sb.appendLine("  Total Directives: ${valuesStatus["total_directives"] ?: 0}")
        sb.appendLine("  Immutable: ${valuesStatus["immutable_directives"] ?: 0}")
        sb.appendLine("  User-Defined: ${valuesStatus["user_directives"] ?: 0}")
        sb.appendLine()
        sb.appendLine("Registered Heretic Models: ${manager.getRegisteredModels().size}")

        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun handleCatalog(start: Long): PluginResult {
        val hereticEntries = ModelCatalog.entries.filter { it.family == "Heretic" }

        if (hereticEntries.isEmpty()) {
            return PluginResult.success(
                "No heretic models in catalog yet.\n\n" +
                "Heretic models are standard models processed through " +
                "github.com/p-e-w/heretic to remove baked-in safety alignment.\n" +
                "Any MNN-compatible model can be used after heretic processing.\n\n" +
                "To use a heretic model:\n" +
                "1. Process a model with heretic (Python, requires GPU)\n" +
                "2. Export to MNN format via llmexport.py\n" +
                "3. Load via: load|<path_to_model>\n" +
                "4. Register via: register|<model_id>|<source_model>",
                elapsed(start)
            )
        }

        val sb = StringBuilder()
        sb.appendLine("=== Heretic Model Catalog ===")
        for (entry in hereticEntries) {
            sb.appendLine()
            sb.appendLine("${entry.id}: ${entry.name}")
            sb.appendLine("  ${entry.description}")
            sb.appendLine("  Size: ${entry.sizeMb}MB | RAM: ${entry.ramRequirement.minRamMb}MB min")
            sb.appendLine("  Quantization: ${entry.quantization} | GPU: ${entry.supportsGpu}")
        }

        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun handleLoad(manager: HereticModelManager, arg: String, start: Long): PluginResult {
        if (arg.isBlank()) {
            return PluginResult.error("Usage: load|<model_path_or_catalog_id>", elapsed(start))
        }

        // Try catalog lookup first
        val catalogEntry = ModelCatalog.findById(arg)
        if (catalogEntry != null) {
            return PluginResult.error(
                "Catalog download not yet supported for heretic models. " +
                "Use the on_device_llm plugin to download, then load by path.",
                elapsed(start)
            )
        }

        // Load from path
        val llmManager = onDeviceLLMManager
            ?: return PluginResult.error("LLM manager not available", elapsed(start))

        val config = llmManager.createConfigFromDirectory(java.io.File(arg))
        val loaded = manager.loadModel(config)

        return if (loaded) {
            PluginResult.success(
                "Heretic model loaded: ${config.modelName}\n" +
                "Constitutional values enforcement: ACTIVE\n" +
                "All inputs/outputs will be evaluated against the constitution.",
                elapsed(start)
            )
        } else {
            PluginResult.error("Failed to load model from: $arg", elapsed(start))
        }
    }

    private fun handleUnload(manager: HereticModelManager, start: Long): PluginResult {
        val modelName = manager.activeConfig?.modelName ?: "none"
        manager.unloadModel()
        return PluginResult.success("Heretic model unloaded: $modelName", elapsed(start))
    }

    private fun handleGenerate(manager: HereticModelManager, arg: String, start: Long): PluginResult {
        if (arg.isBlank()) {
            return PluginResult.error("Usage: generate|<prompt>", elapsed(start))
        }

        val result = manager.generate(arg)

        val sb = StringBuilder()
        if (result.success) {
            sb.appendLine(result.text)
            sb.appendLine()
            sb.appendLine("[Heretic Model | ${result.tokensGenerated} tokens | " +
                    "${result.latencyMs}ms | ${String.format("%.1f", result.tokensPerSecond)} tok/s | " +
                    "Constitution v${result.constitutionVersion}]")
        } else {
            sb.appendLine(result.text ?: result.error ?: "Generation failed")
            if (result.valuesViolations.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Values violations: ${result.valuesViolations.joinToString(", ") { it.directiveId }}")
            }
        }

        if (result.valuesWarnings.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Warnings: ${result.valuesWarnings.joinToString(", ") { it.directiveId }}")
        }

        return if (result.success) {
            PluginResult.success(sb.toString().trimEnd(), elapsed(start))
        } else {
            PluginResult.error(sb.toString().trimEnd(), elapsed(start))
        }
    }

    private fun handleValues(start: Long): PluginResult {
        val engine = valuesEngine
            ?: return PluginResult.error("Values engine not initialized", elapsed(start))

        val status = engine.getValuesStatus()
        val sb = StringBuilder()
        sb.appendLine("=== Constitutional Values System ===")
        sb.appendLine()
        sb.appendLine("Version: ${status["constitution_version"]}")
        sb.appendLine("Total Directives: ${status["total_directives"]}")
        sb.appendLine("Immutable (core safety): ${status["immutable_directives"]}")
        sb.appendLine("User-Defined: ${status["user_directives"]}")
        sb.appendLine()
        sb.appendLine("By Category:")
        @Suppress("UNCHECKED_CAST")
        val categories = status["categories"] as? Map<*, *> ?: emptyMap<String, Int>()
        for ((cat, count) in categories) {
            sb.appendLine("  $cat: $count")
        }
        sb.appendLine()
        sb.appendLine("By Enforcement Level:")
        @Suppress("UNCHECKED_CAST")
        val enforcement = status["enforcement_levels"] as? Map<*, *> ?: emptyMap<String, Int>()
        for ((level, count) in enforcement) {
            sb.appendLine("  $level: $count")
        }
        sb.appendLine()
        sb.appendLine("Philosophy: This system replaces opaque model-level alignment")
        sb.appendLine("with transparent, versioned, auditable constitutional directives.")
        sb.appendLine("Every safety decision is traceable to a specific directive.")

        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun handleStats(manager: HereticModelManager, start: Long): PluginResult {
        val stats = manager.getStats()
        val sb = StringBuilder()
        sb.appendLine("=== Heretic Model Statistics ===")
        sb.appendLine()
        sb.appendLine("Generation Statistics:")
        sb.appendLine("  Total Generations: ${stats["heretic_total_generations"] ?: 0}")
        sb.appendLine("  Successful: ${stats["heretic_successful_generations"] ?: 0}")
        sb.appendLine("  Prompts Blocked: ${stats["heretic_prompts_blocked"] ?: 0}")
        sb.appendLine("  Responses Blocked: ${stats["heretic_responses_blocked"] ?: 0}")
        sb.appendLine()
        sb.appendLine("Inference Statistics:")
        sb.appendLine("  Total Inferences: ${stats["total_inferences"] ?: 0}")
        sb.appendLine("  Total Tokens: ${stats["total_tokens_generated"] ?: 0}")
        sb.appendLine("  Avg Inference Time: ${stats["avg_inference_time_ms"] ?: "N/A"}ms")
        sb.appendLine("  Active Model: ${stats["active_model"] ?: "none"}")
        sb.appendLine()
        sb.appendLine("Registered Heretic Models: ${stats["heretic_registered_models"] ?: 0}")

        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun handleRegister(manager: HereticModelManager, arg: String, start: Long): PluginResult {
        val registerParts = arg.split("|", limit = 2)
        if (registerParts.size < 2) {
            return PluginResult.error("Usage: register|<model_id>|<source_model_name>", elapsed(start))
        }

        val modelId = registerParts[0].trim()
        val sourceModel = registerParts[1].trim()

        val metadata = HereticModelManager.HereticModelMetadata(
            sourceModelId = sourceModel.lowercase().replace(" ", "-"),
            sourceModelName = sourceModel,
            hereticVersion = "1.0",
            ablationMethod = HereticModelManager.AblationMethod.PARAMETRIZED_KERNEL,
            directionIndex = 0.0f,
            maxWeight = 1.0f,
            klDivergence = null,
            refusalRate = null,
            processedTimestamp = System.currentTimeMillis()
        )

        manager.registerHereticModel(modelId, metadata)
        return PluginResult.success(
            "Registered heretic model: $modelId (source: $sourceModel)\n" +
            "Ablation method: PARAMETRIZED_KERNEL\n" +
            "Constitutional values enforcement will be applied to all interactions.",
            elapsed(start)
        )
    }

    private fun handleModels(manager: HereticModelManager, start: Long): PluginResult {
        val models = manager.getRegisteredModels()
        if (models.isEmpty()) {
            return PluginResult.success(
                "No heretic models registered.\n" +
                "Use register|<model_id>|<source_model> to register a heretic-processed model.",
                elapsed(start)
            )
        }

        val sb = StringBuilder()
        sb.appendLine("=== Registered Heretic Models ===")
        for ((id, meta) in models) {
            sb.appendLine()
            sb.appendLine("$id:")
            sb.appendLine("  Source: ${meta.sourceModelName}")
            sb.appendLine("  Heretic Version: ${meta.hereticVersion}")
            sb.appendLine("  Ablation Method: ${meta.ablationMethod}")
            sb.appendLine("  KL Divergence: ${meta.klDivergence ?: "unknown"}")
            sb.appendLine("  Refusal Rate: ${meta.refusalRate ?: "unknown"}")
        }
        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    companion object {
        private const val TAG = "HereticModelPlugin"
    }
}

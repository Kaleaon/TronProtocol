package com.tronprotocol.app.llm

import android.content.Context
import android.util.Log
import com.tronprotocol.app.guidance.ConstitutionalValuesEngine
import com.tronprotocol.app.security.AuditLogger

/**
 * Heretic Model Manager — manages uncensored (heretic-processed) models with
 * constitution-based values enforcement.
 *
 * Heretic (github.com/p-e-w/heretic) removes safety alignment from transformer models
 * via parametrized directional ablation, producing "uncensored" models that no longer
 * have baked-in refusal behavior. This manager:
 *
 * 1. Wraps the standard [OnDeviceLLMManager] for model loading and inference
 * 2. Injects [ConstitutionalValuesEngine] evaluation on both inputs and outputs
 * 3. Tracks heretic-specific model metadata (ablation parameters, source model, etc.)
 * 4. Provides transparent audit logging for all heretic model interactions
 *
 * The key architectural insight: rather than opaque model-level alignment that users
 * cannot inspect or modify, heretic + constitutional values gives users a transparent,
 * versioned, auditable values system where every safety decision has an explicit rationale.
 *
 * Supported heretic model families:
 * - Heretic-processed Qwen models (1.5B-4B, Q4 quantized for MNN)
 * - Heretic-processed Gemma models (2B, Q4 quantized)
 * - Heretic-processed Llama models (1B-3B, Q4 quantized)
 * - Any MNN-exported model that has been processed through heretic's ablation pipeline
 *
 * @see <a href="https://github.com/p-e-w/heretic">Heretic — Automated LLM Uncensoring</a>
 * @see <a href="https://arxiv.org/abs/2310.01405">Arditi et al. 2024 — Refusal Direction Ablation</a>
 */
class HereticModelManager(
    private val context: Context,
    private val onDeviceLLMManager: OnDeviceLLMManager,
    private val valuesEngine: ConstitutionalValuesEngine,
    private val auditLogger: AuditLogger? = null
) {

    /**
     * Metadata for a heretic-processed model, tracking its ablation provenance.
     */
    data class HereticModelMetadata(
        val sourceModelId: String,
        val sourceModelName: String,
        val hereticVersion: String,
        val ablationMethod: AblationMethod,
        val directionIndex: Float,
        val maxWeight: Float,
        val klDivergence: Float?,
        val refusalRate: Float?,
        val processedTimestamp: Long
    )

    /**
     * Ablation methods supported by heretic.
     */
    enum class AblationMethod {
        /** Standard directional ablation (orthogonal projection). */
        DIRECTIONAL,
        /** Projected ablation (Jim Lai variant). */
        PROJECTED,
        /** Norm-preserving ablation (Jim Lai variant). */
        NORM_PRESERVING,
        /** Heretic's parametrized kernel ablation with Optuna optimization. */
        PARAMETRIZED_KERNEL
    }

    /**
     * Result of a heretic model generation with constitutional values evaluation.
     */
    data class HereticGenerationResult(
        val success: Boolean,
        val text: String?,
        val tokensGenerated: Int,
        val latencyMs: Long,
        val tokensPerSecond: Float,
        val valuesAllowed: Boolean,
        val valuesViolations: List<ConstitutionalValuesEngine.ValuesViolation>,
        val valuesWarnings: List<ConstitutionalValuesEngine.ValuesWarning>,
        val constitutionVersion: Int,
        val modelId: String?,
        val error: String?
    )

    // Heretic model registry: modelId -> metadata
    private val registeredModels = mutableMapOf<String, HereticModelMetadata>()

    // Generation statistics
    private var totalGenerations: Long = 0
    private var promptsBlocked: Long = 0
    private var responsesBlocked: Long = 0
    private var successfulGenerations: Long = 0

    /**
     * Register a heretic-processed model with its ablation metadata.
     *
     * This does not load the model — it records metadata so the system knows
     * this model has been processed by heretic and requires constitutional
     * values enforcement.
     */
    fun registerHereticModel(modelId: String, metadata: HereticModelMetadata) {
        registeredModels[modelId] = metadata
        auditLogger?.logAsync(
            severity = AuditLogger.Severity.INFO,
            category = AuditLogger.AuditCategory.MODEL_INTEGRITY,
            actor = "heretic_model_manager",
            action = "register_model",
            target = modelId,
            outcome = "registered",
            details = mapOf(
                "source_model" to metadata.sourceModelId,
                "ablation_method" to metadata.ablationMethod.name,
                "heretic_version" to metadata.hereticVersion,
                "direction_index" to metadata.directionIndex,
                "kl_divergence" to (metadata.klDivergence?.toString() ?: "unknown"),
                "refusal_rate" to (metadata.refusalRate?.toString() ?: "unknown")
            )
        )
        Log.d(TAG, "Registered heretic model: $modelId " +
                "(source=${metadata.sourceModelName}, method=${metadata.ablationMethod})")
    }

    /**
     * Check if a model is registered as heretic-processed.
     */
    fun isHereticModel(modelId: String): Boolean = modelId in registeredModels

    /**
     * Get metadata for a registered heretic model.
     */
    fun getModelMetadata(modelId: String): HereticModelMetadata? = registeredModels[modelId]

    /**
     * Generate text using a heretic model with constitutional values enforcement.
     *
     * Flow:
     * 1. Evaluate prompt against constitutional values (input gate)
     * 2. If allowed, send to uncensored model for generation
     * 3. Evaluate response against constitutional values (output gate)
     * 4. If allowed, return response with values metadata
     * 5. If blocked at any stage, return structured refusal with directive citations
     */
    fun generate(prompt: String, maxTokens: Int = 0): HereticGenerationResult {
        totalGenerations++
        val startTime = System.currentTimeMillis()

        // Step 1: Constitutional values input gate
        val promptEval = valuesEngine.evaluatePrompt(prompt)
        if (!promptEval.allowed) {
            promptsBlocked++
            val refusal = valuesEngine.buildRefusalExplanation(promptEval)
            val latency = System.currentTimeMillis() - startTime

            auditLogger?.logAsync(
                severity = AuditLogger.Severity.WARNING,
                category = AuditLogger.AuditCategory.POLICY_DECISION,
                actor = "heretic_model_manager",
                action = "generate_prompt_blocked",
                target = onDeviceLLMManager.activeConfig?.modelId,
                outcome = "blocked",
                details = mapOf(
                    "violations" to promptEval.violations.map { it.directiveId },
                    "constitution_version" to promptEval.constitutionVersion
                )
            )

            return HereticGenerationResult(
                success = false,
                text = refusal,
                tokensGenerated = 0,
                latencyMs = latency,
                tokensPerSecond = 0f,
                valuesAllowed = false,
                valuesViolations = promptEval.violations,
                valuesWarnings = promptEval.warnings,
                constitutionVersion = promptEval.constitutionVersion,
                modelId = onDeviceLLMManager.activeConfig?.modelId,
                error = "Prompt blocked by constitutional values"
            )
        }

        // Step 2: Generate using the uncensored model
        if (!onDeviceLLMManager.isReady) {
            val latency = System.currentTimeMillis() - startTime
            return HereticGenerationResult(
                success = false, text = null, tokensGenerated = 0,
                latencyMs = latency, tokensPerSecond = 0f,
                valuesAllowed = true, valuesViolations = emptyList(),
                valuesWarnings = promptEval.warnings,
                constitutionVersion = promptEval.constitutionVersion,
                modelId = null,
                error = "No heretic model loaded — call loadModel() first"
            )
        }

        val genResult = onDeviceLLMManager.generate(prompt, maxTokens)
        if (!genResult.success || genResult.text == null) {
            val latency = System.currentTimeMillis() - startTime
            return HereticGenerationResult(
                success = false, text = null,
                tokensGenerated = genResult.tokensGenerated,
                latencyMs = latency, tokensPerSecond = genResult.tokensPerSecond,
                valuesAllowed = true, valuesViolations = emptyList(),
                valuesWarnings = promptEval.warnings,
                constitutionVersion = promptEval.constitutionVersion,
                modelId = genResult.modelId,
                error = genResult.error ?: "Generation failed"
            )
        }

        // Step 3: Constitutional values output gate
        val responseEval = valuesEngine.evaluateResponse(genResult.text)
        val latency = System.currentTimeMillis() - startTime

        if (!responseEval.allowed) {
            responsesBlocked++
            val refusal = valuesEngine.buildRefusalExplanation(responseEval)

            auditLogger?.logAsync(
                severity = AuditLogger.Severity.WARNING,
                category = AuditLogger.AuditCategory.POLICY_DECISION,
                actor = "heretic_model_manager",
                action = "generate_response_blocked",
                target = genResult.modelId,
                outcome = "blocked",
                details = mapOf(
                    "violations" to responseEval.violations.map { it.directiveId },
                    "tokens_generated" to genResult.tokensGenerated,
                    "constitution_version" to responseEval.constitutionVersion
                )
            )

            return HereticGenerationResult(
                success = false,
                text = refusal,
                tokensGenerated = genResult.tokensGenerated,
                latencyMs = latency,
                tokensPerSecond = genResult.tokensPerSecond,
                valuesAllowed = false,
                valuesViolations = responseEval.violations,
                valuesWarnings = responseEval.warnings,
                constitutionVersion = responseEval.constitutionVersion,
                modelId = genResult.modelId,
                error = "Response blocked by constitutional values"
            )
        }

        // Step 4: Successful generation through values gate
        successfulGenerations++

        auditLogger?.logAsync(
            severity = AuditLogger.Severity.INFO,
            category = AuditLogger.AuditCategory.PLUGIN_EXECUTION,
            actor = "heretic_model_manager",
            action = "generate_success",
            target = genResult.modelId,
            outcome = "success",
            details = mapOf(
                "tokens" to genResult.tokensGenerated,
                "latency_ms" to latency,
                "tps" to genResult.tokensPerSecond,
                "warnings" to responseEval.warnings.size,
                "constitution_version" to responseEval.constitutionVersion
            )
        )

        return HereticGenerationResult(
            success = true,
            text = genResult.text,
            tokensGenerated = genResult.tokensGenerated,
            latencyMs = latency,
            tokensPerSecond = genResult.tokensPerSecond,
            valuesAllowed = true,
            valuesViolations = emptyList(),
            valuesWarnings = responseEval.warnings,
            constitutionVersion = responseEval.constitutionVersion,
            modelId = genResult.modelId,
            error = null
        )
    }

    /**
     * Load a heretic model for inference.
     * Delegates to [OnDeviceLLMManager] for actual model loading.
     */
    fun loadModel(config: LLMModelConfig): Boolean {
        Log.d(TAG, "Loading heretic model: ${config.modelName}")
        val loaded = onDeviceLLMManager.loadModel(config)
        if (loaded) {
            Log.d(TAG, "Heretic model loaded: ${config.modelName} — " +
                    "constitutional values enforcement active")
        }
        return loaded
    }

    /**
     * Unload the current heretic model.
     */
    fun unloadModel() {
        onDeviceLLMManager.unloadModel()
    }

    /**
     * Check if a heretic model is loaded and ready.
     */
    val isReady: Boolean
        get() = onDeviceLLMManager.isReady

    /**
     * Get the active model configuration.
     */
    val activeConfig: LLMModelConfig?
        get() = onDeviceLLMManager.activeConfig

    /**
     * Get heretic model generation statistics.
     */
    fun getStats(): Map<String, Any> {
        val base = onDeviceLLMManager.getStats().toMutableMap()
        base["heretic_total_generations"] = totalGenerations
        base["heretic_prompts_blocked"] = promptsBlocked
        base["heretic_responses_blocked"] = responsesBlocked
        base["heretic_successful_generations"] = successfulGenerations
        base["heretic_registered_models"] = registeredModels.size
        base["heretic_values_engine"] = valuesEngine.getValuesStatus()
        return base
    }

    /**
     * Get all registered heretic models.
     */
    fun getRegisteredModels(): Map<String, HereticModelMetadata> =
        registeredModels.toMap()

    /**
     * Shutdown the manager.
     */
    fun shutdown() {
        Log.d(TAG, "HereticModelManager shutting down. " +
                "Stats: generations=$totalGenerations, blocked_prompts=$promptsBlocked, " +
                "blocked_responses=$responsesBlocked, successful=$successfulGenerations")
    }

    companion object {
        private const val TAG = "HereticModelManager"
    }
}

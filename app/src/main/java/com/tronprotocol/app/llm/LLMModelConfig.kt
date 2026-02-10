package com.tronprotocol.app.llm

import kotlin.math.max
import kotlin.math.min

/**
 * Configuration for an on-device LLM model loaded via MNN.
 *
 * Encapsulates all parameters needed to load and run inference:
 * - Model location and identity
 * - Hardware backend selection (CPU/GPU/NPU)
 * - Inference parameters (temperature, top-p, max tokens)
 * - Memory optimization settings (mmap, quantization)
 *
 * Models should be exported from HuggingFace using MNN's llmexport.py:
 *   python llmexport.py --path Qwen2.5-1.5B-Instruct --export mnn --quant_bit 4
 *
 * The model directory must contain:
 *   llm.mnn          - Compiled model graph
 *   llm.mnn.weight   - Quantized model weights
 *   config.json       - Runtime configuration
 *   llm_config.json   - Model architecture specs
 *   tokenizer.txt     - Token vocabulary
 *
 * @see OnDeviceLLMManager
 * @see [MNN GitHub](https://github.com/alibaba/MNN)
 */
class LLMModelConfig private constructor(
    val modelId: String,
    val modelName: String,
    val modelPath: String,
    val parameterCount: String,
    val quantization: String,
    val contextWindow: Int,
    val maxTokens: Int,
    val backend: Int,
    val threadCount: Int,
    val temperature: Float,
    val topP: Float,
    val useMmap: Boolean
) {

    /** Human-readable backend name. */
    val backendName: String
        get() = when (backend) {
            OnDeviceLLMManager.BACKEND_OPENCL -> "opencl"
            OnDeviceLLMManager.BACKEND_VULKAN -> "vulkan"
            else -> "cpu"
        }

    override fun toString(): String =
        "LLMModelConfig{name='$modelName', params=$parameterCount, quant=$quantization, " +
                "backend=$backendName, threads=$threadCount, maxTokens=$maxTokens, mmap=$useMmap}"

    /** Builder for [LLMModelConfig]. */
    class Builder(
        private val modelName: String,
        private val modelPath: String
    ) {
        private var modelId: String = "mnn_" + modelName.lowercase()
            .replace(Regex("[^a-z0-9]"), "_")
            .replace(Regex("_+"), "_")
        private var parameterCount: String = "unknown"
        private var quantization: String = "Q4"
        private var contextWindow: Int = 4096
        private var maxTokens: Int = 512
        private var backend: Int = OnDeviceLLMManager.BACKEND_CPU
        private var threadCount: Int = 4
        private var temperature: Float = 0.7f
        private var topP: Float = 0.9f
        private var useMmap: Boolean = false

        fun setModelId(modelId: String) = apply { this.modelId = modelId }
        fun setParameterCount(parameterCount: String) = apply { this.parameterCount = parameterCount }
        fun setQuantization(quantization: String) = apply { this.quantization = quantization }
        fun setContextWindow(contextWindow: Int) = apply { this.contextWindow = contextWindow }
        fun setMaxTokens(maxTokens: Int) = apply { this.maxTokens = maxTokens }

        /** Set the MNN backend: [OnDeviceLLMManager.BACKEND_CPU], BACKEND_OPENCL, or BACKEND_VULKAN. */
        fun setBackend(backend: Int) = apply { this.backend = backend }

        fun setThreadCount(threadCount: Int) = apply {
            this.threadCount = max(1, min(threadCount, 8))
        }

        fun setTemperature(temperature: Float) = apply {
            this.temperature = temperature.coerceIn(0.0f, 2.0f)
        }

        fun setTopP(topP: Float) = apply {
            this.topP = topP.coerceIn(0.0f, 1.0f)
        }

        /** Enable memory-mapped weights to reduce DRAM usage. Recommended for models > 2GB. */
        fun setUseMmap(useMmap: Boolean) = apply { this.useMmap = useMmap }

        fun build(): LLMModelConfig = LLMModelConfig(
            modelId, modelName, modelPath, parameterCount, quantization,
            contextWindow, maxTokens, backend, threadCount, temperature, topP, useMmap
        )
    }

    companion object {

        /** Qwen2.5-1.5B-Instruct Q4 — smallest recommended, runs on 3GB+ RAM (~1GB disk, ~1.5GB DRAM). */
        @JvmStatic
        fun qwen25_1_5b(modelPath: String): LLMModelConfig =
            Builder("Qwen2.5-1.5B-Instruct", modelPath)
                .setParameterCount("1.5B")
                .setQuantization("Q4_K_M")
                .setContextWindow(4096)
                .setMaxTokens(512)
                .setThreadCount(4)
                .build()

        /** Qwen3-1.7B Q4 — good balance of quality and speed (~1.2GB disk, ~1.7GB DRAM). */
        @JvmStatic
        fun qwen3_1_7b(modelPath: String): LLMModelConfig =
            Builder("Qwen3-1.7B", modelPath)
                .setParameterCount("1.7B")
                .setQuantization("Q4_K_M")
                .setContextWindow(4096)
                .setMaxTokens(512)
                .setThreadCount(4)
                .build()

        /** Qwen2.5-3B-Instruct Q4 — higher quality, needs 6GB+ RAM (~2GB disk, ~2.5GB DRAM). */
        @JvmStatic
        fun qwen25_3b(modelPath: String): LLMModelConfig =
            Builder("Qwen2.5-3B-Instruct", modelPath)
                .setParameterCount("3B")
                .setQuantization("Q4_K_M")
                .setContextWindow(4096)
                .setMaxTokens(512)
                .setThreadCount(4)
                .setUseMmap(true)
                .build()

        /** Gemma-2B Q4 — Google's lightweight model (~1.5GB disk, ~2GB DRAM). */
        @JvmStatic
        fun gemma_2b(modelPath: String): LLMModelConfig =
            Builder("Gemma-2B", modelPath)
                .setParameterCount("2B")
                .setQuantization("Q4_K_M")
                .setContextWindow(2048)
                .setMaxTokens(256)
                .setThreadCount(4)
                .build()

        /** DeepSeek-R1-1.5B Q4 — reasoning-focused model (~1GB disk, ~1.5GB DRAM). */
        @JvmStatic
        fun deepseek_r1_1_5b(modelPath: String): LLMModelConfig =
            Builder("DeepSeek-R1-1.5B", modelPath)
                .setParameterCount("1.5B")
                .setQuantization("Q4_K_M")
                .setContextWindow(4096)
                .setMaxTokens(512)
                .setThreadCount(4)
                .build()
    }
}

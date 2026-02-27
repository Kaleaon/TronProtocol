package com.tronprotocol.app.llm

import com.tronprotocol.app.llm.backend.BackendType
import kotlin.math.max
import kotlin.math.min

/**
 * Configuration for an on-device LLM model.
 *
 * Supports both MNN and GGUF model formats. The [format] field determines
 * which backend will be used for inference and which artifact validation
 * rules apply.
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
    val useMmap: Boolean,
    val artifacts: List<ModelArtifact>,
    val integrityStatus: IntegrityStatus,
    val format: String = "mnn"
) {

    /** The backend type determined by model format. */
    val backendType: BackendType
        get() = when (format.lowercase()) {
            "gguf" -> BackendType.GGUF
            else -> BackendType.MNN
        }

    /** Whether this config is for a GGUF model. */
    val isGguf: Boolean get() = format.lowercase() == "gguf"

    data class SignatureMetadata(
        val algorithm: String,
        val signer: String? = null,
        val signature: String
    )

    data class ModelArtifact(
        val fileName: String,
        val sha256: String,
        val signature: SignatureMetadata? = null
    )

    enum class IntegrityStatus {
        VERIFIED,
        UNTRUSTED_MIGRATED
    }

    val backendName: String
        get() = when (backend) {
            OnDeviceLLMManager.BACKEND_OPENCL -> "opencl"
            OnDeviceLLMManager.BACKEND_VULKAN -> "vulkan"
            else -> "cpu"
        }

    override fun toString(): String =
        "LLMModelConfig{name='$modelName', params=$parameterCount, quant=$quantization, " +
                "backend=$backendName, threads=$threadCount, maxTokens=$maxTokens, mmap=$useMmap, " +
                "artifacts=${artifacts.size}, integrity=$integrityStatus}"

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
        private val artifacts: MutableList<ModelArtifact> = mutableListOf()
        private var integrityStatus: IntegrityStatus = IntegrityStatus.VERIFIED
        private var format: String = "mnn"

        fun setFormat(format: String) = apply { this.format = format }

        fun setModelId(modelId: String) = apply { this.modelId = modelId }
        fun setParameterCount(parameterCount: String) = apply { this.parameterCount = parameterCount }
        fun setQuantization(quantization: String) = apply { this.quantization = quantization }
        fun setContextWindow(contextWindow: Int) = apply { this.contextWindow = contextWindow }
        fun setMaxTokens(maxTokens: Int) = apply { this.maxTokens = maxTokens }
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

        fun setUseMmap(useMmap: Boolean) = apply { this.useMmap = useMmap }

        fun addArtifact(
            fileName: String,
            sha256: String,
            signature: SignatureMetadata? = null
        ) = apply {
            artifacts.add(ModelArtifact(fileName, sha256, signature))
        }

        fun setArtifacts(artifacts: List<ModelArtifact>) = apply {
            this.artifacts.clear()
            this.artifacts.addAll(artifacts)
        }

        fun setIntegrityStatus(status: IntegrityStatus) = apply { this.integrityStatus = status }

        fun markMigratedWithoutChecksums() = apply {
            artifacts.clear()
            integrityStatus = IntegrityStatus.UNTRUSTED_MIGRATED
        }

        fun build(): LLMModelConfig {
            if (integrityStatus == IntegrityStatus.VERIFIED) {
                require(artifacts.isNotEmpty()) {
                    "At least one model artifact checksum is required"
                }
                // GGUF models have different artifact requirements
                if (format != "gguf") {
                    val required = REQUIRED_MODEL_ARTIFACTS.toSet()
                    val names = artifacts.map { it.fileName }.toSet()
                    require(required.subtract(names).isEmpty()) {
                        "Missing checksums for required artifacts: ${required.subtract(names)}"
                    }
                }
                artifacts.forEach { artifact ->
                    require(SHA256_REGEX.matches(artifact.sha256)) {
                        "Invalid SHA-256 for ${artifact.fileName}: ${artifact.sha256}"
                    }
                }
            }

            return LLMModelConfig(
                modelId, modelName, modelPath, parameterCount, quantization,
                contextWindow, maxTokens, backend, threadCount, temperature, topP, useMmap,
                artifacts.toList(), integrityStatus, format
            )
        }
    }

    companion object {
        private val SHA256_REGEX = Regex("^[a-fA-F0-9]{64}$")

        val REQUIRED_MODEL_ARTIFACTS = listOf(
            "llm.mnn",
            "llm.mnn.weight",
            "config.json",
            "llm_config.json",
            "tokenizer.txt"
        )

        fun migrateLegacyConfig(config: LLMModelConfig): LLMModelConfig {
            if (config.artifacts.isNotEmpty()) return config
            return Builder(config.modelName, config.modelPath)
                .setModelId(config.modelId)
                .setParameterCount(config.parameterCount)
                .setQuantization(config.quantization)
                .setContextWindow(config.contextWindow)
                .setMaxTokens(config.maxTokens)
                .setBackend(config.backend)
                .setThreadCount(config.threadCount)
                .setTemperature(config.temperature)
                .setTopP(config.topP)
                .setUseMmap(config.useMmap)
                .markMigratedWithoutChecksums()
                .build()
        }

        @JvmStatic
        fun qwen25_1_5b(modelPath: String, artifacts: List<ModelArtifact>): LLMModelConfig =
            Builder("Qwen2.5-1.5B-Instruct", modelPath)
                .setParameterCount("1.5B")
                .setQuantization("Q4_K_M")
                .setContextWindow(4096)
                .setMaxTokens(512)
                .setThreadCount(4)
                .setArtifacts(artifacts)
                .build()

        @JvmStatic
        fun qwen3_1_7b(modelPath: String, artifacts: List<ModelArtifact>): LLMModelConfig =
            Builder("Qwen3-1.7B", modelPath)
                .setParameterCount("1.7B")
                .setQuantization("Q4_K_M")
                .setContextWindow(4096)
                .setMaxTokens(512)
                .setThreadCount(4)
                .setArtifacts(artifacts)
                .build()

        @JvmStatic
        fun qwen25_3b(modelPath: String, artifacts: List<ModelArtifact>): LLMModelConfig =
            Builder("Qwen2.5-3B-Instruct", modelPath)
                .setParameterCount("3B")
                .setQuantization("Q4_K_M")
                .setContextWindow(4096)
                .setMaxTokens(512)
                .setThreadCount(4)
                .setUseMmap(true)
                .setArtifacts(artifacts)
                .build()

        @JvmStatic
        fun gemma_2b(modelPath: String, artifacts: List<ModelArtifact>): LLMModelConfig =
            Builder("Gemma-2B", modelPath)
                .setParameterCount("2B")
                .setQuantization("Q4_K_M")
                .setContextWindow(2048)
                .setMaxTokens(256)
                .setThreadCount(4)
                .setArtifacts(artifacts)
                .build()

        @JvmStatic
        fun deepseek_r1_1_5b(modelPath: String, artifacts: List<ModelArtifact>): LLMModelConfig =
            Builder("DeepSeek-R1-1.5B", modelPath)
                .setParameterCount("1.5B")
                .setQuantization("Q4_K_M")
                .setContextWindow(4096)
                .setMaxTokens(512)
                .setThreadCount(4)
                .setArtifacts(artifacts)
                .build()

        // ---- GGUF model factory methods ----

        /**
         * Required artifacts for GGUF models.
         * GGUF bundles tokenizer inside the model file, so only the .gguf file is required.
         */
        val REQUIRED_GGUF_ARTIFACTS = listOf<String>()

        @JvmStatic
        fun gguf_qwen_0_5b(modelPath: String, artifacts: List<ModelArtifact>): LLMModelConfig =
            Builder("Ruvltra-Claude-Code-0.5B", modelPath)
                .setModelId("gguf_ruvltra_claude_code_0_5b")
                .setFormat("gguf")
                .setParameterCount("0.5B")
                .setQuantization("Q4_K_M")
                .setContextWindow(4096)
                .setMaxTokens(512)
                .setThreadCount(4)
                .setArtifacts(artifacts)
                .build()

        @JvmStatic
        fun gguf_gemma3_1b(modelPath: String, artifacts: List<ModelArtifact>): LLMModelConfig =
            Builder("Gemma3-1B-IT", modelPath)
                .setModelId("gguf_gemma3_1b")
                .setFormat("gguf")
                .setParameterCount("1B")
                .setQuantization("Q4_K_M")
                .setContextWindow(4096)
                .setMaxTokens(512)
                .setThreadCount(4)
                .setArtifacts(artifacts)
                .build()

        @JvmStatic
        fun gguf_qwen3_8b(modelPath: String, artifacts: List<ModelArtifact>): LLMModelConfig =
            Builder("Qwen3-8B", modelPath)
                .setModelId("gguf_qwen3_8b")
                .setFormat("gguf")
                .setParameterCount("8B")
                .setQuantization("Q4_K_M")
                .setContextWindow(8192)
                .setMaxTokens(1024)
                .setThreadCount(4)
                .setUseMmap(true)
                .setArtifacts(artifacts)
                .build()
    }
}

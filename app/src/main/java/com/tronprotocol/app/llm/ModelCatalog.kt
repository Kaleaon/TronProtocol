package com.tronprotocol.app.llm

import android.os.Build

/**
 * Comprehensive model catalog inspired by LLM-Hub's ModelData registry.
 *
 * Provides a curated list of downloadable on-device LLM models with metadata
 * including download URLs, size, RAM requirements, format, and capabilities.
 * Models are sourced from HuggingFace in both MNN and GGUF formats for
 * dual-backend inference via [com.tronprotocol.app.llm.backend.BackendSelector].
 *
 * @see <a href="https://github.com/timmyy123/LLM-Hub">LLM-Hub</a>
 */
object ModelCatalog {

    /** Device SoC detection for optimal model recommendations. */
    object DeviceSocInfo {
        fun getDeviceSoc(): String {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL
            } else {
                "UNKNOWN"
            }
        }

        private val chipsetModelSuffixes = mapOf(
            "SM8475" to "8gen1",
            "SM8450" to "8gen1",
            "SM8550" to "8gen2",
            "SM8550P" to "8gen2",
            "SM8650" to "8gen3",
            "SM8650P" to "8gen3",
            "SM8750" to "8gen4",
            "SM8750P" to "8gen4",
            "SM8735" to "8gen3",
            "SM8845" to "8gen4"
        )

        fun getChipsetGeneration(): String? {
            val soc = getDeviceSoc()
            return chipsetModelSuffixes[soc] ?: if (soc.startsWith("SM")) "unknown_qcom" else null
        }

        fun isHighEndQualcomm(): Boolean {
            val gen = getChipsetGeneration() ?: return false
            return gen in setOf("8gen2", "8gen3", "8gen4")
        }
    }

    /** RAM requirement specification for a model. */
    data class RamRequirement(
        val minRamMb: Long,
        val recommendedRamMb: Long
    )

    /**
     * Standard MNN model files to download from HuggingFace repos.
     * Required files are listed in [LLMModelConfig.REQUIRED_MODEL_ARTIFACTS].
     * Additional optional files (e.g. embeddings) are included when present.
     */
    val DEFAULT_MNN_MODEL_FILES = listOf(
        "config.json",
        "llm.mnn",
        "llm.mnn.json",
        "llm.mnn.weight",
        "llm_config.json",
        "tokenizer.txt",
        "embeddings_bf16.bin"
    )

    /**
     * GGUF models are single-file: the `.gguf` file contains model weights,
     * tokenizer, and config. No additional files needed.
     */
    val DEFAULT_GGUF_MODEL_FILES = emptyList<String>()

    /** A downloadable model entry in the catalog. */
    data class CatalogEntry(
        val id: String,
        val name: String,
        val description: String,
        val family: String,
        val parameterCount: String,
        val quantization: String,
        val format: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val contextWindow: Int,
        val ramRequirement: RamRequirement,
        val supportsGpu: Boolean,
        val source: String,
        val category: String = "text",
        /** List of individual files to download. When non-empty, [downloadUrl] is
         *  the base resolve URL and each file is fetched separately. */
        val modelFiles: List<String> = emptyList()
    ) {
        val sizeMb: Long get() = sizeBytes / (1024 * 1024)

        val localDirectoryName: String
            get() = id.replace(Regex("[^a-z0-9._-]"), "_")

        /** Whether this catalog entry is a GGUF model. */
        val isGguf: Boolean get() = format.lowercase() == "gguf"

        /** Whether this catalog entry is an MNN model. */
        val isMnn: Boolean get() = format.lowercase() == "mnn"
    }

    /**
     * Complete model catalog. Entries are listed in recommended order
     * (smaller / more compatible models first).
     *
     * MNN models require pre-export via `llmexport.py` from the MNN project.
     * HuggingFace URLs point to community-exported MNN packages where available.
     */
    val entries: List<CatalogEntry> = listOf(
        // ---- Qwen family ----
        CatalogEntry(
            id = "qwen2.5-1.5b-instruct-q4",
            name = "Qwen2.5-1.5B-Instruct",
            description = "Alibaba's Qwen2.5 1.5B instruction-tuned model. " +
                    "Q4 quantized for mobile. Fast inference, good for general tasks.",
            family = "Qwen",
            parameterCount = "1.5B",
            quantization = "Q4_K_M",
            format = "mnn",
            downloadUrl = "https://huggingface.co/taobao-mnn/Qwen2.5-1.5B-Instruct-MNN/resolve/main/",
            sizeBytes = 1_100_000_000L,
            contextWindow = 4096,
            ramRequirement = RamRequirement(minRamMb = 2048, recommendedRamMb = 3072),
            supportsGpu = true,
            source = "Alibaba via MNN Community",
            modelFiles = DEFAULT_MNN_MODEL_FILES
        ),
        CatalogEntry(
            id = "qwen3-1.7b-q4",
            name = "Qwen3-1.7B",
            description = "Latest Qwen3 architecture with 1.7B parameters. " +
                    "Improved quality over Qwen2.5 at similar size.",
            family = "Qwen",
            parameterCount = "1.7B",
            quantization = "Q4_K_M",
            format = "mnn",
            downloadUrl = "https://huggingface.co/taobao-mnn/Qwen3-1.7B-MNN/resolve/main/",
            sizeBytes = 1_300_000_000L,
            contextWindow = 4096,
            ramRequirement = RamRequirement(minRamMb = 2560, recommendedRamMb = 4096),
            supportsGpu = true,
            source = "Alibaba via MNN Community",
            modelFiles = DEFAULT_MNN_MODEL_FILES
        ),
        CatalogEntry(
            id = "qwen2.5-3b-instruct-q4",
            name = "Qwen2.5-3B-Instruct",
            description = "Larger Qwen2.5 model with 3B parameters. Higher quality " +
                    "responses. Recommended for devices with 8GB+ RAM.",
            family = "Qwen",
            parameterCount = "3B",
            quantization = "Q4_K_M",
            format = "mnn",
            downloadUrl = "https://huggingface.co/taobao-mnn/Qwen2.5-3B-Instruct-MNN/resolve/main/",
            sizeBytes = 2_100_000_000L,
            contextWindow = 4096,
            ramRequirement = RamRequirement(minRamMb = 4096, recommendedRamMb = 6144),
            supportsGpu = true,
            source = "Alibaba via MNN Community",
            modelFiles = DEFAULT_MNN_MODEL_FILES
        ),

        // ---- DeepSeek family ----
        CatalogEntry(
            id = "deepseek-r1-1.5b-q4",
            name = "DeepSeek-R1-1.5B",
            description = "DeepSeek's reasoning-focused 1.5B model. " +
                    "Strong at logic and step-by-step problem solving.",
            family = "DeepSeek",
            parameterCount = "1.5B",
            quantization = "Q4_K_M",
            format = "mnn",
            downloadUrl = "https://huggingface.co/taobao-mnn/DeepSeek-R1-1.5B-Qwen-MNN/resolve/main/",
            sizeBytes = 1_100_000_000L,
            contextWindow = 4096,
            ramRequirement = RamRequirement(minRamMb = 2048, recommendedRamMb = 3072),
            supportsGpu = true,
            source = "DeepSeek via MNN Community",
            modelFiles = DEFAULT_MNN_MODEL_FILES
        ),

        // ---- Gemma family ----
        CatalogEntry(
            id = "gemma-2b-q4",
            name = "Gemma-2B",
            description = "Google's Gemma 2B model. Balanced quality and speed. " +
                    "Good instruction following capabilities.",
            family = "Gemma",
            parameterCount = "2B",
            quantization = "Q4_K_M",
            format = "mnn",
            downloadUrl = "https://huggingface.co/taobao-mnn/gemma-2-2b-it-MNN/resolve/main/",
            sizeBytes = 1_500_000_000L,
            contextWindow = 2048,
            ramRequirement = RamRequirement(minRamMb = 2560, recommendedRamMb = 4096),
            supportsGpu = true,
            source = "Google via MNN Community",
            modelFiles = DEFAULT_MNN_MODEL_FILES
        ),

        // ---- SmolLM family ----
        CatalogEntry(
            id = "smollm2-1.7b-q4",
            name = "SmolLM2-1.7B-Instruct",
            description = "HuggingFace's compact instruction-tuned model. " +
                    "Excellent efficiency for its size class.",
            family = "SmolLM",
            parameterCount = "1.7B",
            quantization = "Q4_K_M",
            format = "mnn",
            downloadUrl = "https://huggingface.co/taobao-mnn/SmolLM2-1.7B-Instruct-MNN/resolve/main/",
            sizeBytes = 1_200_000_000L,
            contextWindow = 2048,
            ramRequirement = RamRequirement(minRamMb = 2048, recommendedRamMb = 3072),
            supportsGpu = true,
            source = "HuggingFace via MNN Community",
            modelFiles = DEFAULT_MNN_MODEL_FILES
        ),

        // ---- Qwen3-4B (replaced Phi-3.5 which is unavailable on taobao-mnn) ----
        CatalogEntry(
            id = "qwen3-4b-q4",
            name = "Qwen3-4B",
            description = "Alibaba's Qwen3 with 4B parameters. " +
                    "High quality responses for devices with 8GB+ RAM.",
            family = "Qwen",
            parameterCount = "4B",
            quantization = "Q4_K_M",
            format = "mnn",
            downloadUrl = "https://huggingface.co/taobao-mnn/Qwen3-4B-MNN/resolve/main/",
            sizeBytes = 2_500_000_000L,
            contextWindow = 4096,
            ramRequirement = RamRequirement(minRamMb = 4096, recommendedRamMb = 8192),
            supportsGpu = true,
            source = "Alibaba via MNN Community",
            modelFiles = DEFAULT_MNN_MODEL_FILES
        ),

        // ---- Llama family ----
        CatalogEntry(
            id = "llama-3.2-1b-q4",
            name = "Llama-3.2-1B-Instruct",
            description = "Meta's Llama 3.2 1B instruction-tuned model. " +
                    "Well-rounded performance for mobile inference.",
            family = "Llama",
            parameterCount = "1B",
            quantization = "Q4_K_M",
            format = "mnn",
            downloadUrl = "https://huggingface.co/taobao-mnn/Llama-3.2-1B-Instruct-MNN/resolve/main/",
            sizeBytes = 800_000_000L,
            contextWindow = 4096,
            ramRequirement = RamRequirement(minRamMb = 2048, recommendedRamMb = 3072),
            supportsGpu = true,
            source = "Meta via MNN Community",
            modelFiles = DEFAULT_MNN_MODEL_FILES
        ),
        CatalogEntry(
            id = "llama-3.2-3b-q4",
            name = "Llama-3.2-3B-Instruct",
            description = "Meta's Llama 3.2 3B model. Higher quality responses. " +
                    "Recommended for devices with 8GB+ RAM.",
            family = "Llama",
            parameterCount = "3B",
            quantization = "Q4_K_M",
            format = "mnn",
            downloadUrl = "https://huggingface.co/taobao-mnn/Llama-3.2-3B-Instruct-MNN/resolve/main/",
            sizeBytes = 2_200_000_000L,
            contextWindow = 4096,
            ramRequirement = RamRequirement(minRamMb = 4096, recommendedRamMb = 8192),
            supportsGpu = true,
            source = "Meta via MNN Community",
            modelFiles = DEFAULT_MNN_MODEL_FILES
        ),

        // ---- Heretic family (uncensored, constitution-based values) ----
        // Models processed through github.com/p-e-w/heretic directional ablation
        // to remove baked-in safety alignment. Safety is enforced by TronProtocol's
        // ConstitutionalValuesEngine instead — transparent, versioned, auditable.
        CatalogEntry(
            id = "heretic-qwen2.5-1.5b-q4",
            name = "Heretic-Qwen2.5-1.5B",
            description = "Qwen2.5-1.5B processed through heretic ablation. " +
                    "Uncensored model with constitution-based values enforcement. " +
                    "Fast inference, good for general tasks without opaque refusal.",
            family = "Heretic",
            parameterCount = "1.5B",
            quantization = "Q4_K_M",
            format = "mnn",
            downloadUrl = "https://huggingface.co/taobao-mnn/Qwen2.5-1.5B-Instruct-MNN/resolve/main/",
            sizeBytes = 1_100_000_000L,
            contextWindow = 4096,
            ramRequirement = RamRequirement(minRamMb = 2048, recommendedRamMb = 3072),
            supportsGpu = true,
            source = "Heretic-processed (github.com/p-e-w/heretic)",
            modelFiles = DEFAULT_MNN_MODEL_FILES
        ),
        CatalogEntry(
            id = "heretic-gemma-2b-q4",
            name = "Heretic-Gemma-2B",
            description = "Google Gemma 2B processed through heretic ablation. " +
                    "Balanced quality/speed with constitutional values replacing alignment.",
            family = "Heretic",
            parameterCount = "2B",
            quantization = "Q4_K_M",
            format = "mnn",
            downloadUrl = "https://huggingface.co/taobao-mnn/gemma-2-2b-it-MNN/resolve/main/",
            sizeBytes = 1_500_000_000L,
            contextWindow = 2048,
            ramRequirement = RamRequirement(minRamMb = 2560, recommendedRamMb = 4096),
            supportsGpu = true,
            source = "Heretic-processed (github.com/p-e-w/heretic)",
            modelFiles = DEFAULT_MNN_MODEL_FILES
        ),
        CatalogEntry(
            id = "heretic-llama-3.2-3b-q4",
            name = "Heretic-Llama-3.2-3B",
            description = "Meta Llama 3.2 3B processed through heretic ablation. " +
                    "Higher quality uncensored generation for 8GB+ RAM devices. " +
                    "Constitutional values provide transparent safety controls.",
            family = "Heretic",
            parameterCount = "3B",
            quantization = "Q4_K_M",
            format = "mnn",
            downloadUrl = "https://huggingface.co/taobao-mnn/Llama-3.2-3B-Instruct-MNN/resolve/main/",
            sizeBytes = 2_200_000_000L,
            contextWindow = 4096,
            ramRequirement = RamRequirement(minRamMb = 4096, recommendedRamMb = 8192),
            supportsGpu = true,
            source = "Heretic-processed (github.com/p-e-w/heretic)",
            modelFiles = DEFAULT_MNN_MODEL_FILES
        ),

        // ---- GGUF models (llama.cpp backend) ----
        // GGUF models are single-file, bundling tokenizer and config inside the model.
        // These provide an alternative backend via ToolNeuron's llama.cpp integration,
        // with support for tool calling, control vectors, and KV cache persistence.
        CatalogEntry(
            id = "gguf-ruvltra-claude-code-0.5b-q4",
            name = "Ruvltra-Claude-Code-0.5B",
            description = "Compact 0.5B GGUF model optimized for code and tool calling. " +
                    "Very fast inference, ideal for grammar-constrained plugin dispatch.",
            family = "Qwen",
            parameterCount = "0.5B",
            quantization = "Q4_K_M",
            format = "gguf",
            downloadUrl = "https://huggingface.co/Ruvltra/Claude-Code-0.5b-Q4_K_M-GGUF/resolve/main/claude-code-0.5b-q4_k_m.gguf",
            sizeBytes = 400_000_000L,
            contextWindow = 4096,
            ramRequirement = RamRequirement(minRamMb = 1024, recommendedRamMb = 2048),
            supportsGpu = false,
            source = "Ruvltra via HuggingFace",
            modelFiles = DEFAULT_GGUF_MODEL_FILES
        ),
        CatalogEntry(
            id = "gguf-gemma3-1b-q4",
            name = "Gemma3-1B-IT-GGUF",
            description = "Google Gemma3 1B instruction-tuned in GGUF format. " +
                    "Lightweight model with good instruction following.",
            family = "Gemma",
            parameterCount = "1B",
            quantization = "Q4_K_M",
            format = "gguf",
            downloadUrl = "https://huggingface.co/bartowski/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
            sizeBytes = 750_000_000L,
            contextWindow = 4096,
            ramRequirement = RamRequirement(minRamMb = 1536, recommendedRamMb = 2560),
            supportsGpu = false,
            source = "Google via bartowski GGUF",
            modelFiles = DEFAULT_GGUF_MODEL_FILES
        ),
        CatalogEntry(
            id = "gguf-qwen3-8b-q4",
            name = "Qwen3-8B-GGUF",
            description = "Alibaba's Qwen3 8B in GGUF format. High quality responses " +
                    "with tool calling support. Recommended for 12GB+ RAM devices.",
            family = "Qwen",
            parameterCount = "8B",
            quantization = "Q4_K_M",
            format = "gguf",
            downloadUrl = "https://huggingface.co/bartowski/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf",
            sizeBytes = 5_000_000_000L,
            contextWindow = 8192,
            ramRequirement = RamRequirement(minRamMb = 6144, recommendedRamMb = 12288),
            supportsGpu = false,
            source = "Alibaba via bartowski GGUF",
            modelFiles = DEFAULT_GGUF_MODEL_FILES
        )
    )

    /** Get all model families. */
    val families: List<String>
        get() = entries.map { it.family }.distinct().sorted()

    /** Filter catalog by family name. */
    fun byFamily(family: String): List<CatalogEntry> =
        entries.filter { it.family.equals(family, ignoreCase = true) }

    /** Filter catalog by maximum RAM requirement. */
    fun fittingInRam(availableRamMb: Long): List<CatalogEntry> =
        entries.filter { it.ramRequirement.minRamMb <= availableRamMb }

    /** Find a catalog entry by ID. */
    fun findById(id: String): CatalogEntry? =
        entries.find { it.id == id }

    /** Find catalog entries by model format. */
    fun byFormat(format: String): List<CatalogEntry> =
        entries.filter { it.format.equals(format, ignoreCase = true) }

    /**
     * Recommend the best model for the given device RAM.
     * Returns the largest model that fits comfortably in available RAM.
     */
    fun recommendForDevice(availableRamMb: Long): CatalogEntry? {
        return entries
            .filter { it.ramRequirement.recommendedRamMb <= availableRamMb }
            .maxByOrNull { it.sizeBytes }
    }

    /**
     * Recommend the best model for the given device RAM and preferred format.
     *
     * @param preferredFormat "mnn", "gguf", or null for any format
     */
    fun recommendForDevice(availableRamMb: Long, preferredFormat: String?): CatalogEntry? {
        val candidates = entries
            .filter { it.ramRequirement.recommendedRamMb <= availableRamMb }
            .let { list ->
                if (preferredFormat != null) {
                    val filtered = list.filter { it.format.equals(preferredFormat, ignoreCase = true) }
                    filtered.ifEmpty { list } // Fall back to all formats if none match
                } else list
            }
        return candidates.maxByOrNull { it.sizeBytes }
    }

    /**
     * Recommend models sorted by suitability for the device.
     * Compatible models first (sorted by quality/size descending),
     * then incompatible models.
     */
    fun sortedForDevice(availableRamMb: Long): List<CatalogEntry> {
        val (compatible, incompatible) = entries.partition {
            it.ramRequirement.minRamMb <= availableRamMb
        }
        return compatible.sortedByDescending { it.sizeBytes } + incompatible.sortedBy { it.sizeBytes }
    }
}

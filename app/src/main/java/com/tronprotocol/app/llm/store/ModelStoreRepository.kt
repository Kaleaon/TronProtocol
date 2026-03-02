package com.tronprotocol.app.llm.store

import android.util.Log
import com.tronprotocol.app.llm.ModelCatalog

/**
 * Repository for browsing and discovering models from HuggingFace repos.
 *
 * Ported from ToolNeuron's ModelStoreRepository. Provides device-aware filtering,
 * model type detection (GGUF, SD, etc.), and caching of HuggingFace API responses.
 *
 * Integrates with [ModelCatalog] for local catalog entries and [HuggingFaceApi]
 * for remote discovery.
 */
class ModelStoreRepository(
    private val api: HuggingFaceApi = HuggingFaceClient.api,
    private val repoDataStore: ModelRepoDataStore? = null
) {

    /** A browsable model discovered from a HuggingFace repo. */
    data class StoreModel(
        val repoId: String,
        val fileName: String,
        val displayName: String,
        val parameterCount: String?,
        val quantization: String?,
        val format: String,
        val sizeBytes: Long,
        val downloadUrl: String,
        val supportsToolCalling: Boolean = false,
        val category: ModelCategory = ModelCategory.GENERAL
    ) {
        val sizeMb: Long get() = sizeBytes / (1024 * 1024)
    }

    enum class ModelCategory {
        GENERAL, UNCENSORED, IMAGE_NPU, IMAGE_CPU, TTS, EMBEDDING
    }

    // In-memory cache: repo ID → list of discovered models
    private val cache = mutableMapOf<String, CacheEntry>()

    private data class CacheEntry(
        val models: List<StoreModel>,
        val timestamp: Long
    )

    /**
     * Browse a HuggingFace repo for downloadable GGUF models.
     *
     * @param repoId HuggingFace repo ID (e.g. "bartowski/Qwen3-8B-GGUF")
     * @param token Optional HuggingFace API token for gated repos
     * @return List of discovered models in the repo
     */
    suspend fun browseRepo(repoId: String, token: String? = null): List<StoreModel> {
        // Check cache
        val cached = cache[repoId]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return cached.models
        }

        return try {
            val auth = HuggingFaceClient.bearerHeader(token)
            val files = api.getRepoFiles(repoId, auth)

            val models = files
                .filter { it.type == "file" && ModelMetadataExtractor.isGgufFile(it.path) }
                .map { file ->
                    val size = file.lfs?.size ?: file.size
                    StoreModel(
                        repoId = repoId,
                        fileName = file.path,
                        displayName = file.path.substringBeforeLast("."),
                        parameterCount = ModelMetadataExtractor.extractParameterCount(file.path),
                        quantization = ModelMetadataExtractor.extractQuantization(file.path),
                        format = "gguf",
                        sizeBytes = size,
                        downloadUrl = "https://huggingface.co/$repoId/resolve/main/${file.path}",
                        supportsToolCalling = ModelMetadataExtractor.isLikelyToolCallingModel(file.path)
                    )
                }

            cache[repoId] = CacheEntry(models, System.currentTimeMillis())
            models
        } catch (e: Exception) {
            Log.e(TAG, "Failed to browse repo $repoId: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Browse all configured default repos and return all discovered models.
     */
    suspend fun browseAllRepos(token: String? = null): List<StoreModel> {
        val repos = repoDataStore?.getRepos() ?: DEFAULT_REPOS
        return repos.flatMap { repo ->
            try {
                browseRepo(repo.repoId, token)
            } catch (e: Exception) {
                Log.w(TAG, "Skipping repo ${repo.repoId}: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * Search across all repos for models matching a query.
     */
    suspend fun search(query: String, token: String? = null): List<StoreModel> {
        val allModels = browseAllRepos(token)
        val lowerQuery = query.lowercase()
        return allModels.filter { model ->
            model.displayName.lowercase().contains(lowerQuery) ||
                    model.repoId.lowercase().contains(lowerQuery) ||
                    model.parameterCount?.lowercase()?.contains(lowerQuery) == true
        }
    }

    /**
     * Convert a [StoreModel] to a [ModelCatalog.CatalogEntry] for downloading.
     */
    fun toCatalogEntry(model: StoreModel): ModelCatalog.CatalogEntry {
        val sizeCategory = ModelMetadataExtractor.extractSizeCategory(model.parameterCount)
        val ramMin = when (sizeCategory) {
            ModelMetadataExtractor.SizeCategory.TINY -> 1024L
            ModelMetadataExtractor.SizeCategory.SMALL -> 2048L
            ModelMetadataExtractor.SizeCategory.MEDIUM -> 4096L
            ModelMetadataExtractor.SizeCategory.LARGE -> 6144L
            ModelMetadataExtractor.SizeCategory.XLARGE -> 12288L
        }
        return ModelCatalog.CatalogEntry(
            id = "store_${model.repoId.replace("/", "_")}_${model.fileName.substringBeforeLast(".")}",
            name = model.displayName,
            description = "Discovered from HuggingFace: ${model.repoId}",
            family = model.repoId.substringAfterLast("/").substringBefore("-"),
            parameterCount = model.parameterCount ?: "unknown",
            quantization = model.quantization ?: "unknown",
            format = model.format,
            downloadUrl = model.downloadUrl,
            sizeBytes = model.sizeBytes,
            contextWindow = 4096,
            ramRequirement = ModelCatalog.RamRequirement(minRamMb = ramMin, recommendedRamMb = ramMin * 2),
            supportsGpu = false,
            source = "HuggingFace: ${model.repoId}"
        )
    }

    /** Clear the in-memory cache. */
    fun clearCache() {
        cache.clear()
    }

    /** Filter models by device RAM in MB. */
    fun filterForDevice(models: List<StoreModel>, availableRamMb: Long): List<StoreModel> {
        return models.filter { model ->
            val sizeCategory = ModelMetadataExtractor.extractSizeCategory(model.parameterCount)
            val ramMin = when (sizeCategory) {
                ModelMetadataExtractor.SizeCategory.TINY -> 1024L
                ModelMetadataExtractor.SizeCategory.SMALL -> 2048L
                ModelMetadataExtractor.SizeCategory.MEDIUM -> 4096L
                ModelMetadataExtractor.SizeCategory.LARGE -> 6144L
                ModelMetadataExtractor.SizeCategory.XLARGE -> 12288L
            }
            ramMin <= availableRamMb
        }
    }

    /** Default repo entry. */
    data class RepoConfig(
        val repoId: String,
        val category: ModelCategory = ModelCategory.GENERAL,
        val description: String = ""
    )

    companion object {
        private const val TAG = "ModelStoreRepository"
        private const val CACHE_TTL_MS = 15 * 60 * 1000L  // 15 minutes

        val DEFAULT_REPOS = listOf(
            RepoConfig("bartowski/Qwen3-8B-GGUF", ModelCategory.GENERAL, "Qwen3 8B quantized"),
            RepoConfig("bartowski/gemma-3-1b-it-GGUF", ModelCategory.GENERAL, "Gemma3 1B instruction-tuned"),
            RepoConfig("Ruvltra/Claude-Code-0.5b-Q4_K_M-GGUF", ModelCategory.GENERAL, "Tool-calling optimized 0.5B"),
            RepoConfig("bartowski/Llama-3.2-3B-Instruct-GGUF", ModelCategory.GENERAL, "Llama 3.2 3B"),
            RepoConfig("bartowski/Qwen2.5-1.5B-Instruct-GGUF", ModelCategory.GENERAL, "Qwen2.5 1.5B")
        )
    }
}

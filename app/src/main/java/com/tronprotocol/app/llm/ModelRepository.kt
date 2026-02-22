package com.tronprotocol.app.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Repository for managing downloaded and discovered on-device LLM models.
 *
 * Tracks which models are available on the device, their download status,
 * active selection, and per-model configuration preferences. Inspired by
 * LLM-Hub's ModelRepository pattern.
 *
 * @see <a href="https://github.com/timmyy123/LLM-Hub">LLM-Hub</a>
 */
class ModelRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** A model available on the device. */
    data class AvailableModel(
        val id: String,
        val name: String,
        val parameterCount: String,
        val quantization: String,
        val family: String,
        val format: String,
        val directory: File,
        val diskUsageBytes: Long,
        val contextWindow: Int,
        val catalogEntry: ModelCatalog.CatalogEntry?,
        val isFromCatalog: Boolean,
        val source: String
    ) {
        val diskUsageMb: Long get() = diskUsageBytes / (1024 * 1024)
    }

    /**
     * Scan the filesystem and return all available (downloaded) models.
     * Checks both internal mnn_models directory and external/sdcard paths.
     */
    fun getAvailableModels(): List<AvailableModel> {
        val models = mutableListOf<AvailableModel>()

        // Scan primary internal directory
        val internalDir = File(appContext.filesDir, "mnn_models")
        scanDirectory(internalDir, models)

        // Scan external app files
        appContext.getExternalFilesDir(null)?.let { extDir ->
            scanDirectory(File(extDir, "mnn_models"), models)
        }

        // Scan well-known SD card path
        scanDirectory(File("/sdcard/mnn_models"), models)

        Log.d(TAG, "Found ${models.size} available models")
        return models.distinctBy { it.id }
    }

    /** Get the currently selected model ID, or null if none selected. */
    fun getSelectedModelId(): String? =
        prefs.getString(KEY_SELECTED_MODEL, null)

    /** Set the currently selected model ID. */
    fun setSelectedModelId(modelId: String?) {
        prefs.edit().putString(KEY_SELECTED_MODEL, modelId).apply()
        Log.d(TAG, "Selected model: $modelId")
    }

    /** Get the currently selected available model, if any. */
    fun getSelectedModel(): AvailableModel? {
        val selectedId = getSelectedModelId() ?: return null
        return getAvailableModels().find { it.id == selectedId }
    }

    /**
     * Auto-select the best available model if none is currently selected.
     * Picks the largest model that fits the device's capabilities.
     */
    fun autoSelectIfNeeded(availableRamMb: Long): AvailableModel? {
        if (getSelectedModelId() != null) {
            val selected = getSelectedModel()
            if (selected != null) return selected
            // Previously selected model no longer available, clear
            setSelectedModelId(null)
        }

        val available = getAvailableModels()
        if (available.isEmpty()) return null

        // Pick the model with the best catalog recommendation
        val recommended = ModelCatalog.recommendForDevice(availableRamMb)
        if (recommended != null) {
            val matching = available.find { it.id == recommended.id }
            if (matching != null) {
                setSelectedModelId(matching.id)
                return matching
            }
        }

        // Fallback: pick the first available model
        val first = available.first()
        setSelectedModelId(first.id)
        return first
    }

    /** Get saved per-model configuration overrides. */
    fun getModelConfig(modelId: String): ModelConfigOverrides? {
        val json = prefs.getString("$KEY_CONFIG_PREFIX$modelId", null) ?: return null
        return try {
            val obj = JSONObject(json)
            ModelConfigOverrides(
                maxTokens = obj.optInt("maxTokens", 512),
                temperature = obj.optDouble("temperature", 0.7).toFloat(),
                topP = obj.optDouble("topP", 0.9).toFloat(),
                threadCount = obj.optInt("threadCount", 4),
                backend = obj.optInt("backend", OnDeviceLLMManager.BACKEND_CPU),
                useMmap = obj.optBoolean("useMmap", false)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse config for $modelId: ${e.message}")
            null
        }
    }

    /** Save per-model configuration overrides. */
    fun setModelConfig(modelId: String, config: ModelConfigOverrides) {
        val obj = JSONObject().apply {
            put("maxTokens", config.maxTokens)
            put("temperature", config.temperature.toDouble())
            put("topP", config.topP.toDouble())
            put("threadCount", config.threadCount)
            put("backend", config.backend)
            put("useMmap", config.useMmap)
        }
        prefs.edit().putString("$KEY_CONFIG_PREFIX$modelId", obj.toString()).apply()
    }

    /** Remove per-model configuration overrides. */
    fun removeModelConfig(modelId: String) {
        prefs.edit().remove("$KEY_CONFIG_PREFIX$modelId").apply()
    }

    /** Track a custom-imported model (not from catalog). */
    fun addImportedModel(model: ImportedModelEntry) {
        val existing = getImportedModels().toMutableList()
        existing.removeAll { it.id == model.id }
        existing.add(model)
        saveImportedModels(existing)
    }

    /** Remove a custom-imported model entry. */
    fun removeImportedModel(modelId: String) {
        val existing = getImportedModels().toMutableList()
        existing.removeAll { it.id == modelId }
        saveImportedModels(existing)
    }

    /** Get all custom-imported model entries. */
    fun getImportedModels(): List<ImportedModelEntry> {
        val json = prefs.getString(KEY_IMPORTED_MODELS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                ImportedModelEntry(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    directory = obj.getString("directory"),
                    parameterCount = obj.optString("parameterCount", "unknown"),
                    quantization = obj.optString("quantization", "unknown"),
                    family = obj.optString("family", "Custom")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse imported models: ${e.message}")
            emptyList()
        }
    }

    /** Per-model configuration overrides. */
    data class ModelConfigOverrides(
        val maxTokens: Int = 512,
        val temperature: Float = 0.7f,
        val topP: Float = 0.9f,
        val threadCount: Int = 4,
        val backend: Int = OnDeviceLLMManager.BACKEND_CPU,
        val useMmap: Boolean = false
    )

    /** Entry for a custom-imported model. */
    data class ImportedModelEntry(
        val id: String,
        val name: String,
        val directory: String,
        val parameterCount: String = "unknown",
        val quantization: String = "unknown",
        val family: String = "Custom"
    )

    // ---- Internal helpers ----

    private fun scanDirectory(baseDir: File, results: MutableList<AvailableModel>) {
        if (!baseDir.exists() || !baseDir.isDirectory) return
        baseDir.listFiles()?.forEach { child ->
            if (child.isDirectory && File(child, "llm.mnn").exists()) {
                val model = buildAvailableModel(child)
                if (model != null) results.add(model)
            }
        }
    }

    private fun buildAvailableModel(modelDir: File): AvailableModel? {
        val dirName = modelDir.name

        // Try to match to a catalog entry
        val catalogEntry = ModelCatalog.entries.find {
            it.localDirectoryName == dirName || it.id == dirName
        }

        // Read config.json for metadata
        val configJson = readConfigJson(modelDir)
        val modelName = catalogEntry?.name ?: configJson?.optString("model_name") ?: dirName
        val paramCount = catalogEntry?.parameterCount ?: configJson?.optString("parameter_count") ?: "unknown"
        val quant = catalogEntry?.quantization ?: configJson?.optString("quantization") ?: "unknown"
        val family = catalogEntry?.family ?: inferFamily(modelName)
        val contextWindow = catalogEntry?.contextWindow ?: configJson?.optInt("context_window", 2048) ?: 2048

        // Calculate disk usage
        val diskUsage = modelDir.listFiles()?.sumOf { it.length() } ?: 0L

        // Match against imported models
        val imported = getImportedModels().find { it.directory == modelDir.absolutePath }

        return AvailableModel(
            id = catalogEntry?.id ?: imported?.id ?: "local_${dirName}",
            name = imported?.name ?: modelName,
            parameterCount = imported?.parameterCount ?: paramCount,
            quantization = imported?.quantization ?: quant,
            family = imported?.family ?: family,
            format = "mnn",
            directory = modelDir,
            diskUsageBytes = diskUsage,
            contextWindow = contextWindow,
            catalogEntry = catalogEntry,
            isFromCatalog = catalogEntry != null,
            source = when {
                catalogEntry != null -> catalogEntry.source
                imported != null -> "Custom Import"
                else -> "Local"
            }
        )
    }

    private fun readConfigJson(modelDir: File): JSONObject? {
        val configFile = File(modelDir, "config.json")
        if (!configFile.exists()) return null
        return try {
            JSONObject(configFile.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun inferFamily(name: String): String {
        val lower = name.lowercase()
        return when {
            "qwen" in lower -> "Qwen"
            "llama" in lower -> "Llama"
            "gemma" in lower -> "Gemma"
            "deepseek" in lower -> "DeepSeek"
            "phi" in lower -> "Phi"
            "smol" in lower -> "SmolLM"
            else -> "Other"
        }
    }

    private fun saveImportedModels(models: List<ImportedModelEntry>) {
        val array = JSONArray()
        for (model in models) {
            val obj = JSONObject().apply {
                put("id", model.id)
                put("name", model.name)
                put("directory", model.directory)
                put("parameterCount", model.parameterCount)
                put("quantization", model.quantization)
                put("family", model.family)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_IMPORTED_MODELS, array.toString()).apply()
    }

    companion object {
        private const val TAG = "ModelRepository"
        private const val PREFS_NAME = "tronprotocol_model_prefs"
        private const val KEY_SELECTED_MODEL = "selected_model_id"
        private const val KEY_CONFIG_PREFIX = "model_config_"
        private const val KEY_IMPORTED_MODELS = "imported_models"
    }
}

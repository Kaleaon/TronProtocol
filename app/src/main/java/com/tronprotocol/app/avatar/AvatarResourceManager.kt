package com.tronprotocol.app.avatar

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages download, storage, and integrity verification of MNN TaoAvatar model resources.
 *
 * Model components (NNR, A2BS, TTS, ASR, LLM) are stored under the app's private files
 * directory in `mnn_avatar_models/<component_id>/`. Custom avatars uploaded by the user
 * are stored in `mnn_avatar_custom/`.
 */
class AvatarResourceManager(context: Context) {

    private val appContext = context.applicationContext
    private val modelsBaseDir = File(appContext.filesDir, MODELS_DIR)
    private val customBaseDir = File(appContext.filesDir, CUSTOM_DIR)
    private val configFile = File(appContext.filesDir, CONFIG_FILE)

    private val downloadExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "Avatar-Download").apply { isDaemon = true }
    }
    private val downloadProgress = ConcurrentHashMap<String, DownloadProgress>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    data class DownloadProgress(
        val componentId: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val state: DownloadState
    ) {
        val progressPercent: Float
            get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes) * 100f else 0f
    }

    enum class DownloadState {
        IDLE, QUEUED, DOWNLOADING, COMPLETED, CANCELLED, ERROR
    }

    interface DownloadListener {
        fun onProgress(progress: DownloadProgress)
        fun onComplete(componentId: String, modelDir: File)
        fun onError(componentId: String, error: String)
    }

    init {
        modelsBaseDir.mkdirs()
        customBaseDir.mkdirs()
    }

    /** Check if a specific model component has been downloaded. */
    fun isComponentDownloaded(componentId: String): Boolean {
        val dir = File(modelsBaseDir, sanitizeDirName(componentId))
        return dir.exists() && dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
    }

    /** Get the local directory for a model component. */
    fun getComponentDir(componentId: String): File =
        File(modelsBaseDir, sanitizeDirName(componentId))

    /** Get all downloaded component IDs. */
    fun getDownloadedComponents(): List<String> {
        return modelsBaseDir.listFiles()
            ?.filter { it.isDirectory && it.listFiles()?.isNotEmpty() == true }
            ?.map { it.name }
            ?: emptyList()
    }

    /** Get the custom avatar directory for user uploads. */
    fun getCustomAvatarDir(avatarName: String): File {
        val dir = File(customBaseDir, sanitizeDirName(avatarName))
        dir.mkdirs()
        return dir
    }

    /** List all custom avatar directories. */
    fun getCustomAvatars(): List<File> {
        return customBaseDir.listFiles()
            ?.filter { it.isDirectory }
            ?.toList()
            ?: emptyList()
    }

    /**
     * Download a model component from the catalog.
     *
     * @param entry  The catalog entry for the component to download.
     * @param listener  Callback for download progress and completion.
     */
    fun downloadComponent(
        entry: AvatarModelCatalog.AvatarModelEntry,
        listener: DownloadListener
    ) {
        val cancelFlag = AtomicBoolean(false)
        cancelFlags[entry.id] = cancelFlag

        downloadProgress[entry.id] = DownloadProgress(
            entry.id, 0, entry.sizeEstimateMb * 1024 * 1024, DownloadState.QUEUED
        )

        downloadExecutor.submit {
            val targetDir = getComponentDir(entry.id)
            try {
                targetDir.mkdirs()
                downloadProgress[entry.id] = DownloadProgress(
                    entry.id, 0, entry.sizeEstimateMb * 1024 * 1024, DownloadState.DOWNLOADING
                )

                downloadModelFiles(entry.downloadUrl, targetDir, entry.id, cancelFlag, listener)

                if (cancelFlag.get()) {
                    downloadProgress[entry.id] = DownloadProgress(
                        entry.id, 0, 0, DownloadState.CANCELLED
                    )
                    return@submit
                }

                downloadProgress[entry.id] = DownloadProgress(
                    entry.id,
                    targetDir.walkTopDown().sumOf { it.length() },
                    targetDir.walkTopDown().sumOf { it.length() },
                    DownloadState.COMPLETED
                )
                listener.onComplete(entry.id, targetDir)
                Log.d(TAG, "Download complete: ${entry.id} -> ${targetDir.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${entry.id}: ${e.message}", e)
                downloadProgress[entry.id] = DownloadProgress(
                    entry.id, 0, 0, DownloadState.ERROR
                )
                listener.onError(entry.id, e.message ?: "Unknown download error")
            }
        }
    }

    /** Cancel an in-progress download. */
    fun cancelDownload(componentId: String) {
        cancelFlags[componentId]?.set(true)
    }

    /** Delete a downloaded model component. */
    fun deleteComponent(componentId: String): Boolean {
        val dir = getComponentDir(componentId)
        if (!dir.exists()) return false
        val deleted = dir.deleteRecursively()
        if (deleted) {
            downloadProgress.remove(componentId)
            Log.d(TAG, "Deleted component: $componentId")
        }
        return deleted
    }

    /** Delete a custom avatar. */
    fun deleteCustomAvatar(avatarName: String): Boolean {
        val dir = File(customBaseDir, sanitizeDirName(avatarName))
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }

    /** Get current download progress for a component. */
    fun getProgress(componentId: String): DownloadProgress? = downloadProgress[componentId]

    /**
     * Verify that all required files for a component are present.
     */
    fun verifyComponent(entry: AvatarModelCatalog.AvatarModelEntry): Boolean {
        val dir = getComponentDir(entry.id)
        if (!dir.exists()) return false
        if (entry.requiredFiles.isEmpty()) {
            return dir.listFiles()?.isNotEmpty() == true
        }
        return entry.requiredFiles.all { File(dir, it).exists() }
    }

    /**
     * Compute SHA-256 checksum for a file.
     */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(IO_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    /**
     * Save an AvatarConfig to persistent storage.
     */
    fun saveAvatarConfig(config: AvatarConfig) {
        val configs = loadAllConfigs().toMutableList()
        configs.removeAll { it.avatarId == config.avatarId }
        configs.add(config)

        val jsonArray = JSONArray()
        configs.forEach { jsonArray.put(it.toJson()) }
        configFile.writeText(jsonArray.toString(2))
    }

    /**
     * Load all saved avatar configurations.
     */
    fun loadAllConfigs(): List<AvatarConfig> {
        if (!configFile.exists()) return emptyList()
        return try {
            val jsonArray = JSONArray(configFile.readText())
            (0 until jsonArray.length()).map { AvatarConfig.fromJson(jsonArray.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load avatar configs: ${e.message}")
            emptyList()
        }
    }

    /**
     * Build an AvatarConfig from a preset and the local download directories.
     */
    fun buildConfigFromPreset(preset: AvatarModelCatalog.AvatarPreset): AvatarConfig? {
        val root = modelsBaseDir.absolutePath

        val nnrId = preset.componentIds[AvatarModelCatalog.AvatarComponent.NNR] ?: return null
        val a2bsId = preset.componentIds[AvatarModelCatalog.AvatarComponent.A2BS] ?: return null

        val ttsId = preset.componentIds[AvatarModelCatalog.AvatarComponent.TTS] ?: ""
        val asrId = preset.componentIds[AvatarModelCatalog.AvatarComponent.ASR] ?: ""
        val llmId = preset.componentIds[AvatarModelCatalog.AvatarComponent.LLM] ?: ""

        return AvatarConfig(
            avatarId = preset.id,
            displayName = preset.name,
            rootPath = root,
            nnrModelDir = getComponentDir(nnrId).absolutePath,
            a2bsModelDir = getComponentDir(a2bsId).absolutePath,
            ttsModelDir = if (ttsId.isNotEmpty()) getComponentDir(ttsId).absolutePath else "",
            asrModelDir = if (asrId.isNotEmpty()) getComponentDir(asrId).absolutePath else "",
            llmModelDir = if (llmId.isNotEmpty()) getComponentDir(llmId).absolutePath else ""
        )
    }

    /** Shutdown download threads. */
    fun shutdown() {
        cancelFlags.values.forEach { it.set(true) }
        downloadExecutor.shutdownNow()
    }

    // ---- Internal helpers ----

    private fun downloadModelFiles(
        baseUrl: String,
        targetDir: File,
        componentId: String,
        cancelFlag: AtomicBoolean,
        listener: DownloadListener
    ) {
        // ModelScope URLs serve a directory listing — download by fetching files
        // from the known model structure. For CDN URLs, download directly.
        if (baseUrl.startsWith("https://meta.alicdn.com")) {
            downloadSingleFile(baseUrl, File(targetDir, "model.bin"), componentId, cancelFlag, listener)
            return
        }

        // For ModelScope repos, attempt to download the whole repo as a snapshot
        val snapshotUrl = "$baseUrl/resolve/master"
        val manifestFile = File(targetDir, ".manifest.json")

        // Try fetching a file listing first, fallback to known file patterns
        val filesToDownload = listOf(
            "config.json",
            "model.safetensors",
            "model.mnn",
            "tokenizer.json",
            "tokenizer.txt",
            "compute.nnr",
            "render_full.nnr",
            "background.nnr",
            "input_nnr.json",
            "a2bs.mnn",
            "tts.mnn",
            "vocab.txt",
            "llm.mnn",
            "llm.mnn.weight",
            "llm_config.json"
        )

        var totalDownloaded = 0L
        for (fileName in filesToDownload) {
            if (cancelFlag.get()) return

            val fileUrl = "$snapshotUrl/$fileName"
            val outFile = File(targetDir, fileName)

            try {
                val conn = URL(fileUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = NETWORK_TIMEOUT_MS
                conn.readTimeout = NETWORK_TIMEOUT_MS
                conn.instanceFollowRedirects = true

                val responseCode = conn.responseCode
                if (responseCode == 404) {
                    conn.disconnect()
                    continue // Optional file not present
                }
                if (responseCode !in 200..299) {
                    conn.disconnect()
                    continue
                }

                outFile.parentFile?.mkdirs()
                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(IO_BUFFER_SIZE)
                        while (true) {
                            if (cancelFlag.get()) {
                                conn.disconnect()
                                return
                            }
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            totalDownloaded += read
                        }
                    }
                }
                conn.disconnect()

                downloadProgress[componentId] = DownloadProgress(
                    componentId, totalDownloaded, totalDownloaded, DownloadState.DOWNLOADING
                )
                listener.onProgress(downloadProgress[componentId]!!)
                Log.d(TAG, "Downloaded $fileName (${outFile.length()} bytes)")
            } catch (e: Exception) {
                Log.d(TAG, "Skipped $fileName: ${e.message}")
            }
        }
    }

    private fun downloadSingleFile(
        url: String,
        targetFile: File,
        componentId: String,
        cancelFlag: AtomicBoolean,
        listener: DownloadListener
    ) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = NETWORK_TIMEOUT_MS
        conn.readTimeout = NETWORK_TIMEOUT_MS
        conn.instanceFollowRedirects = true

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            conn.disconnect()
            throw RuntimeException("HTTP $responseCode downloading $url")
        }

        val totalSize = conn.contentLengthLong
        var downloaded = 0L

        targetFile.parentFile?.mkdirs()
        conn.inputStream.use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(IO_BUFFER_SIZE)
                while (true) {
                    if (cancelFlag.get()) {
                        conn.disconnect()
                        return
                    }
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    downloaded += read

                    downloadProgress[componentId] = DownloadProgress(
                        componentId, downloaded, totalSize, DownloadState.DOWNLOADING
                    )
                    listener.onProgress(downloadProgress[componentId]!!)
                }
            }
        }
        conn.disconnect()
    }

    private fun sanitizeDirName(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "unnamed" }

    companion object {
        private const val TAG = "AvatarResourceManager"
        private const val MODELS_DIR = "mnn_avatar_models"
        private const val CUSTOM_DIR = "mnn_avatar_custom"
        private const val CONFIG_FILE = "avatar_configs.json"
        private const val IO_BUFFER_SIZE = 8 * 1024
        private const val NETWORK_TIMEOUT_MS = 60_000
    }
}

package com.tronprotocol.app.llm

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * Model download manager with progress tracking, resume, pause, and cancel support.
 *
 * Inspired by LLM-Hub's ModelDownloader, adapted for TronProtocol's MNN-based
 * model packaging (ZIP archives containing llm.mnn, config.json, tokenizer.txt, etc.).
 *
 * @see <a href="https://github.com/timmyy123/LLM-Hub">LLM-Hub</a>
 */
class ModelDownloadManager(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val downloadExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "ModelDownload-${System.nanoTime()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val activeDownloads = ConcurrentHashMap<String, DownloadTask>()

    /** Get the stored HuggingFace token, or null if not set. */
    fun getHuggingFaceToken(): String? = prefs.getString(KEY_HF_TOKEN, null)

    /** Set or clear the HuggingFace API token for downloading gated models. */
    fun setHuggingFaceToken(token: String?) {
        if (token.isNullOrBlank()) {
            prefs.edit().remove(KEY_HF_TOKEN).apply()
        } else {
            prefs.edit().putString(KEY_HF_TOKEN, token.trim()).apply()
        }
        Log.d(TAG, "HF token ${if (token.isNullOrBlank()) "cleared" else "set (${token.take(8)}...)"}")
    }

    /** Current state of a download. */
    enum class DownloadState {
        IDLE, QUEUED, DOWNLOADING, EXTRACTING, COMPLETED, PAUSED, CANCELLED, ERROR
    }

    /** Progress snapshot for a download. */
    data class DownloadProgress(
        val modelId: String,
        val state: DownloadState,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
        val progressFraction: Float,
        val errorMessage: String? = null
    ) {
        val progressPercent: Int get() = (progressFraction * 100).toInt()
        val isTerminal: Boolean get() = state in setOf(DownloadState.COMPLETED, DownloadState.CANCELLED, DownloadState.ERROR)
    }

    /** Callback interface for download progress updates. */
    fun interface DownloadListener {
        fun onProgress(progress: DownloadProgress)
    }

    private class DownloadTask(
        val modelId: String,
        val catalogEntry: ModelCatalog.CatalogEntry,
        val listener: DownloadListener?,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val paused: AtomicBoolean = AtomicBoolean(false),
        var future: Future<*>? = null
    )

    /** Get the models directory where downloads are stored. */
    fun getModelsBaseDir(): File {
        val dir = File(appContext.filesDir, "mnn_models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Get the directory for a specific model. */
    fun getModelDir(modelId: String): File {
        val safeName = modelId.replace(Regex("[^a-z0-9._-]"), "_")
        return File(getModelsBaseDir(), safeName)
    }

    /**
     * Start downloading a model from the catalog.
     * Supports resume if a partial download exists.
     */
    fun downloadModel(
        entry: ModelCatalog.CatalogEntry,
        listener: DownloadListener? = null
    ): Boolean {
        val existing = activeDownloads[entry.id]
        if (existing != null) {
            if (!existing.cancelled.get() && !existing.paused.get()) {
                Log.w(TAG, "Download already active for ${entry.id}")
                return false
            }
        }

        val task = DownloadTask(entry.id, entry, listener)
        activeDownloads[entry.id] = task

        task.future = downloadExecutor.submit {
            executeDownload(task)
        }

        return true
    }

    /** Pause an active download. The partial file is preserved for resume. */
    fun pauseDownload(modelId: String) {
        val task = activeDownloads[modelId] ?: return
        task.paused.set(true)
        Log.d(TAG, "Pausing download: $modelId")
    }

    /** Resume a paused download. */
    fun resumeDownload(modelId: String, listener: DownloadListener? = null): Boolean {
        val entry = ModelCatalog.findById(modelId) ?: return false
        return downloadModel(entry, listener)
    }

    /** Cancel and remove a download. Deletes partial files. */
    fun cancelDownload(modelId: String) {
        val task = activeDownloads.remove(modelId)
        if (task != null) {
            task.cancelled.set(true)
            task.future?.cancel(true)
            Log.d(TAG, "Cancelled download: $modelId")
        }

        // Clean up partial files
        val modelDir = getModelDir(modelId)
        val tempFile = File(appContext.cacheDir, "dl_${modelId}.tmp")
        tempFile.delete()
        // Don't delete modelDir here as it might have a valid extracted model
    }

    /** Delete a downloaded model from disk. */
    fun deleteModel(modelId: String): Boolean {
        cancelDownload(modelId)
        val modelDir = getModelDir(modelId)
        if (modelDir.exists()) {
            val deleted = modelDir.deleteRecursively()
            Log.d(TAG, "Deleted model directory $modelId: $deleted")
            return deleted
        }
        return false
    }

    /** Check if a model is currently downloading. */
    fun isDownloading(modelId: String): Boolean {
        val task = activeDownloads[modelId] ?: return false
        return !task.cancelled.get() && !task.paused.get()
    }

    /** Get progress for an active download. */
    fun getProgress(modelId: String): DownloadProgress? {
        val task = activeDownloads[modelId] ?: return null
        return DownloadProgress(
            modelId = modelId,
            state = when {
                task.cancelled.get() -> DownloadState.CANCELLED
                task.paused.get() -> DownloadState.PAUSED
                else -> DownloadState.DOWNLOADING
            },
            downloadedBytes = 0,
            totalBytes = task.catalogEntry.sizeBytes,
            speedBytesPerSec = 0,
            progressFraction = 0f
        )
    }

    /** Check if a model's files are fully downloaded and valid. */
    fun isModelDownloaded(modelId: String): Boolean {
        val modelDir = getModelDir(modelId)
        return modelDir.exists() && File(modelDir, "llm.mnn").exists()
    }

    /** Get all downloaded model IDs. */
    fun getDownloadedModelIds(): List<String> {
        val baseDir = getModelsBaseDir()
        if (!baseDir.exists()) return emptyList()
        return baseDir.listFiles()
            ?.filter { it.isDirectory && File(it, "llm.mnn").exists() }
            ?.map { it.name }
            .orEmpty()
    }

    /** Get disk usage for a downloaded model in bytes. */
    fun getModelDiskUsage(modelId: String): Long {
        val modelDir = getModelDir(modelId)
        if (!modelDir.exists()) return 0
        return modelDir.listFiles()?.sumOf { it.length() } ?: 0
    }

    /**
     * Download a model from a custom URL (not from the catalog).
     * Creates a synthetic catalog entry for tracking.
     */
    fun downloadFromUrl(
        modelId: String,
        modelName: String,
        downloadUrl: String,
        listener: DownloadListener? = null
    ): Boolean {
        val syntheticEntry = ModelCatalog.CatalogEntry(
            id = modelId,
            name = modelName,
            description = "Custom model downloaded from URL",
            family = "Custom",
            parameterCount = "unknown",
            quantization = "unknown",
            format = "mnn",
            downloadUrl = downloadUrl,
            sizeBytes = 0L,
            contextWindow = 2048,
            ramRequirement = ModelCatalog.RamRequirement(minRamMb = 2048, recommendedRamMb = 4096),
            supportsGpu = true,
            source = "Custom URL"
        )
        return downloadModel(syntheticEntry, listener)
    }

    /**
     * Import a model from a local directory path.
     * Validates required files and registers it.
     */
    fun importLocalModel(modelId: String, sourceDir: File): Boolean {
        if (!sourceDir.exists() || !sourceDir.isDirectory) {
            Log.w(TAG, "Import source directory does not exist: ${sourceDir.absolutePath}")
            return false
        }

        if (!File(sourceDir, "llm.mnn").exists()) {
            Log.w(TAG, "Import directory missing llm.mnn: ${sourceDir.absolutePath}")
            return false
        }

        val targetDir = getModelDir(modelId)
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        // Copy all files from source to target
        sourceDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.copyTo(File(targetDir, file.name), overwrite = true)
            }
        }

        val success = File(targetDir, "llm.mnn").exists()
        Log.d(TAG, "Imported model $modelId from ${sourceDir.absolutePath}: $success")
        return success
    }

    /** Shutdown the download manager and cancel all active downloads. */
    fun shutdown() {
        activeDownloads.keys.toList().forEach { cancelDownload(it) }
        downloadExecutor.shutdownNow()
        Log.d(TAG, "ModelDownloadManager shut down")
    }

    // ---- Internal download logic ----

    private fun executeDownload(task: DownloadTask) {
        val entry = task.catalogEntry
        val modelDir = getModelDir(entry.id)

        try {
            emitProgress(task, DownloadState.QUEUED, 0, entry.sizeBytes, 0)

            // If model is already fully downloaded, skip
            if (modelDir.exists() && File(modelDir, "llm.mnn").exists()) {
                Log.d(TAG, "Model already downloaded: ${entry.id}")
                emitProgress(task, DownloadState.COMPLETED, entry.sizeBytes, entry.sizeBytes, 0)
                activeDownloads.remove(entry.id)
                return
            }

            if (entry.modelFiles.isNotEmpty()) {
                // Multi-file download: fetch individual files from HuggingFace repo
                executeMultiFileDownload(task, modelDir)
            } else {
                // Legacy single-file download (ZIP or raw model file)
                executeSingleFileDownload(task, modelDir)
            }
        } catch (e: InterruptedException) {
            emitProgress(task, DownloadState.CANCELLED, 0, entry.sizeBytes, 0)
            Log.d(TAG, "Download interrupted: ${entry.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${entry.id}: ${e.message}", e)
            emitProgress(task, DownloadState.ERROR, 0, entry.sizeBytes, 0, e.message)
        } finally {
            activeDownloads.remove(entry.id)
        }
    }

    /**
     * Download individual model files from a HuggingFace repo.
     * Each file in [ModelCatalog.CatalogEntry.modelFiles] is fetched from the base
     * [ModelCatalog.CatalogEntry.downloadUrl] and saved directly to the model directory.
     * Files that return HTTP 404 are silently skipped (they may be optional).
     * The download fails only if the required `llm.mnn` file is missing afterward.
     */
    private fun executeMultiFileDownload(task: DownloadTask, modelDir: File) {
        val entry = task.catalogEntry
        val baseUrl = entry.downloadUrl.trimEnd('/')

        if (modelDir.exists()) modelDir.deleteRecursively()
        modelDir.mkdirs()

        var totalDownloaded = 0L
        val totalExpected = entry.sizeBytes
        val startTime = System.currentTimeMillis()
        val skippedFiles = mutableListOf<String>()

        for (fileName in entry.modelFiles) {
            if (task.cancelled.get() || Thread.currentThread().isInterrupted) {
                emitProgress(task, DownloadState.CANCELLED, totalDownloaded, totalExpected, 0)
                return
            }
            if (task.paused.get()) {
                emitProgress(task, DownloadState.PAUSED, totalDownloaded, totalExpected, 0)
                return
            }

            val fileUrl = "$baseUrl/$fileName"
            val outputFile = File(modelDir, fileName)

            try {
                val bytesDownloaded = downloadSingleFile(task, fileUrl, outputFile, totalDownloaded, totalExpected)
                if (bytesDownloaded < 0) {
                    // HTTP 404 — file does not exist in this repo, skip it
                    skippedFiles.add(fileName)
                    Log.d(TAG, "Skipped optional file (404): $fileName")
                    continue
                }
                totalDownloaded += bytesDownloaded
            } catch (e: Exception) {
                // If a required file fails, re-throw
                if (fileName == "llm.mnn" || fileName == "llm.mnn.weight") {
                    throw RuntimeException("Failed to download required file $fileName: ${e.message}", e)
                }
                // Optional file failed — log and continue
                skippedFiles.add(fileName)
                Log.w(TAG, "Failed to download optional file $fileName: ${e.message}")
            }
        }

        if (task.cancelled.get()) {
            emitProgress(task, DownloadState.CANCELLED, totalDownloaded, totalExpected, 0)
            return
        }

        // Verify required files
        if (!File(modelDir, "llm.mnn").exists()) {
            throw RuntimeException("Download did not produce required file llm.mnn")
        }

        if (skippedFiles.isNotEmpty()) {
            Log.d(TAG, "Skipped ${skippedFiles.size} optional files: $skippedFiles")
        }

        emitProgress(task, DownloadState.COMPLETED, totalDownloaded, totalExpected, 0)
        Log.d(TAG, "Multi-file download complete: ${entry.name} -> ${modelDir.absolutePath} " +
                "(${entry.modelFiles.size - skippedFiles.size}/${entry.modelFiles.size} files)")
    }

    /**
     * Download a single file from a URL directly to the output file.
     * Returns the number of bytes downloaded, or -1 if the server returned HTTP 404.
     */
    private fun downloadSingleFile(
        task: DownloadTask,
        fileUrl: String,
        outputFile: File,
        previouslyDownloaded: Long,
        totalExpected: Long
    ): Long {
        val connection = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            getHuggingFaceToken()?.let { token ->
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        try {
            val responseCode = connection.responseCode

            if (responseCode == 404) {
                return -1
            }

            if (responseCode == 401 || responseCode == 403) {
                throw RuntimeException(
                    "HTTP $responseCode: Access denied. " +
                    if (responseCode == 401) "Set a HuggingFace token for gated models."
                    else "This model requires authorization."
                )
            }

            if (responseCode !in 200..299) {
                throw RuntimeException("HTTP $responseCode from ${connection.url}")
            }

            var fileBytes = 0L
            var lastEmitTime = System.currentTimeMillis()
            var bytesSinceLastEmit = 0L

            outputFile.parentFile?.mkdirs()

            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    while (true) {
                        if (task.cancelled.get() || Thread.currentThread().isInterrupted) break
                        if (task.paused.get()) break

                        val read = input.read(buffer)
                        if (read <= 0) break

                        output.write(buffer, 0, read)
                        fileBytes += read
                        bytesSinceLastEmit += read

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastEmitTime
                        if (elapsed >= PROGRESS_INTERVAL_MS) {
                            val speed = if (elapsed > 0) (bytesSinceLastEmit * 1000 / elapsed) else 0L
                            emitProgress(
                                task, DownloadState.DOWNLOADING,
                                previouslyDownloaded + fileBytes, totalExpected, speed
                            )
                            lastEmitTime = now
                            bytesSinceLastEmit = 0L
                        }
                    }
                }
            }

            return fileBytes
        } finally {
            connection.disconnect()
        }
    }

    /** Legacy single-file download path for ZIP archives or raw model files. */
    private fun executeSingleFileDownload(task: DownloadTask, modelDir: File) {
        val entry = task.catalogEntry
        val tempFile = File(appContext.cacheDir, "dl_${entry.id}.tmp")

        try {
            val downloadedBytes = downloadFile(task, entry.downloadUrl, tempFile)
            if (task.cancelled.get()) {
                tempFile.delete()
                emitProgress(task, DownloadState.CANCELLED, 0, entry.sizeBytes, 0)
                return
            }
            if (task.paused.get()) {
                emitProgress(task, DownloadState.PAUSED, downloadedBytes, entry.sizeBytes, 0)
                return
            }

            // Extraction phase
            emitProgress(task, DownloadState.EXTRACTING, downloadedBytes, entry.sizeBytes, 0)

            if (modelDir.exists()) modelDir.deleteRecursively()
            modelDir.mkdirs()

            if (entry.downloadUrl.lowercase().endsWith(".zip") || isZipFile(tempFile)) {
                extractZip(tempFile, modelDir)
            } else {
                // Single file — copy as llm.mnn
                tempFile.copyTo(File(modelDir, "llm.mnn"), overwrite = true)
            }

            // Handle nested directory from ZIP (some archives have a single subdirectory)
            flattenSingleSubdirectory(modelDir)

            tempFile.delete()

            // Verify extraction produced required files
            if (!File(modelDir, "llm.mnn").exists()) {
                throw RuntimeException("Extracted archive does not contain llm.mnn")
            }

            emitProgress(task, DownloadState.COMPLETED, entry.sizeBytes, entry.sizeBytes, 0)
            Log.d(TAG, "Download complete: ${entry.name} -> ${modelDir.absolutePath}")
        } finally {
            tempFile.delete()
        }
    }

    private fun downloadFile(task: DownloadTask, downloadUrl: String, outputFile: File): Long {
        var downloadedBytes = if (outputFile.exists()) outputFile.length() else 0L
        var totalBytes = task.catalogEntry.sizeBytes

        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            if (downloadedBytes > 0) {
                setRequestProperty("Range", "bytes=$downloadedBytes-")
            }
            // Add HuggingFace token for gated model downloads
            getHuggingFaceToken()?.let { token ->
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        try {
            val responseCode = connection.responseCode

            // 416 = Range Not Satisfiable — file already complete
            if (responseCode == 416) {
                Log.d(TAG, "HTTP 416: file already complete at $downloadedBytes bytes")
                return downloadedBytes
            }

            if (responseCode == 401 || responseCode == 403) {
                throw RuntimeException(
                    "HTTP $responseCode: Access denied. " +
                    if (responseCode == 401) "Set a HuggingFace token for gated models."
                    else "This model requires authorization."
                )
            }

            if (responseCode == 404) {
                throw RuntimeException(
                    "HTTP 404: File not found at ${connection.url}. " +
                    "The model may have been moved or removed from HuggingFace."
                )
            }

            if (responseCode !in 200..299 && responseCode != 206) {
                throw RuntimeException("HTTP $responseCode from ${connection.url}")
            }

            // Parse Content-Range for total size
            val contentRange = connection.getHeaderField("Content-Range")
            val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull()
            if (contentRange != null) {
                val total = contentRange.substringAfter("/").toLongOrNull()
                if (total != null && total > 0) totalBytes = total
            } else if (contentLength != null && contentLength > 0) {
                totalBytes = if (downloadedBytes > 0 && responseCode == 206) {
                    downloadedBytes + contentLength
                } else {
                    contentLength
                }
            }

            // If server doesn't support resume, restart
            if (downloadedBytes > 0 && responseCode == 200) {
                Log.w(TAG, "Server doesn't support resume. Restarting download.")
                if (outputFile.exists()) outputFile.delete()
                downloadedBytes = 0L
            }

            var lastEmitTime = System.currentTimeMillis()
            var bytesSinceLastEmit = 0L

            connection.inputStream.use { input ->
                RandomAccessFile(outputFile, "rw").use { raf ->
                    raf.seek(downloadedBytes)
                    val buffer = ByteArray(IO_BUFFER_SIZE)

                    while (true) {
                        if (task.cancelled.get() || Thread.currentThread().isInterrupted) break
                        if (task.paused.get()) break

                        val read = input.read(buffer)
                        if (read <= 0) break

                        raf.write(buffer, 0, read)
                        downloadedBytes += read
                        bytesSinceLastEmit += read

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastEmitTime
                        if (elapsed >= PROGRESS_INTERVAL_MS) {
                            val speed = if (elapsed > 0) (bytesSinceLastEmit * 1000 / elapsed) else 0L
                            emitProgress(task, DownloadState.DOWNLOADING, downloadedBytes, totalBytes, speed)
                            lastEmitTime = now
                            bytesSinceLastEmit = 0L
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        return downloadedBytes
    }

    private fun extractZip(zipFile: File, destinationDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zipInput ->
            var entry = zipInput.nextEntry
            while (entry != null) {
                val outputFile = File(destinationDir, entry.name)
                // Prevent zip-slip
                if (!outputFile.canonicalPath.startsWith(destinationDir.canonicalPath + File.separator)) {
                    throw SecurityException("Blocked zip-slip entry: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { output ->
                        zipInput.copyTo(output)
                    }
                }
                zipInput.closeEntry()
                entry = zipInput.nextEntry
            }
        }
    }

    /**
     * If the extracted directory contains exactly one subdirectory and no files,
     * move all contents up one level. This handles ZIP archives that wrap
     * everything in a top-level folder.
     */
    private fun flattenSingleSubdirectory(dir: File) {
        val children = dir.listFiles() ?: return
        if (children.size == 1 && children[0].isDirectory) {
            val subDir = children[0]
            val subFiles = subDir.listFiles() ?: return
            for (file in subFiles) {
                file.renameTo(File(dir, file.name))
            }
            subDir.delete()
            Log.d(TAG, "Flattened single subdirectory: ${subDir.name}")
        }
    }

    private fun isZipFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            // PK\x03\x04 or PK\x05\x06
            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        } catch (_: Exception) {
            false
        }
    }

    private fun emitProgress(
        task: DownloadTask,
        state: DownloadState,
        downloadedBytes: Long,
        totalBytes: Long,
        speed: Long,
        error: String? = null
    ) {
        val fraction = if (totalBytes > 0) {
            (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else 0f

        val progress = DownloadProgress(
            modelId = task.modelId,
            state = state,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            speedBytesPerSec = speed,
            progressFraction = fraction,
            errorMessage = error
        )

        try {
            task.listener?.onProgress(progress)
        } catch (e: Exception) {
            Log.w(TAG, "Listener error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ModelDownloadManager"
        private const val PREFS_NAME = "tronprotocol_download_prefs"
        private const val KEY_HF_TOKEN = "huggingface_token"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val IO_BUFFER_SIZE = 8 * 1024
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val USER_AGENT = "TronProtocol/1.0 (Android)"
    }
}

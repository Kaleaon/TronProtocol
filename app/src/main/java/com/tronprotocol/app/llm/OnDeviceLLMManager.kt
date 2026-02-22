package com.tronprotocol.app.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.tronprotocol.app.security.AuditLogger
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/**
 * On-Device LLM Manager — MNN-powered local inference for small language models.
 *
 * Integrates Alibaba's MNN (Mobile Neural Network) framework for running quantized
 * LLMs (up to ~4B parameters) directly on Android hardware. This enables:
 * - Offline inference when cloud API is unavailable
 * - Low-latency responses for simple/medium queries
 * - Privacy-preserving local processing
 * - Fallback when Anthropic API quota is exhausted
 *
 * Recommended models (from MNN Chat / makeuseof.com guidance):
 * - Qwen2.5-1.5B-Instruct (Q4): ~1GB RAM, fast on most devices
 * - Qwen3-1.7B (Q4): ~1.2GB RAM, good quality
 * - Gemma-2B (Q4): ~1.5GB RAM, balanced
 * - Qwen2.5-3B-Instruct (Q4): ~2GB RAM, higher quality
 *
 * MNN achieves 8.6x faster prefill and 2.3x faster decode than llama.cpp
 * on mobile ARM processors through optimized NEON/FP16 kernels.
 *
 * Architecture:
 * - Uses MNN's native C++ LLM engine via JNI bridge
 * - Supports CPU (ARM NEON/FP16), OpenCL GPU, and Qualcomm QNN backends
 * - Memory-mapped weights (use_mmap) for constrained devices
 * - Graceful degradation when native libraries are unavailable
 *
 * @see [MNN GitHub](https://github.com/alibaba/MNN)
 * @see [MNN-LLM Paper](https://arxiv.org/html/2506.10443v1)
 */
class OnDeviceLLMManager(
    context: Context,
    private val auditLogger: AuditLogger? = null,
    private val integrityVerifier: ModelIntegrityVerifier = ModelIntegrityVerifier()
) {

    private val context: Context = context.applicationContext
    private val inferenceExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MNN-LLM-Inference").apply {
            priority = Thread.NORM_PRIORITY + 1
        }
    }
    private val currentModelState = AtomicReference(ModelState.UNINITIALIZED)
    private val statsMap = ConcurrentHashMap<String, Any>()

    var activeConfig: LLMModelConfig? = null
        private set

    private var nativeSessionHandle: Long = 0

    /** Derived from currentModelState; kept in sync for fast-path checks. */
    private val isModelLoaded: Boolean
        get() = currentModelState.get() == ModelState.READY ||
                currentModelState.get() == ModelState.GENERATING

    // Performance tracking
    private var totalInferences: Long = 0
    private var totalTokensGenerated: Long = 0
    private var totalInferenceTimeMs: Long = 0
    private var lastInferenceTimeMs: Long = 0

    /** Model lifecycle states. */
    enum class ModelState {
        UNINITIALIZED, CHECKING_DEVICE, DOWNLOADING, LOADING,
        READY, GENERATING, ERROR, UNLOADED
    }

    /** Result of an LLM generation request. */
    class GenerationResult private constructor(
        val success: Boolean,
        val text: String?,
        val tokensGenerated: Int,
        val latencyMs: Long,
        val tokensPerSecond: Float,
        val error: String?,
        val modelId: String?
    ) {
        companion object {
            fun success(text: String, tokens: Int, latencyMs: Long, tps: Float, modelId: String) =
                GenerationResult(true, text, tokens, latencyMs, tps, null, modelId)

            fun error(error: String) =
                GenerationResult(false, null, 0, 0, 0f, error, null)
        }
    }

    /** Result of downloading and initializing a model package. */
    data class ModelSetupResult(
        val success: Boolean,
        val config: LLMModelConfig?,
        val modelDirectory: File?,
        val downloadedBytes: Long,
        val error: String?
    )

    /** Device capability assessment for LLM inference. */
    data class DeviceCapability(
        val totalRamMb: Long,
        val availableRamMb: Long,
        val cpuArch: String,
        val supportsArm64: Boolean,
        val supportsFp16: Boolean,
        val hasGpu: Boolean,
        val recommendedThreads: Int,
        val maxModelSizeMb: Long,
        val recommendedModel: String,
        val canRunLLM: Boolean,
        val reason: String
    )

    /** Check if a model is loaded and ready for inference. */
    val isReady: Boolean
        get() = isModelLoaded && currentModelState.get() == ModelState.READY

    /** Get the current model state. */
    val modelState: ModelState
        get() = currentModelState.get()

    /** Assess this device's capability for on-device LLM inference. */
    fun assessDevice(): DeviceCapability {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val availableRamMb = memInfo.availMem / (1024 * 1024)

        val cpuArch = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "unknown"
        val supportsArm64 = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

        // ARMv8.2+ supports FP16 natively (most devices from 2018+)
        val supportsFp16 = supportsArm64 && Build.VERSION.SDK_INT >= 28

        // Estimate GPU availability (OpenCL) — conservative check
        val hasGpu = supportsArm64

        // Thread recommendation: use big cores, cap at 4 for battery
        val recommendedThreads = minOf(Runtime.getRuntime().availableProcessors(), DEFAULT_THREAD_COUNT)

        // Model size recommendation based on available RAM
        val (maxModelSizeMb, recommendedModel, canRunLLM, baseReason) = when {
            !supportsArm64 -> Quad(
                0L, "none", false,
                "Device does not support arm64-v8a — MNN LLM requires 64-bit ARM"
            )
            availableRamMb < MIN_RAM_MB -> Quad(
                0L, "none", false,
                "Insufficient RAM: ${availableRamMb}MB available, ${MIN_RAM_MB}MB required minimum"
            )
            availableRamMb < RECOMMENDED_RAM_MB -> Quad(
                1500L, "Qwen2.5-1.5B-Instruct-Q4", true,
                "Limited RAM — recommend 1.5B parameter model with Q4 quantization"
            )
            availableRamMb < LARGE_MODEL_RAM_MB -> Quad(
                2500L, "Qwen3-1.7B-Q4", true,
                "Moderate RAM — can run up to 1.7B parameter model comfortably"
            )
            else -> Quad(
                4000L, "Qwen2.5-3B-Instruct-Q4", true,
                "Good RAM — can run up to 3B+ parameter models"
            )
        }

        val reason = if (canRunLLM && !nativeAvailable.get())
            "$baseReason (MNN native libraries not installed — build from github.com/alibaba/MNN)"
        else baseReason

        return DeviceCapability(
            totalRamMb, availableRamMb, cpuArch,
            supportsArm64, supportsFp16, hasGpu,
            recommendedThreads, maxModelSizeMb,
            recommendedModel, canRunLLM, reason
        )
    }

    /**
     * Load an LLM model from the specified configuration.
     * The model directory must contain MNN-exported files:
     * llm.mnn, llm.mnn.weight, config.json, llm_config.json, tokenizer.txt
     *
     * @return true if model loaded successfully
     */
    fun loadModel(config: LLMModelConfig): Boolean {
        if (!nativeAvailable.get()) {
            Log.e(TAG, "Cannot load model — MNN native libraries not available")
            currentModelState.set(ModelState.ERROR)
            return false
        }

        val modelDir = File(config.modelPath)
        if (!modelDir.exists() || !modelDir.isDirectory) {
            Log.e(TAG, "Model directory does not exist: ${config.modelPath}")
            currentModelState.set(ModelState.ERROR)
            return false
        }

        if (!File(modelDir, "llm.mnn").exists()) {
            Log.e(TAG, "llm.mnn not found in ${config.modelPath}")
            currentModelState.set(ModelState.ERROR)
            return false
        }

        if (!verifyModelIntegrity(config, "load_model")) {
            currentModelState.set(ModelState.ERROR)
            return false
        }

        // Unload any existing model
        if (isModelLoaded) {
            unloadModel()
        }

        currentModelState.set(ModelState.LOADING)
        Log.d(TAG, "Loading model: ${config.modelName} from ${config.modelPath} " +
                "backend=${config.backend} threads=${config.threadCount}")

        return try {
            val handle = nativeCreateSession(
                config.modelPath, config.backend, config.threadCount, config.useMmap
            )

            if (handle == 0L) {
                Log.e(TAG, "nativeCreateSession returned null handle")
                currentModelState.set(ModelState.ERROR)
                return false
            }

            nativeSessionHandle = handle
            activeConfig = config
            currentModelState.set(ModelState.READY)

            Log.d(TAG, "Model loaded successfully: ${config.modelName} " +
                    "(${config.parameterCount} params, ${config.quantization} quantization)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}", e)
            currentModelState.set(ModelState.ERROR)
            false
        }
    }

    /**
     * Generate text using the loaded on-device LLM.
     *
     * @param prompt    Input prompt
     * @param maxTokens Maximum tokens to generate (0 = use config default)
     * @return [GenerationResult] with the generated text and performance metrics
     */
    @JvmOverloads
    fun generate(prompt: String, maxTokens: Int = 0): GenerationResult {
        if (!isModelLoaded || nativeSessionHandle == 0L) {
            return GenerationResult.error("No model loaded — call loadModel() first")
        }
        if (prompt.isBlank()) {
            return GenerationResult.error("Prompt is empty")
        }

        val config = activeConfig
        if (config == null || !verifyModelIntegrity(config, "generate")) {
            currentModelState.set(ModelState.ERROR)
            return GenerationResult.error("Model integrity verification failed")
        }

        currentModelState.set(ModelState.GENERATING)
        val startTime = System.currentTimeMillis()

        return try {
            val tokens = if (maxTokens > 0) maxTokens
            else activeConfig?.maxTokens ?: DEFAULT_MAX_TOKENS
            val temp = activeConfig?.temperature ?: DEFAULT_TEMPERATURE
            val topP = activeConfig?.topP ?: DEFAULT_TOP_P

            val result = nativeGenerate(nativeSessionHandle, prompt, tokens, temp, topP)
            val latencyMs = System.currentTimeMillis() - startTime

            if (result.isNullOrEmpty()) {
                currentModelState.set(ModelState.READY)
                return GenerationResult.error("Model returned empty response")
            }

            val tokenCount = nativeGetLastTokenCount(nativeSessionHandle)
            val tps = if (tokenCount > 0 && latencyMs > 0)
                (tokenCount * 1000f) / latencyMs else 0f

            // Update stats
            totalInferences++
            totalTokensGenerated += tokenCount
            totalInferenceTimeMs += latencyMs
            lastInferenceTimeMs = latencyMs
            updateStats()

            currentModelState.set(ModelState.READY)

            val modelId = activeConfig?.modelId ?: "unknown"
            Log.d(TAG, "Generated $tokenCount tokens in ${latencyMs}ms " +
                    "(${String.format("%.1f", tps)} tok/s) model=$modelId")

            GenerationResult.success(result, tokenCount, latencyMs, tps, modelId)
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            Log.e(TAG, "Generation failed after ${latencyMs}ms: ${e.message}", e)
            currentModelState.set(ModelState.READY)
            GenerationResult.error("Generation failed: ${e.message}")
        }
    }

    /** Generate text asynchronously, returning a [Future]. */
    fun generateAsync(prompt: String, maxTokens: Int = 0): Future<GenerationResult> =
        inferenceExecutor.submit<GenerationResult> { generate(prompt, maxTokens) }

    /** Unload the current model and free native memory. */
    fun unloadModel() {
        if (nativeSessionHandle != 0L && nativeAvailable.get()) {
            try {
                nativeDestroySession(nativeSessionHandle)
                Log.d(TAG, "Model unloaded: ${activeConfig?.modelName ?: "unknown"}")
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying native session: ${e.message}", e)
            }
        }
        nativeSessionHandle = 0
        activeConfig = null
        currentModelState.set(ModelState.UNLOADED)
    }

    /** Run a quick benchmark to measure inference performance. */
    fun benchmark(): Map<String, Any> {
        val results = ConcurrentHashMap<String, Any>()

        if (!isModelLoaded) {
            results["error"] = "No model loaded"
            return results
        }

        val benchmarkPrompt = "Explain in one sentence what artificial intelligence is."
        val start = System.currentTimeMillis()
        val result = generate(benchmarkPrompt, 64)
        val elapsed = System.currentTimeMillis() - start

        results["model"] = activeConfig?.modelName ?: "unknown"
        results["backend"] = activeConfig?.backendName ?: "cpu"
        results["success"] = result.success
        results["latency_ms"] = elapsed
        results["tokens_generated"] = result.tokensGenerated
        results["tokens_per_second"] = result.tokensPerSecond

        if (nativeAvailable.get() && nativeSessionHandle != 0L) {
            try {
                val nativeStats = nativeGetStats(nativeSessionHandle)
                if (nativeStats != null) {
                    results["native_stats"] = nativeStats
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not retrieve native stats: ${e.message}")
            }
        }

        return results
    }

    /** Get cumulative inference statistics. */
    fun getStats(): Map<String, Any> {
        updateStats()
        return ConcurrentHashMap(statsMap)
    }

    /** Shutdown the manager and release all resources. */
    fun shutdown() {
        unloadModel()
        inferenceExecutor.shutdownNow()
        Log.d(TAG, "OnDeviceLLMManager shut down")
    }

    /**
     * Discover model directories in the standard locations.
     * Scans: app files dir, external files dir, and /sdcard/mnn_models/.
     */
    fun discoverModels(): List<File> {
        val found = mutableListOf<File>()

        // Internal app files
        scanModelDir(File(context.filesDir, "mnn_models"), found)

        // External app files
        context.getExternalFilesDir(null)?.let { extDir ->
            scanModelDir(File(extDir, "mnn_models"), found)
        }

        // Well-known SD card path (used by MNN Chat for ADB push)
        scanModelDir(File("/sdcard/mnn_models"), found)

        Log.d(TAG, "Discovered ${found.size} model directories")
        return found
    }

    /**
     * Create a default [LLMModelConfig] from a discovered model directory.
     * Reads config.json if present.
     */
    fun createConfigFromDirectory(modelDir: File): LLMModelConfig {
        val modelName = modelDir.name
        val modelPath = modelDir.absolutePath

        // Attempt to read config.json for backend/thread settings
        var backend = BACKEND_CPU
        var threads = DEFAULT_THREAD_COUNT
        var useMmap = false

        val configFile = File(modelDir, "config.json")
        if (configFile.exists()) {
            try {
                val data = FileInputStream(configFile).use { it.readBytes() }
                val config = JSONObject(String(data))

                backend = when (config.optString("backend_type", "cpu").lowercase()) {
                    "opencl" -> BACKEND_OPENCL
                    "vulkan" -> BACKEND_VULKAN
                    else -> BACKEND_CPU
                }
                threads = config.optInt("thread_num", DEFAULT_THREAD_COUNT)
                useMmap = config.optBoolean("use_mmap", false)
            } catch (e: Exception) {
                Log.d(TAG, "Could not parse config.json: ${e.message}")
            }
        }

        return LLMModelConfig.Builder(modelName, modelPath)
            .setBackend(backend)
            .setThreadCount(threads)
            .setUseMmap(useMmap)
            .markMigratedWithoutChecksums()
            .build()
    }

    /**
     * Download a model archive and initialize it for inference.
     *
     * Expected archive format: zip containing llm.mnn and supporting files.
     */
    fun downloadAndInitializeModel(modelName: String, downloadUrl: String): ModelSetupResult {
        if (modelName.isBlank()) {
            return ModelSetupResult(false, null, null, 0, "Model name is required")
        }
        if (downloadUrl.isBlank()) {
            return ModelSetupResult(false, null, null, 0, "Download URL is required")
        }

        currentModelState.set(ModelState.DOWNLOADING)
        val safeDirName = modelName.lowercase()
            .replace(Regex("[^a-z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "downloaded_model" }

        val modelDir = File(File(context.filesDir, "mnn_models"), safeDirName)
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
        modelDir.mkdirs()

        val downloadedBytes = try {
            downloadToDirectory(downloadUrl, modelDir)
        } catch (e: Exception) {
            currentModelState.set(ModelState.ERROR)
            return ModelSetupResult(false, null, modelDir, 0, "Download failed: ${e.message}")
        }

        if (!File(modelDir, "llm.mnn").exists()) {
            currentModelState.set(ModelState.ERROR)
            return ModelSetupResult(
                false,
                null,
                modelDir,
                downloadedBytes,
                "Model package is missing llm.mnn. Expected a valid MNN LLM export package"
            )
        }

        val config = createConfigFromDirectory(modelDir)
        val loaded = loadModel(config)
        if (!loaded) {
            currentModelState.set(ModelState.ERROR)
            return ModelSetupResult(
                false,
                config,
                modelDir,
                downloadedBytes,
                "Model downloaded but failed to initialize"
            )
        }

        return ModelSetupResult(true, config, modelDir, downloadedBytes, null)
    }

    // ---- Internal helpers ----

    private fun verifyModelIntegrity(config: LLMModelConfig, action: String): Boolean {
        val result = integrityVerifier.verifyModel(config)
        auditLogger?.logModelIntegrityVerification(
            modelId = config.modelId,
            success = result.success,
            details = mapOf(
                "action" to action,
                "message" to result.message,
                "verified_artifacts" to result.verifiedArtifacts,
                "failure_reason" to (result.failureReason?.name ?: "NONE"),
                "failed_artifact" to (result.failedArtifact ?: "")
            )
        )

        if (!result.success) {
            Log.e(TAG, "Model integrity verification failed for ${config.modelName}: ${result.message}")
        }
        return result.success
    }

    private fun scanModelDir(baseDir: File?, results: MutableList<File>) {
        if (baseDir == null || !baseDir.exists() || !baseDir.isDirectory) return
        baseDir.listFiles()?.forEach { child ->
            if (child.isDirectory && File(child, "llm.mnn").exists()) {
                results.add(child)
            }
        }
    }

    private fun downloadToDirectory(downloadUrl: String, modelDir: File): Long {
        val tempFile = File.createTempFile("mnn_model", ".pkg", context.cacheDir)
        var totalBytes = 0L

        var connection: HttpURLConnection? = null
        try {
            connection = URL(downloadUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = NETWORK_TIMEOUT_MS
            connection.readTimeout = NETWORK_TIMEOUT_MS
            connection.instanceFollowRedirects = true

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        totalBytes += read
                    }
                }
            }

            if (downloadUrl.lowercase().endsWith(".zip")) {
                unzipToDirectory(tempFile, modelDir)
            } else {
                tempFile.copyTo(File(modelDir, "llm.mnn"), overwrite = true)
            }

            Log.d(TAG, "Downloaded model package to ${modelDir.absolutePath} (${totalBytes} bytes)")
            return totalBytes
        } finally {
            connection?.disconnect()
            tempFile.delete()
        }
    }

    private fun unzipToDirectory(zipFile: File, destinationDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zipInput ->
            var entry = zipInput.nextEntry
            while (entry != null) {
                val outputFile = File(destinationDir, entry.name)
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

    private fun updateStats() {
        statsMap["native_available"] = nativeAvailable.get()
        statsMap["model_loaded"] = isModelLoaded
        statsMap["model_state"] = currentModelState.get().name
        statsMap["total_inferences"] = totalInferences
        statsMap["total_tokens_generated"] = totalTokensGenerated
        statsMap["total_inference_time_ms"] = totalInferenceTimeMs
        statsMap["last_inference_time_ms"] = lastInferenceTimeMs
        if (totalInferences > 0) {
            statsMap["avg_inference_time_ms"] = totalInferenceTimeMs / totalInferences
            statsMap["avg_tokens_per_inference"] = totalTokensGenerated.toFloat() / totalInferences
        }
        activeConfig?.let { cfg ->
            statsMap["active_model"] = cfg.modelName
            statsMap["active_model_params"] = cfg.parameterCount
            statsMap["active_backend"] = cfg.backendName
        }
    }

    /** Simple quad tuple for destructuring in assessDevice(). */
    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    companion object {
        private const val TAG = "OnDeviceLLMManager"

        // MNN backend constants
        const val BACKEND_CPU = 0
        const val BACKEND_OPENCL = 3
        const val BACKEND_VULKAN = 7

        // Device capability thresholds
        private const val MIN_RAM_MB = 3072L          // 3GB minimum for any LLM
        private const val RECOMMENDED_RAM_MB = 6144L   // 6GB for comfortable operation
        private const val LARGE_MODEL_RAM_MB = 8192L   // 8GB for 3B+ models

        // Inference defaults
        private const val DEFAULT_THREAD_COUNT = 4
        private const val DEFAULT_MAX_TOKENS = 512
        private const val DEFAULT_TEMPERATURE = 0.7f
        private const val DEFAULT_TOP_P = 0.9f
        private const val NETWORK_TIMEOUT_MS = 30_000
        private const val IO_BUFFER_SIZE = 8 * 1024

        // Native library availability
        private val nativeAvailable = AtomicBoolean(false)
        private val nativeLoadAttempted = AtomicBoolean(false)

        init {
            tryLoadNativeLibraries()
        }

        /**
         * Check if MNN native libraries are available for LLM inference.
         */
        @JvmStatic
        fun isNativeAvailable(): Boolean = nativeAvailable.get()

        /**
         * Attempt to load MNN native libraries.
         * These must be pre-built from MNN source with -DMNN_BUILD_LLM=true
         * and placed in app/src/main/jniLibs/arm64-v8a/.
         */
        private fun tryLoadNativeLibraries() {
            if (nativeLoadAttempted.getAndSet(true)) return

            try {
                System.loadLibrary("MNN")
                Log.d(TAG, "Loaded libMNN.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libMNN.so not found — MNN core not available: ${e.message}")
            }

            try {
                System.loadLibrary("MNN_Express")
                Log.d(TAG, "Loaded libMNN_Express.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.d(TAG, "libMNN_Express.so not available: ${e.message}")
            }

            try {
                System.loadLibrary("mnnllm")
                nativeAvailable.set(true)
                Log.d(TAG, "Loaded libmnnllm.so — MNN LLM inference available")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libmnnllm.so not found — on-device LLM inference unavailable. " +
                        "Build MNN from source with -DMNN_BUILD_LLM=true to enable. ${e.message}")
            }
        }

        // ---- JNI native methods (implemented in llm_jni.cpp) ----

        /** Create an MNN LLM session from a model directory. Returns native handle or 0 on failure. */
        @JvmStatic
        private external fun nativeCreateSession(
            modelDir: String, backend: Int, threads: Int, useMmap: Boolean
        ): Long

        /** Generate a response from the loaded model. Returns generated text or null on error. */
        @JvmStatic
        private external fun nativeGenerate(
            handle: Long, prompt: String, maxTokens: Int, temp: Float, topP: Float
        ): String?

        /** Get the number of tokens generated in the last inference call. */
        @JvmStatic
        private external fun nativeGetLastTokenCount(handle: Long): Int

        /** Get inference performance stats. Returns JSON with prefill_ms, decode_ms, tokens_per_second. */
        @JvmStatic
        private external fun nativeGetStats(handle: Long): String?

        /** Release a native LLM session and free memory. */
        @JvmStatic
        private external fun nativeDestroySession(handle: Long)
    }
}

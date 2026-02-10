package com.tronprotocol.app.llm;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
 * @see <a href="https://github.com/alibaba/MNN">MNN GitHub</a>
 * @see <a href="https://arxiv.org/html/2506.10443v1">MNN-LLM Paper</a>
 */
public class OnDeviceLLMManager {

    private static final String TAG = "OnDeviceLLMManager";

    // Native library availability
    private static final AtomicBoolean nativeAvailable = new AtomicBoolean(false);
    private static final AtomicBoolean nativeLoadAttempted = new AtomicBoolean(false);

    // MNN backend constants
    public static final int BACKEND_CPU = 0;
    public static final int BACKEND_OPENCL = 3;
    public static final int BACKEND_VULKAN = 7;

    // Device capability thresholds
    private static final long MIN_RAM_MB = 3072;          // 3GB minimum for any LLM
    private static final long RECOMMENDED_RAM_MB = 6144;   // 6GB for comfortable operation
    private static final long LARGE_MODEL_RAM_MB = 8192;   // 8GB for 3B+ models

    // Inference defaults
    private static final int DEFAULT_THREAD_COUNT = 4;
    private static final int DEFAULT_MAX_TOKENS = 512;
    private static final float DEFAULT_TEMPERATURE = 0.7f;
    private static final float DEFAULT_TOP_P = 0.9f;

    private final Context context;
    private final ExecutorService inferenceExecutor;
    private final AtomicReference<ModelState> currentModelState;
    private final ConcurrentHashMap<String, Object> stats;

    private LLMModelConfig activeConfig;
    private long nativeSessionHandle;
    private volatile boolean isModelLoaded;

    // Performance tracking
    private long totalInferences;
    private long totalTokensGenerated;
    private long totalInferenceTimeMs;
    private long lastInferenceTimeMs;

    /**
     * Model lifecycle states.
     */
    public enum ModelState {
        UNINITIALIZED,
        CHECKING_DEVICE,
        DOWNLOADING,
        LOADING,
        READY,
        GENERATING,
        ERROR,
        UNLOADED
    }

    /**
     * Result of an LLM generation request.
     */
    public static class GenerationResult {
        public final boolean success;
        public final String text;
        public final int tokensGenerated;
        public final long latencyMs;
        public final float tokensPerSecond;
        public final String error;
        public final String modelId;

        private GenerationResult(boolean success, String text, int tokensGenerated,
                                 long latencyMs, float tokensPerSecond,
                                 String error, String modelId) {
            this.success = success;
            this.text = text;
            this.tokensGenerated = tokensGenerated;
            this.latencyMs = latencyMs;
            this.tokensPerSecond = tokensPerSecond;
            this.error = error;
            this.modelId = modelId;
        }

        public static GenerationResult success(String text, int tokens, long latencyMs,
                                                float tps, String modelId) {
            return new GenerationResult(true, text, tokens, latencyMs, tps, null, modelId);
        }

        public static GenerationResult error(String error) {
            return new GenerationResult(false, null, 0, 0, 0f, error, null);
        }
    }

    /**
     * Device capability assessment for LLM inference.
     */
    public static class DeviceCapability {
        public final long totalRamMb;
        public final long availableRamMb;
        public final String cpuArch;
        public final boolean supportsArm64;
        public final boolean supportsFp16;
        public final boolean hasGpu;
        public final int recommendedThreads;
        public final long maxModelSizeMb;
        public final String recommendedModel;
        public final boolean canRunLLM;
        public final String reason;

        public DeviceCapability(long totalRamMb, long availableRamMb, String cpuArch,
                                boolean supportsArm64, boolean supportsFp16, boolean hasGpu,
                                int recommendedThreads, long maxModelSizeMb,
                                String recommendedModel, boolean canRunLLM, String reason) {
            this.totalRamMb = totalRamMb;
            this.availableRamMb = availableRamMb;
            this.cpuArch = cpuArch;
            this.supportsArm64 = supportsArm64;
            this.supportsFp16 = supportsFp16;
            this.hasGpu = hasGpu;
            this.recommendedThreads = recommendedThreads;
            this.maxModelSizeMb = maxModelSizeMb;
            this.recommendedModel = recommendedModel;
            this.canRunLLM = canRunLLM;
            this.reason = reason;
        }
    }

    static {
        tryLoadNativeLibraries();
    }

    /**
     * Attempt to load MNN native libraries.
     * These must be pre-built from MNN source with -DMNN_BUILD_LLM=true
     * and placed in app/src/main/jniLibs/arm64-v8a/.
     */
    private static void tryLoadNativeLibraries() {
        if (nativeLoadAttempted.getAndSet(true)) {
            return;
        }
        try {
            System.loadLibrary("MNN");
            Log.d(TAG, "Loaded libMNN.so");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "libMNN.so not found — MNN core not available: " + e.getMessage());
        }

        try {
            System.loadLibrary("MNN_Express");
            Log.d(TAG, "Loaded libMNN_Express.so");
        } catch (UnsatisfiedLinkError e) {
            Log.d(TAG, "libMNN_Express.so not available: " + e.getMessage());
        }

        try {
            System.loadLibrary("mnnllm");
            nativeAvailable.set(true);
            Log.d(TAG, "Loaded libmnnllm.so — MNN LLM inference available");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "libmnnllm.so not found — on-device LLM inference unavailable. "
                    + "Build MNN from source with -DMNN_BUILD_LLM=true to enable. "
                    + e.getMessage());
        }
    }

    // ---- JNI native methods (implemented in llm_jni.cpp) ----

    /**
     * Create an MNN LLM session from a model directory.
     *
     * @param modelDir  Path to directory containing llm.mnn, config.json, tokenizer.txt
     * @param backend   Backend type (0=CPU, 3=OpenCL, 7=Vulkan)
     * @param threads   Number of CPU threads for inference
     * @param useMmap   Whether to use memory-mapped weights (reduces RAM for large models)
     * @return Native session handle, or 0 on failure
     */
    private static native long nativeCreateSession(String modelDir, int backend,
                                                    int threads, boolean useMmap);

    /**
     * Generate a response from the loaded model.
     *
     * @param handle    Session handle from nativeCreateSession
     * @param prompt    Input prompt text
     * @param maxTokens Maximum tokens to generate
     * @param temp      Temperature for sampling (0.0-2.0)
     * @param topP      Top-p nucleus sampling threshold
     * @return Generated text, or null on error
     */
    private static native String nativeGenerate(long handle, String prompt,
                                                 int maxTokens, float temp, float topP);

    /**
     * Get the number of tokens generated in the last inference call.
     */
    private static native int nativeGetLastTokenCount(long handle);

    /**
     * Get inference performance stats from the native session.
     * Returns JSON string with prefill_ms, decode_ms, tokens_per_second.
     */
    private static native String nativeGetStats(long handle);

    /**
     * Release a native LLM session and free memory.
     */
    private static native void nativeDestroySession(long handle);

    // ---- Public API ----

    public OnDeviceLLMManager(Context context) {
        this.context = context.getApplicationContext();
        this.inferenceExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MNN-LLM-Inference");
            t.setPriority(Thread.NORM_PRIORITY + 1);
            return t;
        });
        this.currentModelState = new AtomicReference<>(ModelState.UNINITIALIZED);
        this.stats = new ConcurrentHashMap<>();
        this.nativeSessionHandle = 0;
        this.isModelLoaded = false;
    }

    /**
     * Check if MNN native libraries are available for LLM inference.
     */
    public static boolean isNativeAvailable() {
        return nativeAvailable.get();
    }

    /**
     * Assess this device's capability for on-device LLM inference.
     */
    public DeviceCapability assessDevice() {
        currentModelState.set(ModelState.CHECKING_DEVICE);

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        if (am != null) {
            am.getMemoryInfo(memInfo);
        }

        long totalRamMb = memInfo.totalMem / (1024 * 1024);
        long availableRamMb = memInfo.availMem / (1024 * 1024);

        String cpuArch = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
        boolean supportsArm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                supportsArm64 = true;
                break;
            }
        }

        // ARMv8.2+ supports FP16 natively (most devices from 2018+)
        boolean supportsFp16 = supportsArm64 && Build.VERSION.SDK_INT >= 28;

        // Estimate GPU availability (OpenCL) — conservative check
        boolean hasGpu = supportsArm64;

        // Thread recommendation: use big cores, cap at 4 for battery
        int recommendedThreads = Math.min(Runtime.getRuntime().availableProcessors(), DEFAULT_THREAD_COUNT);

        // Model size recommendation based on available RAM
        long maxModelSizeMb;
        String recommendedModel;
        boolean canRunLLM;
        String reason;

        if (!supportsArm64) {
            maxModelSizeMb = 0;
            recommendedModel = "none";
            canRunLLM = false;
            reason = "Device does not support arm64-v8a — MNN LLM requires 64-bit ARM";
        } else if (availableRamMb < MIN_RAM_MB) {
            maxModelSizeMb = 0;
            recommendedModel = "none";
            canRunLLM = false;
            reason = String.format("Insufficient RAM: %dMB available, %dMB required minimum",
                    availableRamMb, MIN_RAM_MB);
        } else if (availableRamMb < RECOMMENDED_RAM_MB) {
            maxModelSizeMb = 1500;
            recommendedModel = "Qwen2.5-1.5B-Instruct-Q4";
            canRunLLM = true;
            reason = "Limited RAM — recommend 1.5B parameter model with Q4 quantization";
        } else if (availableRamMb < LARGE_MODEL_RAM_MB) {
            maxModelSizeMb = 2500;
            recommendedModel = "Qwen3-1.7B-Q4";
            canRunLLM = true;
            reason = "Moderate RAM — can run up to 1.7B parameter model comfortably";
        } else {
            maxModelSizeMb = 4000;
            recommendedModel = "Qwen2.5-3B-Instruct-Q4";
            canRunLLM = true;
            reason = "Good RAM — can run up to 3B+ parameter models";
        }

        if (canRunLLM && !nativeAvailable.get()) {
            reason += " (MNN native libraries not installed — build from github.com/alibaba/MNN)";
        }

        return new DeviceCapability(
                totalRamMb, availableRamMb, cpuArch,
                supportsArm64, supportsFp16, hasGpu,
                recommendedThreads, maxModelSizeMb,
                recommendedModel, canRunLLM, reason
        );
    }

    /**
     * Load an LLM model from the specified configuration.
     * The model directory must contain MNN-exported files:
     * llm.mnn, llm.mnn.weight, config.json, llm_config.json, tokenizer.txt
     *
     * @param config Model configuration specifying path, backend, threads, etc.
     * @return true if model loaded successfully
     */
    public boolean loadModel(LLMModelConfig config) {
        if (!nativeAvailable.get()) {
            Log.e(TAG, "Cannot load model — MNN native libraries not available");
            currentModelState.set(ModelState.ERROR);
            return false;
        }

        // Validate model directory
        File modelDir = new File(config.getModelPath());
        if (!modelDir.exists() || !modelDir.isDirectory()) {
            Log.e(TAG, "Model directory does not exist: " + config.getModelPath());
            currentModelState.set(ModelState.ERROR);
            return false;
        }

        File modelFile = new File(modelDir, "llm.mnn");
        File configFile = new File(modelDir, "config.json");
        if (!modelFile.exists()) {
            Log.e(TAG, "llm.mnn not found in " + config.getModelPath());
            currentModelState.set(ModelState.ERROR);
            return false;
        }

        // Unload any existing model
        if (isModelLoaded) {
            unloadModel();
        }

        currentModelState.set(ModelState.LOADING);
        Log.d(TAG, "Loading model: " + config.getModelName()
                + " from " + config.getModelPath()
                + " backend=" + config.getBackend()
                + " threads=" + config.getThreadCount());

        try {
            long handle = nativeCreateSession(
                    config.getModelPath(),
                    config.getBackend(),
                    config.getThreadCount(),
                    config.isUseMmap()
            );

            if (handle == 0) {
                Log.e(TAG, "nativeCreateSession returned null handle");
                currentModelState.set(ModelState.ERROR);
                return false;
            }

            nativeSessionHandle = handle;
            activeConfig = config;
            isModelLoaded = true;
            currentModelState.set(ModelState.READY);

            Log.d(TAG, "Model loaded successfully: " + config.getModelName()
                    + " (" + config.getParameterCount() + " params, "
                    + config.getQuantization() + " quantization)");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load model: " + e.getMessage(), e);
            currentModelState.set(ModelState.ERROR);
            return false;
        }
    }

    /**
     * Generate text using the loaded on-device LLM.
     *
     * @param prompt    Input prompt
     * @param maxTokens Maximum tokens to generate (0 = use config default)
     * @return GenerationResult with the generated text and performance metrics
     */
    public GenerationResult generate(String prompt, int maxTokens) {
        if (!isModelLoaded || nativeSessionHandle == 0) {
            return GenerationResult.error("No model loaded — call loadModel() first");
        }

        if (prompt == null || prompt.trim().isEmpty()) {
            return GenerationResult.error("Prompt is empty");
        }

        currentModelState.set(ModelState.GENERATING);
        long startTime = System.currentTimeMillis();

        try {
            int tokens = maxTokens > 0 ? maxTokens
                    : (activeConfig != null ? activeConfig.getMaxTokens() : DEFAULT_MAX_TOKENS);
            float temp = activeConfig != null ? activeConfig.getTemperature() : DEFAULT_TEMPERATURE;
            float topP = activeConfig != null ? activeConfig.getTopP() : DEFAULT_TOP_P;

            String result = nativeGenerate(nativeSessionHandle, prompt, tokens, temp, topP);

            long latencyMs = System.currentTimeMillis() - startTime;

            if (result == null || result.isEmpty()) {
                currentModelState.set(ModelState.READY);
                return GenerationResult.error("Model returned empty response");
            }

            int tokenCount = nativeGetLastTokenCount(nativeSessionHandle);
            float tps = tokenCount > 0 && latencyMs > 0
                    ? (tokenCount * 1000f) / latencyMs : 0f;

            // Update stats
            totalInferences++;
            totalTokensGenerated += tokenCount;
            totalInferenceTimeMs += latencyMs;
            lastInferenceTimeMs = latencyMs;
            updateStats();

            currentModelState.set(ModelState.READY);

            String modelId = activeConfig != null ? activeConfig.getModelId() : "unknown";
            Log.d(TAG, String.format("Generated %d tokens in %dms (%.1f tok/s) model=%s",
                    tokenCount, latencyMs, tps, modelId));

            return GenerationResult.success(result, tokenCount, latencyMs, tps, modelId);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            Log.e(TAG, "Generation failed after " + latencyMs + "ms: " + e.getMessage(), e);
            currentModelState.set(ModelState.READY);
            return GenerationResult.error("Generation failed: " + e.getMessage());
        }
    }

    /**
     * Generate text asynchronously, returning a Future.
     */
    public Future<GenerationResult> generateAsync(String prompt, int maxTokens) {
        return inferenceExecutor.submit(() -> generate(prompt, maxTokens));
    }

    /**
     * Generate a response using the on-device LLM with default token limit.
     * Convenience method for the guidance system integration.
     */
    public GenerationResult generate(String prompt) {
        return generate(prompt, 0);
    }

    /**
     * Unload the current model and free native memory.
     */
    public void unloadModel() {
        if (nativeSessionHandle != 0 && nativeAvailable.get()) {
            try {
                nativeDestroySession(nativeSessionHandle);
                Log.d(TAG, "Model unloaded: "
                        + (activeConfig != null ? activeConfig.getModelName() : "unknown"));
            } catch (Exception e) {
                Log.e(TAG, "Error destroying native session: " + e.getMessage(), e);
            }
        }
        nativeSessionHandle = 0;
        isModelLoaded = false;
        activeConfig = null;
        currentModelState.set(ModelState.UNLOADED);
    }

    /**
     * Get the current model state.
     */
    public ModelState getModelState() {
        return currentModelState.get();
    }

    /**
     * Check if a model is loaded and ready for inference.
     */
    public boolean isReady() {
        return isModelLoaded && currentModelState.get() == ModelState.READY;
    }

    /**
     * Get the active model configuration, or null if no model is loaded.
     */
    public LLMModelConfig getActiveConfig() {
        return activeConfig;
    }

    /**
     * Run a quick benchmark to measure inference performance.
     *
     * @return Benchmark results as a JSON-compatible map
     */
    public Map<String, Object> benchmark() {
        Map<String, Object> results = new ConcurrentHashMap<>();

        if (!isModelLoaded) {
            results.put("error", "No model loaded");
            return results;
        }

        String benchmarkPrompt = "Explain in one sentence what artificial intelligence is.";
        long start = System.currentTimeMillis();
        GenerationResult result = generate(benchmarkPrompt, 64);
        long elapsed = System.currentTimeMillis() - start;

        results.put("model", activeConfig != null ? activeConfig.getModelName() : "unknown");
        results.put("backend", activeConfig != null ? activeConfig.getBackendName() : "cpu");
        results.put("success", result.success);
        results.put("latency_ms", elapsed);
        results.put("tokens_generated", result.tokensGenerated);
        results.put("tokens_per_second", result.tokensPerSecond);

        if (nativeAvailable.get() && nativeSessionHandle != 0) {
            try {
                String nativeStats = nativeGetStats(nativeSessionHandle);
                if (nativeStats != null) {
                    results.put("native_stats", nativeStats);
                }
            } catch (Exception e) {
                Log.d(TAG, "Could not retrieve native stats: " + e.getMessage());
            }
        }

        return results;
    }

    /**
     * Get cumulative inference statistics.
     */
    public Map<String, Object> getStats() {
        updateStats();
        return new ConcurrentHashMap<>(stats);
    }

    /**
     * Shutdown the manager and release all resources.
     */
    public void shutdown() {
        unloadModel();
        inferenceExecutor.shutdownNow();
        Log.d(TAG, "OnDeviceLLMManager shut down");
    }

    // ---- Internal helpers ----

    private void updateStats() {
        stats.put("native_available", nativeAvailable.get());
        stats.put("model_loaded", isModelLoaded);
        stats.put("model_state", currentModelState.get().name());
        stats.put("total_inferences", totalInferences);
        stats.put("total_tokens_generated", totalTokensGenerated);
        stats.put("total_inference_time_ms", totalInferenceTimeMs);
        stats.put("last_inference_time_ms", lastInferenceTimeMs);
        if (totalInferences > 0) {
            stats.put("avg_inference_time_ms", totalInferenceTimeMs / totalInferences);
            stats.put("avg_tokens_per_inference",
                    (float) totalTokensGenerated / totalInferences);
        }
        if (activeConfig != null) {
            stats.put("active_model", activeConfig.getModelName());
            stats.put("active_model_params", activeConfig.getParameterCount());
            stats.put("active_backend", activeConfig.getBackendName());
        }
    }

    /**
     * Discover model directories in the standard locations.
     * Scans: app files dir, external files dir, and /sdcard/mnn_models/.
     */
    public java.util.List<File> discoverModels() {
        java.util.List<File> found = new java.util.ArrayList<>();

        // Internal app files
        File internalModels = new File(context.getFilesDir(), "mnn_models");
        scanModelDir(internalModels, found);

        // External app files
        File externalModels = new File(context.getExternalFilesDir(null), "mnn_models");
        scanModelDir(externalModels, found);

        // Well-known SD card path (used by MNN Chat for ADB push)
        File sdcardModels = new File("/sdcard/mnn_models");
        scanModelDir(sdcardModels, found);

        Log.d(TAG, "Discovered " + found.size() + " model directories");
        return found;
    }

    private void scanModelDir(File baseDir, java.util.List<File> results) {
        if (baseDir == null || !baseDir.exists() || !baseDir.isDirectory()) {
            return;
        }
        File[] children = baseDir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                File modelFile = new File(child, "llm.mnn");
                if (modelFile.exists()) {
                    results.add(child);
                }
            }
        }
    }

    /**
     * Create a default LLMModelConfig from a discovered model directory.
     * Reads config.json and llm_config.json if present.
     */
    public LLMModelConfig createConfigFromDirectory(File modelDir) {
        String modelName = modelDir.getName();
        String modelPath = modelDir.getAbsolutePath();

        // Attempt to read config.json for backend/thread settings
        int backend = BACKEND_CPU;
        int threads = DEFAULT_THREAD_COUNT;
        boolean useMmap = false;

        File configFile = new File(modelDir, "config.json");
        if (configFile.exists()) {
            try {
                java.io.FileInputStream fis = new java.io.FileInputStream(configFile);
                byte[] data = new byte[(int) configFile.length()];
                fis.read(data);
                fis.close();
                JSONObject config = new JSONObject(new String(data));

                String backendType = config.optString("backend_type", "cpu");
                if ("opencl".equalsIgnoreCase(backendType)) {
                    backend = BACKEND_OPENCL;
                } else if ("vulkan".equalsIgnoreCase(backendType)) {
                    backend = BACKEND_VULKAN;
                }
                threads = config.optInt("thread_num", DEFAULT_THREAD_COUNT);
                useMmap = config.optBoolean("use_mmap", false);
            } catch (Exception e) {
                Log.d(TAG, "Could not parse config.json: " + e.getMessage());
            }
        }

        return new LLMModelConfig.Builder(modelName, modelPath)
                .setBackend(backend)
                .setThreadCount(threads)
                .setUseMmap(useMmap)
                .build();
    }
}

package com.tronprotocol.app.avatar.backend

import android.graphics.Bitmap

/**
 * Abstraction over image diffusion inference backends.
 *
 * Supports both MNN diffusion (existing) and ToolNeuron's Stable Diffusion
 * backend (QNN NPU for Qualcomm, CPU fallback).
 */
interface DiffusionBackend {

    /** Human-readable backend identifier: "mnn" or "toolneuron". */
    val name: String

    /** Whether the native libraries for this backend were successfully loaded. */
    val isAvailable: Boolean

    /** Whether a model is currently loaded and ready for inference. */
    fun isModelLoaded(): Boolean

    /**
     * Load a diffusion model.
     *
     * @param config Model configuration including path, scheduler, and NPU settings
     * @return true if model was loaded successfully
     */
    fun loadModel(config: DiffusionModelConfig): Boolean

    /**
     * Generate an image from a text prompt.
     *
     * @param params Generation parameters (prompt, size, steps, etc.)
     * @param callback Callback for progress updates
     */
    fun generateImage(params: DiffusionGenerationParams, callback: DiffusionCallback)

    /** Cancel an in-progress generation. */
    fun cancelGeneration()

    /** Unload the current model and free resources. */
    fun unloadModel()
}

/**
 * Configuration for loading a diffusion model.
 */
data class DiffusionModelConfig(
    val modelPath: String,
    val scheduler: DiffusionScheduler = DiffusionScheduler.DPM,
    val useNpu: Boolean = false,
    val useSafetyChecker: Boolean = true,
    val numThreads: Int = 4
)

/**
 * Parameters for image generation.
 */
data class DiffusionGenerationParams(
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val numSteps: Int = 20,
    val guidanceScale: Float = 7.5f,
    val seed: Long = -1,
    val inputImage: Bitmap? = null,
    val maskImage: Bitmap? = null,
    val strength: Float = 0.75f
)

/**
 * Supported diffusion schedulers.
 */
enum class DiffusionScheduler {
    DPM, EULER, EULER_A, DDIM, PNDM
}

/**
 * Callback interface for diffusion generation events.
 */
interface DiffusionCallback {
    fun onProgress(step: Int, totalSteps: Int, latencyMs: Long)
    fun onComplete(bitmap: Bitmap, totalLatencyMs: Long)
    fun onError(message: String)
}

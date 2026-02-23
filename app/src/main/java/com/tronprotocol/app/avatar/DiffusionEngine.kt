package com.tronprotocol.app.avatar

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device Stable Diffusion engine via MNN for avatar image generation.
 *
 * Runs the full text-to-image diffusion pipeline (CLIP Text Encoder -> UNet -> VAE Decoder)
 * on-device using MNN's quantized diffusion support. Supports ControlNet for pose-guided
 * generation and LoRA adapters for consistent avatar style.
 *
 * Use cases:
 * - Profile avatar generation from text descriptions
 * - Style transfer for avatar portraits
 * - Pose-controlled avatar image generation (with ControlNet)
 * - Consistent character generation (with LoRA)
 *
 * Supported models: Stable Diffusion 1.5 (INT8), SD-Turbo, LCM
 *
 * Native libraries required:
 * - libMNN.so (core framework)
 * - libmnn_diffusion.so (diffusion pipeline)
 *
 * @see [MNN Diffusion](https://github.com/alibaba/MNN/tree/master/transformers/diffusion)
 */
class DiffusionEngine {

    private val nativeHandle = AtomicLong(0)
    private val isInitialized = AtomicBoolean(false)
    private val currentState = AtomicReference(DiffusionState.IDLE)
    private var currentProgress: Float = 0f

    val isReady: Boolean get() = isInitialized.get() && nativeHandle.get() != 0L
    val state: DiffusionState get() = currentState.get()
    val progress: Float get() = currentProgress

    /** Diffusion pipeline state. */
    enum class DiffusionState {
        IDLE,
        LOADING,
        ENCODING_TEXT,
        DENOISING,
        DECODING,
        COMPLETE,
        ERROR
    }

    /** Diffusion model configuration. */
    data class DiffusionModelConfig(
        val modelDir: String,
        val clipModelPath: String = "clip_text_encoder.mnn",
        val unetModelPath: String = "unet.mnn",
        val vaeDecoderPath: String = "vae_decoder.mnn",
        val controlNetPath: String? = null,
        val loraPath: String? = null,
        val loraWeight: Float = 0.8f
    )

    /** Generation parameters. */
    data class GenerationConfig(
        val prompt: String,
        val negativePrompt: String = DEFAULT_NEGATIVE_PROMPT,
        val width: Int = 512,
        val height: Int = 512,
        val steps: Int = 20,
        val guidanceScale: Float = 7.5f,
        val seed: Long = -1,
        val scheduler: Scheduler = Scheduler.EULER_DISCRETE
    )

    /** Supported noise schedulers. */
    enum class Scheduler(val id: String) {
        DDIM("ddim"),
        PNDM("pndm"),
        EULER_DISCRETE("euler"),
        DPM_SOLVER("dpm_solver"),
        LCM("lcm")
    }

    /** Generation result with output image and metadata. */
    data class GenerationResult(
        val image: Bitmap?,
        val success: Boolean,
        val error: String?,
        val generationTimeMs: Long,
        val steps: Int,
        val seed: Long,
        val config: GenerationConfig
    )

    /** Progress callback for step-by-step updates. */
    interface ProgressListener {
        fun onProgress(step: Int, totalSteps: Int, state: DiffusionState)
        fun onComplete(result: GenerationResult)
    }

    /**
     * Initialize the diffusion pipeline.
     *
     * @param config Model configuration with paths to CLIP, UNet, and VAE components.
     * @param backend Inference backend.
     * @param threads Number of inference threads.
     * @return true if all pipeline components loaded successfully.
     */
    fun initialize(
        config: DiffusionModelConfig,
        backend: MnnVisionEngine.BackendType = MnnVisionEngine.BackendType.CPU,
        threads: Int = 4
    ): Boolean {
        if (isInitialized.get()) {
            Log.w(TAG, "Diffusion engine already initialized")
            return true
        }

        if (!nativeAvailable.get()) {
            Log.e(TAG, "Diffusion native libraries not available")
            return false
        }

        val modelDir = File(config.modelDir)
        if (!modelDir.exists()) {
            Log.e(TAG, "Diffusion model directory not found: ${config.modelDir}")
            return false
        }

        currentState.set(DiffusionState.LOADING)

        return try {
            val handle = nativeCreateDiffusion()
            if (handle == 0L) {
                Log.e(TAG, "nativeCreateDiffusion() returned null handle")
                currentState.set(DiffusionState.ERROR)
                return false
            }
            nativeHandle.set(handle)

            val clipPath = "${config.modelDir}/${config.clipModelPath}"
            val unetPath = "${config.modelDir}/${config.unetModelPath}"
            val vaePath = "${config.modelDir}/${config.vaeDecoderPath}"

            val loaded = nativeInitPipeline(
                handle, clipPath, unetPath, vaePath,
                config.controlNetPath ?: "",
                config.loraPath ?: "",
                config.loraWeight,
                backend.nativeId, threads
            )

            if (!loaded) {
                Log.e(TAG, "Failed to initialize diffusion pipeline")
                nativeDestroyDiffusion(handle)
                nativeHandle.set(0)
                currentState.set(DiffusionState.ERROR)
                return false
            }

            isInitialized.set(true)
            currentState.set(DiffusionState.IDLE)
            Log.d(TAG, "Diffusion engine initialized from: ${config.modelDir}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Diffusion initialization failed: ${e.message}", e)
            currentState.set(DiffusionState.ERROR)
            false
        }
    }

    /**
     * Generate an image from a text prompt.
     *
     * @param config Generation configuration (prompt, steps, size, etc.).
     * @param listener Optional progress callback.
     * @return GenerationResult with the output bitmap.
     */
    fun generate(
        config: GenerationConfig,
        listener: ProgressListener? = null
    ): GenerationResult {
        val handle = nativeHandle.get()
        if (handle == 0L || !isInitialized.get()) {
            return GenerationResult(
                null, false, "Diffusion engine not initialized",
                0, config.steps, -1, config
            )
        }

        val seed = if (config.seed < 0) System.nanoTime() else config.seed
        val startMs = System.currentTimeMillis()

        return try {
            // 1. Encode text prompt
            currentState.set(DiffusionState.ENCODING_TEXT)
            listener?.onProgress(0, config.steps, DiffusionState.ENCODING_TEXT)

            val textEncoded = nativeEncodeText(handle, config.prompt, config.negativePrompt)
            if (!textEncoded) {
                currentState.set(DiffusionState.ERROR)
                return GenerationResult(null, false, "Text encoding failed", 0, config.steps, seed, config)
            }

            // 2. Denoise (iterative UNet steps)
            currentState.set(DiffusionState.DENOISING)
            val denoised = nativeDenoise(
                handle, config.width, config.height,
                config.steps, config.guidanceScale, seed,
                config.scheduler.id
            ) { step, total ->
                currentProgress = step.toFloat() / total
                listener?.onProgress(step, total, DiffusionState.DENOISING)
            }

            if (!denoised) {
                currentState.set(DiffusionState.ERROR)
                return GenerationResult(null, false, "Denoising failed", 0, config.steps, seed, config)
            }

            // 3. Decode latents to image
            currentState.set(DiffusionState.DECODING)
            listener?.onProgress(config.steps, config.steps, DiffusionState.DECODING)

            val pixels = nativeDecodeToBitmap(handle, config.width, config.height)
            if (pixels == null) {
                currentState.set(DiffusionState.ERROR)
                return GenerationResult(null, false, "VAE decoding failed", 0, config.steps, seed, config)
            }

            // Convert raw pixel data to Bitmap
            val bitmap = Bitmap.createBitmap(config.width, config.height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, config.width, 0, 0, config.width, config.height)

            val elapsed = System.currentTimeMillis() - startMs
            currentState.set(DiffusionState.COMPLETE)
            currentProgress = 1f

            val result = GenerationResult(
                image = bitmap,
                success = true,
                error = null,
                generationTimeMs = elapsed,
                steps = config.steps,
                seed = seed,
                config = config
            )
            listener?.onComplete(result)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed: ${e.message}", e)
            currentState.set(DiffusionState.ERROR)
            GenerationResult(
                null, false, "Generation error: ${e.message}",
                System.currentTimeMillis() - startMs, config.steps, seed, config
            )
        }
    }

    /**
     * Generate an image guided by a pose/control image (ControlNet).
     *
     * @param config Generation configuration.
     * @param controlImage Control image (e.g., OpenPose skeleton, depth map, edge map).
     * @param controlStrength ControlNet conditioning strength (0.0–2.0).
     * @param listener Progress callback.
     * @return GenerationResult with pose-controlled output.
     */
    fun generateWithControl(
        config: GenerationConfig,
        controlImage: Bitmap,
        controlStrength: Float = 1.0f,
        listener: ProgressListener? = null
    ): GenerationResult {
        val handle = nativeHandle.get()
        if (handle == 0L || !isInitialized.get()) {
            return GenerationResult(null, false, "Not initialized", 0, config.steps, -1, config)
        }

        // Scale control image to match output dimensions
        val scaledControl = if (controlImage.width != config.width || controlImage.height != config.height) {
            Bitmap.createScaledBitmap(controlImage, config.width, config.height, true)
        } else {
            controlImage
        }

        val controlPixels = IntArray(scaledControl.width * scaledControl.height)
        scaledControl.getPixels(controlPixels, 0, scaledControl.width, 0, 0, scaledControl.width, scaledControl.height)

        val setControl = nativeSetControlImage(handle, controlPixels, config.width, config.height, controlStrength)
        if (!setControl) {
            Log.w(TAG, "ControlNet not available, falling back to standard generation")
        }

        return generate(config, listener)
    }

    /** Cancel an in-progress generation. */
    fun cancel() {
        val handle = nativeHandle.get()
        if (handle != 0L) {
            nativeCancelGeneration(handle)
        }
        currentState.set(DiffusionState.IDLE)
        currentProgress = 0f
    }

    /** Release all native resources. */
    fun destroy() {
        val handle = nativeHandle.getAndSet(0)
        if (handle != 0L && nativeAvailable.get()) {
            try {
                nativeDestroyDiffusion(handle)
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying diffusion engine: ${e.message}", e)
            }
        }
        isInitialized.set(false)
        currentState.set(DiffusionState.IDLE)
        currentProgress = 0f
        Log.d(TAG, "Diffusion engine destroyed")
    }

    companion object {
        private const val TAG = "DiffusionEngine"

        const val DEFAULT_NEGATIVE_PROMPT = "low quality, blurry, deformed, ugly, bad anatomy, disfigured"

        private val nativeAvailable = AtomicBoolean(false)
        private val nativeLoadAttempted = AtomicBoolean(false)

        init {
            tryLoadNativeLibraries()
        }

        @JvmStatic
        fun isNativeAvailable(): Boolean = nativeAvailable.get()

        private fun tryLoadNativeLibraries() {
            if (nativeLoadAttempted.getAndSet(true)) return

            try {
                System.loadLibrary("MNN")
            } catch (_: UnsatisfiedLinkError) { }

            try {
                System.loadLibrary("mnn_diffusion")
                nativeAvailable.set(true)
                Log.d(TAG, "Loaded libmnn_diffusion.so — diffusion generation available")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libmnn_diffusion.so not found — diffusion unavailable. ${e.message}")
            }
        }

        // ---- JNI native methods ----
        @JvmStatic private external fun nativeCreateDiffusion(): Long
        @JvmStatic private external fun nativeInitPipeline(
            handle: Long,
            clipPath: String, unetPath: String, vaePath: String,
            controlNetPath: String, loraPath: String, loraWeight: Float,
            backendType: Int, numThreads: Int
        ): Boolean
        @JvmStatic private external fun nativeEncodeText(
            handle: Long, prompt: String, negativePrompt: String
        ): Boolean
        @JvmStatic private external fun nativeDenoise(
            handle: Long, width: Int, height: Int,
            steps: Int, guidanceScale: Float, seed: Long,
            schedulerId: String,
            progressCallback: (Int, Int) -> Unit
        ): Boolean
        @JvmStatic private external fun nativeDecodeToBitmap(
            handle: Long, width: Int, height: Int
        ): IntArray?
        @JvmStatic private external fun nativeSetControlImage(
            handle: Long, pixels: IntArray, width: Int, height: Int, strength: Float
        ): Boolean
        @JvmStatic private external fun nativeCancelGeneration(handle: Long)
        @JvmStatic private external fun nativeDestroyDiffusion(handle: Long)
    }
}

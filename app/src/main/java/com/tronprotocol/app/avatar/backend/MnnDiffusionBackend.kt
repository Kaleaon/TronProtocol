package com.tronprotocol.app.avatar.backend

import android.graphics.Bitmap
import android.util.Log

/**
 * MNN-based diffusion backend wrapping the existing DiffusionEngine JNI calls.
 *
 * Delegates to the native MNN diffusion pipeline (nativeCreateDiffusion,
 * nativeInitPipeline, nativeDiffusionRun, etc.).
 */
class MnnDiffusionBackend : DiffusionBackend {

    override val name: String = "mnn"

    override val isAvailable: Boolean
        get() = nativeAvailable

    private var modelLoaded = false

    override fun isModelLoaded(): Boolean = modelLoaded

    override fun loadModel(config: DiffusionModelConfig): Boolean {
        if (!isAvailable) {
            Log.w(TAG, "MNN diffusion native library not available")
            return false
        }
        return try {
            nativeCreateDiffusion(config.modelPath, config.numThreads)
            modelLoaded = true
            Log.d(TAG, "MNN diffusion model loaded from ${config.modelPath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load MNN diffusion model: ${e.message}", e)
            false
        }
    }

    override fun generateImage(params: DiffusionGenerationParams, callback: DiffusionCallback) {
        if (!modelLoaded) {
            callback.onError("No diffusion model loaded")
            return
        }

        try {
            val startTime = System.currentTimeMillis()
            // MNN diffusion generates synchronously; emit progress at each step
            val result = nativeDiffusionRun(
                params.prompt,
                params.negativePrompt,
                params.width,
                params.height,
                params.numSteps,
                params.guidanceScale,
                params.seed
            )

            if (result != null) {
                val latency = System.currentTimeMillis() - startTime
                callback.onComplete(result, latency)
            } else {
                callback.onError("MNN diffusion returned null")
            }
        } catch (e: Exception) {
            callback.onError("MNN diffusion failed: ${e.message}")
        }
    }

    override fun cancelGeneration() {
        if (modelLoaded) {
            try {
                nativeCancelDiffusion()
            } catch (_: Exception) { }
        }
    }

    override fun unloadModel() {
        if (modelLoaded) {
            try {
                nativeDestroyDiffusion()
            } catch (_: Exception) { }
            modelLoaded = false
        }
    }

    companion object {
        private const val TAG = "MnnDiffusionBackend"

        private var nativeAvailable = false

        init {
            try {
                System.loadLibrary("MNN")
                System.loadLibrary("MNN_Express")
                nativeAvailable = true
            } catch (_: UnsatisfiedLinkError) {
                Log.w(TAG, "MNN diffusion native libraries not available")
            }
        }

        @JvmStatic
        private external fun nativeCreateDiffusion(modelPath: String, numThreads: Int)

        @JvmStatic
        private external fun nativeDiffusionRun(
            prompt: String,
            negativePrompt: String,
            width: Int,
            height: Int,
            numSteps: Int,
            guidanceScale: Float,
            seed: Long
        ): Bitmap?

        @JvmStatic
        private external fun nativeCancelDiffusion()

        @JvmStatic
        private external fun nativeDestroyDiffusion()
    }
}

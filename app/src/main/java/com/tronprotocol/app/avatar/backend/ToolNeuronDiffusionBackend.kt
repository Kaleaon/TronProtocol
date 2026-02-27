package com.tronprotocol.app.avatar.backend

import android.graphics.Bitmap
import android.util.Log
import com.tronprotocol.app.llm.ModelCatalog

/**
 * ToolNeuron-based Stable Diffusion backend.
 *
 * Ported from ToolNeuron's DiffusionEngine. Supports:
 * - QNN NPU acceleration on Qualcomm Snapdragon 8 Gen 1+ devices
 * - CPU fallback for non-Qualcomm or older devices
 * - Safety checker integration
 * - Multiple schedulers (DPM, Euler, Euler_A, DDIM, PNDM)
 * - Image-to-image with mask support (inpainting)
 *
 * Requires the `libai_sd.so` native library from ToolNeuron.
 */
class ToolNeuronDiffusionBackend : DiffusionBackend {

    override val name: String = "toolneuron"

    override val isAvailable: Boolean
        get() = nativeAvailable

    private var modelLoaded = false
    private var currentConfig: DiffusionModelConfig? = null

    override fun isModelLoaded(): Boolean = modelLoaded

    override fun loadModel(config: DiffusionModelConfig): Boolean {
        if (!isAvailable) {
            Log.w(TAG, "ToolNeuron SD native library not available")
            return false
        }

        // Determine if NPU should be used based on device SoC
        val useNpu = config.useNpu && ModelCatalog.DeviceSocInfo.isHighEndQualcomm()

        return try {
            val backendStr = if (useNpu) "qnn" else "cpu"
            nativeLoadDiffusionModel(
                config.modelPath,
                backendStr,
                config.numThreads,
                config.useSafetyChecker
            )
            modelLoaded = true
            currentConfig = config
            Log.d(TAG, "ToolNeuron SD model loaded (backend=$backendStr)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ToolNeuron SD model: ${e.message}", e)
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

            val schedulerInt = when (params.negativePrompt.let { currentConfig?.scheduler ?: DiffusionScheduler.DPM }) {
                DiffusionScheduler.DPM -> 0
                DiffusionScheduler.EULER -> 1
                DiffusionScheduler.EULER_A -> 2
                DiffusionScheduler.DDIM -> 3
                DiffusionScheduler.PNDM -> 4
            }

            val hasInputImage = params.inputImage != null

            val result = if (hasInputImage) {
                nativeImg2Img(
                    params.prompt,
                    params.negativePrompt,
                    params.inputImage!!,
                    params.maskImage,
                    params.width,
                    params.height,
                    params.numSteps,
                    params.guidanceScale,
                    params.strength,
                    params.seed,
                    schedulerInt
                )
            } else {
                nativeTxt2Img(
                    params.prompt,
                    params.negativePrompt,
                    params.width,
                    params.height,
                    params.numSteps,
                    params.guidanceScale,
                    params.seed,
                    schedulerInt
                )
            }

            if (result != null) {
                val latency = System.currentTimeMillis() - startTime
                callback.onComplete(result, latency)
            } else {
                callback.onError("ToolNeuron SD returned null result")
            }
        } catch (e: Exception) {
            callback.onError("ToolNeuron SD failed: ${e.message}")
        }
    }

    override fun cancelGeneration() {
        if (modelLoaded) {
            try {
                nativeCancelGeneration()
            } catch (_: Exception) { }
        }
    }

    override fun unloadModel() {
        if (modelLoaded) {
            try {
                nativeUnloadModel()
            } catch (_: Exception) { }
            modelLoaded = false
            currentConfig = null
        }
    }

    companion object {
        private const val TAG = "ToolNeuronDiffusion"

        private var nativeAvailable = false

        init {
            try {
                System.loadLibrary("ai_sd")
                nativeAvailable = true
                Log.d(TAG, "ToolNeuron SD native library loaded")
            } catch (_: UnsatisfiedLinkError) {
                Log.w(TAG, "ToolNeuron SD native library not available")
            }
        }

        @JvmStatic
        private external fun nativeLoadDiffusionModel(
            modelPath: String,
            backend: String,
            numThreads: Int,
            useSafetyChecker: Boolean
        )

        @JvmStatic
        private external fun nativeTxt2Img(
            prompt: String,
            negativePrompt: String,
            width: Int,
            height: Int,
            numSteps: Int,
            guidanceScale: Float,
            seed: Long,
            scheduler: Int
        ): Bitmap?

        @JvmStatic
        private external fun nativeImg2Img(
            prompt: String,
            negativePrompt: String,
            inputImage: Bitmap,
            maskImage: Bitmap?,
            width: Int,
            height: Int,
            numSteps: Int,
            guidanceScale: Float,
            strength: Float,
            seed: Long,
            scheduler: Int
        ): Bitmap?

        @JvmStatic
        private external fun nativeCancelGeneration()

        @JvmStatic
        private external fun nativeUnloadModel()
    }
}

package com.tronprotocol.app.avatar

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * General-purpose MNN vision inference engine.
 *
 * Provides a lightweight wrapper around MNN's native inference API for running
 * computer vision models (face detection, landmarks, pose estimation, classification, etc.)
 * on Android. Manages model loading, input preprocessing, and output postprocessing.
 *
 * Uses MNN's Java API via JNI for model loading and session execution.
 *
 * Native libraries required:
 * - libMNN.so (core inference engine)
 *
 * @see [MNN GitHub](https://github.com/alibaba/MNN)
 */
class MnnVisionEngine {

    private val nativeHandle = AtomicLong(0)
    private val isInitialized = AtomicBoolean(false)

    private var modelPath: String = ""
    private var inputWidth: Int = 0
    private var inputHeight: Int = 0
    private var inputChannels: Int = 3
    private var backendType: BackendType = BackendType.CPU

    val isReady: Boolean get() = isInitialized.get() && nativeHandle.get() != 0L
    val loadedModelPath: String get() = modelPath

    /** Available inference backends. */
    enum class BackendType(val nativeId: Int) {
        CPU(0),
        OPENCL(3),
        VULKAN(7),
        NNAPI(11)
    }

    /** Preprocessing configuration for model input. */
    data class PreprocessConfig(
        val meanValues: FloatArray = floatArrayOf(127.5f, 127.5f, 127.5f),
        val normValues: FloatArray = floatArrayOf(1f / 127.5f, 1f / 127.5f, 1f / 127.5f),
        val inputFormat: InputFormat = InputFormat.RGB,
        val resizeFilter: ResizeFilter = ResizeFilter.BILINEAR
    ) {
        enum class InputFormat(val channels: Int) {
            RGB(3), BGR(3), GRAY(1), RGBA(4)
        }

        enum class ResizeFilter { NEAREST, BILINEAR, BICUBIC }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PreprocessConfig) return false
            return meanValues.contentEquals(other.meanValues) &&
                    normValues.contentEquals(other.normValues) &&
                    inputFormat == other.inputFormat &&
                    resizeFilter == other.resizeFilter
        }

        override fun hashCode(): Int {
            var result = meanValues.contentHashCode()
            result = 31 * result + normValues.contentHashCode()
            result = 31 * result + inputFormat.hashCode()
            result = 31 * result + resizeFilter.hashCode()
            return result
        }
    }

    /** Inference output containing raw tensor data. */
    data class InferenceOutput(
        val data: FloatArray,
        val shape: IntArray,
        val inferenceTimeMs: Long
    ) {
        val elementCount: Int get() = data.size

        /** Reshape data to 2D [rows x cols]. */
        fun reshape2D(): Array<FloatArray> {
            if (shape.size < 2) return arrayOf(data)
            val rows = shape[shape.size - 2]
            val cols = shape[shape.size - 1]
            return Array(rows) { r ->
                FloatArray(cols) { c -> data[r * cols + c] }
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is InferenceOutput) return false
            return data.contentEquals(other.data) && shape.contentEquals(other.shape) &&
                    inferenceTimeMs == other.inferenceTimeMs
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + shape.contentHashCode()
            result = 31 * result + inferenceTimeMs.hashCode()
            return result
        }
    }

    /**
     * Load an MNN model from file.
     *
     * @param mnnModelPath Path to the .mnn model file.
     * @param width Expected input width.
     * @param height Expected input height.
     * @param channels Number of input channels (1 for grayscale, 3 for RGB).
     * @param backend Inference backend selection.
     * @param numThreads Number of inference threads (CPU backend).
     * @return true if loaded successfully.
     */
    fun loadModel(
        mnnModelPath: String,
        width: Int,
        height: Int,
        channels: Int = 3,
        backend: BackendType = BackendType.CPU,
        numThreads: Int = 4
    ): Boolean {
        if (isInitialized.get()) {
            Log.w(TAG, "Model already loaded. Call release() first.")
            return false
        }

        if (!nativeAvailable.get()) {
            Log.e(TAG, "MNN native libraries not available")
            return false
        }

        val file = File(mnnModelPath)
        if (!file.exists()) {
            Log.e(TAG, "Model file not found: $mnnModelPath")
            return false
        }

        return try {
            val handle = nativeCreateSession(
                mnnModelPath, width, height, channels,
                backend.nativeId, numThreads
            )
            if (handle == 0L) {
                Log.e(TAG, "Failed to create MNN session for: $mnnModelPath")
                return false
            }

            nativeHandle.set(handle)
            modelPath = mnnModelPath
            inputWidth = width
            inputHeight = height
            inputChannels = channels
            backendType = backend
            isInitialized.set(true)

            Log.d(TAG, "Model loaded: $mnnModelPath (${width}x${height}x$channels, ${backend.name})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}", e)
            false
        }
    }

    /**
     * Run inference on a Bitmap input.
     *
     * @param bitmap Input image (will be resized to model input dimensions).
     * @param config Preprocessing configuration.
     * @return InferenceOutput with raw tensor data, or null on failure.
     */
    fun infer(bitmap: Bitmap, config: PreprocessConfig = PreprocessConfig()): InferenceOutput? {
        val handle = nativeHandle.get()
        if (handle == 0L || !isInitialized.get()) {
            Log.w(TAG, "Model not loaded")
            return null
        }

        val resized = if (bitmap.width != inputWidth || bitmap.height != inputHeight) {
            Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        } else {
            bitmap
        }

        val floatBuffer = bitmapToFloatBuffer(resized, config)

        val startMs = System.currentTimeMillis()
        return try {
            val result = nativeInfer(handle, floatBuffer, inputWidth, inputHeight, inputChannels)
            if (result == null) {
                Log.e(TAG, "Inference returned null")
                return null
            }
            val shape = nativeGetOutputShape(handle)
            val elapsed = System.currentTimeMillis() - startMs

            InferenceOutput(result, shape ?: intArrayOf(result.size), elapsed)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}", e)
            null
        }
    }

    /**
     * Run inference on raw float input data.
     *
     * @param inputData Pre-processed float data matching model input dimensions.
     * @return InferenceOutput with raw tensor data, or null on failure.
     */
    fun inferRaw(inputData: FloatArray): InferenceOutput? {
        val handle = nativeHandle.get()
        if (handle == 0L || !isInitialized.get()) return null

        val startMs = System.currentTimeMillis()
        return try {
            val result = nativeInfer(handle, inputData, inputWidth, inputHeight, inputChannels)
                ?: return null
            val shape = nativeGetOutputShape(handle)
            val elapsed = System.currentTimeMillis() - startMs
            InferenceOutput(result, shape ?: intArrayOf(result.size), elapsed)
        } catch (e: Exception) {
            Log.e(TAG, "Raw inference failed: ${e.message}", e)
            null
        }
    }

    /**
     * Run inference and return multiple named outputs.
     */
    fun inferMultiOutput(
        bitmap: Bitmap,
        outputNames: List<String>,
        config: PreprocessConfig = PreprocessConfig()
    ): Map<String, InferenceOutput>? {
        val handle = nativeHandle.get()
        if (handle == 0L || !isInitialized.get()) return null

        val resized = if (bitmap.width != inputWidth || bitmap.height != inputHeight) {
            Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        } else {
            bitmap
        }

        val floatBuffer = bitmapToFloatBuffer(resized, config)
        val startMs = System.currentTimeMillis()

        return try {
            val results = mutableMapOf<String, InferenceOutput>()
            // First run inference
            nativeInfer(handle, floatBuffer, inputWidth, inputHeight, inputChannels)
                ?: return null

            val elapsed = System.currentTimeMillis() - startMs

            // Then extract each named output
            for (name in outputNames) {
                val data = nativeGetNamedOutput(handle, name)
                val shape = nativeGetNamedOutputShape(handle, name)
                if (data != null) {
                    results[name] = InferenceOutput(data, shape ?: intArrayOf(data.size), elapsed)
                }
            }
            results
        } catch (e: Exception) {
            Log.e(TAG, "Multi-output inference failed: ${e.message}", e)
            null
        }
    }

    /** Release all native resources. */
    fun release() {
        val handle = nativeHandle.getAndSet(0)
        if (handle != 0L && nativeAvailable.get()) {
            try {
                nativeDestroySession(handle)
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MNN session: ${e.message}", e)
            }
        }
        isInitialized.set(false)
        modelPath = ""
        Log.d(TAG, "MNN vision engine released")
    }

    /** Get model input dimensions. */
    fun getInputDimensions(): Triple<Int, Int, Int> = Triple(inputWidth, inputHeight, inputChannels)

    // ---- Internal helpers ----

    private fun bitmapToFloatBuffer(bitmap: Bitmap, config: PreprocessConfig): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val channels = config.inputFormat.channels
        val floatData = FloatArray(channels * bitmap.width * bitmap.height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF).toFloat()
            val g = ((pixel shr 8) and 0xFF).toFloat()
            val b = (pixel and 0xFF).toFloat()

            when (config.inputFormat) {
                PreprocessConfig.InputFormat.RGB -> {
                    floatData[i * 3] = (r - config.meanValues[0]) * config.normValues[0]
                    floatData[i * 3 + 1] = (g - config.meanValues[1]) * config.normValues[1]
                    floatData[i * 3 + 2] = (b - config.meanValues[2]) * config.normValues[2]
                }
                PreprocessConfig.InputFormat.BGR -> {
                    floatData[i * 3] = (b - config.meanValues[0]) * config.normValues[0]
                    floatData[i * 3 + 1] = (g - config.meanValues[1]) * config.normValues[1]
                    floatData[i * 3 + 2] = (r - config.meanValues[2]) * config.normValues[2]
                }
                PreprocessConfig.InputFormat.GRAY -> {
                    val gray = 0.299f * r + 0.587f * g + 0.114f * b
                    floatData[i] = (gray - config.meanValues[0]) * config.normValues[0]
                }
                PreprocessConfig.InputFormat.RGBA -> {
                    val a = ((pixel shr 24) and 0xFF).toFloat()
                    floatData[i * 4] = (r - config.meanValues[0]) * config.normValues[0]
                    floatData[i * 4 + 1] = (g - config.meanValues[1]) * config.normValues[1]
                    floatData[i * 4 + 2] = (b - config.meanValues[2]) * config.normValues[2]
                    floatData[i * 4 + 3] = a / 255f
                }
            }
        }
        return floatData
    }

    companion object {
        private const val TAG = "MnnVisionEngine"

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
                nativeAvailable.set(true)
                Log.d(TAG, "Loaded libMNN.so — MNN vision inference available")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libMNN.so not found — MNN vision inference unavailable. ${e.message}")
            }
        }

        // ---- JNI native methods (implemented in mnn_vision_jni.cpp) ----

        @JvmStatic private external fun nativeCreateSession(
            modelPath: String, width: Int, height: Int, channels: Int,
            backendType: Int, numThreads: Int
        ): Long

        @JvmStatic private external fun nativeInfer(
            handle: Long, inputData: FloatArray, width: Int, height: Int, channels: Int
        ): FloatArray?

        @JvmStatic private external fun nativeGetOutputShape(handle: Long): IntArray?

        @JvmStatic private external fun nativeGetNamedOutput(handle: Long, name: String): FloatArray?

        @JvmStatic private external fun nativeGetNamedOutputShape(handle: Long, name: String): IntArray?

        @JvmStatic private external fun nativeDestroySession(handle: Long)
    }
}

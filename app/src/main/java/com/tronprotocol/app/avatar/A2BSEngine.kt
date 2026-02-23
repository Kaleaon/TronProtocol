package com.tronprotocol.app.avatar

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Audio-to-BlendShape (A2BS) engine for MNN TaoAvatar.
 *
 * Converts audio input (PCM samples) into blendshape animation frames using
 * the UniTalker-MNN model. BlendShapes control facial expressions, lip-sync,
 * and body gestures on the avatar mesh.
 *
 * Pipeline: Audio PCM (ShortArray) -> A2BS Model -> BlendShapeFrame sequence
 *
 * Native libraries required:
 * - libMNN.so (core framework)
 * - libmnn_a2bs.so (audio-to-blendshape module)
 */
class A2BSEngine {

    private val nativeHandle = AtomicLong(0)
    private val isInitialized = AtomicBoolean(false)
    private var frameIndex: Int = 0

    val isReady: Boolean get() = isInitialized.get() && nativeHandle.get() != 0L

    /**
     * Initialize the A2BS engine with a model directory.
     *
     * @param modelDir Directory containing UniTalker-MNN model files.
     * @return true if initialized successfully.
     */
    fun initialize(modelDir: String): Boolean {
        if (isInitialized.get()) {
            Log.w(TAG, "A2BS engine already initialized")
            return true
        }

        if (!nativeAvailable.get()) {
            Log.e(TAG, "A2BS native libraries not available")
            return false
        }

        val dir = File(modelDir)
        if (!dir.exists() || !dir.isDirectory) {
            Log.e(TAG, "A2BS model directory not found: $modelDir")
            return false
        }

        return try {
            val handle = nativeCreateA2BS()
            if (handle == 0L) {
                Log.e(TAG, "nativeCreateA2BS() returned null handle")
                return false
            }
            nativeHandle.set(handle)

            val loadResult = nativeLoadResources(handle, modelDir)
            if (!loadResult) {
                Log.e(TAG, "Failed to load A2BS resources from $modelDir")
                nativeDestroyA2BS(handle)
                nativeHandle.set(0)
                return false
            }

            frameIndex = 0
            isInitialized.set(true)
            Log.d(TAG, "A2BS engine initialized from $modelDir")
            true
        } catch (e: Exception) {
            Log.e(TAG, "A2BS initialization failed: ${e.message}", e)
            false
        }
    }

    /**
     * Process an audio buffer and generate blendshape animation frames.
     *
     * @param audioData PCM audio samples (16-bit signed).
     * @param sampleRate Audio sample rate (typically 16000 or 44100).
     * @return A list of blendshape frames for this audio segment.
     */
    fun processAudio(audioData: ShortArray, sampleRate: Int): List<BlendShapeFrame> {
        val handle = nativeHandle.get()
        if (handle == 0L || !isInitialized.get()) {
            Log.w(TAG, "A2BS not initialized — cannot process audio")
            return emptyList()
        }

        return try {
            val rawResult = nativeProcessBuffer(handle, audioData, audioData.size, sampleRate)
            if (rawResult == null || rawResult.isEmpty()) {
                return emptyList()
            }

            // Native returns a flat float array where each chunk of BLENDSHAPE_COUNT
            // floats represents one frame's weights
            val frames = mutableListOf<BlendShapeFrame>()
            val numFrames = rawResult.size / BLENDSHAPE_COUNT

            for (i in 0 until numFrames) {
                val offset = i * BLENDSHAPE_COUNT
                val weights = rawResult.copyOfRange(offset, offset + BLENDSHAPE_COUNT)
                frames.add(
                    BlendShapeFrame(
                        index = frameIndex++,
                        timestamp = System.currentTimeMillis(),
                        weights = weights,
                        isLast = false
                    )
                )
            }

            Log.d(TAG, "Processed ${audioData.size} samples -> ${frames.size} blendshape frames")
            frames
        } catch (e: Exception) {
            Log.e(TAG, "Audio processing failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Generate a neutral (resting) blendshape frame.
     */
    fun neutralFrame(): BlendShapeFrame = BlendShapeFrame(
        index = frameIndex++,
        timestamp = System.currentTimeMillis(),
        weights = FloatArray(BLENDSHAPE_COUNT) { 0f },
        isLast = false
    )

    /**
     * Generate a blendshape frame from manually specified expression weights.
     * Useful for testing and for custom avatar animations.
     *
     * @param expressionWeights Map of blendshape names to weight values (0.0–1.0).
     */
    fun frameFromExpression(expressionWeights: Map<String, Float>): BlendShapeFrame {
        val weights = FloatArray(BLENDSHAPE_COUNT)
        expressionWeights.forEach { (name, value) ->
            val idx = BLENDSHAPE_NAMES.indexOf(name)
            if (idx >= 0) {
                weights[idx] = value.coerceIn(0f, 1f)
            }
        }
        return BlendShapeFrame(
            index = frameIndex++,
            timestamp = System.currentTimeMillis(),
            weights = weights
        )
    }

    /** Reset frame counter and internal state. */
    fun reset() {
        frameIndex = 0
        val handle = nativeHandle.get()
        if (handle != 0L && isInitialized.get()) {
            nativeResetA2BS(handle)
        }
    }

    /** Release all native resources. */
    fun destroy() {
        val handle = nativeHandle.getAndSet(0)
        if (handle != 0L && nativeAvailable.get()) {
            try {
                nativeDestroyA2BS(handle)
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying A2BS: ${e.message}", e)
            }
        }
        isInitialized.set(false)
        Log.d(TAG, "A2BS engine destroyed")
    }

    companion object {
        private const val TAG = "A2BSEngine"

        /**
         * Number of blendshape channels output by UniTalker-MNN.
         * Based on ARKit 52 face blendshapes + SMPL-X body pose parameters.
         */
        const val BLENDSHAPE_COUNT = 68

        /**
         * Standard blendshape channel names (ARKit 52 face + 16 body).
         */
        val BLENDSHAPE_NAMES = listOf(
            // ARKit face blendshapes (52)
            "eyeBlinkLeft", "eyeLookDownLeft", "eyeLookInLeft", "eyeLookOutLeft",
            "eyeLookUpLeft", "eyeSquintLeft", "eyeWideLeft",
            "eyeBlinkRight", "eyeLookDownRight", "eyeLookInRight", "eyeLookOutRight",
            "eyeLookUpRight", "eyeSquintRight", "eyeWideRight",
            "jawForward", "jawLeft", "jawRight", "jawOpen",
            "mouthClose", "mouthFunnel", "mouthPucker", "mouthLeft", "mouthRight",
            "mouthSmileLeft", "mouthSmileRight", "mouthFrownLeft", "mouthFrownRight",
            "mouthDimpleLeft", "mouthDimpleRight", "mouthStretchLeft", "mouthStretchRight",
            "mouthRollLower", "mouthRollUpper", "mouthShrugLower", "mouthShrugUpper",
            "mouthPressLeft", "mouthPressRight", "mouthLowerDownLeft", "mouthLowerDownRight",
            "mouthUpperUpLeft", "mouthUpperUpRight",
            "browDownLeft", "browDownRight", "browInnerUp", "browOuterUpLeft", "browOuterUpRight",
            "cheekPuff", "cheekSquintLeft", "cheekSquintRight",
            "noseSneerLeft", "noseSneerRight", "tongueOut",
            // SMPL-X body pose parameters (16)
            "headYaw", "headPitch", "headRoll",
            "neckYaw", "neckPitch", "neckRoll",
            "shoulderLeftRotation", "shoulderRightRotation",
            "elbowLeftFlex", "elbowRightFlex",
            "wristLeftRotation", "wristRightRotation",
            "spineYaw", "spinePitch",
            "bodySwayX", "bodySwayZ"
        )

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
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libMNN.so not found: ${e.message}")
            }

            try {
                System.loadLibrary("mnn_a2bs")
                nativeAvailable.set(true)
                Log.d(TAG, "Loaded libmnn_a2bs.so — A2BS audio-to-blendshape available")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libmnn_a2bs.so not found — A2BS unavailable. " +
                        "Build MNN from source to enable. ${e.message}")
            }
        }

        // ---- JNI native methods ----

        @JvmStatic private external fun nativeCreateA2BS(): Long
        @JvmStatic private external fun nativeLoadResources(handle: Long, modelDir: String): Boolean
        @JvmStatic private external fun nativeProcessBuffer(
            handle: Long, audioData: ShortArray, length: Int, sampleRate: Int
        ): FloatArray?
        @JvmStatic private external fun nativeResetA2BS(handle: Long)
        @JvmStatic private external fun nativeDestroyA2BS(handle: Long)
    }
}

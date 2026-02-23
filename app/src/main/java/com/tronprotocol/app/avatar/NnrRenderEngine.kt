package com.tronprotocol.app.avatar

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Neural Network Rendering (NNR) engine for MNN TaoAvatar.
 *
 * Wraps the native C++ NNR renderer via JNI. The renderer uses 3D Gaussian Splatting
 * with distilled deformation networks to produce photorealistic avatar frames in real time.
 *
 * Pipeline: BlendShapeFrame -> NNR Scene Update -> Gaussian Splatting Render -> Surface
 *
 * Native libraries required:
 * - libMNN.so (core framework)
 * - libmnn_nnr.so (neural rendering module)
 *
 * @see [TaoAvatar Paper](https://arxiv.org/html/2503.17032v1)
 */
class NnrRenderEngine {

    private val nativeHandle = AtomicLong(0)
    private val isInitialized = AtomicBoolean(false)
    private val currentState = AtomicReference(RenderState.UNINITIALIZED)
    private var renderSurface: Surface? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    // Camera parameters for avatar viewing
    private var cameraYaw: Float = 0f
    private var cameraPitch: Float = 0f
    private var cameraDistance: Float = 2.5f
    private var cameraFov: Float = 45f

    // Rendering metrics
    private var totalFramesRendered: Long = 0
    private var lastFrameTimeNs: Long = 0
    private var fps: Float = 0f

    enum class RenderState {
        UNINITIALIZED,
        LOADING_RESOURCES,
        READY,
        RENDERING,
        ERROR,
        DESTROYED
    }

    val state: RenderState get() = currentState.get()
    val isReady: Boolean get() = currentState.get() == RenderState.READY || currentState.get() == RenderState.RENDERING
    val currentFps: Float get() = fps
    val framesRendered: Long get() = totalFramesRendered

    /**
     * Initialize the NNR renderer with model resources.
     *
     * @param config Avatar configuration containing NNR model paths.
     * @param surface The rendering surface (from TextureView or SurfaceView).
     * @param width Surface width in pixels.
     * @param height Surface height in pixels.
     * @return true if initialization succeeded.
     */
    fun initialize(config: AvatarConfig, surface: Surface, width: Int, height: Int): Boolean {
        if (isInitialized.get()) {
            Log.w(TAG, "NnrRenderEngine already initialized — call destroy() first")
            return false
        }

        if (!nativeAvailable.get()) {
            Log.e(TAG, "NNR native libraries not available")
            currentState.set(RenderState.ERROR)
            return false
        }

        // Validate model files exist
        val nnrDir = File(config.nnrModelDir)
        if (!nnrDir.exists()) {
            Log.e(TAG, "NNR model directory not found: ${config.nnrModelDir}")
            currentState.set(RenderState.ERROR)
            return false
        }

        val requiredFiles = listOf("compute.nnr", "render_full.nnr", "background.nnr", "input_nnr.json")
        val missing = requiredFiles.filter { !File(nnrDir, it).exists() }
        if (missing.isNotEmpty()) {
            Log.e(TAG, "Missing NNR model files: $missing")
            currentState.set(RenderState.ERROR)
            return false
        }

        currentState.set(RenderState.LOADING_RESOURCES)
        renderSurface = surface
        surfaceWidth = width
        surfaceHeight = height

        return try {
            val handle = nativeCreateNNR()
            if (handle == 0L) {
                Log.e(TAG, "nativeCreateNNR() returned null handle")
                currentState.set(RenderState.ERROR)
                return false
            }
            nativeHandle.set(handle)

            val initResult = nativeInitNNR(
                handle, surface,
                config.nnrComputePath,
                config.nnrRenderPath,
                config.nnrBackgroundPath,
                config.nnrInputConfigPath,
                width, height
            )

            if (!initResult) {
                Log.e(TAG, "nativeInitNNR() failed")
                nativeDestroyNNR(handle)
                nativeHandle.set(0)
                currentState.set(RenderState.ERROR)
                return false
            }

            isInitialized.set(true)
            currentState.set(RenderState.READY)
            Log.d(TAG, "NNR renderer initialized: ${width}x${height}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "NNR initialization failed: ${e.message}", e)
            currentState.set(RenderState.ERROR)
            false
        }
    }

    /**
     * Render a single frame with the given blendshape animation data.
     *
     * @param frame Blendshape weights for this frame (from A2BS engine).
     * @return true if the frame was rendered successfully.
     */
    fun renderFrame(frame: BlendShapeFrame): Boolean {
        val handle = nativeHandle.get()
        if (handle == 0L || !isInitialized.get()) {
            return false
        }

        currentState.set(RenderState.RENDERING)
        val startNs = System.nanoTime()

        return try {
            val success = nativeUpdateAndRender(
                handle,
                frame.weights,
                frame.weights.size,
                cameraYaw, cameraPitch, cameraDistance, cameraFov,
                frame.timestamp
            )

            val elapsed = System.nanoTime() - startNs
            if (lastFrameTimeNs > 0) {
                fps = 1_000_000_000f / elapsed
            }
            lastFrameTimeNs = startNs
            totalFramesRendered++

            currentState.set(RenderState.READY)
            success
        } catch (e: Exception) {
            Log.e(TAG, "Render frame failed: ${e.message}", e)
            currentState.set(RenderState.READY)
            false
        }
    }

    /**
     * Render an idle frame (no blendshape animation, neutral pose).
     */
    fun renderIdleFrame(): Boolean {
        val handle = nativeHandle.get()
        if (handle == 0L || !isInitialized.get()) return false

        return try {
            nativeRenderIdle(handle, cameraYaw, cameraPitch, cameraDistance, cameraFov)
        } catch (e: Exception) {
            Log.e(TAG, "Idle render failed: ${e.message}", e)
            false
        }
    }

    /**
     * Update camera orientation for the avatar view.
     */
    fun setCamera(yaw: Float, pitch: Float, distance: Float = cameraDistance, fov: Float = cameraFov) {
        cameraYaw = yaw
        cameraPitch = pitch.coerceIn(-80f, 80f)
        cameraDistance = distance.coerceIn(0.5f, 10f)
        cameraFov = fov.coerceIn(20f, 120f)
    }

    /**
     * Handle surface size change.
     */
    fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        val handle = nativeHandle.get()
        if (handle != 0L && isInitialized.get()) {
            nativeOnSurfaceChanged(handle, width, height)
        }
    }

    /**
     * Reset the avatar to neutral pose.
     */
    fun reset() {
        val handle = nativeHandle.get()
        if (handle != 0L && isInitialized.get()) {
            nativeReset(handle)
        }
        cameraYaw = 0f
        cameraPitch = 0f
        cameraDistance = 2.5f
    }

    /** Get rendering performance metrics. */
    fun getMetrics(): Map<String, Any> = mapOf(
        "state" to currentState.get().name,
        "initialized" to isInitialized.get(),
        "native_available" to nativeAvailable.get(),
        "total_frames" to totalFramesRendered,
        "current_fps" to fps,
        "surface_width" to surfaceWidth,
        "surface_height" to surfaceHeight,
        "camera_yaw" to cameraYaw,
        "camera_pitch" to cameraPitch,
        "camera_distance" to cameraDistance
    )

    /** Release all native resources. */
    fun destroy() {
        val handle = nativeHandle.getAndSet(0)
        if (handle != 0L && nativeAvailable.get()) {
            try {
                nativeDestroyNNR(handle)
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying NNR: ${e.message}", e)
            }
        }
        isInitialized.set(false)
        renderSurface = null
        currentState.set(RenderState.DESTROYED)
        Log.d(TAG, "NNR renderer destroyed (rendered $totalFramesRendered frames)")
    }

    companion object {
        private const val TAG = "NnrRenderEngine"

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
                Log.d(TAG, "Loaded libMNN.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libMNN.so not found: ${e.message}")
            }

            try {
                System.loadLibrary("mnn_nnr")
                nativeAvailable.set(true)
                Log.d(TAG, "Loaded libmnn_nnr.so — NNR avatar rendering available")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libmnn_nnr.so not found — avatar rendering unavailable. " +
                        "Build MNN from source with -DMNN_BUILD_NNR=true. ${e.message}")
            }
        }

        // ---- JNI native methods (implemented in nnr_jni.cpp) ----

        @JvmStatic private external fun nativeCreateNNR(): Long

        @JvmStatic private external fun nativeInitNNR(
            handle: Long, surface: Surface,
            computePath: String, renderPath: String,
            backgroundPath: String, inputConfigPath: String,
            width: Int, height: Int
        ): Boolean

        @JvmStatic private external fun nativeUpdateAndRender(
            handle: Long, blendWeights: FloatArray, numWeights: Int,
            cameraYaw: Float, cameraPitch: Float, cameraDistance: Float, cameraFov: Float,
            timestamp: Long
        ): Boolean

        @JvmStatic private external fun nativeRenderIdle(
            handle: Long,
            cameraYaw: Float, cameraPitch: Float, cameraDistance: Float, cameraFov: Float
        ): Boolean

        @JvmStatic private external fun nativeOnSurfaceChanged(handle: Long, width: Int, height: Int)

        @JvmStatic private external fun nativeReset(handle: Long)

        @JvmStatic private external fun nativeDestroyNNR(handle: Long)
    }
}

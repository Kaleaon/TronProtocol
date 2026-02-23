package com.tronprotocol.app.avatar

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Orchestrates the full MNN TaoAvatar pipeline: model management, NNR rendering,
 * A2BS animation, and custom avatar uploads.
 *
 * This is the primary entry point for avatar operations. It coordinates:
 * - Device capability assessment
 * - Model downloading and verification
 * - NNR render engine lifecycle
 * - A2BS audio-to-blendshape animation
 * - Custom avatar/skeleton management
 *
 * Pipeline: [Audio/Text] -> A2BS -> BlendShapeFrames -> NNR -> Surface
 */
class AvatarSessionManager(context: Context) {

    private val appContext = context.applicationContext
    val resourceManager = AvatarResourceManager(appContext)
    private val customAvatarManager = CustomAvatarManager(appContext, resourceManager)

    private var nnrEngine: NnrRenderEngine? = null
    private var a2bsEngine: A2BSEngine? = null

    private val currentState = AtomicReference(AvatarState.UNINITIALIZED)
    private var activeConfig: AvatarConfig? = null
    private val stats = ConcurrentHashMap<String, Any>()

    private val renderExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Avatar-Render").apply { priority = Thread.MAX_PRIORITY - 1 }
    }

    val state: AvatarState get() = currentState.get()
    val isReady: Boolean get() = currentState.get() == AvatarState.READY || currentState.get() == AvatarState.RENDERING
    val activeAvatar: AvatarConfig? get() = activeConfig

    /**
     * Assess device capability for running the TaoAvatar pipeline.
     */
    fun assessDevice(): DeviceAssessment {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val availableRamMb = memInfo.availMem / (1024 * 1024)
        val cpuArch = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "unknown"
        val supportsArm64 = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

        val isNnrAvailable = NnrRenderEngine.isNativeAvailable()
        val isA2bsAvailable = A2BSEngine.isNativeAvailable()

        val canRunAvatar = supportsArm64 && totalRamMb >= AvatarModelCatalog.MIN_RAM_MB
        val recommended = AvatarModelCatalog.recommendPreset(totalRamMb)

        val reason = when {
            !supportsArm64 -> "Device does not support arm64-v8a — TaoAvatar requires 64-bit ARM"
            totalRamMb < AvatarModelCatalog.MIN_RAM_MB ->
                "Insufficient RAM: ${totalRamMb}MB total, ${AvatarModelCatalog.MIN_RAM_MB}MB minimum required"
            !isNnrAvailable && !isA2bsAvailable ->
                "MNN avatar native libraries not installed — build from github.com/alibaba/MNN"
            !isNnrAvailable -> "NNR native library missing — build MNN with -DMNN_BUILD_NNR=true"
            !isA2bsAvailable -> "A2BS native library missing — build MNN with A2BS support"
            else -> "Device meets requirements for TaoAvatar (${totalRamMb}MB RAM, $cpuArch)"
        }

        return DeviceAssessment(
            totalRamMb = totalRamMb,
            availableRamMb = availableRamMb,
            cpuArch = cpuArch,
            supportsArm64 = supportsArm64,
            isNnrAvailable = isNnrAvailable,
            isA2bsAvailable = isA2bsAvailable,
            canRunAvatar = canRunAvatar,
            recommendedPreset = recommended,
            reason = reason
        )
    }

    /**
     * Download all components for an avatar preset.
     *
     * @param presetId The preset ID from AvatarModelCatalog.
     * @param listener Download progress callback.
     */
    fun downloadPreset(
        presetId: String,
        listener: AvatarResourceManager.DownloadListener
    ): AvatarOperationResult {
        val preset = AvatarModelCatalog.getPreset(presetId)
            ?: return AvatarOperationResult.fail("Unknown preset: $presetId")

        currentState.set(AvatarState.DOWNLOADING_MODELS)

        for ((_, componentId) in preset.componentIds) {
            if (resourceManager.isComponentDownloaded(componentId)) {
                Log.d(TAG, "Component already downloaded: $componentId")
                continue
            }

            val entry = AvatarModelCatalog.getComponent(componentId)
            if (entry == null) {
                Log.w(TAG, "Unknown component in preset: $componentId")
                continue
            }

            resourceManager.downloadComponent(entry, listener)
        }

        return AvatarOperationResult.ok(
            "Downloading ${preset.componentIds.size} components for preset: ${preset.name}"
        )
    }

    /**
     * Download a single avatar model component.
     */
    fun downloadComponent(
        componentId: String,
        listener: AvatarResourceManager.DownloadListener
    ): AvatarOperationResult {
        val entry = AvatarModelCatalog.getComponent(componentId)
            ?: return AvatarOperationResult.fail("Unknown component: $componentId")

        if (resourceManager.isComponentDownloaded(componentId)) {
            return AvatarOperationResult.ok("Component already downloaded: $componentId")
        }

        resourceManager.downloadComponent(entry, listener)
        return AvatarOperationResult.ok("Downloading: ${entry.name} (~${entry.sizeEstimateMb}MB)")
    }

    /**
     * Load an avatar from a preset configuration and prepare for rendering.
     *
     * @param presetId The preset to load.
     * @param surface Rendering surface.
     * @param width Surface width.
     * @param height Surface height.
     */
    fun loadAvatar(
        presetId: String,
        surface: Surface,
        width: Int,
        height: Int
    ): AvatarOperationResult {
        val preset = AvatarModelCatalog.getPreset(presetId)
            ?: return AvatarOperationResult.fail("Unknown preset: $presetId")

        val config = resourceManager.buildConfigFromPreset(preset)
            ?: return AvatarOperationResult.fail("Failed to build config from preset — components may not be downloaded")

        return loadAvatarFromConfig(config, surface, width, height)
    }

    /**
     * Load an avatar from a specific AvatarConfig.
     */
    fun loadAvatarFromConfig(
        config: AvatarConfig,
        surface: Surface,
        width: Int,
        height: Int
    ): AvatarOperationResult {
        // Unload any existing avatar
        unloadAvatar()

        // Initialize NNR
        currentState.set(AvatarState.LOADING_NNR)
        val nnr = NnrRenderEngine()
        val nnrSuccess = nnr.initialize(config, surface, width, height)
        if (!nnrSuccess) {
            nnr.destroy()
            currentState.set(AvatarState.ERROR)
            return AvatarOperationResult.fail("Failed to initialize NNR renderer")
        }
        nnrEngine = nnr

        // Initialize A2BS
        currentState.set(AvatarState.LOADING_A2BS)
        if (config.a2bsModelDir.isNotEmpty() && File(config.a2bsModelDir).exists()) {
            val a2bs = A2BSEngine()
            val a2bsSuccess = a2bs.initialize(config.a2bsModelDir)
            if (a2bsSuccess) {
                a2bsEngine = a2bs
            } else {
                Log.w(TAG, "A2BS initialization failed — avatar will render without animation")
            }
        }

        activeConfig = config
        currentState.set(AvatarState.READY)
        resourceManager.saveAvatarConfig(config)

        Log.d(TAG, "Avatar loaded: ${config.displayName}")
        return AvatarOperationResult.ok(
            "Avatar loaded: ${config.displayName}",
            mapOf(
                "nnr_ready" to true,
                "a2bs_ready" to (a2bsEngine?.isReady == true),
                "render_size" to "${width}x${height}"
            )
        )
    }

    /**
     * Load a custom user-uploaded avatar.
     */
    fun loadCustomAvatar(
        avatarName: String,
        surface: Surface,
        width: Int,
        height: Int
    ): AvatarOperationResult {
        val config = customAvatarManager.getAvatarConfig(avatarName)
            ?: return AvatarOperationResult.fail("Custom avatar not found: $avatarName")

        return loadAvatarFromConfig(config, surface, width, height)
    }

    /**
     * Process audio and render animated frames.
     *
     * @param audioData PCM audio samples.
     * @param sampleRate Audio sample rate.
     * @return Number of frames rendered.
     */
    fun processAndRender(audioData: ShortArray, sampleRate: Int): Int {
        val a2bs = a2bsEngine ?: return 0
        val nnr = nnrEngine ?: return 0

        if (!a2bs.isReady || !nnr.isReady) return 0

        val frames = a2bs.processAudio(audioData, sampleRate)
        var rendered = 0

        for (frame in frames) {
            if (nnr.renderFrame(frame)) {
                rendered++
            }
        }

        currentState.set(if (rendered > 0) AvatarState.RENDERING else AvatarState.READY)
        return rendered
    }

    /**
     * Render an idle (neutral) avatar frame.
     */
    fun renderIdle(): Boolean {
        return nnrEngine?.renderIdleFrame() ?: false
    }

    /**
     * Render a specific blendshape expression.
     *
     * @param expressionWeights Map of blendshape names to weight values.
     */
    fun renderExpression(expressionWeights: Map<String, Float>): Boolean {
        val a2bs = a2bsEngine ?: return false
        val nnr = nnrEngine ?: return false

        val frame = a2bs.frameFromExpression(expressionWeights)
        return nnr.renderFrame(frame)
    }

    /**
     * Update camera view of the avatar.
     */
    fun setCamera(yaw: Float, pitch: Float, distance: Float = 2.5f) {
        nnrEngine?.setCamera(yaw, pitch, distance)
    }

    /** Unload the current avatar and free resources. */
    fun unloadAvatar() {
        nnrEngine?.destroy()
        nnrEngine = null
        a2bsEngine?.destroy()
        a2bsEngine = null
        activeConfig = null
        currentState.set(AvatarState.UNINITIALIZED)
    }

    // ---- Custom Avatar Operations ----

    /**
     * Import a custom avatar from a user-provided directory.
     */
    fun importCustomAvatar(
        avatarName: String,
        sourcePath: String,
        skeletonPath: String? = null
    ): AvatarOperationResult = customAvatarManager.importAvatar(avatarName, sourcePath, skeletonPath)

    /**
     * Import a custom avatar from individual component files.
     */
    fun importCustomAvatarFiles(
        avatarName: String,
        nnrDir: String,
        a2bsDir: String? = null,
        skeletonFile: String? = null,
        thumbnailFile: String? = null
    ): AvatarOperationResult = customAvatarManager.importAvatarFiles(
        avatarName, nnrDir, a2bsDir, skeletonFile, thumbnailFile
    )

    /**
     * Upload a custom skeleton file for an existing avatar.
     */
    fun uploadSkeleton(avatarName: String, skeletonPath: String): AvatarOperationResult =
        customAvatarManager.uploadSkeleton(avatarName, skeletonPath)

    /**
     * Upload a custom thumbnail image for an avatar.
     */
    fun uploadThumbnail(avatarName: String, thumbnailPath: String): AvatarOperationResult =
        customAvatarManager.uploadThumbnail(avatarName, thumbnailPath)

    /** List all custom avatars. */
    fun listCustomAvatars(): List<CustomAvatarManager.CustomAvatarInfo> =
        customAvatarManager.listAvatars()

    /** Delete a custom avatar. */
    fun deleteCustomAvatar(avatarName: String): AvatarOperationResult =
        customAvatarManager.deleteAvatar(avatarName)

    /** Validate a skeleton file format and structure. */
    fun validateSkeleton(skeletonPath: String): AvatarOperationResult =
        customAvatarManager.validateSkeleton(skeletonPath)

    /**
     * List all supported avatar skeleton formats.
     */
    fun supportedSkeletonFormats(): List<String> = CustomAvatarManager.SUPPORTED_SKELETON_FORMATS

    /**
     * List all supported avatar model formats.
     */
    fun supportedModelFormats(): List<String> = CustomAvatarManager.SUPPORTED_MODEL_FORMATS

    /** Get comprehensive session statistics. */
    fun getStats(): Map<String, Any> {
        stats["state"] = currentState.get().name
        stats["active_avatar"] = activeConfig?.displayName ?: "none"
        stats["nnr_available"] = NnrRenderEngine.isNativeAvailable()
        stats["a2bs_available"] = A2BSEngine.isNativeAvailable()
        stats["nnr_ready"] = nnrEngine?.isReady ?: false
        stats["a2bs_ready"] = a2bsEngine?.isReady ?: false
        stats["downloaded_components"] = resourceManager.getDownloadedComponents().size
        stats["custom_avatars"] = resourceManager.getCustomAvatars().size

        nnrEngine?.let { stats.putAll(it.getMetrics().mapKeys { (k, _) -> "nnr_$k" }) }

        return ConcurrentHashMap(stats)
    }

    /** Shutdown manager and release all resources. */
    fun shutdown() {
        unloadAvatar()
        resourceManager.shutdown()
        renderExecutor.shutdownNow()
        Log.d(TAG, "AvatarSessionManager shut down")
    }

    data class DeviceAssessment(
        val totalRamMb: Long,
        val availableRamMb: Long,
        val cpuArch: String,
        val supportsArm64: Boolean,
        val isNnrAvailable: Boolean,
        val isA2bsAvailable: Boolean,
        val canRunAvatar: Boolean,
        val recommendedPreset: AvatarModelCatalog.AvatarPreset?,
        val reason: String
    )

    companion object {
        private const val TAG = "AvatarSessionManager"
    }
}

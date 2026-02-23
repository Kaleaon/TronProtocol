package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.avatar.AvatarModelCatalog
import com.tronprotocol.app.avatar.AvatarResourceManager
import com.tronprotocol.app.avatar.AvatarSessionManager
import com.tronprotocol.app.avatar.CustomAvatarManager
import com.tronprotocol.app.avatar.NnrRenderEngine
import com.tronprotocol.app.avatar.A2BSEngine
import java.io.File

/**
 * MNN TaoAvatar Plugin — 3D avatar intelligence with neural rendering and animation.
 *
 * Integrates Alibaba's MNN TaoAvatar pipeline for on-device 3D avatar rendering,
 * audio-driven facial animation, and custom avatar management. Based on 3D Gaussian
 * Splatting with distilled deformation networks for real-time photorealistic avatars.
 *
 * Commands:
 *   status                          - Device capability and avatar state
 *   catalog                         - List available model components
 *   presets                         - List avatar presets (bundled component sets)
 *   recommend                       - Recommend best preset for this device
 *   download|<component_id>         - Download a model component
 *   download_preset|<preset_id>     - Download all components for a preset
 *   downloaded                      - List downloaded components
 *   delete|<component_id>           - Delete a downloaded component
 *   custom_list                     - List custom user-uploaded avatars
 *   custom_import|<name>|<path>     - Import custom avatar from directory
 *   custom_import_files|<name>|<nnr_dir>[|<a2bs_dir>] - Import from individual dirs
 *   custom_delete|<name>            - Delete a custom avatar
 *   upload_skeleton|<avatar>|<path> - Upload skeleton file for a custom avatar
 *   upload_thumbnail|<avatar>|<path> - Upload thumbnail for a custom avatar
 *   validate_skeleton|<path>        - Validate a skeleton file
 *   formats                         - List supported file formats
 *   blendshapes                     - List supported blendshape channels
 *   stats                           - Show session statistics
 *
 * @see AvatarSessionManager
 * @see NnrRenderEngine
 * @see A2BSEngine
 * @see [MNN TaoAvatar](https://github.com/alibaba/MNN/tree/master/apps/Android/MnnTaoAvatar)
 * @see [TaoAvatar Paper](https://arxiv.org/html/2503.17032v1)
 */
class MnnAvatarPlugin : Plugin {

    override val id = "mnn_avatar"
    override val name = "MNN TaoAvatar"
    override val description = "3D avatar rendering and animation via MNN TaoAvatar " +
            "(neural rendering, audio-to-blendshape, custom avatar support)"
    override var isEnabled = true

    private var sessionManager: AvatarSessionManager? = null
    private var appContext: Context? = null

    override fun requiredCapabilities(): Set<Capability> =
        setOf(Capability.AVATAR_RENDER, Capability.AVATAR_ANIMATE)

    override fun initialize(context: Context) {
        appContext = context.applicationContext
        sessionManager = AvatarSessionManager(context)
        Log.d(TAG, "MnnAvatarPlugin initialized")
    }

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()
        val manager = sessionManager
            ?: return PluginResult.error("Avatar plugin not initialized", elapsed(startTime))

        val parts = input.split("|", limit = 5)
        val command = parts[0].trim().lowercase()

        return try {
            when (command) {
                "status" -> handleStatus(manager, startTime)
                "catalog" -> handleCatalog(startTime)
                "presets" -> handlePresets(startTime)
                "recommend" -> handleRecommend(manager, startTime)
                "download" -> handleDownload(manager, parts, startTime)
                "download_preset" -> handleDownloadPreset(manager, parts, startTime)
                "downloaded" -> handleDownloaded(manager, startTime)
                "delete" -> handleDelete(manager, parts, startTime)
                "custom_list" -> handleCustomList(manager, startTime)
                "custom_import" -> handleCustomImport(manager, parts, startTime)
                "custom_import_files" -> handleCustomImportFiles(manager, parts, startTime)
                "custom_delete" -> handleCustomDelete(manager, parts, startTime)
                "upload_skeleton" -> handleUploadSkeleton(manager, parts, startTime)
                "upload_thumbnail" -> handleUploadThumbnail(manager, parts, startTime)
                "validate_skeleton" -> handleValidateSkeleton(manager, parts, startTime)
                "formats" -> handleFormats(startTime)
                "blendshapes" -> handleBlendshapes(startTime)
                "stats" -> handleStats(manager, startTime)
                else -> PluginResult.error(
                    "Unknown command: $command. " +
                    "Available: status, catalog, presets, recommend, download, download_preset, " +
                    "downloaded, delete, custom_list, custom_import, custom_import_files, " +
                    "custom_delete, upload_skeleton, upload_thumbnail, validate_skeleton, " +
                    "formats, blendshapes, stats",
                    elapsed(startTime)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command '$command' failed: ${e.message}", e)
            PluginResult.error("Command failed: ${e.message}", elapsed(startTime))
        }
    }

    override fun destroy() {
        sessionManager?.shutdown()
        sessionManager = null
        appContext = null
        Log.d(TAG, "MnnAvatarPlugin destroyed")
    }

    // ---- Command handlers ----

    private fun handleStatus(manager: AvatarSessionManager, start: Long): PluginResult {
        val assessment = manager.assessDevice()
        val sb = StringBuilder()
        sb.appendLine("=== MNN TaoAvatar Status ===")
        sb.appendLine("Device: ${assessment.cpuArch} | ARM64: ${assessment.supportsArm64}")
        sb.appendLine("RAM: ${assessment.totalRamMb}MB total, ${assessment.availableRamMb}MB available")
        sb.appendLine("NNR Native: ${if (assessment.isNnrAvailable) "available" else "NOT INSTALLED"}")
        sb.appendLine("A2BS Native: ${if (assessment.isA2bsAvailable) "available" else "NOT INSTALLED"}")
        sb.appendLine("Can Run Avatar: ${assessment.canRunAvatar}")
        sb.appendLine("Reason: ${assessment.reason}")
        sb.appendLine()
        sb.appendLine("Pipeline State: ${manager.state}")
        sb.appendLine("Active Avatar: ${manager.activeAvatar?.displayName ?: "none"}")
        sb.appendLine("Downloaded Components: ${manager.resourceManager.getDownloadedComponents().size}")
        sb.appendLine("Custom Avatars: ${manager.resourceManager.getCustomAvatars().size}")

        assessment.recommendedPreset?.let {
            sb.appendLine()
            sb.appendLine("Recommended Preset: ${it.name}")
            sb.appendLine("  ${it.description}")
            sb.appendLine("  Total Size: ~${it.totalSizeMb}MB")
        }

        if (!assessment.isNnrAvailable || !assessment.isA2bsAvailable) {
            sb.appendLine()
            sb.appendLine("--- Native Library Setup ---")
            sb.appendLine("Build MNN from source: https://github.com/alibaba/MNN")
            sb.appendLine("Required flags: -DMNN_BUILD_NNR=true")
            sb.appendLine("Copy .so files to: app/src/main/jniLibs/arm64-v8a/")
            sb.appendLine("Required: libMNN.so, libmnn_nnr.so, libmnn_a2bs.so")
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun handleCatalog(start: Long): PluginResult {
        val sb = StringBuilder()
        sb.appendLine("=== Avatar Model Catalog ===")
        sb.appendLine()

        AvatarModelCatalog.AvatarComponent.entries.forEach { type ->
            val models = AvatarModelCatalog.getComponentsByType(type)
            if (models.isNotEmpty()) {
                sb.appendLine("--- ${type.name} ---")
                models.forEach { entry ->
                    sb.appendLine("  ${entry.id}")
                    sb.appendLine("    ${entry.name} (~${entry.sizeEstimateMb}MB)")
                    sb.appendLine("    ${entry.description}")
                }
                sb.appendLine()
            }
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun handlePresets(start: Long): PluginResult {
        val sb = StringBuilder()
        sb.appendLine("=== Avatar Presets ===")

        AvatarModelCatalog.presets.forEach { preset ->
            sb.appendLine()
            sb.appendLine("${preset.id}: ${preset.name}")
            sb.appendLine("  ${preset.description}")
            sb.appendLine("  Total Size: ~${preset.totalSizeMb}MB | Min RAM: ${preset.minRamMb}MB")
            sb.appendLine("  Components:")
            preset.componentIds.forEach { (type, id) ->
                sb.appendLine("    $type -> $id")
            }
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun handleRecommend(manager: AvatarSessionManager, start: Long): PluginResult {
        val assessment = manager.assessDevice()
        val sb = StringBuilder()

        if (!assessment.canRunAvatar) {
            sb.appendLine("Device does not meet minimum requirements for TaoAvatar.")
            sb.appendLine("Reason: ${assessment.reason}")
            sb.appendLine("Minimum: ARM64, 8GB RAM, Snapdragon 8 Gen 3 or equivalent")
            return PluginResult.success(sb.toString(), elapsed(start))
        }

        val preset = assessment.recommendedPreset
        if (preset == null) {
            sb.appendLine("No suitable preset found for this device.")
        } else {
            sb.appendLine("Recommended: ${preset.name} (${preset.id})")
            sb.appendLine("  ${preset.description}")
            sb.appendLine("  Download size: ~${preset.totalSizeMb}MB")
            sb.appendLine()
            sb.appendLine("To download: download_preset|${preset.id}")
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun handleDownload(
        manager: AvatarSessionManager,
        parts: List<String>,
        start: Long
    ): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: download|<component_id>", elapsed(start))
        }
        val componentId = parts[1].trim()
        val result = manager.downloadComponent(componentId, LoggingDownloadListener())
        return if (result.success) {
            PluginResult.success(result.message, elapsed(start))
        } else {
            PluginResult.error(result.message, elapsed(start))
        }
    }

    private fun handleDownloadPreset(
        manager: AvatarSessionManager,
        parts: List<String>,
        start: Long
    ): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: download_preset|<preset_id>", elapsed(start))
        }
        val presetId = parts[1].trim()
        val result = manager.downloadPreset(presetId, LoggingDownloadListener())
        return if (result.success) {
            PluginResult.success(result.message, elapsed(start))
        } else {
            PluginResult.error(result.message, elapsed(start))
        }
    }

    private fun handleDownloaded(manager: AvatarSessionManager, start: Long): PluginResult {
        val components = manager.resourceManager.getDownloadedComponents()
        if (components.isEmpty()) {
            return PluginResult.success("No avatar components downloaded yet.", elapsed(start))
        }

        val sb = StringBuilder()
        sb.appendLine("=== Downloaded Avatar Components ===")
        components.forEach { id ->
            val dir = manager.resourceManager.getComponentDir(id)
            val sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val sizeMb = sizeBytes / (1024 * 1024)
            sb.appendLine("  $id (${sizeMb}MB) -> ${dir.absolutePath}")
        }
        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun handleDelete(
        manager: AvatarSessionManager,
        parts: List<String>,
        start: Long
    ): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: delete|<component_id>", elapsed(start))
        }
        val componentId = parts[1].trim()
        val deleted = manager.resourceManager.deleteComponent(componentId)
        return if (deleted) {
            PluginResult.success("Deleted component: $componentId", elapsed(start))
        } else {
            PluginResult.error("Component not found: $componentId", elapsed(start))
        }
    }

    private fun handleCustomList(manager: AvatarSessionManager, start: Long): PluginResult {
        val avatars = manager.listCustomAvatars()
        if (avatars.isEmpty()) {
            return PluginResult.success("No custom avatars. Use custom_import to add one.", elapsed(start))
        }

        val sb = StringBuilder()
        sb.appendLine("=== Custom Avatars ===")
        avatars.forEach { info ->
            val sizeMb = info.sizeBytes / (1024 * 1024)
            sb.appendLine()
            sb.appendLine("  ${info.name} (${sizeMb}MB)")
            sb.appendLine("    NNR: ${if (info.hasNnr) "yes" else "NO"}")
            sb.appendLine("    A2BS: ${if (info.hasA2bs) "yes" else "no"}")
            sb.appendLine("    Skeleton: ${info.skeletonFormat ?: "none"}")
            sb.appendLine("    Thumbnail: ${if (info.hasThumbnail) "yes" else "no"}")
            sb.appendLine("    Path: ${info.directory.absolutePath}")
        }
        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun handleCustomImport(
        manager: AvatarSessionManager,
        parts: List<String>,
        start: Long
    ): PluginResult {
        if (parts.size < 3) {
            return PluginResult.error("Usage: custom_import|<name>|<path>[|<skeleton_path>]", elapsed(start))
        }
        val name = parts[1].trim()
        val path = parts[2].trim()
        val skeletonPath = parts.getOrNull(3)?.trim()

        val result = manager.importCustomAvatar(name, path, skeletonPath)
        return if (result.success) {
            PluginResult.success(result.message, elapsed(start))
        } else {
            PluginResult.error(result.message, elapsed(start))
        }
    }

    private fun handleCustomImportFiles(
        manager: AvatarSessionManager,
        parts: List<String>,
        start: Long
    ): PluginResult {
        if (parts.size < 3) {
            return PluginResult.error(
                "Usage: custom_import_files|<name>|<nnr_dir>[|<a2bs_dir>][|<skeleton_file>]",
                elapsed(start)
            )
        }
        val name = parts[1].trim()
        val nnrDir = parts[2].trim()
        val a2bsDir = parts.getOrNull(3)?.trim()
        val skeletonFile = parts.getOrNull(4)?.trim()

        val result = manager.importCustomAvatarFiles(name, nnrDir, a2bsDir, skeletonFile)
        return if (result.success) {
            PluginResult.success(result.message, elapsed(start))
        } else {
            PluginResult.error(result.message, elapsed(start))
        }
    }

    private fun handleCustomDelete(
        manager: AvatarSessionManager,
        parts: List<String>,
        start: Long
    ): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: custom_delete|<name>", elapsed(start))
        }
        val result = manager.deleteCustomAvatar(parts[1].trim())
        return if (result.success) {
            PluginResult.success(result.message, elapsed(start))
        } else {
            PluginResult.error(result.message, elapsed(start))
        }
    }

    private fun handleUploadSkeleton(
        manager: AvatarSessionManager,
        parts: List<String>,
        start: Long
    ): PluginResult {
        if (parts.size < 3) {
            return PluginResult.error("Usage: upload_skeleton|<avatar_name>|<skeleton_path>", elapsed(start))
        }
        val result = manager.uploadSkeleton(parts[1].trim(), parts[2].trim())
        return if (result.success) {
            PluginResult.success(result.message, elapsed(start))
        } else {
            PluginResult.error(result.message, elapsed(start))
        }
    }

    private fun handleUploadThumbnail(
        manager: AvatarSessionManager,
        parts: List<String>,
        start: Long
    ): PluginResult {
        if (parts.size < 3) {
            return PluginResult.error("Usage: upload_thumbnail|<avatar_name>|<thumbnail_path>", elapsed(start))
        }
        val result = manager.uploadThumbnail(parts[1].trim(), parts[2].trim())
        return if (result.success) {
            PluginResult.success(result.message, elapsed(start))
        } else {
            PluginResult.error(result.message, elapsed(start))
        }
    }

    private fun handleValidateSkeleton(
        manager: AvatarSessionManager,
        parts: List<String>,
        start: Long
    ): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: validate_skeleton|<path>", elapsed(start))
        }
        val result = manager.validateSkeleton(parts[1].trim())
        return if (result.success) {
            PluginResult.success(result.message, elapsed(start))
        } else {
            PluginResult.error(result.message, elapsed(start))
        }
    }

    private fun handleFormats(start: Long): PluginResult {
        val sb = StringBuilder()
        sb.appendLine("=== Supported File Formats ===")
        sb.appendLine()
        sb.appendLine("Skeleton formats:")
        CustomAvatarManager.SUPPORTED_SKELETON_FORMATS.forEach { sb.appendLine("  $it") }
        sb.appendLine()
        sb.appendLine("Model formats:")
        CustomAvatarManager.SUPPORTED_MODEL_FORMATS.forEach { sb.appendLine("  $it") }
        sb.appendLine()
        sb.appendLine("Image formats (thumbnails):")
        CustomAvatarManager.SUPPORTED_IMAGE_FORMATS.forEach { sb.appendLine("  $it") }
        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun handleBlendshapes(start: Long): PluginResult {
        val sb = StringBuilder()
        sb.appendLine("=== BlendShape Channels (${A2BSEngine.BLENDSHAPE_COUNT} total) ===")
        sb.appendLine()
        sb.appendLine("Face (ARKit 52):")
        A2BSEngine.BLENDSHAPE_NAMES.take(52).forEachIndexed { i, name ->
            sb.appendLine("  [$i] $name")
        }
        sb.appendLine()
        sb.appendLine("Body (SMPL-X 16):")
        A2BSEngine.BLENDSHAPE_NAMES.drop(52).forEachIndexed { i, name ->
            sb.appendLine("  [${i + 52}] $name")
        }
        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun handleStats(manager: AvatarSessionManager, start: Long): PluginResult {
        val stats = manager.getStats()
        val sb = StringBuilder()
        sb.appendLine("=== Avatar Session Statistics ===")
        stats.toSortedMap().forEach { (key, value) ->
            sb.appendLine("  $key: $value")
        }
        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun elapsed(startMs: Long): Long = System.currentTimeMillis() - startMs

    /** Simple logging download listener for non-UI download operations. */
    private class LoggingDownloadListener : AvatarResourceManager.DownloadListener {
        override fun onProgress(progress: AvatarResourceManager.DownloadProgress) {
            Log.d(TAG, "Download ${progress.componentId}: " +
                    "${progress.bytesDownloaded}/${progress.totalBytes} " +
                    "(${String.format("%.1f", progress.progressPercent)}%)")
        }

        override fun onComplete(componentId: String, modelDir: File) {
            Log.d(TAG, "Download complete: $componentId -> ${modelDir.absolutePath}")
        }

        override fun onError(componentId: String, error: String) {
            Log.e(TAG, "Download error for $componentId: $error")
        }
    }

    companion object {
        private const val TAG = "MnnAvatarPlugin"
    }
}

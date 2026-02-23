package com.tronprotocol.app.avatar

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages user-uploaded custom avatars, skeletons, and associated resources.
 *
 * Custom avatars are stored under `mnn_avatar_custom/<avatar_name>/` with the following
 * structure:
 * ```
 * mnn_avatar_custom/
 * └── my_avatar/
 *     ├── nnr/          # Neural rendering model files (compute.nnr, render_full.nnr, etc.)
 *     ├── a2bs/         # Audio-to-BlendShape model files (optional)
 *     ├── skeleton/     # Custom skeleton data (.json, .skel, .bvh)
 *     ├── thumbnail.png # Avatar preview image (optional)
 *     └── config.json   # Avatar configuration metadata
 * ```
 *
 * Users can upload:
 * - NNR model files (required) — custom trained TaoAvatar neural rendering models
 * - A2BS model files (optional) — custom audio-to-blendshape models
 * - Skeleton files (.json, .skel, .bvh) — custom bone hierarchies
 * - Thumbnail images (.png, .jpg, .webp) — avatar preview
 */
class CustomAvatarManager(
    context: Context,
    private val resourceManager: AvatarResourceManager
) {
    private val appContext = context.applicationContext
    private val customBaseDir = File(appContext.filesDir, CUSTOM_DIR)
    private val registryFile = File(appContext.filesDir, REGISTRY_FILE)

    init {
        customBaseDir.mkdirs()
    }

    data class CustomAvatarInfo(
        val name: String,
        val directory: File,
        val hasNnr: Boolean,
        val hasA2bs: Boolean,
        val hasSkeleton: Boolean,
        val hasThumbnail: Boolean,
        val sizeBytes: Long,
        val skeletonFormat: String?
    )

    /**
     * Import a custom avatar from a source directory.
     * The source should contain NNR model files directly or in an `nnr/` subdirectory.
     *
     * @param avatarName Unique name for this avatar.
     * @param sourcePath Path to the source directory with avatar files.
     * @param skeletonPath Optional path to a skeleton file.
     */
    fun importAvatar(
        avatarName: String,
        sourcePath: String,
        skeletonPath: String? = null
    ): AvatarOperationResult {
        val safeName = sanitizeName(avatarName)
        if (safeName.isBlank()) {
            return AvatarOperationResult.fail("Invalid avatar name")
        }

        val sourceDir = File(sourcePath)
        if (!sourceDir.exists() || !sourceDir.isDirectory) {
            return AvatarOperationResult.fail("Source directory not found: $sourcePath")
        }

        val avatarDir = File(customBaseDir, safeName)
        if (avatarDir.exists()) {
            return AvatarOperationResult.fail("Avatar '$safeName' already exists. Delete it first or choose another name.")
        }
        avatarDir.mkdirs()

        return try {
            // Copy NNR files
            val nnrDestDir = File(avatarDir, "nnr")
            nnrDestDir.mkdirs()
            val nnrSourceDir = File(sourceDir, "nnr").let { if (it.exists()) it else sourceDir }
            copyDirectoryContents(nnrSourceDir, nnrDestDir)

            // Copy A2BS files if present
            val a2bsSourceDir = File(sourceDir, "a2bs")
            if (a2bsSourceDir.exists()) {
                val a2bsDestDir = File(avatarDir, "a2bs")
                a2bsDestDir.mkdirs()
                copyDirectoryContents(a2bsSourceDir, a2bsDestDir)
            }

            // Copy skeleton file
            if (skeletonPath != null) {
                val skelFile = File(skeletonPath)
                if (skelFile.exists()) {
                    val skelDir = File(avatarDir, "skeleton")
                    skelDir.mkdirs()
                    skelFile.copyTo(File(skelDir, skelFile.name), overwrite = true)
                }
            }

            // Save config
            val config = buildConfig(safeName, avatarDir)
            saveConfig(safeName, config)
            resourceManager.saveAvatarConfig(config)

            Log.d(TAG, "Custom avatar imported: $safeName -> ${avatarDir.absolutePath}")
            AvatarOperationResult.ok(
                "Custom avatar '$safeName' imported successfully",
                mapOf(
                    "path" to avatarDir.absolutePath,
                    "has_nnr" to File(avatarDir, "nnr").listFiles()?.isNotEmpty().toString(),
                    "has_a2bs" to File(avatarDir, "a2bs").exists().toString(),
                    "has_skeleton" to (skeletonPath != null).toString()
                )
            )
        } catch (e: Exception) {
            avatarDir.deleteRecursively()
            Log.e(TAG, "Failed to import avatar: ${e.message}", e)
            AvatarOperationResult.fail("Import failed: ${e.message}")
        }
    }

    /**
     * Import a custom avatar from individual component directories.
     */
    fun importAvatarFiles(
        avatarName: String,
        nnrDir: String,
        a2bsDir: String? = null,
        skeletonFile: String? = null,
        thumbnailFile: String? = null
    ): AvatarOperationResult {
        val safeName = sanitizeName(avatarName)
        if (safeName.isBlank()) {
            return AvatarOperationResult.fail("Invalid avatar name")
        }

        val nnrSource = File(nnrDir)
        if (!nnrSource.exists()) {
            return AvatarOperationResult.fail("NNR directory not found: $nnrDir")
        }

        val avatarDir = File(customBaseDir, safeName)
        if (avatarDir.exists()) {
            return AvatarOperationResult.fail("Avatar '$safeName' already exists")
        }
        avatarDir.mkdirs()

        return try {
            // NNR (required)
            val nnrDest = File(avatarDir, "nnr")
            nnrDest.mkdirs()
            copyDirectoryContents(nnrSource, nnrDest)

            // A2BS (optional)
            a2bsDir?.let {
                val a2bsSource = File(it)
                if (a2bsSource.exists()) {
                    val a2bsDest = File(avatarDir, "a2bs")
                    a2bsDest.mkdirs()
                    copyDirectoryContents(a2bsSource, a2bsDest)
                }
            }

            // Skeleton (optional)
            skeletonFile?.let {
                val skelSource = File(it)
                if (skelSource.exists() && isValidSkeletonFormat(skelSource.name)) {
                    val skelDir = File(avatarDir, "skeleton")
                    skelDir.mkdirs()
                    skelSource.copyTo(File(skelDir, skelSource.name), overwrite = true)
                }
            }

            // Thumbnail (optional)
            thumbnailFile?.let {
                val thumbSource = File(it)
                if (thumbSource.exists() && isValidImageFormat(thumbSource.name)) {
                    thumbSource.copyTo(File(avatarDir, "thumbnail${getExtension(thumbSource.name)}"), overwrite = true)
                }
            }

            val config = buildConfig(safeName, avatarDir)
            saveConfig(safeName, config)
            resourceManager.saveAvatarConfig(config)

            AvatarOperationResult.ok("Custom avatar '$safeName' imported from files")
        } catch (e: Exception) {
            avatarDir.deleteRecursively()
            AvatarOperationResult.fail("Import failed: ${e.message}")
        }
    }

    /**
     * Upload a skeleton file for an existing custom avatar.
     */
    fun uploadSkeleton(avatarName: String, skeletonPath: String): AvatarOperationResult {
        val safeName = sanitizeName(avatarName)
        val avatarDir = File(customBaseDir, safeName)
        if (!avatarDir.exists()) {
            return AvatarOperationResult.fail("Avatar not found: $safeName")
        }

        val skelFile = File(skeletonPath)
        if (!skelFile.exists()) {
            return AvatarOperationResult.fail("Skeleton file not found: $skeletonPath")
        }
        if (!isValidSkeletonFormat(skelFile.name)) {
            return AvatarOperationResult.fail(
                "Unsupported skeleton format: ${getExtension(skelFile.name)}. " +
                "Supported: ${SUPPORTED_SKELETON_FORMATS.joinToString()}"
            )
        }

        return try {
            val skelDir = File(avatarDir, "skeleton")
            skelDir.mkdirs()
            // Clear existing skeleton files
            skelDir.listFiles()?.forEach { it.delete() }
            skelFile.copyTo(File(skelDir, skelFile.name), overwrite = true)

            // Update config
            val config = buildConfig(safeName, avatarDir)
            saveConfig(safeName, config)
            resourceManager.saveAvatarConfig(config)

            AvatarOperationResult.ok("Skeleton uploaded for '$safeName': ${skelFile.name}")
        } catch (e: Exception) {
            AvatarOperationResult.fail("Skeleton upload failed: ${e.message}")
        }
    }

    /**
     * Upload a thumbnail image for an avatar.
     */
    fun uploadThumbnail(avatarName: String, thumbnailPath: String): AvatarOperationResult {
        val safeName = sanitizeName(avatarName)
        val avatarDir = File(customBaseDir, safeName)
        if (!avatarDir.exists()) {
            return AvatarOperationResult.fail("Avatar not found: $safeName")
        }

        val thumbFile = File(thumbnailPath)
        if (!thumbFile.exists()) {
            return AvatarOperationResult.fail("Thumbnail file not found: $thumbnailPath")
        }
        if (!isValidImageFormat(thumbFile.name)) {
            return AvatarOperationResult.fail(
                "Unsupported image format. Supported: ${SUPPORTED_IMAGE_FORMATS.joinToString()}"
            )
        }

        return try {
            // Remove existing thumbnails
            avatarDir.listFiles()?.filter { it.name.startsWith("thumbnail") }?.forEach { it.delete() }
            thumbFile.copyTo(File(avatarDir, "thumbnail${getExtension(thumbFile.name)}"), overwrite = true)

            AvatarOperationResult.ok("Thumbnail uploaded for '$safeName'")
        } catch (e: Exception) {
            AvatarOperationResult.fail("Thumbnail upload failed: ${e.message}")
        }
    }

    /**
     * List all custom avatars with their details.
     */
    fun listAvatars(): List<CustomAvatarInfo> {
        return customBaseDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { dir ->
                val nnrDir = File(dir, "nnr")
                val a2bsDir = File(dir, "a2bs")
                val skelDir = File(dir, "skeleton")
                val skelFile = skelDir.listFiles()?.firstOrNull()

                CustomAvatarInfo(
                    name = dir.name,
                    directory = dir,
                    hasNnr = nnrDir.exists() && nnrDir.listFiles()?.isNotEmpty() == true,
                    hasA2bs = a2bsDir.exists() && a2bsDir.listFiles()?.isNotEmpty() == true,
                    hasSkeleton = skelFile != null,
                    hasThumbnail = dir.listFiles()?.any { it.name.startsWith("thumbnail") } == true,
                    sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    skeletonFormat = skelFile?.let { getExtension(it.name) }
                )
            }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * Get the AvatarConfig for a custom avatar.
     */
    fun getAvatarConfig(avatarName: String): AvatarConfig? {
        val safeName = sanitizeName(avatarName)
        val avatarDir = File(customBaseDir, safeName)
        if (!avatarDir.exists()) return null
        return buildConfig(safeName, avatarDir)
    }

    /**
     * Delete a custom avatar and all its files.
     */
    fun deleteAvatar(avatarName: String): AvatarOperationResult {
        val safeName = sanitizeName(avatarName)
        val avatarDir = File(customBaseDir, safeName)
        if (!avatarDir.exists()) {
            return AvatarOperationResult.fail("Avatar not found: $safeName")
        }

        return if (avatarDir.deleteRecursively()) {
            removeConfig(safeName)
            AvatarOperationResult.ok("Deleted custom avatar: $safeName")
        } else {
            AvatarOperationResult.fail("Failed to delete avatar: $safeName")
        }
    }

    /**
     * Validate a skeleton file format and basic structure.
     */
    fun validateSkeleton(skeletonPath: String): AvatarOperationResult {
        val file = File(skeletonPath)
        if (!file.exists()) return AvatarOperationResult.fail("File not found")
        if (!isValidSkeletonFormat(file.name)) {
            return AvatarOperationResult.fail("Unsupported format: ${getExtension(file.name)}")
        }

        return try {
            when {
                file.name.endsWith(".json") -> {
                    val content = file.readText()
                    val json = JSONObject(content)
                    val boneCount = json.optJSONArray("bones")?.length() ?: 0
                    AvatarOperationResult.ok(
                        "Valid JSON skeleton: $boneCount bones",
                        mapOf("bones" to boneCount, "format" to "json")
                    )
                }
                file.name.endsWith(".bvh") -> {
                    val lines = file.readLines()
                    val hasHierarchy = lines.any { it.trim().startsWith("HIERARCHY") }
                    val hasMotion = lines.any { it.trim().startsWith("MOTION") }
                    AvatarOperationResult.ok(
                        "Valid BVH skeleton: hierarchy=${hasHierarchy}, motion=${hasMotion}",
                        mapOf("format" to "bvh", "lines" to lines.size)
                    )
                }
                else -> AvatarOperationResult.ok(
                    "Skeleton file accepted: ${file.name} (${file.length()} bytes)",
                    mapOf("format" to getExtension(file.name))
                )
            }
        } catch (e: Exception) {
            AvatarOperationResult.fail("Invalid skeleton file: ${e.message}")
        }
    }

    // ---- Internal helpers ----

    private fun buildConfig(name: String, avatarDir: File): AvatarConfig {
        val nnrDir = File(avatarDir, "nnr")
        val a2bsDir = File(avatarDir, "a2bs")
        val skelDir = File(avatarDir, "skeleton")
        val skelFile = skelDir.listFiles()?.firstOrNull()
        val thumbFile = avatarDir.listFiles()?.find { it.name.startsWith("thumbnail") }

        return AvatarConfig(
            avatarId = "custom_$name",
            displayName = name,
            rootPath = avatarDir.absolutePath,
            nnrModelDir = nnrDir.absolutePath,
            a2bsModelDir = if (a2bsDir.exists()) a2bsDir.absolutePath else "",
            ttsModelDir = "",
            asrModelDir = "",
            llmModelDir = "",
            isCustom = true,
            skeletonPath = skelFile?.absolutePath,
            thumbnailPath = thumbFile?.absolutePath
        )
    }

    private fun saveConfig(name: String, config: AvatarConfig) {
        val configFile = File(File(customBaseDir, sanitizeName(name)), "config.json")
        configFile.writeText(config.toJson().toString(2))
    }

    private fun removeConfig(name: String) {
        // Config is stored inside the avatar directory which was already deleted
    }

    private fun copyDirectoryContents(source: File, dest: File) {
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source)
            val destFile = File(dest, relative.path)
            if (file.isDirectory) {
                destFile.mkdirs()
            } else {
                destFile.parentFile?.mkdirs()
                file.copyTo(destFile, overwrite = true)
            }
        }
    }

    private fun sanitizeName(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "" }

    private fun isValidSkeletonFormat(fileName: String): Boolean =
        SUPPORTED_SKELETON_FORMATS.any { fileName.lowercase().endsWith(it) }

    private fun isValidImageFormat(fileName: String): Boolean =
        SUPPORTED_IMAGE_FORMATS.any { fileName.lowercase().endsWith(it) }

    private fun getExtension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot >= 0) fileName.substring(dot) else ""
    }

    companion object {
        private const val TAG = "CustomAvatarManager"
        private const val CUSTOM_DIR = "mnn_avatar_custom"
        private const val REGISTRY_FILE = "custom_avatars_registry.json"

        /** Supported skeleton file formats. */
        val SUPPORTED_SKELETON_FORMATS = listOf(
            ".json",    // JSON bone hierarchy
            ".skel",    // Binary skeleton (Spine, etc.)
            ".bvh",     // BioVision Hierarchy (motion capture)
            ".fbx",     // Autodesk FBX (skeleton + mesh)
            ".gltf",    // glTF 2.0 (skeleton + mesh)
            ".glb"      // glTF binary (skeleton + mesh)
        )

        /** Supported avatar model file formats. */
        val SUPPORTED_MODEL_FORMATS = listOf(
            ".nnr",     // MNN neural rendering model
            ".mnn",     // MNN generic model
            ".ply",     // Point cloud (3D Gaussian Splatting)
            ".splat",   // 3DGS splat file
            ".gltf",    // glTF 2.0 mesh
            ".glb",     // glTF binary mesh
            ".obj"      // Wavefront OBJ mesh
        )

        /** Supported thumbnail image formats. */
        val SUPPORTED_IMAGE_FORMATS = listOf(".png", ".jpg", ".jpeg", ".webp")
    }
}

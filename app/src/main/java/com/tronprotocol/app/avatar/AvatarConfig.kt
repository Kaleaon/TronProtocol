package com.tronprotocol.app.avatar

import org.json.JSONObject

/**
 * Configuration for an MNN TaoAvatar model set.
 *
 * A complete avatar requires five sub-models (NNR, A2BS, TTS, ASR, LLM), each stored
 * in its own directory. This config tracks the root path and per-component directories.
 *
 * @see [TaoAvatar Paper](https://arxiv.org/html/2503.17032v1)
 */
data class AvatarConfig(
    val avatarId: String,
    val displayName: String,
    val rootPath: String,
    val nnrModelDir: String,
    val a2bsModelDir: String,
    val ttsModelDir: String,
    val asrModelDir: String,
    val llmModelDir: String,
    val isCustom: Boolean = false,
    val skeletonPath: String? = null,
    val thumbnailPath: String? = null,
    val renderWidth: Int = DEFAULT_RENDER_WIDTH,
    val renderHeight: Int = DEFAULT_RENDER_HEIGHT
) {
    /** NNR resource files required for neural rendering. */
    val nnrComputePath: String get() = "$nnrModelDir/compute.nnr"
    val nnrRenderPath: String get() = "$nnrModelDir/render_full.nnr"
    val nnrBackgroundPath: String get() = "$nnrModelDir/background.nnr"
    val nnrInputConfigPath: String get() = "$nnrModelDir/input_nnr.json"

    fun toJson(): JSONObject = JSONObject().apply {
        put("avatar_id", avatarId)
        put("display_name", displayName)
        put("root_path", rootPath)
        put("nnr_model_dir", nnrModelDir)
        put("a2bs_model_dir", a2bsModelDir)
        put("tts_model_dir", ttsModelDir)
        put("asr_model_dir", asrModelDir)
        put("llm_model_dir", llmModelDir)
        put("is_custom", isCustom)
        skeletonPath?.let { put("skeleton_path", it) }
        thumbnailPath?.let { put("thumbnail_path", it) }
        put("render_width", renderWidth)
        put("render_height", renderHeight)
    }

    companion object {
        const val DEFAULT_RENDER_WIDTH = 512
        const val DEFAULT_RENDER_HEIGHT = 512

        fun fromJson(json: JSONObject): AvatarConfig = AvatarConfig(
            avatarId = json.getString("avatar_id"),
            displayName = json.getString("display_name"),
            rootPath = json.getString("root_path"),
            nnrModelDir = json.getString("nnr_model_dir"),
            a2bsModelDir = json.getString("a2bs_model_dir"),
            ttsModelDir = json.getString("tts_model_dir"),
            asrModelDir = json.getString("asr_model_dir"),
            llmModelDir = json.getString("llm_model_dir"),
            isCustom = json.optBoolean("is_custom", false),
            skeletonPath = json.optString("skeleton_path", null),
            thumbnailPath = json.optString("thumbnail_path", null),
            renderWidth = json.optInt("render_width", DEFAULT_RENDER_WIDTH),
            renderHeight = json.optInt("render_height", DEFAULT_RENDER_HEIGHT)
        )
    }
}

/**
 * A single blendshape animation frame produced by the A2BS (Audio-to-BlendShape) model.
 *
 * BlendShapes map named facial/body regions to float weights (0.0–1.0) that control
 * deformation of the avatar mesh. These follow the ARKit 52-blendshape convention
 * extended with body pose parameters from SMPL-X.
 */
data class BlendShapeFrame(
    val index: Int,
    val timestamp: Long,
    val weights: FloatArray,
    val isLast: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BlendShapeFrame) return false
        return index == other.index && timestamp == other.timestamp &&
                weights.contentEquals(other.weights) && isLast == other.isLast
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + weights.contentHashCode()
        result = 31 * result + isLast.hashCode()
        return result
    }
}

/**
 * Skeleton data for custom avatar rigging.
 *
 * Represents a hierarchical bone structure that can be loaded from custom
 * skeleton files (JSON or binary) for user-uploaded avatars.
 */
data class SkeletonData(
    val name: String,
    val bones: List<Bone>,
    val bindPose: FloatArray
) {
    data class Bone(
        val id: Int,
        val name: String,
        val parentId: Int,
        val localTransform: FloatArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bone) return false
            return id == other.id && name == other.name && parentId == other.parentId &&
                    localTransform.contentEquals(other.localTransform)
        }

        override fun hashCode(): Int {
            var result = id
            result = 31 * result + name.hashCode()
            result = 31 * result + parentId
            result = 31 * result + localTransform.contentHashCode()
            return result
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SkeletonData) return false
        return name == other.name && bones == other.bones && bindPose.contentEquals(other.bindPose)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + bones.hashCode()
        result = 31 * result + bindPose.contentHashCode()
        return result
    }
}

/** Lifecycle state for the avatar rendering pipeline. */
enum class AvatarState {
    UNINITIALIZED,
    CHECKING_DEVICE,
    DOWNLOADING_MODELS,
    LOADING_NNR,
    LOADING_A2BS,
    READY,
    RENDERING,
    ERROR,
    DESTROYED
}

/** Result of an avatar operation. */
data class AvatarOperationResult(
    val success: Boolean,
    val message: String,
    val data: Map<String, Any> = emptyMap()
) {
    companion object {
        fun ok(message: String, data: Map<String, Any> = emptyMap()) =
            AvatarOperationResult(true, message, data)

        fun fail(message: String) =
            AvatarOperationResult(false, message)
    }
}

package com.tronprotocol.app.avatar

import android.os.Build

/**
 * Catalog of MNN TaoAvatar model components available for download.
 *
 * Models are hosted on ModelScope (Alibaba) and follow the TaoAvatar pipeline:
 * NNR (neural rendering) + A2BS (audio-to-blendshape) + TTS + ASR + LLM.
 *
 * @see [ModelScope Collection](https://modelscope.cn/collections/TaoAvatar-68d8a46f2e554a)
 */
object AvatarModelCatalog {

    /** A downloadable model component for the avatar pipeline. */
    data class AvatarModelEntry(
        val id: String,
        val name: String,
        val component: AvatarComponent,
        val downloadUrl: String,
        val sizeEstimateMb: Long,
        val description: String,
        val requiredFiles: List<String> = emptyList()
    )

    /** Pipeline component type. */
    enum class AvatarComponent {
        NNR, A2BS, TTS, ASR, LLM
    }

    /** Pre-configured avatar presets that bundle all components. */
    data class AvatarPreset(
        val id: String,
        val name: String,
        val description: String,
        val componentIds: Map<AvatarComponent, String>,
        val totalSizeMb: Long,
        val minRamMb: Long = MIN_RAM_MB
    )

    // ModelScope base URLs for TaoAvatar components
    private const val MODELSCOPE_BASE = "https://modelscope.cn/models/MNN"
    private const val TAOBAO_CDN_BASE = "https://meta.alicdn.com/data/mnn/avatar"

    // Hardware thresholds
    const val MIN_RAM_MB = 8192L    // 8GB minimum for avatar pipeline
    const val RECOMMENDED_RAM_MB = 12288L  // 12GB for smooth operation

    /** All available model components. */
    val components: List<AvatarModelEntry> = listOf(
        // Neural Network Rendering
        AvatarModelEntry(
            id = "taoavatar-nnr-mnn",
            name = "TaoAvatar NNR",
            component = AvatarComponent.NNR,
            downloadUrl = "$MODELSCOPE_BASE/TaoAvatar-NNR-MNN",
            sizeEstimateMb = 800,
            description = "3D Gaussian Splatting neural renderer for photorealistic avatar visualization",
            requiredFiles = listOf("compute.nnr", "render_full.nnr", "background.nnr", "input_nnr.json")
        ),

        // Audio-to-BlendShape
        AvatarModelEntry(
            id = "unitalker-mnn",
            name = "UniTalker A2BS",
            component = AvatarComponent.A2BS,
            downloadUrl = "$MODELSCOPE_BASE/UniTalker-MNN",
            sizeEstimateMb = 200,
            description = "Audio-to-blendshape model for facial expression and lip-sync animation"
        ),

        // Text-to-Speech (Chinese)
        AvatarModelEntry(
            id = "bert-vits2-mnn",
            name = "BERT-VITS2 TTS",
            component = AvatarComponent.TTS,
            downloadUrl = "$MODELSCOPE_BASE/bert-vits2-MNN",
            sizeEstimateMb = 300,
            description = "Chinese text-to-speech synthesis based on BERT-VITS2"
        ),

        // Text-to-Speech (English)
        AvatarModelEntry(
            id = "supertonic-tts-mnn",
            name = "SuperTonic TTS",
            component = AvatarComponent.TTS,
            downloadUrl = "$MODELSCOPE_BASE/supertonic-tts-mnn",
            sizeEstimateMb = 250,
            description = "English text-to-speech synthesis with natural prosody"
        ),

        // Automatic Speech Recognition
        AvatarModelEntry(
            id = "sherpa-mnn-asr",
            name = "Sherpa ASR",
            component = AvatarComponent.ASR,
            downloadUrl = "$MODELSCOPE_BASE/sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20",
            sizeEstimateMb = 150,
            description = "Bilingual (zh/en) streaming speech recognition via Sherpa-MNN"
        ),

        // LLM (conversational intelligence)
        AvatarModelEntry(
            id = "qwen2.5-1.5b-avatar",
            name = "Qwen2.5-1.5B (Avatar)",
            component = AvatarComponent.LLM,
            downloadUrl = "$MODELSCOPE_BASE/Qwen2.5-1.5B-Instruct-MNN",
            sizeEstimateMb = 1200,
            description = "Quantized Qwen2.5-1.5B for on-device conversational avatar intelligence"
        )
    )

    /** Pre-configured avatar presets. */
    val presets: List<AvatarPreset> = listOf(
        AvatarPreset(
            id = "taoavatar-full-en",
            name = "TaoAvatar (English)",
            description = "Full avatar pipeline with English TTS, bilingual ASR, and Qwen LLM",
            componentIds = mapOf(
                AvatarComponent.NNR to "taoavatar-nnr-mnn",
                AvatarComponent.A2BS to "unitalker-mnn",
                AvatarComponent.TTS to "supertonic-tts-mnn",
                AvatarComponent.ASR to "sherpa-mnn-asr",
                AvatarComponent.LLM to "qwen2.5-1.5b-avatar"
            ),
            totalSizeMb = 2600
        ),
        AvatarPreset(
            id = "taoavatar-full-zh",
            name = "TaoAvatar (Chinese)",
            description = "Full avatar pipeline with Chinese TTS, bilingual ASR, and Qwen LLM",
            componentIds = mapOf(
                AvatarComponent.NNR to "taoavatar-nnr-mnn",
                AvatarComponent.A2BS to "unitalker-mnn",
                AvatarComponent.TTS to "bert-vits2-mnn",
                AvatarComponent.ASR to "sherpa-mnn-asr",
                AvatarComponent.LLM to "qwen2.5-1.5b-avatar"
            ),
            totalSizeMb = 2650
        ),
        AvatarPreset(
            id = "taoavatar-render-only",
            name = "TaoAvatar (Render Only)",
            description = "NNR + A2BS only — avatar rendering and animation without speech pipeline",
            componentIds = mapOf(
                AvatarComponent.NNR to "taoavatar-nnr-mnn",
                AvatarComponent.A2BS to "unitalker-mnn"
            ),
            totalSizeMb = 1000,
            minRamMb = 6144
        )
    )

    fun getComponent(id: String): AvatarModelEntry? = components.find { it.id == id }

    fun getPreset(id: String): AvatarPreset? = presets.find { it.id == id }

    fun getComponentsByType(type: AvatarComponent): List<AvatarModelEntry> =
        components.filter { it.component == type }

    /**
     * Check if the current device meets minimum requirements for the TaoAvatar pipeline.
     * Requires Snapdragon 8 Gen 3 or equivalent flagship SoC and 8GB+ RAM.
     */
    fun isDeviceSupported(totalRamMb: Long): Boolean {
        val supportsArm64 = try {
            Build.SUPPORTED_ABIS?.any { it == "arm64-v8a" } ?: false
        } catch (_: Error) {
            false
        }
        return supportsArm64 && totalRamMb >= MIN_RAM_MB
    }

    fun recommendPreset(totalRamMb: Long): AvatarPreset? {
        if (!isDeviceSupported(totalRamMb)) return null
        return if (totalRamMb >= RECOMMENDED_RAM_MB) {
            presets.first { it.id == "taoavatar-full-en" }
        } else {
            presets.first { it.id == "taoavatar-render-only" }
        }
    }
}

package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.avatar.ASREngine
import com.tronprotocol.app.avatar.AvatarAffectBridge
import com.tronprotocol.app.avatar.DiffusionEngine
import com.tronprotocol.app.avatar.ExpressionRecognizer
import com.tronprotocol.app.avatar.FaceDetectionEngine
import com.tronprotocol.app.avatar.FaceLandmarkEngine
import com.tronprotocol.app.avatar.MnnVisionEngine
import com.tronprotocol.app.avatar.PoseEstimationEngine
import com.tronprotocol.app.avatar.TTSEngine

/**
 * Avatar Perception Plugin — MNN-powered face/pose tracking and avatar generation.
 *
 * Exposes the full MNN perception pipeline (face detection, landmarks, pose estimation,
 * expression recognition) and generation capabilities (TTS, ASR, diffusion) through
 * the plugin command interface.
 *
 * Commands:
 *   status                          - Show all engine availability and status
 *   face_detect_init|<model_dir>    - Initialize face detection engine
 *   face_landmark_init|<model_dir>  - Initialize face landmark engine
 *   pose_init|<model_dir>           - Initialize pose estimation engine
 *   expression_init[|<model_dir>]   - Initialize expression recognizer (model_dir optional)
 *   tts_init|<model_dir>[|<type>]   - Initialize TTS engine
 *   tts_speak|<text>                - Synthesize speech from text
 *   asr_init|<model_dir>            - Initialize ASR engine
 *   diffusion_init|<model_dir>      - Initialize diffusion engine
 *   diffusion_generate|<prompt>     - Generate image from text prompt
 *   engines                         - List all MNN engine availability
 *   capabilities                    - Show MNN native library status
 *
 * @see FaceDetectionEngine
 * @see FaceLandmarkEngine
 * @see PoseEstimationEngine
 * @see ExpressionRecognizer
 * @see AvatarAffectBridge
 * @see TTSEngine
 * @see ASREngine
 * @see DiffusionEngine
 */
class AvatarPerceptionPlugin : Plugin {

    override val id = "avatar_perception"
    override val name = "Avatar Perception"
    override val description = "MNN-powered face detection, landmarks, pose estimation, " +
            "expression recognition, TTS, ASR, and diffusion generation"
    override var isEnabled = true

    private var appContext: Context? = null

    private var faceDetector: FaceDetectionEngine? = null
    private var faceLandmarks: FaceLandmarkEngine? = null
    private var poseEstimator: PoseEstimationEngine? = null
    private var expressionRecognizer: ExpressionRecognizer? = null
    private var affectBridge: AvatarAffectBridge? = null
    private var ttsEngine: TTSEngine? = null
    private var asrEngine: ASREngine? = null
    private var diffusionEngine: DiffusionEngine? = null

    override fun requiredCapabilities(): Set<Capability> =
        setOf(Capability.AVATAR_RENDER, Capability.AVATAR_ANIMATE, Capability.MODEL_EXECUTION)

    override fun initialize(context: Context) {
        appContext = context.applicationContext
        affectBridge = AvatarAffectBridge()
        Log.d(TAG, "AvatarPerceptionPlugin initialized")
    }

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        val parts = input.split("|", limit = 5)
        val command = parts[0].trim().lowercase()

        return try {
            when (command) {
                "status" -> handleStatus(start)
                "face_detect_init" -> handleFaceDetectInit(parts, start)
                "face_landmark_init" -> handleFaceLandmarkInit(parts, start)
                "pose_init" -> handlePoseInit(parts, start)
                "expression_init" -> handleExpressionInit(parts, start)
                "tts_init" -> handleTtsInit(parts, start)
                "tts_speak" -> handleTtsSpeak(parts, start)
                "asr_init" -> handleAsrInit(parts, start)
                "diffusion_init" -> handleDiffusionInit(parts, start)
                "diffusion_generate" -> handleDiffusionGenerate(parts, start)
                "engines" -> handleEngines(start)
                "capabilities" -> handleCapabilities(start)
                else -> PluginResult.error(
                    "Unknown command: $command. Available: status, face_detect_init, " +
                    "face_landmark_init, pose_init, expression_init, tts_init, tts_speak, " +
                    "asr_init, diffusion_init, diffusion_generate, engines, capabilities",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command '$command' failed: ${e.message}", e)
            PluginResult.error("Command failed: ${e.message}", elapsed(start))
        }
    }

    override fun destroy() {
        faceDetector?.release()
        faceLandmarks?.release()
        poseEstimator?.release()
        expressionRecognizer?.release()
        ttsEngine?.destroy()
        asrEngine?.destroy()
        diffusionEngine?.destroy()

        faceDetector = null
        faceLandmarks = null
        poseEstimator = null
        expressionRecognizer = null
        affectBridge = null
        ttsEngine = null
        asrEngine = null
        diffusionEngine = null
        appContext = null

        Log.d(TAG, "AvatarPerceptionPlugin destroyed")
    }

    // ---- Command handlers ----

    private fun handleStatus(start: Long): PluginResult {
        val sb = StringBuilder()
        sb.appendLine("=== Avatar Perception Status ===")
        sb.appendLine()
        sb.appendLine("--- Native Libraries ---")
        sb.appendLine("MNN Core (vision): ${status(MnnVisionEngine.isNativeAvailable())}")
        sb.appendLine("MNN TTS: ${status(TTSEngine.isNativeAvailable())}")
        sb.appendLine("MNN ASR: ${status(ASREngine.isNativeAvailable())}")
        sb.appendLine("MNN Diffusion: ${status(DiffusionEngine.isNativeAvailable())}")
        sb.appendLine()
        sb.appendLine("--- Perception Engines ---")
        sb.appendLine("Face Detection: ${status(faceDetector?.isReady)}")
        sb.appendLine("Face Landmarks: ${status(faceLandmarks?.isReady)}")
        sb.appendLine("Pose Estimation: ${status(poseEstimator?.isReady)}")
        sb.appendLine("Expression Recognizer: ${status(expressionRecognizer?.isReady)}")
        sb.appendLine("Affect Bridge: ${status(affectBridge != null)}")
        sb.appendLine()
        sb.appendLine("--- Generation Engines ---")
        sb.appendLine("TTS: ${status(ttsEngine?.isReady)}")
        sb.appendLine("ASR: ${status(asrEngine?.isReady)}")
        sb.appendLine("Diffusion: ${status(diffusionEngine?.isReady)}")

        if (diffusionEngine?.isReady == true) {
            sb.appendLine("  State: ${diffusionEngine?.state}")
            sb.appendLine("  Progress: ${String.format("%.1f", (diffusionEngine?.progress ?: 0f) * 100f)}%")
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun handleFaceDetectInit(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: face_detect_init|<model_dir>", elapsed(start))
        }

        val engine = FaceDetectionEngine()
        val typeStr = parts.getOrNull(2)?.trim()?.uppercase()
        val type = typeStr?.let {
            try { FaceDetectionEngine.ModelType.valueOf(it) } catch (_: Exception) { null }
        } ?: FaceDetectionEngine.ModelType.BLAZEFACE_SHORT

        val success = engine.initialize(parts[1].trim(), type)
        return if (success) {
            faceDetector = engine
            PluginResult.success("Face detection initialized: ${type.name} (${type.inputSize}x${type.inputSize})", elapsed(start))
        } else {
            PluginResult.error("Failed to initialize face detection", elapsed(start))
        }
    }

    private fun handleFaceLandmarkInit(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: face_landmark_init|<model_dir>", elapsed(start))
        }

        val engine = FaceLandmarkEngine()
        val typeStr = parts.getOrNull(2)?.trim()?.uppercase()
        val type = typeStr?.let {
            try { FaceLandmarkEngine.ModelType.valueOf(it) } catch (_: Exception) { null }
        } ?: FaceLandmarkEngine.ModelType.FACEMESH_468

        val success = engine.initialize(parts[1].trim(), type)
        return if (success) {
            faceLandmarks = engine
            PluginResult.success("Face landmarks initialized: ${type.name} (${type.landmarkCount} points)", elapsed(start))
        } else {
            PluginResult.error("Failed to initialize face landmarks", elapsed(start))
        }
    }

    private fun handlePoseInit(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: pose_init|<model_dir>", elapsed(start))
        }

        val engine = PoseEstimationEngine()
        val typeStr = parts.getOrNull(2)?.trim()?.uppercase()
        val type = typeStr?.let {
            try { PoseEstimationEngine.ModelType.valueOf(it) } catch (_: Exception) { null }
        } ?: PoseEstimationEngine.ModelType.MOVENET_LIGHTNING

        val success = engine.initialize(parts[1].trim(), type)
        return if (success) {
            poseEstimator = engine
            PluginResult.success("Pose estimation initialized: ${type.name} (${type.keypointCount} keypoints)", elapsed(start))
        } else {
            PluginResult.error("Failed to initialize pose estimation", elapsed(start))
        }
    }

    private fun handleExpressionInit(parts: List<String>, start: Long): PluginResult {
        val modelDir = parts.getOrNull(1)?.trim()
        val recognizer = ExpressionRecognizer()
        recognizer.initialize(modelDir)
        expressionRecognizer = recognizer

        val mode = if (modelDir != null) "model-based" else "geometric"
        return PluginResult.success("Expression recognizer initialized ($mode)", elapsed(start))
    }

    private fun handleTtsInit(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: tts_init|<model_dir>[|<en|zh>]", elapsed(start))
        }

        val typeStr = parts.getOrNull(2)?.trim()?.lowercase()
        val type = when (typeStr) {
            "zh" -> TTSEngine.TTSModelType.BERT_VITS2_ZH
            "en" -> TTSEngine.TTSModelType.SUPERTONIC_EN
            else -> TTSEngine.TTSModelType.SUPERTONIC_EN
        }

        val engine = TTSEngine()
        val success = engine.initialize(parts[1].trim(), type)
        return if (success) {
            ttsEngine = engine
            PluginResult.success("TTS initialized: ${type.name} (${type.sampleRate}Hz)", elapsed(start))
        } else {
            PluginResult.error("Failed to initialize TTS", elapsed(start))
        }
    }

    private fun handleTtsSpeak(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: tts_speak|<text>", elapsed(start))
        }

        val engine = ttsEngine
            ?: return PluginResult.error("TTS not initialized. Use: tts_init|<model_dir>", elapsed(start))

        val result = engine.synthesize(parts[1].trim())
            ?: return PluginResult.error("TTS synthesis failed", elapsed(start))

        return PluginResult.success(
            "Synthesized: ${result.audioData.size} samples, ${result.durationMs}ms audio, " +
            "${result.synthesisTimeMs}ms synthesis (RTF: ${String.format("%.2f", result.realTimeFactor)})",
            elapsed(start)
        )
    }

    private fun handleAsrInit(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: asr_init|<model_dir>", elapsed(start))
        }

        val engine = ASREngine()
        val success = engine.initialize(parts[1].trim())
        return if (success) {
            asrEngine = engine
            PluginResult.success("ASR initialized: Sherpa-MNN (${ASREngine.DEFAULT_SAMPLE_RATE}Hz)", elapsed(start))
        } else {
            PluginResult.error("Failed to initialize ASR", elapsed(start))
        }
    }

    private fun handleDiffusionInit(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: diffusion_init|<model_dir>", elapsed(start))
        }

        val config = DiffusionEngine.DiffusionModelConfig(modelDir = parts[1].trim())
        val engine = DiffusionEngine()
        val success = engine.initialize(config)
        return if (success) {
            diffusionEngine = engine
            PluginResult.success("Diffusion engine initialized from: ${parts[1].trim()}", elapsed(start))
        } else {
            PluginResult.error("Failed to initialize diffusion engine", elapsed(start))
        }
    }

    private fun handleDiffusionGenerate(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2) {
            return PluginResult.error("Usage: diffusion_generate|<prompt>", elapsed(start))
        }

        val engine = diffusionEngine
            ?: return PluginResult.error("Diffusion not initialized. Use: diffusion_init|<model_dir>", elapsed(start))

        val config = DiffusionEngine.GenerationConfig(
            prompt = parts[1].trim(),
            steps = 20,
            width = 512,
            height = 512
        )

        val result = engine.generate(config)
        return if (result.success) {
            PluginResult.success(
                "Image generated: ${config.width}x${config.height}, ${result.steps} steps, " +
                "${result.generationTimeMs}ms (seed: ${result.seed})",
                elapsed(start)
            )
        } else {
            PluginResult.error("Generation failed: ${result.error}", elapsed(start))
        }
    }

    private fun handleEngines(start: Long): PluginResult {
        val sb = StringBuilder()
        sb.appendLine("=== MNN Avatar Engines ===")
        sb.appendLine()
        sb.appendLine("Perception:")
        sb.appendLine("  FaceDetectionEngine — BlazeFace/SCRFD face detection (128-320px)")
        sb.appendLine("  FaceLandmarkEngine — FaceMesh 468-point 3D landmarks")
        sb.appendLine("  PoseEstimationEngine — MoveNet/BlazePose body keypoints (17-33)")
        sb.appendLine("  ExpressionRecognizer — FACS action units + expression classification")
        sb.appendLine("  AvatarAffectBridge — Affect state <-> blendshape bidirectional mapping")
        sb.appendLine()
        sb.appendLine("Speech:")
        sb.appendLine("  TTSEngine — BERT-VITS2 (Chinese) / SuperTonic (English) TTS")
        sb.appendLine("  ASREngine — Sherpa-MNN streaming bilingual ASR")
        sb.appendLine()
        sb.appendLine("Generation:")
        sb.appendLine("  DiffusionEngine — Stable Diffusion text-to-image (INT8, ControlNet, LoRA)")
        sb.appendLine("  MnnVisionEngine — General-purpose MNN model inference")
        sb.appendLine()
        sb.appendLine("Rendering (see mnn_avatar plugin):")
        sb.appendLine("  NnrRenderEngine — 3D Gaussian Splatting neural rendering")
        sb.appendLine("  A2BSEngine — Audio-to-BlendShape facial animation")

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun handleCapabilities(start: Long): PluginResult {
        val sb = StringBuilder()
        sb.appendLine("=== MNN Native Library Status ===")
        sb.appendLine()
        sb.appendLine("libMNN.so (core): ${status(MnnVisionEngine.isNativeAvailable())}")
        sb.appendLine("libmnn_tts.so (TTS): ${status(TTSEngine.isNativeAvailable())}")
        sb.appendLine("libmnn_asr.so (ASR): ${status(ASREngine.isNativeAvailable())}")
        sb.appendLine("libmnn_diffusion.so (diffusion): ${status(DiffusionEngine.isNativeAvailable())}")
        sb.appendLine()

        val missing = mutableListOf<String>()
        if (!MnnVisionEngine.isNativeAvailable()) missing.add("libMNN.so")
        if (!TTSEngine.isNativeAvailable()) missing.add("libmnn_tts.so")
        if (!ASREngine.isNativeAvailable()) missing.add("libmnn_asr.so")
        if (!DiffusionEngine.isNativeAvailable()) missing.add("libmnn_diffusion.so")

        if (missing.isNotEmpty()) {
            sb.appendLine("Missing libraries: ${missing.joinToString(", ")}")
            sb.appendLine()
            sb.appendLine("To build from source:")
            sb.appendLine("  git clone https://github.com/alibaba/MNN")
            sb.appendLine("  cd MNN && mkdir build && cd build")
            sb.appendLine("  cmake .. -DMNN_BUILD_LLM=true -DMNN_BUILD_NNR=true \\")
            sb.appendLine("    -DCMAKE_TOOLCHAIN_FILE=\$NDK/build/cmake/android.toolchain.cmake \\")
            sb.appendLine("    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24")
            sb.appendLine("  make -j\$(nproc)")
            sb.appendLine("  Copy .so files to: app/src/main/jniLibs/arm64-v8a/")
        } else {
            sb.appendLine("All MNN native libraries available.")
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun status(available: Boolean?): String = when (available) {
        true -> "AVAILABLE"
        false -> "NOT AVAILABLE"
        null -> "NOT INITIALIZED"
    }

    private fun elapsed(startMs: Long): Long = System.currentTimeMillis() - startMs

    companion object {
        private const val TAG = "AvatarPerceptionPlugin"
    }
}

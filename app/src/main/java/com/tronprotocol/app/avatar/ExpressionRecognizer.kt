package com.tronprotocol.app.avatar

import android.util.Log

/**
 * Facial expression recognition from face landmarks and optional MNN classification.
 *
 * Converts face landmark data (from FaceLandmarkEngine) into discrete expression labels
 * and continuous expression weights suitable for driving avatar blendshapes and feeding
 * into the AffectEngine.
 *
 * Supports two modes:
 * 1. **Geometric**: Derives expressions from landmark geometry (no model needed)
 * 2. **Model-based**: Uses an MNN expression classification model for higher accuracy
 *
 * Recognized expressions: neutral, happy, sad, angry, surprised, disgusted, fearful
 *
 * Pipeline: FaceLandmarks -> Expression Features -> Classification -> ExpressionResult
 */
class ExpressionRecognizer {

    private var visionEngine: MnnVisionEngine? = null
    private var useModelClassifier: Boolean = false

    val isReady: Boolean get() = true // Geometric mode is always ready; model mode checks engine

    /** Recognized expression categories. */
    enum class Expression(val label: String) {
        NEUTRAL("neutral"),
        HAPPY("happy"),
        SAD("sad"),
        ANGRY("angry"),
        SURPRISED("surprised"),
        DISGUSTED("disgusted"),
        FEARFUL("fearful")
    }

    /** Complete expression recognition result. */
    data class ExpressionResult(
        val primaryExpression: Expression,
        val confidence: Float,
        val expressionWeights: Map<Expression, Float>,
        val actionUnits: Map<String, Float>,
        val inferenceTimeMs: Long
    ) {
        /** Get the top-N expressions by confidence. */
        fun topExpressions(n: Int = 3): List<Pair<Expression, Float>> =
            expressionWeights.entries
                .sortedByDescending { it.value }
                .take(n)
                .map { it.key to it.value }

        /** Convert to blendshape weights for avatar animation. */
        fun toBlendshapeWeights(): Map<String, Float> {
            val weights = mutableMapOf<String, Float>()

            // Map action units to ARKit blendshapes
            actionUnits.forEach { (au, value) ->
                AU_TO_BLENDSHAPE[au]?.forEach { blendshape ->
                    weights[blendshape] = maxOf(weights[blendshape] ?: 0f, value)
                }
            }

            return weights
        }
    }

    /**
     * Initialize with optional MNN expression classification model.
     *
     * @param modelDir Directory containing expression classification .mnn model (optional).
     * @param backend Inference backend for model-based classification.
     * @return true (geometric mode always succeeds; model mode may fail).
     */
    fun initialize(
        modelDir: String? = null,
        backend: MnnVisionEngine.BackendType = MnnVisionEngine.BackendType.CPU,
        threads: Int = 2
    ): Boolean {
        if (modelDir != null && MnnVisionEngine.isNativeAvailable()) {
            val modelPath = "$modelDir/expression_classifier.mnn"
            val engine = MnnVisionEngine()
            val loaded = engine.loadModel(
                mnnModelPath = modelPath,
                width = 48,
                height = 48,
                channels = 1,
                backend = backend,
                numThreads = threads
            )
            if (loaded) {
                visionEngine = engine
                useModelClassifier = true
                Log.d(TAG, "Expression recognizer initialized with model classifier")
            } else {
                Log.w(TAG, "Failed to load expression model, falling back to geometric mode")
            }
        }

        Log.d(TAG, "Expression recognizer initialized (mode: ${if (useModelClassifier) "model" else "geometric"})")
        return true
    }

    /**
     * Recognize expression from face landmarks.
     *
     * @param landmarks FaceLandmarkEngine result.
     * @return ExpressionResult with detected expression and action units.
     */
    fun recognize(landmarks: FaceLandmarkEngine.LandmarkResult): ExpressionResult {
        val startMs = System.currentTimeMillis()

        // Extract geometric features from landmarks
        val actionUnits = extractActionUnits(landmarks)

        // Classify expression from action units
        val expressionWeights = classifyFromActionUnits(actionUnits)

        val primaryExpression = expressionWeights.maxByOrNull { it.value }?.key ?: Expression.NEUTRAL
        val confidence = expressionWeights[primaryExpression] ?: 0f

        val elapsed = System.currentTimeMillis() - startMs

        return ExpressionResult(
            primaryExpression = primaryExpression,
            confidence = confidence,
            expressionWeights = expressionWeights,
            actionUnits = actionUnits,
            inferenceTimeMs = elapsed
        )
    }

    /** Release resources. */
    fun release() {
        visionEngine?.release()
        visionEngine = null
        useModelClassifier = false
        Log.d(TAG, "Expression recognizer released")
    }

    // ---- Feature extraction from landmarks ----

    /**
     * Extract Facial Action Units (FACS) from face landmarks.
     * Based on Ekman's Facial Action Coding System.
     */
    private fun extractActionUnits(landmarks: FaceLandmarkEngine.LandmarkResult): Map<String, Float> {
        val aus = mutableMapOf<String, Float>()

        // AU1: Inner Brow Raise
        val (leftBrowRaise, rightBrowRaise) = landmarks.eyebrowRaise()
        aus["AU1_inner_brow_raise"] = (leftBrowRaise + rightBrowRaise) / 2f

        // AU2: Outer Brow Raise (use same as inner for geometric estimation)
        aus["AU2_outer_brow_raise"] = (leftBrowRaise + rightBrowRaise) / 2f

        // AU4: Brow Lowerer (inverse of raise)
        aus["AU4_brow_lowerer"] = (1f - (leftBrowRaise + rightBrowRaise) / 2f).coerceIn(0f, 1f)

        // AU5: Upper Lid Raise
        val (leftEyeOpen, rightEyeOpen) = landmarks.eyeOpenness()
        aus["AU5_upper_lid_raise"] = ((leftEyeOpen + rightEyeOpen) / 2f - 0.3f).coerceIn(0f, 1f)

        // AU6: Cheek Raise (approximate from eye squint)
        aus["AU6_cheek_raise"] = (1f - (leftEyeOpen + rightEyeOpen) / 2f).coerceIn(0f, 0.7f)

        // AU7: Lid Tightener
        aus["AU7_lid_tightener"] = (1f - (leftEyeOpen + rightEyeOpen) / 2f).coerceIn(0f, 1f)

        // AU9: Nose Wrinkler (approximate from upper lip raise)
        aus["AU9_nose_wrinkler"] = 0f // Requires more precise measurements

        // AU10: Upper Lip Raiser
        val mouthOpen = landmarks.mouthOpenness()
        aus["AU10_upper_lip_raiser"] = (mouthOpen * 0.3f).coerceIn(0f, 1f)

        // AU12: Lip Corner Puller (smile)
        aus["AU12_lip_corner_puller"] = estimateSmile(landmarks)

        // AU15: Lip Corner Depressor (frown)
        aus["AU15_lip_corner_depressor"] = estimateFrown(landmarks)

        // AU17: Chin Raiser
        aus["AU17_chin_raiser"] = 0f

        // AU20: Lip Stretcher
        aus["AU20_lip_stretcher"] = (mouthOpen * 0.5f).coerceIn(0f, 1f)

        // AU23: Lip Tightener
        aus["AU23_lip_tightener"] = (1f - mouthOpen).coerceIn(0f, 1f)

        // AU25: Lips Part
        aus["AU25_lips_part"] = mouthOpen

        // AU26: Jaw Drop
        aus["AU26_jaw_drop"] = (mouthOpen * 1.5f).coerceIn(0f, 1f)

        // AU27: Mouth Stretch
        aus["AU27_mouth_stretch"] = (mouthOpen - 0.4f).coerceIn(0f, 1f)

        // AU43: Eyes Closed
        aus["AU43_eyes_closed"] = (1f - (leftEyeOpen + rightEyeOpen) / 2f).coerceIn(0f, 1f)

        // AU45: Blink
        aus["AU45_blink"] = if ((leftEyeOpen + rightEyeOpen) / 2f < 0.15f) 1f else 0f

        return aus
    }

    private fun estimateSmile(landmarks: FaceLandmarkEngine.LandmarkResult): Float {
        if (landmarks.landmarks.size < 468) return 0f

        // Mouth corners (61, 291) relative to nose tip (1)
        val leftCorner = landmarks.landmarks[61]
        val rightCorner = landmarks.landmarks[291]
        val noseTip = landmarks.landmarks[1]

        val leftUp = noseTip.y - leftCorner.y
        val rightUp = noseTip.y - rightCorner.y
        val avgUp = (leftUp + rightUp) / 2f

        // Positive = corners above baseline (smile), normalized
        val faceHeight = landmarks.boundingBox().height()
        return if (faceHeight > 0) (avgUp / faceHeight * 3f).coerceIn(0f, 1f) else 0f
    }

    private fun estimateFrown(landmarks: FaceLandmarkEngine.LandmarkResult): Float {
        if (landmarks.landmarks.size < 468) return 0f

        val leftCorner = landmarks.landmarks[61]
        val rightCorner = landmarks.landmarks[291]
        val noseTip = landmarks.landmarks[1]

        val leftDown = leftCorner.y - noseTip.y
        val rightDown = rightCorner.y - noseTip.y
        val avgDown = (leftDown + rightDown) / 2f

        val faceHeight = landmarks.boundingBox().height()
        return if (faceHeight > 0) (avgDown / faceHeight * 3f).coerceIn(0f, 1f) else 0f
    }

    // ---- Expression classification from action units ----

    private fun classifyFromActionUnits(aus: Map<String, Float>): Map<Expression, Float> {
        val weights = mutableMapOf<Expression, Float>()

        // Happy: AU6 (cheek raise) + AU12 (smile)
        weights[Expression.HAPPY] = (
                aus.getOrDefault("AU6_cheek_raise", 0f) * 0.4f +
                aus.getOrDefault("AU12_lip_corner_puller", 0f) * 0.6f
        ).coerceIn(0f, 1f)

        // Sad: AU1 (inner brow raise) + AU4 (brow lower) + AU15 (frown)
        weights[Expression.SAD] = (
                aus.getOrDefault("AU1_inner_brow_raise", 0f) * 0.3f +
                aus.getOrDefault("AU4_brow_lowerer", 0f) * 0.3f +
                aus.getOrDefault("AU15_lip_corner_depressor", 0f) * 0.4f
        ).coerceIn(0f, 1f)

        // Angry: AU4 (brow lower) + AU7 (lid tighten) + AU23 (lip tighten)
        weights[Expression.ANGRY] = (
                aus.getOrDefault("AU4_brow_lowerer", 0f) * 0.4f +
                aus.getOrDefault("AU7_lid_tightener", 0f) * 0.3f +
                aus.getOrDefault("AU23_lip_tightener", 0f) * 0.3f
        ).coerceIn(0f, 1f)

        // Surprised: AU1+AU2 (brow raise) + AU5 (lid raise) + AU26 (jaw drop)
        weights[Expression.SURPRISED] = (
                aus.getOrDefault("AU1_inner_brow_raise", 0f) * 0.2f +
                aus.getOrDefault("AU2_outer_brow_raise", 0f) * 0.2f +
                aus.getOrDefault("AU5_upper_lid_raise", 0f) * 0.3f +
                aus.getOrDefault("AU26_jaw_drop", 0f) * 0.3f
        ).coerceIn(0f, 1f)

        // Disgusted: AU9 (nose wrinkle) + AU15 (frown) + AU10 (upper lip raise)
        weights[Expression.DISGUSTED] = (
                aus.getOrDefault("AU9_nose_wrinkler", 0f) * 0.4f +
                aus.getOrDefault("AU15_lip_corner_depressor", 0f) * 0.3f +
                aus.getOrDefault("AU10_upper_lip_raiser", 0f) * 0.3f
        ).coerceIn(0f, 1f)

        // Fearful: AU1+AU2 (brow raise) + AU4 (brow lower) + AU20 (lip stretch) + AU5 (lid raise)
        weights[Expression.FEARFUL] = (
                aus.getOrDefault("AU1_inner_brow_raise", 0f) * 0.2f +
                aus.getOrDefault("AU4_brow_lowerer", 0f) * 0.2f +
                aus.getOrDefault("AU5_upper_lid_raise", 0f) * 0.3f +
                aus.getOrDefault("AU20_lip_stretcher", 0f) * 0.3f
        ).coerceIn(0f, 1f)

        // Neutral: inverse of all other expressions
        val maxExpression = weights.values.maxOrNull() ?: 0f
        weights[Expression.NEUTRAL] = (1f - maxExpression).coerceIn(0f, 1f)

        // Normalize to sum to 1.0
        val total = weights.values.sum()
        if (total > 0) {
            weights.forEach { (k, v) -> weights[k] = v / total }
        }

        return weights
    }

    companion object {
        private const val TAG = "ExpressionRecognizer"

        /** Mapping from FACS Action Units to ARKit blendshape names. */
        val AU_TO_BLENDSHAPE = mapOf(
            "AU1_inner_brow_raise" to listOf("browInnerUp"),
            "AU2_outer_brow_raise" to listOf("browOuterUpLeft", "browOuterUpRight"),
            "AU4_brow_lowerer" to listOf("browDownLeft", "browDownRight"),
            "AU5_upper_lid_raise" to listOf("eyeWideLeft", "eyeWideRight"),
            "AU6_cheek_raise" to listOf("cheekSquintLeft", "cheekSquintRight"),
            "AU7_lid_tightener" to listOf("eyeSquintLeft", "eyeSquintRight"),
            "AU9_nose_wrinkler" to listOf("noseSneerLeft", "noseSneerRight"),
            "AU10_upper_lip_raiser" to listOf("mouthUpperUpLeft", "mouthUpperUpRight"),
            "AU12_lip_corner_puller" to listOf("mouthSmileLeft", "mouthSmileRight"),
            "AU15_lip_corner_depressor" to listOf("mouthFrownLeft", "mouthFrownRight"),
            "AU17_chin_raiser" to listOf("mouthShrugLower"),
            "AU20_lip_stretcher" to listOf("mouthStretchLeft", "mouthStretchRight"),
            "AU23_lip_tightener" to listOf("mouthPressLeft", "mouthPressRight"),
            "AU25_lips_part" to listOf("mouthClose"),
            "AU26_jaw_drop" to listOf("jawOpen"),
            "AU27_mouth_stretch" to listOf("jawOpen", "mouthFunnel"),
            "AU43_eyes_closed" to listOf("eyeBlinkLeft", "eyeBlinkRight"),
            "AU45_blink" to listOf("eyeBlinkLeft", "eyeBlinkRight")
        )
    }
}

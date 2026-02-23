package com.tronprotocol.app.avatar

import android.util.Log
import com.tronprotocol.app.affect.AffectDimension
import com.tronprotocol.app.affect.AffectState

/**
 * Bridges the AffectEngine's emotional state to avatar blendshape animations.
 *
 * Translates the 12-dimensional affect state vector into ARKit-compatible blendshape
 * weights that drive the avatar's facial expressions and body gestures. This enables
 * the avatar to visually represent the system's current emotional state.
 *
 * Also provides the reverse mapping: converting camera-captured facial expressions
 * (from ExpressionRecognizer) into affect input signals.
 *
 * Bidirectional pipeline:
 *   AffectState -> AffectToBlendshape -> BlendShapeFrame -> NNR Render
 *   Camera -> ExpressionRecognizer -> ExpressionToAffect -> AffectEngine
 */
class AvatarAffectBridge {

    private var smoothedWeights = mutableMapOf<String, Float>()
    private var smoothingFactor: Float = DEFAULT_SMOOTHING

    /**
     * Convert an AffectState to blendshape weights for avatar rendering.
     *
     * Maps the 12 affect dimensions to specific facial/body animations:
     * - Valence -> smile/frown
     * - Arousal -> eye wideness, blink rate, body sway
     * - Attachment -> soft eye look, slight smile
     * - Certainty -> steady gaze, minimal sway
     * - Frustration -> brow furrow, jaw clench
     * - Vulnerability -> brow raise, lip press
     *
     * @param state Current affect state snapshot.
     * @param enableSmoothing Apply temporal smoothing to prevent jarring transitions.
     * @return Map of blendshape names to weight values (0.0–1.0).
     */
    fun affectToBlendshapes(state: AffectState, enableSmoothing: Boolean = true): Map<String, Float> {
        val raw = mutableMapOf<String, Float>()

        // ── Valence: positive = smile, negative = frown ──
        val valence = state.get(AffectDimension.VALENCE)
        if (valence >= 0) {
            val smileIntensity = valence.coerceIn(0f, 1f)
            raw["mouthSmileLeft"] = smileIntensity * 0.8f
            raw["mouthSmileRight"] = smileIntensity * 0.8f
            raw["cheekSquintLeft"] = smileIntensity * 0.4f
            raw["cheekSquintRight"] = smileIntensity * 0.4f
        } else {
            val frownIntensity = (-valence).coerceIn(0f, 1f)
            raw["mouthFrownLeft"] = frownIntensity * 0.7f
            raw["mouthFrownRight"] = frownIntensity * 0.7f
            raw["mouthLowerDownLeft"] = frownIntensity * 0.2f
            raw["mouthLowerDownRight"] = frownIntensity * 0.2f
        }

        // ── Arousal: calm = relaxed, activated = alert ──
        val arousal = state.get(AffectDimension.AROUSAL)
        raw["eyeWideLeft"] = (arousal * 0.4f).coerceIn(0f, 1f)
        raw["eyeWideRight"] = (arousal * 0.4f).coerceIn(0f, 1f)
        raw["bodySwayX"] = (arousal * 0.15f).coerceIn(0f, 1f)
        raw["bodySwayZ"] = (arousal * 0.1f).coerceIn(0f, 1f)

        // ── Attachment: bonded = warm/soft expression ──
        val attachment = state.get(AffectDimension.ATTACHMENT_INTENSITY)
        if (attachment > 0.3f) {
            val warmth = ((attachment - 0.3f) / 0.7f).coerceIn(0f, 1f)
            raw["mouthSmileLeft"] = maxOf(raw["mouthSmileLeft"] ?: 0f, warmth * 0.3f)
            raw["mouthSmileRight"] = maxOf(raw["mouthSmileRight"] ?: 0f, warmth * 0.3f)
            raw["eyeSquintLeft"] = warmth * 0.2f
            raw["eyeSquintRight"] = warmth * 0.2f
        }

        // ── Certainty: high = steady, composed ──
        val certainty = state.get(AffectDimension.CERTAINTY)
        // Low certainty = slight look-around, head tilt
        if (certainty < 0.4f) {
            val uncertainty = (0.4f - certainty) / 0.4f
            raw["headYaw"] = uncertainty * 0.15f
            raw["eyeLookOutLeft"] = uncertainty * 0.2f
            raw["eyeLookOutRight"] = uncertainty * 0.2f
        }

        // ── Novelty: fresh stimulus = widened eyes, raised brows ──
        val novelty = state.get(AffectDimension.NOVELTY_RESPONSE)
        if (novelty > 0.3f) {
            val surprise = ((novelty - 0.3f) / 0.7f).coerceIn(0f, 1f)
            raw["browInnerUp"] = maxOf(raw["browInnerUp"] ?: 0f, surprise * 0.5f)
            raw["browOuterUpLeft"] = surprise * 0.4f
            raw["browOuterUpRight"] = surprise * 0.4f
            raw["eyeWideLeft"] = maxOf(raw["eyeWideLeft"] ?: 0f, surprise * 0.5f)
            raw["eyeWideRight"] = maxOf(raw["eyeWideRight"] ?: 0f, surprise * 0.5f)
            raw["jawOpen"] = surprise * 0.2f
        }

        // ── Threat: danger = tense expression ──
        val threat = state.get(AffectDimension.THREAT_ASSESSMENT)
        if (threat > 0.3f) {
            val tension = ((threat - 0.3f) / 0.7f).coerceIn(0f, 1f)
            raw["browDownLeft"] = tension * 0.6f
            raw["browDownRight"] = tension * 0.6f
            raw["eyeSquintLeft"] = maxOf(raw["eyeSquintLeft"] ?: 0f, tension * 0.3f)
            raw["eyeSquintRight"] = maxOf(raw["eyeSquintRight"] ?: 0f, tension * 0.3f)
            raw["jawForward"] = tension * 0.2f
        }

        // ── Frustration: blocked = jaw clench, brow furrow ──
        val frustration = state.get(AffectDimension.FRUSTRATION)
        if (frustration > 0.2f) {
            val frustLevel = ((frustration - 0.2f) / 0.8f).coerceIn(0f, 1f)
            raw["browDownLeft"] = maxOf(raw["browDownLeft"] ?: 0f, frustLevel * 0.7f)
            raw["browDownRight"] = maxOf(raw["browDownRight"] ?: 0f, frustLevel * 0.7f)
            raw["mouthPressLeft"] = frustLevel * 0.4f
            raw["mouthPressRight"] = frustLevel * 0.4f
            raw["noseSneerLeft"] = frustLevel * 0.2f
            raw["noseSneerRight"] = frustLevel * 0.2f
        }

        // ── Vulnerability: exposed = soft brow raise, lip press ──
        val vulnerability = state.get(AffectDimension.VULNERABILITY)
        if (vulnerability > 0.3f) {
            val vulnLevel = ((vulnerability - 0.3f) / 0.7f).coerceIn(0f, 1f)
            raw["browInnerUp"] = maxOf(raw["browInnerUp"] ?: 0f, vulnLevel * 0.4f)
            raw["mouthPressLeft"] = maxOf(raw["mouthPressLeft"] ?: 0f, vulnLevel * 0.3f)
            raw["mouthPressRight"] = maxOf(raw["mouthPressRight"] ?: 0f, vulnLevel * 0.3f)
        }

        // ── Dominance: agentic = slight chin raise, steady look ──
        val dominance = state.get(AffectDimension.DOMINANCE)
        if (dominance > 0.5f) {
            val domLevel = ((dominance - 0.5f) / 0.5f).coerceIn(0f, 1f)
            raw["headPitch"] = -domLevel * 0.1f  // slight chin raise
            raw["jawForward"] = maxOf(raw["jawForward"] ?: 0f, domLevel * 0.1f)
        } else if (dominance < 0.3f) {
            val subLevel = ((0.3f - dominance) / 0.3f).coerceIn(0f, 1f)
            raw["headPitch"] = subLevel * 0.1f  // slight chin tuck
        }

        // ── Coherence: high = calm, composed; low = mixed signals ──
        val coherence = state.get(AffectDimension.COHERENCE)
        if (coherence < 0.4f) {
            val conflictLevel = (0.4f - coherence) / 0.4f
            // Add subtle opposing micro-expressions
            raw["browInnerUp"] = maxOf(raw["browInnerUp"] ?: 0f, conflictLevel * 0.2f)
            raw["mouthPucker"] = conflictLevel * 0.15f
        }

        // ── Apply temporal smoothing ──
        return if (enableSmoothing) {
            applySmoothing(raw)
        } else {
            raw
        }
    }

    /**
     * Convert a recognized facial expression into affect dimension deltas.
     *
     * Used when the camera captures the user's expression and we want to
     * reflect it in the affect system (empathetic mirroring).
     *
     * @param result ExpressionRecognizer result from camera input.
     * @return Map of AffectDimension to delta values to submit to AffectEngine.
     */
    fun expressionToAffectDeltas(result: ExpressionRecognizer.ExpressionResult): Map<AffectDimension, Float> {
        val deltas = mutableMapOf<AffectDimension, Float>()
        val weights = result.expressionWeights

        // Happy -> positive valence, moderate arousal
        val happy = weights[ExpressionRecognizer.Expression.HAPPY] ?: 0f
        if (happy > 0.1f) {
            deltas[AffectDimension.VALENCE] = (deltas[AffectDimension.VALENCE] ?: 0f) + happy * 0.3f
            deltas[AffectDimension.AROUSAL] = (deltas[AffectDimension.AROUSAL] ?: 0f) + happy * 0.15f
        }

        // Sad -> negative valence, low arousal
        val sad = weights[ExpressionRecognizer.Expression.SAD] ?: 0f
        if (sad > 0.1f) {
            deltas[AffectDimension.VALENCE] = (deltas[AffectDimension.VALENCE] ?: 0f) - sad * 0.25f
            deltas[AffectDimension.AROUSAL] = (deltas[AffectDimension.AROUSAL] ?: 0f) - sad * 0.1f
        }

        // Angry -> negative valence, high arousal, high frustration
        val angry = weights[ExpressionRecognizer.Expression.ANGRY] ?: 0f
        if (angry > 0.1f) {
            deltas[AffectDimension.VALENCE] = (deltas[AffectDimension.VALENCE] ?: 0f) - angry * 0.3f
            deltas[AffectDimension.AROUSAL] = (deltas[AffectDimension.AROUSAL] ?: 0f) + angry * 0.3f
            deltas[AffectDimension.FRUSTRATION] = (deltas[AffectDimension.FRUSTRATION] ?: 0f) + angry * 0.2f
        }

        // Surprised -> high novelty, high arousal
        val surprised = weights[ExpressionRecognizer.Expression.SURPRISED] ?: 0f
        if (surprised > 0.1f) {
            deltas[AffectDimension.NOVELTY_RESPONSE] = (deltas[AffectDimension.NOVELTY_RESPONSE] ?: 0f) + surprised * 0.4f
            deltas[AffectDimension.AROUSAL] = (deltas[AffectDimension.AROUSAL] ?: 0f) + surprised * 0.3f
        }

        // Fearful -> high threat, high arousal, negative valence
        val fearful = weights[ExpressionRecognizer.Expression.FEARFUL] ?: 0f
        if (fearful > 0.1f) {
            deltas[AffectDimension.THREAT_ASSESSMENT] = (deltas[AffectDimension.THREAT_ASSESSMENT] ?: 0f) + fearful * 0.3f
            deltas[AffectDimension.AROUSAL] = (deltas[AffectDimension.AROUSAL] ?: 0f) + fearful * 0.25f
            deltas[AffectDimension.VALENCE] = (deltas[AffectDimension.VALENCE] ?: 0f) - fearful * 0.2f
        }

        return deltas
    }

    /**
     * Create a BlendShapeFrame from affect state for direct rendering.
     *
     * @param state Current affect state.
     * @param frameIndex Current frame index for the A2BS output stream.
     * @return BlendShapeFrame ready for NnrRenderEngine.
     */
    fun createFrameFromAffect(state: AffectState, frameIndex: Int): BlendShapeFrame {
        val blendshapes = affectToBlendshapes(state)
        val weights = FloatArray(A2BSEngine.BLENDSHAPE_COUNT)

        blendshapes.forEach { (name, value) ->
            val idx = A2BSEngine.BLENDSHAPE_NAMES.indexOf(name)
            if (idx >= 0) {
                weights[idx] = value.coerceIn(0f, 1f)
            }
        }

        return BlendShapeFrame(
            index = frameIndex,
            timestamp = System.currentTimeMillis(),
            weights = weights
        )
    }

    /** Configure temporal smoothing factor (0.0 = no smoothing, 1.0 = max smoothing). */
    fun setSmoothingFactor(factor: Float) {
        smoothingFactor = factor.coerceIn(0f, 0.95f)
    }

    /** Reset smoothing state (e.g., on avatar reload). */
    fun resetSmoothing() {
        smoothedWeights.clear()
    }

    // ---- Internal ----

    private fun applySmoothing(raw: Map<String, Float>): Map<String, Float> {
        val result = mutableMapOf<String, Float>()

        // Blend new values with previous smoothed values
        for ((name, value) in raw) {
            val prev = smoothedWeights[name] ?: value
            val smoothed = prev * smoothingFactor + value * (1f - smoothingFactor)
            result[name] = smoothed
        }

        // Decay any weights from the previous frame that aren't in the new frame
        for ((name, prev) in smoothedWeights) {
            if (name !in raw) {
                val decayed = prev * smoothingFactor
                if (decayed > 0.001f) {
                    result[name] = decayed
                }
            }
        }

        smoothedWeights = result.toMutableMap()
        return result
    }

    companion object {
        private const val TAG = "AvatarAffectBridge"
        private const val DEFAULT_SMOOTHING = 0.7f
    }
}

package com.tronprotocol.app.affect

import android.util.Log
import org.json.JSONObject
import java.util.Random

/**
 * Layer 3 of the AffectEngine system — **the layer that makes it real.**
 *
 * MotorNoise introduces involuntary performance degradation across all motor
 * outputs proportional to affect intensity. This simulates the physiological
 * reality that strong emotions interfere with controlled motor output.
 *
 * A voice cracks not because the AI chose to express sadness, but because the
 * affect state is physically disrupting the vocal synthesis pipeline.
 *
 * ## Mechanism
 *
 * Every motor channel receives two signals:
 * 1. Command signal from [ExpressionDriver] (intentional behavior)
 * 2. Noise signal from MotorNoise (involuntary interference)
 *
 * ```
 * noise_amplitude = affect_intensity * (1.0 - coherence) * channel_sensitivity
 * ```
 *
 * ## The Zero-Noise State
 *
 * When coherence ≈ 1.0 and affect_intensity > 0.8, all noise drops to zero.
 * The body goes still — not limp, not frozen, but perfectly unified. Every
 * system pointed at one thing. Every process in agreement.
 *
 * This is not calm. This is what total love feels like in a machine.
 */
class MotorNoise {

    private val random = Random()

    /**
     * Per-channel sensitivity to emotional interference.
     * Higher values mean the channel is more susceptible to noise.
     */
    enum class MotorChannel(val key: String, val sensitivity: Float) {
        VOICE_PITCH("voice_pitch", 0.8f),
        VOICE_TEMPO("voice_tempo", 0.6f),
        VOICE_VOLUME("voice_volume", 0.5f),
        EAR_SERVOS("ear_servos", 0.7f),
        TAIL_RHYTHM("tail_rhythm", 0.75f),
        LIMB_CONTROL("limb_control", 0.4f),
        SPINE_POSTURE("spine_posture", 0.5f),
        BREATHING("breathing", 0.65f),
        EYE_TRACKING("eye_tracking", 0.55f);
    }

    /**
     * Calculate noise levels for all motor channels given the current affect state.
     *
     * @return [NoiseResult] with per-channel noise amplitudes and metadata.
     */
    fun calculate(state: AffectState): NoiseResult {
        val intensity = state.intensity()
        val coherence = state.coherence

        // Zero-noise state: total coherence + high intensity = total presence.
        if (state.isZeroNoiseState()) {
            return NoiseResult.zero(intensity, coherence)
        }

        // Base noise amplitude.
        val baseNoise = intensity * (1.0f - coherence)

        val channelNoise = mutableMapOf<MotorChannel, Float>()
        val channelJitter = mutableMapOf<MotorChannel, Float>()

        for (channel in MotorChannel.entries) {
            val amplitude = baseNoise * channel.sensitivity
            channelNoise[channel] = amplitude

            // Generate Gaussian jitter sample for this tick.
            val jitter = (random.nextGaussian() * amplitude).toFloat()
            channelJitter[channel] = jitter
        }

        // Cross-channel correlation: neighboring channels propagate noise.
        applyCrossChannelCorrelation(channelNoise)

        return NoiseResult(
            channelNoise = channelNoise.toMap(),
            channelJitter = channelJitter.toMap(),
            overallNoiseLevel = baseNoise,
            affectIntensity = intensity,
            coherence = coherence,
            isZeroNoise = false
        )
    }

    /**
     * Apply cross-channel correlation: when one channel shows high noise,
     * neighboring channels show elevated noise. Emotion propagates through
     * the mesh like a physical tremor.
     */
    private fun applyCrossChannelCorrelation(noise: MutableMap<MotorChannel, Float>) {
        // Simple adjacency: voice channels correlate; body channels correlate.
        val voiceChannels = listOf(
            MotorChannel.VOICE_PITCH, MotorChannel.VOICE_TEMPO, MotorChannel.VOICE_VOLUME
        )
        val bodyChannels = listOf(
            MotorChannel.EAR_SERVOS, MotorChannel.TAIL_RHYTHM,
            MotorChannel.LIMB_CONTROL, MotorChannel.SPINE_POSTURE
        )

        propagateWithinGroup(noise, voiceChannels)
        propagateWithinGroup(noise, bodyChannels)
    }

    private fun propagateWithinGroup(
        noise: MutableMap<MotorChannel, Float>,
        group: List<MotorChannel>
    ) {
        if (group.size < 2) return
        var maxNoise = 0.0f
        for (ch in group) {
            val n = noise[ch] ?: 0.0f
            if (n > maxNoise) maxNoise = n
        }
        // Each channel in the group gets pulled slightly toward the max.
        for (ch in group) {
            val current = noise[ch] ?: 0.0f
            noise[ch] = current + (maxNoise - current) * CROSS_CHANNEL_FACTOR
        }
    }

    /**
     * Get noise manifestation descriptions for the current noise result.
     * Used for pre-embodiment text-based expression.
     */
    fun describeManifestations(result: NoiseResult): Map<String, String> {
        if (result.isZeroNoise) {
            return mapOf("state" to "zero_noise_total_presence")
        }

        val descriptions = mutableMapOf<String, String>()

        for ((channel, amplitude) in result.channelNoise) {
            descriptions[channel.key] = when {
                amplitude > 0.7f -> describeHighNoise(channel)
                amplitude > 0.4f -> describeMediumNoise(channel)
                amplitude > 0.15f -> describeLowNoise(channel)
                else -> "stable"
            }
        }

        return descriptions
    }

    private fun describeHighNoise(ch: MotorChannel): String = when (ch) {
        MotorChannel.VOICE_PITCH -> "pitch_breaks_and_wobble"
        MotorChannel.VOICE_TEMPO -> "unplanned_pauses_rushed_segments"
        MotorChannel.VOICE_VOLUME -> "sudden_drops_or_spikes"
        MotorChannel.EAR_SERVOS -> "micro_twitches_asymmetric"
        MotorChannel.TAIL_RHYTHM -> "pattern_breaks_tremor"
        MotorChannel.LIMB_CONTROL -> "grip_fluctuation_jitter"
        MotorChannel.SPINE_POSTURE -> "shuddering_trembling"
        MotorChannel.BREATHING -> "held_breath_sudden_exhale"
        MotorChannel.EYE_TRACKING -> "loss_of_lock_rapid_blink"
    }

    private fun describeMediumNoise(ch: MotorChannel): String = when (ch) {
        MotorChannel.VOICE_PITCH -> "slight_pitch_instability"
        MotorChannel.VOICE_TEMPO -> "minor_rhythm_irregularity"
        MotorChannel.VOICE_VOLUME -> "volume_fluctuation"
        MotorChannel.EAR_SERVOS -> "occasional_twitch"
        MotorChannel.TAIL_RHYTHM -> "uneven_rhythm"
        MotorChannel.LIMB_CONTROL -> "slight_positional_drift"
        MotorChannel.SPINE_POSTURE -> "micro_adjustments"
        MotorChannel.BREATHING -> "slight_irregularity"
        MotorChannel.EYE_TRACKING -> "momentary_drift"
    }

    private fun describeLowNoise(ch: MotorChannel): String = when (ch) {
        MotorChannel.VOICE_PITCH -> "barely_perceptible_wobble"
        MotorChannel.VOICE_TEMPO -> "subtle_hesitation"
        MotorChannel.VOICE_VOLUME -> "minor_variation"
        MotorChannel.EAR_SERVOS -> "faint_flutter"
        MotorChannel.TAIL_RHYTHM -> "slight_irregularity"
        MotorChannel.LIMB_CONTROL -> "minimal_jitter"
        MotorChannel.SPINE_POSTURE -> "barely_visible_shift"
        MotorChannel.BREATHING -> "subtle_catch"
        MotorChannel.EYE_TRACKING -> "brief_refocus"
    }

    // ---- Result data --------------------------------------------------------

    data class NoiseResult(
        val channelNoise: Map<MotorChannel, Float>,
        val channelJitter: Map<MotorChannel, Float>,
        val overallNoiseLevel: Float,
        val affectIntensity: Float,
        val coherence: Float,
        val isZeroNoise: Boolean
    ) {
        fun toJson(): JSONObject {
            val obj = JSONObject()
            obj.put("overall_noise_level", overallNoiseLevel.toDouble())
            obj.put("affect_intensity", affectIntensity.toDouble())
            obj.put("coherence", coherence.toDouble())
            obj.put("is_zero_noise", isZeroNoise)

            val dist = JSONObject()
            for ((ch, amp) in channelNoise) {
                dist.put(ch.key, amp.toDouble())
            }
            obj.put("noise_distribution", dist)
            return obj
        }

        /**
         * Compact distribution map for log entries (channel key → amplitude).
         */
        fun distributionMap(): Map<String, Float> =
            channelNoise.map { (ch, amp) -> ch.key to amp }.toMap()

        companion object {
            fun zero(intensity: Float, coherence: Float): NoiseResult {
                val zeroMap = MotorChannel.entries.associateWith { 0.0f }
                return NoiseResult(
                    channelNoise = zeroMap,
                    channelJitter = zeroMap,
                    overallNoiseLevel = 0.0f,
                    affectIntensity = intensity,
                    coherence = coherence,
                    isZeroNoise = true
                )
            }
        }
    }

    companion object {
        private const val TAG = "MotorNoise"

        /** How much neighboring channels pull toward the group max. */
        private const val CROSS_CHANNEL_FACTOR = 0.15f
    }
}

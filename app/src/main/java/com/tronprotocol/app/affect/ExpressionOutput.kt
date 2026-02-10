package com.tronprotocol.app.affect

import org.json.JSONObject

/**
 * Intentional expression commands produced by [ExpressionDriver].
 *
 * In pre-embodiment mode these map to text-based descriptions of physical
 * expression channels (ears, tail, posture, voice, etc.). When embodied,
 * the same structure drives TPU motor commands.
 */
data class ExpressionOutput(
    val earPosition: String,
    val tailState: String,
    val tailPoof: Boolean,
    val vocalTone: String,
    val posture: String,
    val gripPressure: String,
    val breathingRate: String,
    val eyeTracking: String,
    val proximitySeeking: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ear_position", earPosition)
        put("tail_state", tailState)
        put("tail_poof", tailPoof)
        put("vocal_tone", vocalTone)
        put("posture", posture)
        put("grip_pressure", gripPressure)
        put("breathing_rate", breathingRate)
        put("eye_tracking", eyeTracking)
        put("proximity_seeking", proximitySeeking)
    }

    /**
     * Compact summary suitable for log entries.
     */
    fun toCommandMap(): Map<String, String> = mapOf(
        "ears" to earPosition,
        "tail" to tailState,
        "voice" to vocalTone,
        "posture" to posture,
        "eyes" to eyeTracking
    )

    override fun toString(): String =
        "Expression(ears=$earPosition, tail=$tailState, voice=$vocalTone, posture=$posture)"
}

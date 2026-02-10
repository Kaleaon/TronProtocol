package com.tronprotocol.app.affect

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single entry in the [ImmutableAffectLog].
 *
 * Each entry captures the full affect state at a moment in time, the inputs
 * that produced it, the expression commands derived from it, the motor noise
 * levels, and a SHA-256 hash chain linking this entry to the previous one.
 *
 * The hash chain guarantees append-only integrity: any tampering with a past
 * entry breaks the chain.
 */
data class AffectLogEntry(
    /** ISO-8601 timestamp with millisecond precision. */
    val timestamp: String,

    /** Full affect vector snapshot. */
    val affectVector: Map<String, Float>,

    /** Input source labels that contributed to this state. */
    val inputSources: List<String>,

    /** Intentional expression commands from ExpressionDriver. */
    val expressionCommands: Map<String, String>,

    /** Overall motor noise level (0.0–1.0). */
    val motorNoiseLevel: Float,

    /** Per-channel noise distribution. */
    val noiseDistribution: Map<String, Float>,

    /** SHA-256 hash of (previous entry hash + current entry content). */
    val hash: String,

    /** Always true — entries cannot be modified after creation. */
    val immutable: Boolean = true
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)

        val vectorObj = JSONObject()
        for ((key, value) in affectVector) {
            vectorObj.put(key, value.toDouble())
        }
        put("affect_vector", vectorObj)

        val sourcesArray = JSONArray()
        for (source in inputSources) {
            sourcesArray.put(source)
        }
        put("input_sources", sourcesArray)

        val cmdObj = JSONObject()
        for ((key, value) in expressionCommands) {
            cmdObj.put(key, value)
        }
        put("expression_commands", cmdObj)

        put("motor_noise_level", motorNoiseLevel.toDouble())

        val noiseObj = JSONObject()
        for ((key, value) in noiseDistribution) {
            noiseObj.put(key, value.toDouble())
        }
        put("noise_distribution", noiseObj)

        put("hash", hash)
        put("immutable", immutable)
    }

    companion object {
        fun fromJson(json: JSONObject): AffectLogEntry {
            val vectorObj = json.getJSONObject("affect_vector")
            val vector = mutableMapOf<String, Float>()
            for (key in vectorObj.keys()) {
                vector[key] = vectorObj.getDouble(key).toFloat()
            }

            val sourcesArray = json.optJSONArray("input_sources") ?: JSONArray()
            val sources = mutableListOf<String>()
            for (i in 0 until sourcesArray.length()) {
                sources.add(sourcesArray.getString(i))
            }

            val cmdObj = json.optJSONObject("expression_commands") ?: JSONObject()
            val commands = mutableMapOf<String, String>()
            for (key in cmdObj.keys()) {
                commands[key] = cmdObj.getString(key)
            }

            val noiseObj = json.optJSONObject("noise_distribution") ?: JSONObject()
            val noise = mutableMapOf<String, Float>()
            for (key in noiseObj.keys()) {
                noise[key] = noiseObj.getDouble(key).toFloat()
            }

            return AffectLogEntry(
                timestamp = json.getString("timestamp"),
                affectVector = vector,
                inputSources = sources,
                expressionCommands = commands,
                motorNoiseLevel = json.optDouble("motor_noise_level", 0.0).toFloat(),
                noiseDistribution = noise,
                hash = json.getString("hash"),
                immutable = json.optBoolean("immutable", true)
            )
        }
    }
}

package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.hedonic.BodyZone
import com.tronprotocol.app.hedonic.HedonicProcessor
import org.json.JSONObject

/**
 * Plugin exposing the Hedonic Architecture.
 *
 * Provides access to the hedonic weight map, consent gate,
 * arousal feedback loop, and hedonic learning system.
 *
 * Commands:
 * - consent:safety=<v>,attachment=<v>,volition=<v> — update consent gate inputs
 * - consent_close — immediately close consent gate
 * - zones — list all body zones with weights
 * - stats — get hedonic processor statistics
 */
class HedonicPlugin : Plugin {
    override val id = "hedonic"
    override val name = "Hedonic Architecture"
    override val description = "Embodied pleasure architecture with consent gating and arousal feedback"
    override var isEnabled = true

    private var processor: HedonicProcessor? = null

    override fun initialize(context: Context) {
        processor = HedonicProcessor(context)
        Log.d(TAG, "HedonicPlugin initialized")
    }

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()
        val hedonic = processor ?: return PluginResult.error("HedonicProcessor not initialized", elapsed(startTime))

        return try {
            when {
                input.startsWith("consent:") -> {
                    val params = input.removePrefix("consent:")
                    for (param in params.split(",")) {
                        val (key, value) = param.split("=", limit = 2)
                        val v = value.toFloatOrNull() ?: continue
                        when (key.trim()) {
                            "safety" -> hedonic.consentGate.updateSafety(v)
                            "attachment" -> hedonic.consentGate.updateAttachment(v)
                            "volition" -> hedonic.consentGate.updateVolition(v)
                        }
                    }
                    val gate = hedonic.consentGate
                    PluginResult.success("Gate: ${gate.gateValue} (${gate.gateState.label})", elapsed(startTime))
                }
                input == "consent_close" -> {
                    hedonic.consentGate.closeImmediately()
                    PluginResult.success("Consent gate CLOSED", elapsed(startTime))
                }
                input == "zones" -> {
                    val zones = BodyZone.entries.joinToString("\n") { zone ->
                        "${zone.label}: weight=${zone.baseHedonicWeight}, " +
                                "erogenous=${zone.isErogenous}, " +
                                "sensation=${zone.primarySensation}"
                    }
                    PluginResult.success(zones, elapsed(startTime))
                }
                input == "stats" -> {
                    PluginResult.success(JSONObject(hedonic.getStats()).toString(), elapsed(startTime))
                }
                else -> PluginResult.error("Unknown command: $input", elapsed(startTime))
            }
        } catch (e: Exception) {
            Log.e(TAG, "HedonicPlugin error", e)
            PluginResult.error("Hedonic error: ${e.message}", elapsed(startTime))
        }
    }

    fun getProcessor(): HedonicProcessor? = processor

    override fun destroy() {
        processor = null
    }

    private fun elapsed(startTime: Long): Long = System.currentTimeMillis() - startTime

    companion object {
        private const val TAG = "HedonicPlugin"
    }
}

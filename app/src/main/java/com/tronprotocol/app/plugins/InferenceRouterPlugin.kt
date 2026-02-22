package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.inference.InferenceRouter
import org.json.JSONObject

/**
 * Plugin exposing the three-tier InferenceRouter.
 *
 * Routes inference to: Local Always-On, Local On-Demand (SLM), or Cloud (Claude API).
 *
 * Commands:
 * - infer:<prompt> — route and execute inference
 * - infer_local:<prompt> — force local-only inference
 * - configure_cloud:<api_key> — configure Claude API key for cloud tier
 * - stats — get routing statistics
 */
class InferenceRouterPlugin : Plugin {
    override val id = "inference_router"
    override val name = "Inference Router"
    override val description = "Three-tier hybrid inference routing (Local/SLM/Cloud)"
    override var isEnabled = true

    private var router: InferenceRouter? = null

    override fun initialize(context: Context) {
        router = InferenceRouter(context)
        Log.d(TAG, "InferenceRouterPlugin initialized")
    }

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()
        val inference = router ?: return PluginResult.error("InferenceRouter not initialized", elapsed(startTime))

        return try {
            when {
                input.startsWith("infer:") -> {
                    val prompt = input.removePrefix("infer:")
                    val result = inference.infer(prompt)
                    val json = result.toJson().apply {
                        put("tier_used", result.tier.label)
                    }
                    PluginResult.success(json.toString(), elapsed(startTime))
                }
                input.startsWith("infer_local:") -> {
                    val prompt = input.removePrefix("infer_local:")
                    val result = inference.infer(prompt, requireLocal = true)
                    PluginResult.success(result.toJson().toString(), elapsed(startTime))
                }
                input.startsWith("configure_cloud:") -> {
                    val apiKey = input.removePrefix("configure_cloud:")
                    inference.configureCloud(apiKey)
                    PluginResult.success("Cloud tier configured", elapsed(startTime))
                }
                input == "stats" -> {
                    PluginResult.success(JSONObject(inference.getStats()).toString(), elapsed(startTime))
                }
                else -> PluginResult.error("Unknown command: $input", elapsed(startTime))
            }
        } catch (e: Exception) {
            Log.e(TAG, "InferenceRouterPlugin error", e)
            PluginResult.error("Inference router error: ${e.message}", elapsed(startTime))
        }
    }

    fun getRouter(): InferenceRouter? = router

    override fun destroy() {
        router = null
    }

    private fun elapsed(startTime: Long): Long = System.currentTimeMillis() - startTime

    companion object {
        private const val TAG = "InferenceRouterPlugin"
    }
}

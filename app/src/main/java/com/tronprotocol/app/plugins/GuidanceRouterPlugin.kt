package com.tronprotocol.app.plugins

import android.content.Context
import com.tronprotocol.app.guidance.AnthropicApiClient
import com.tronprotocol.app.guidance.DecisionRouter
import com.tronprotocol.app.guidance.EthicalKernelValidator
import com.tronprotocol.app.guidance.GuidanceOrchestrator
import com.tronprotocol.app.llm.OnDeviceLLMManager
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.security.SecureStorage
import com.tronprotocol.app.selfmod.CodeModificationManager

/**
 * Routes routine guidance through Sonnet and high-stakes guidance through Opus.
 */
class GuidanceRouterPlugin : Plugin {

    companion object {
        private const val ID = "guidance_router"
        private const val API_KEY = "anthropic_api_key"
        private const val AI_ID = "tronprotocol_ai"
    }

    private var secureStorage: SecureStorage? = null
    private var orchestrator: GuidanceOrchestrator? = null
    private var onDeviceLLMManager: OnDeviceLLMManager? = null

    override val id: String = ID

    override val name: String = "Guidance Router"

    override val description: String =
        "Anthropic-backed guidance with decision routing, cache, and ethical checks. " +
            "Commands: set_api_key|key, guide|question, stats"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            if (input.isNullOrBlank()) {
                return PluginResult.error("No command provided", elapsed(start))
            }

            val parts = input.split("\\|".toRegex(), 2)
            val command = parts[0].trim().lowercase()

            when (command) {
                "set_api_key" -> {
                    if (parts.size < 2 || parts[1].trim().isEmpty()) {
                        return PluginResult.error("Usage: set_api_key|<anthropic_api_key>", elapsed(start))
                    }
                    val storage = secureStorage
                        ?: return PluginResult.error("Plugin not initialized", elapsed(start))
                    storage.store(API_KEY, parts[1].trim())
                    PluginResult.success("Anthropic API key saved", elapsed(start))
                }
                "guide" -> {
                    if (parts.size < 2 || parts[1].trim().isEmpty()) {
                        return PluginResult.error("Usage: guide|<question>", elapsed(start))
                    }
                    val storage = secureStorage
                        ?: return PluginResult.error("Plugin not initialized", elapsed(start))
                    val orch = orchestrator
                        ?: return PluginResult.error("Plugin not initialized", elapsed(start))
                    val key = storage.retrieve(API_KEY)
                        ?: return PluginResult.error("API key not set. Use set_api_key first", elapsed(start))
                    val response = orch.guide(key, parts[1].trim())
                    if (!response.success) {
                        return PluginResult.error("Guidance failed: ${response.error}", elapsed(start))
                    }
                    val payload = "route=${response.route}" +
                        ", model=${response.model}" +
                        ", cache_hit=${response.cacheHit}\n${response.answer}"
                    PluginResult.success(payload, elapsed(start))
                }
                "stats" -> {
                    val llmStatus = if (onDeviceLLMManager?.isReady == true)
                        "READY (${onDeviceLLMManager?.activeConfig?.modelName})"
                    else if (OnDeviceLLMManager.isNativeAvailable())
                        "available (no model loaded)"
                    else
                        "unavailable (MNN native libs not installed)"
                    PluginResult.success(
                        "Configured models: routine=${AnthropicApiClient.MODEL_SONNET}" +
                            ", high_stakes=${AnthropicApiClient.MODEL_OPUS}" +
                            ", on_device_llm=$llmStatus" +
                            "; ethical kernel validation enabled on local+cloud+ondevice layers",
                        elapsed(start)
                    )
                }
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Guidance router failed: ${e.message}", elapsed(start))
        }
    }

    override fun initialize(context: Context) {
        try {
            secureStorage = SecureStorage(context)
            val ragStore = RAGStore(context, AI_ID)
            val codeModificationManager = CodeModificationManager(context)

            // Initialize on-device LLM manager (MNN-powered)
            val llmManager = OnDeviceLLMManager(context)
            onDeviceLLMManager = llmManager

            val client = AnthropicApiClient(20, 1500)
            val router = DecisionRouter()

            // Inform the router whether on-device LLM is available
            router.onDeviceLLMAvailable = llmManager.isReady

            val validator = EthicalKernelValidator(codeModificationManager)
            orchestrator = GuidanceOrchestrator(client, router, validator, ragStore, llmManager)
        } catch (e: Exception) {
            throw RuntimeException("Failed to initialize guidance router", e)
        }
    }

    override fun destroy() {
        onDeviceLLMManager?.shutdown()
        onDeviceLLMManager = null
        orchestrator = null
        secureStorage = null
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start
}

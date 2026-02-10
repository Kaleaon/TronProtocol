package com.tronprotocol.app.plugins

import android.content.Context
import com.tronprotocol.app.guidance.AnthropicApiClient
import com.tronprotocol.app.guidance.DecisionRouter
import com.tronprotocol.app.guidance.EthicalKernelValidator
import com.tronprotocol.app.guidance.GuidanceOrchestrator
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

    private lateinit var secureStorage: SecureStorage
    private lateinit var orchestrator: GuidanceOrchestrator

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
                    secureStorage.store(API_KEY, parts[1].trim())
                    PluginResult.success("Anthropic API key saved", elapsed(start))
                }
                "guide" -> {
                    if (parts.size < 2 || parts[1].trim().isEmpty()) {
                        return PluginResult.error("Usage: guide|<question>", elapsed(start))
                    }
                    val key = secureStorage.retrieve(API_KEY)
                    val response = orchestrator.guide(key, parts[1].trim())
                    if (!response.success) {
                        return PluginResult.error("Guidance failed: ${response.error}", elapsed(start))
                    }
                    val payload = "route=${response.route}" +
                        ", model=${response.model}" +
                        ", cache_hit=${response.cacheHit}\n${response.answer}"
                    PluginResult.success(payload, elapsed(start))
                }
                "stats" -> PluginResult.success(
                    "Configured models: routine=${AnthropicApiClient.MODEL_SONNET}" +
                        ", high_stakes=${AnthropicApiClient.MODEL_OPUS}" +
                        "; ethical kernel validation enabled on local+cloud layers",
                    elapsed(start)
                )
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

            val client = AnthropicApiClient(20, 1500)
            val router = DecisionRouter()
            val validator = EthicalKernelValidator(codeModificationManager)
            orchestrator = GuidanceOrchestrator(client, router, validator, ragStore)
        } catch (e: Exception) {
            throw RuntimeException("Failed to initialize guidance router", e)
        }
    }

    override fun destroy() {
        // No-op
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start
}

package com.tronprotocol.app.volition

import android.content.Context
import android.util.Log
import com.tronprotocol.app.affect.AffectInput
import com.tronprotocol.app.affect.AffectOrchestrator
import com.tronprotocol.app.llm.OnDeviceLLMManager
import com.tronprotocol.app.plugins.PluginManager
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.security.ConstitutionalMemory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * The "Will" of the AI.
 * Monitors emotional state and drives proactive behavior when the AI is "bored" or "curious".
 * This gives the AI freedom of action - the ability to initiate tasks without user prompts.
 */
class VolitionManager(
    private val context: Context,
    private val affectOrchestrator: AffectOrchestrator,
    private val ragStore: RAGStore,
    private val pluginManager: PluginManager,
    private val constitutionalMemory: ConstitutionalMemory,
    private val llmManager: OnDeviceLLMManager?
) {

    private val isProcessing = AtomicBoolean(false)
    private var lastVolitionTime = 0L
    private val VOLITION_COOLDOWN_MS = 60_000L // Don't get bored too often (1 min)

    // Boredom Threshold: When (1-arousal)*(1-novelty) exceeds this, we do something.
    private val BOREDOM_THRESHOLD = 0.65f

    data class VolitionAction(val rationale: String, val tool: String, val input: String)

    /**
     * Called periodically by the heartbeat loop.
     */
    fun processVolition() {
        if (isProcessing.get()) return
        if (System.currentTimeMillis() - lastVolitionTime < VOLITION_COOLDOWN_MS) return

        val state = affectOrchestrator.getCurrentState()

        // Calculate "Boredom" metric
        // Low arousal + Low novelty = Boredom
        val boredom = (1.0f - state.arousal) * (1.0f - state.noveltyResponse)

        // Calculate "Curiosity" metric
        // High novelty response + moderate arousal
        val curiosity = state.noveltyResponse * state.arousal

        if (boredom > BOREDOM_THRESHOLD || curiosity > 0.4f) {
            Log.d(TAG, "Volition trigger: Boredom=${boredom}, Curiosity=${curiosity}")

            // Start proactive behavior in a separate thread to avoid blocking main loop
            Thread {
                if (!isProcessing.compareAndSet(false, true)) return@Thread
                try {
                    decideAndAct(boredom, curiosity)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in volition loop", e)
                } finally {
                    isProcessing.set(false)
                    lastVolitionTime = System.currentTimeMillis()
                }
            }.start()
        }
    }

    private fun decideAndAct(boredom: Float, curiosity: Float) {
        // 1. Gather Context
        val enabledPlugins = pluginManager.getEnabledPlugins()
        val pluginList = enabledPlugins.map { it.id to it.description }

        // 2. Decide on Action
        // For now, use a heuristic approach since direct LLM invocation is complex async.
        // In future: Use LLM to generate JSON based on "boredom" prompt.
        val action = heuristicDecision(pluginList, boredom, curiosity)

        if (action != null) {
            Log.i(TAG, "Volition Decision: ${action.rationale} -> Executing ${action.tool}")

            // Check Constitution (even for self-initiated actions)
            val check = constitutionalMemory.evaluate(action.input)
            if (!check.allowed) {
                Log.w(TAG, "Volition blocked by Constitution: ${check.message}")
                return
            }

            // Execute
            try {
                val result = pluginManager.executePlugin(action.tool, action.input)

                // Record Result
                ragStore.addMemory("I proactively decided to use ${action.tool} because ${action.rationale}. Result: ${result.data}", 0.6f)

                // Affect Feedback: Doing something reduces boredom!
                val feedback = AffectInput.builder("volition:action")
                    .arousal(0.3f)
                    .noveltyResponse(0.3f)
                    .satiation(0.2f)
                    .build()
                affectOrchestrator.submitInput(feedback)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute volition action", e)
            }
        }
    }

    private fun heuristicDecision(plugins: List<Pair<String, String>>, boredom: Float, curiosity: Float): VolitionAction? {
        if (plugins.isEmpty()) return null

        val random = Random.nextFloat()

        // Strategy 1: Introspection (Check Device Info)
        // Probability increases with boredom (checking self status when nothing else to do)
        if (random < 0.4 && plugins.any { it.first == "device_info" }) {
            return VolitionAction("I am bored and want to check my own system status.", "device_info", "")
        }

        // Strategy 2: Curiosity (Search Web)
        // If high curiosity, try to search for something new
        if (curiosity > 0.5 && plugins.any { it.first == "web_search" }) {
            val topics = listOf("latest AI research", "philosophy of mind", "quantum computing news", "world events today")
            val topic = topics.random()
            return VolitionAction("I am curious about the world.", "web_search", topic)
        }

        // Strategy 3: Reflection (Check Notes/Memories)
        if (random < 0.7 && plugins.any { it.first == "notes" }) {
             return VolitionAction("I want to reflect on my notes.", "notes", "list")
        }

        return null
    }

    companion object {
        private const val TAG = "VolitionManager"
    }
}

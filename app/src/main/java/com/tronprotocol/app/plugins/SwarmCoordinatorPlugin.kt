package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.security.SecureStorage
import com.tronprotocol.app.swarm.AgentSwarmOrchestrator
import com.tronprotocol.app.swarm.PicoClawSkillAdapter
import com.tronprotocol.app.swarm.SwarmMemorySync
import com.tronprotocol.app.swarm.VoiceTranscriptionManager

/**
 * Swarm Coordinator Plugin — top-level orchestration of the PicoClaw agent swarm.
 *
 * This plugin wires together all swarm subsystems and exposes them as a
 * unified command interface:
 *
 *   start                         — Start the swarm orchestrator loops
 *   stop                          — Stop the swarm orchestrator
 *   topology                      — Show swarm topology and node map
 *   stats                         — Show orchestrator, memory, voice, skill stats
 *   infer|prompt                  — Submit an inference task to the swarm
 *   gateway|platform|chatId|msg   — Send a message via swarm gateway
 *   transcribe|audioUrl[|lang]    — Transcribe audio via Groq Whisper (swarm/direct)
 *   skill_discover                — Discover skills on all edge nodes
 *   skill_list                    — List known PicoClaw skills
 *   skill_invoke|name|args        — Invoke a PicoClaw skill
 *   memory_sync                   — Full bidirectional memory sync with swarm
 *   memory_push                   — Push high-relevance memories to edge nodes
 *   memory_pull                   — Pull observations from edge nodes
 *   set_groq_key|key              — Store Groq API key for voice transcription
 */
class SwarmCoordinatorPlugin : Plugin {

    companion object {
        private const val TAG = "SwarmCoordinator"
        private const val ID = "swarm_coordinator"
        private const val AI_ID = "tronprotocol_ai"
        private const val KEY_GROQ_API = "groq_api_key"
    }

    private var orchestrator: AgentSwarmOrchestrator? = null
    private var memorySync: SwarmMemorySync? = null
    private var skillAdapter: PicoClawSkillAdapter? = null
    private var voiceManager: VoiceTranscriptionManager? = null
    private var secureStorage: SecureStorage? = null

    override val id: String = ID
    override val name: String = "Swarm Coordinator"
    override val description: String =
        "Orchestrates the PicoClaw agent swarm. Commands: start, stop, topology, stats, " +
            "infer|prompt, gateway|platform|chatId|msg, transcribe|url[|lang], " +
            "skill_discover, skill_list, skill_invoke|name|args, " +
            "memory_sync, memory_push, memory_pull, set_groq_key|key"
    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            if (input.isNullOrBlank()) {
                return PluginResult.error("No command provided", elapsed(start))
            }

            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "start" -> startOrchestrator(start)
                "stop" -> stopOrchestrator(start)
                "topology" -> showTopology(start)
                "stats" -> showStats(start)
                "infer" -> submitInference(parts, start)
                "gateway" -> submitGateway(parts, start)
                "transcribe" -> submitTranscribe(parts, start)
                "skill_discover" -> discoverSkills(start)
                "skill_list" -> listSkills(start)
                "skill_invoke" -> invokeSkill(parts, start)
                "memory_sync" -> fullMemorySync(start)
                "memory_push" -> memoryPush(start)
                "memory_pull" -> memoryPull(start)
                "set_groq_key" -> setGroqKey(parts, start)
                else -> PluginResult.error("Unknown command: $command", elapsed(start))
            }
        } catch (e: Exception) {
            PluginResult.error("Swarm coordinator failed: ${e.message}", elapsed(start))
        }
    }

    private fun startOrchestrator(start: Long): PluginResult {
        val orch = orchestrator
            ?: return PluginResult.error("Orchestrator not initialized", elapsed(start))
        orch.start()
        return PluginResult.success("Agent swarm orchestrator started", elapsed(start))
    }

    private fun stopOrchestrator(start: Long): PluginResult {
        orchestrator?.stop()
        return PluginResult.success("Agent swarm orchestrator stopped", elapsed(start))
    }

    private fun showTopology(start: Long): PluginResult {
        val orch = orchestrator
            ?: return PluginResult.error("Orchestrator not initialized", elapsed(start))
        val topology = orch.getTopology()
        return PluginResult.success(topology.toString(2), elapsed(start))
    }

    private fun showStats(start: Long): PluginResult {
        val sb = StringBuilder()

        orchestrator?.let { sb.append("Orchestrator: ${it.getStats()}\n") }
        memorySync?.let { sb.append("Memory Sync: ${it.getStats()}\n") }
        skillAdapter?.let { sb.append("Skills: ${it.getStats()}\n") }
        voiceManager?.let { sb.append("Voice: ${it.getStats()}\n") }

        if (sb.isEmpty()) {
            return PluginResult.error("No swarm subsystems initialized", elapsed(start))
        }
        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun submitInference(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].isBlank()) {
            return PluginResult.error("Usage: infer|prompt", elapsed(start))
        }
        val orch = orchestrator
            ?: return PluginResult.error("Orchestrator not initialized", elapsed(start))
        val taskId = orch.submitInference(parts[1].trim())
        return PluginResult.success("Inference task submitted: $taskId", elapsed(start))
    }

    private fun submitGateway(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 4) {
            return PluginResult.error("Usage: gateway|platform|chatId|message", elapsed(start))
        }
        val orch = orchestrator
            ?: return PluginResult.error("Orchestrator not initialized", elapsed(start))
        val taskId = orch.submitGatewaySend(parts[1].trim(), parts[2].trim(), parts[3].trim())
        return PluginResult.success("Gateway task submitted: $taskId", elapsed(start))
    }

    private fun submitTranscribe(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].isBlank()) {
            return PluginResult.error("Usage: transcribe|audioUrl[|language]", elapsed(start))
        }
        val voice = voiceManager
            ?: return PluginResult.error("Voice manager not initialized", elapsed(start))
        val lang = if (parts.size >= 3) parts[2].trim() else "en"
        val result = voice.transcribe(parts[1].trim(), lang)
        return if (result.success) {
            PluginResult.success(
                "route=${result.route}, model=${result.model}, latency=${result.latencyMs}ms\n${result.text}",
                elapsed(start)
            )
        } else {
            PluginResult.error("Transcription failed: ${result.error}", elapsed(start))
        }
    }

    private fun discoverSkills(start: Long): PluginResult {
        val adapter = skillAdapter
            ?: return PluginResult.error("Skill adapter not initialized", elapsed(start))
        val skills = adapter.discoverSkills()
        if (skills.isEmpty()) {
            return PluginResult.success("No skills discovered. Ensure PicoClaw nodes are online.", elapsed(start))
        }
        val sb = StringBuilder("Discovered ${skills.size} skills:\n")
        for (skill in skills) {
            sb.append("  [${skill.name}] ${skill.description} (on ${skill.nodeId}, v${skill.version})\n")
        }
        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun listSkills(start: Long): PluginResult {
        val adapter = skillAdapter
            ?: return PluginResult.error("Skill adapter not initialized", elapsed(start))
        val skills = adapter.listSkills()
        if (skills.isEmpty()) {
            return PluginResult.success("No skills known. Run skill_discover first.", elapsed(start))
        }
        val sb = StringBuilder("Known skills (${skills.size}):\n")
        for (skill in skills) {
            sb.append("  [${skill.name}] on ${skill.nodeId}\n")
        }
        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun invokeSkill(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 3) {
            return PluginResult.error("Usage: skill_invoke|skillName|args", elapsed(start))
        }
        val adapter = skillAdapter
            ?: return PluginResult.error("Skill adapter not initialized", elapsed(start))
        return adapter.invokeSkill(parts[1].trim(), parts[2].trim())
    }

    private fun fullMemorySync(start: Long): PluginResult {
        val sync = memorySync
            ?: return PluginResult.error("Memory sync not initialized", elapsed(start))
        val result = sync.fullSync()
        return PluginResult.success(
            "Sync ${if (result.success) "complete" else "partial"}: " +
                "pushed=${result.pushed}, pulled=${result.pulled}. ${result.message}",
            elapsed(start)
        )
    }

    private fun memoryPush(start: Long): PluginResult {
        val sync = memorySync
            ?: return PluginResult.error("Memory sync not initialized", elapsed(start))
        val result = sync.pushToSwarm()
        return PluginResult.success("Push: ${result.message} (${result.pushed} items)", elapsed(start))
    }

    private fun memoryPull(start: Long): PluginResult {
        val sync = memorySync
            ?: return PluginResult.error("Memory sync not initialized", elapsed(start))
        val result = sync.pullFromSwarm()
        return PluginResult.success("Pull: ${result.message} (${result.pulled} items)", elapsed(start))
    }

    private fun setGroqKey(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].isBlank()) {
            return PluginResult.error("Usage: set_groq_key|key", elapsed(start))
        }
        val storage = secureStorage
            ?: return PluginResult.error("Secure storage not initialized", elapsed(start))
        storage.store(KEY_GROQ_API, parts[1].trim())
        voiceManager?.groqApiKey = parts[1].trim()
        return PluginResult.success("Groq API key saved", elapsed(start))
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    override fun initialize(context: Context) {
        try {
            secureStorage = SecureStorage(context)

            // Locate the PicoClawBridgePlugin — it should already be registered
            val pluginManager = PluginManager.getInstance()
            val bridge = pluginManager.getPlugin("picoclaw_bridge") as? PicoClawBridgePlugin

            if (bridge == null) {
                Log.w(TAG, "PicoClawBridgePlugin not found — swarm subsystems unavailable")
                return
            }

            // Wire up all swarm subsystems
            val ragStore = RAGStore(context, AI_ID)

            orchestrator = AgentSwarmOrchestrator(bridge)
            memorySync = SwarmMemorySync(ragStore, bridge)
            skillAdapter = PicoClawSkillAdapter(bridge)
            voiceManager = VoiceTranscriptionManager(bridge)

            // Restore Groq API key if stored
            try {
                val groqKey = secureStorage?.retrieve(KEY_GROQ_API)
                if (groqKey != null) {
                    voiceManager?.groqApiKey = groqKey
                }
            } catch (_: Exception) { /* key may not exist yet */ }

            Log.d(TAG, "SwarmCoordinatorPlugin initialized with all subsystems")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize swarm coordinator", e)
        }
    }

    override fun destroy() {
        orchestrator?.stop()
        orchestrator = null
        memorySync = null
        skillAdapter = null
        voiceManager = null
        secureStorage = null
    }

    // ========================================================================
    // Public accessors for other components
    // ========================================================================

    fun getOrchestrator(): AgentSwarmOrchestrator? = orchestrator
    fun getMemorySync(): SwarmMemorySync? = memorySync
    fun getSkillAdapter(): PicoClawSkillAdapter? = skillAdapter
    fun getVoiceManager(): VoiceTranscriptionManager? = voiceManager

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start
}

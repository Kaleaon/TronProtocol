package com.tronprotocol.app.swarm

import android.util.Log
import com.tronprotocol.app.plugins.PicoClawBridgePlugin
import com.tronprotocol.app.plugins.PluginResult
import com.tronprotocol.app.swarm.SwarmNode.NodeCapability
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapter that allows TronProtocol to discover and invoke PicoClaw skills.
 *
 * PicoClaw skills are defined by SKILL.md files — lightweight markdown contracts
 * that describe what a skill does and how to invoke it. This adapter:
 *
 * 1. Queries online PicoClaw nodes for their available skills
 * 2. Maintains a local skill registry (skill name -> node mapping)
 * 3. Translates TronProtocol plugin-style invocations into PicoClaw skill calls
 * 4. Returns results in TronProtocol's PluginResult format
 *
 * Built-in PicoClaw skills include: github, skill-creator, summarize, tmux, weather.
 * Custom skills can be installed on nodes via `picoclaw skills install <repo>`.
 */
class PicoClawSkillAdapter(
    private val bridge: PicoClawBridgePlugin
) {
    companion object {
        private const val TAG = "PicoClawSkillAdapter"
    }

    data class RemoteSkill(
        val name: String,
        val description: String,
        val nodeId: String,
        val version: String = "unknown"
    )

    private val skillRegistry = ConcurrentHashMap<String, RemoteSkill>()

    /**
     * Discover all available skills from all online PicoClaw nodes.
     * Queries each node's /skills/list endpoint.
     */
    fun discoverSkills(): List<RemoteSkill> {
        val discovered = mutableListOf<RemoteSkill>()
        val nodes = bridge.getNodesByCapability(NodeCapability.SKILL_EXECUTION)

        for (node in nodes) {
            try {
                val msg = SwarmProtocol.SwarmMessage(
                    type = SwarmProtocol.MessageType.SKILL_LIST_REQUEST,
                    sourceNodeId = "tronprotocol_android",
                    targetNodeId = node.nodeId
                )

                val response = bridge.dispatchToNode(node, "/skills/list", msg.toJson().toString())
                val json = JSONObject(response)
                val skills = json.optJSONArray("skills") ?: JSONArray()

                for (i in 0 until skills.length()) {
                    val skillJson = skills.getJSONObject(i)
                    val skill = RemoteSkill(
                        name = skillJson.getString("name"),
                        description = skillJson.optString("description", ""),
                        nodeId = node.nodeId,
                        version = skillJson.optString("version", "unknown")
                    )
                    skillRegistry[skill.name] = skill
                    discovered.add(skill)
                }

                node.recordSuccess()
                Log.d(TAG, "Discovered ${skills.length()} skills on ${node.nodeId}")
            } catch (e: Exception) {
                node.recordFailure()
                Log.w(TAG, "Skill discovery failed on ${node.nodeId}: ${e.message}")
            }
        }

        return discovered
    }

    /**
     * List all known skills (from last discovery).
     */
    fun listSkills(): List<RemoteSkill> = skillRegistry.values.toList()

    /**
     * Invoke a PicoClaw skill by name.
     * Routes the call to the appropriate edge node.
     */
    fun invokeSkill(skillName: String, args: String): PluginResult {
        val start = System.currentTimeMillis()
        val skill = skillRegistry[skillName]
            ?: return PluginResult.error("Unknown skill: $skillName. Run discover first.", elapsed(start))

        val node = bridge.getNodes()[skill.nodeId]
        if (node == null || !node.isAlive) {
            return PluginResult.error(
                "Node '${skill.nodeId}' hosting skill '$skillName' is offline", elapsed(start)
            )
        }

        return try {
            val msg = SwarmProtocol.skillInvoke(
                "tronprotocol_android",
                node.nodeId,
                skillName,
                args
            )

            val response = bridge.dispatchToNode(node, "/skills/invoke", msg.toJson().toString())
            node.recordSuccess()
            node.recordDispatched()

            val result = parseSkillResponse(response)
            PluginResult.success(result, elapsed(start))
        } catch (e: Exception) {
            node.recordFailure()
            PluginResult.error("Skill execution failed: ${e.message}", elapsed(start))
        }
    }

    /**
     * Get info about a specific skill from its hosting node.
     */
    fun getSkillInfo(skillName: String): PluginResult {
        val start = System.currentTimeMillis()
        val skill = skillRegistry[skillName]
            ?: return PluginResult.error("Unknown skill: $skillName", elapsed(start))

        val info = buildString {
            append("Skill: ${skill.name}\n")
            append("Description: ${skill.description}\n")
            append("Version: ${skill.version}\n")
            append("Hosted on: ${skill.nodeId}\n")
        }
        return PluginResult.success(info.trimEnd(), elapsed(start))
    }

    private fun parseSkillResponse(response: String): String {
        return try {
            val json = JSONObject(response)
            json.optString("result",
                json.optString("output",
                    json.optJSONObject("payload")?.optString("result", response) ?: response
                )
            )
        } catch (_: Exception) {
            response
        }
    }

    fun getStats(): Map<String, Any> = mapOf(
        "known_skills" to skillRegistry.size,
        "skill_names" to skillRegistry.keys.toList(),
        "hosting_nodes" to skillRegistry.values.map { it.nodeId }.distinct()
    )

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start
}

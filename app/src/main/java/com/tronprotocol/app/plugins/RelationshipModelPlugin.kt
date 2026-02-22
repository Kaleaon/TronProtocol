package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Relationship model plugin that tracks relationships with users and entities,
 * recording interaction quality and context over time.
 *
 * Models relationships with quality scoring (1-5), interaction history,
 * and health assessment based on average interaction quality.
 *
 * Commands:
 *   add|entity_name|relationship_type      - Add a new relationship
 *   interact|entity_name|quality(1-5)|context - Record an interaction
 *   profile|entity_name                     - Show relationship profile
 *   list                                    - List all relationships
 *   history|entity_name|count               - Show recent interaction history
 *   update_type|entity_name|new_type        - Update relationship type
 */
class RelationshipModelPlugin : Plugin {

    companion object {
        private const val ID = "relationship_model"
        private const val PREFS = "relationship_model_plugin"
        private const val KEY_RELATIONSHIPS = "relationships_json"
        private const val KEY_INTERACTIONS = "interactions_json"
    }

    private lateinit var preferences: SharedPreferences

    override val id: String = ID

    override val name: String = "Relationship Model"

    override val description: String =
        "Relationship and interaction tracker. Commands: add|entity|type, interact|entity|quality(1-5)|context, profile|entity, list, history|entity|count, update_type|entity|type"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 4)
            val command = parts[0].trim().lowercase()

            when (command) {
                "add" -> addRelationship(parts, start)
                "interact" -> recordInteraction(parts, start)
                "profile" -> showProfile(parts, start)
                "list" -> listRelationships(start)
                "history" -> showHistory(parts, start)
                "update_type" -> updateType(parts, start)
                else -> PluginResult.error(
                    "Unknown command '$command'. Use: add|entity|type, interact|entity|quality|context, profile|entity, list, history|entity|count, update_type|entity|type",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            PluginResult.error("Relationship model failed: ${e.message}", elapsed(start))
        }
    }

    private fun addRelationship(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 3 || parts[1].trim().isEmpty() || parts[2].trim().isEmpty()) {
            return PluginResult.error("Usage: add|entity_name|relationship_type", elapsed(start))
        }
        val entityName = parts[1].trim()
        val relType = parts[2].trim()
        val relationships = getRelationships()

        if (relationships.has(entityName.lowercase())) {
            return PluginResult.error("Relationship with '$entityName' already exists. Use update_type to modify.", elapsed(start))
        }

        val relationship = JSONObject().apply {
            put("name", entityName)
            put("type", relType)
            put("created", System.currentTimeMillis())
        }
        relationships.put(entityName.lowercase(), relationship)
        saveRelationships(relationships)

        return PluginResult.success(
            "Added relationship: '$entityName' (type: $relType)",
            elapsed(start)
        )
    }

    private fun recordInteraction(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 4 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: interact|entity_name|quality(1-5)|context", elapsed(start))
        }
        val entityName = parts[1].trim()
        val quality = parts[2].trim().toIntOrNull()
            ?: return PluginResult.error("Quality must be an integer (1-5)", elapsed(start))
        val context = parts[3].trim()

        if (quality < 1 || quality > 5) {
            return PluginResult.error("Quality must be between 1 and 5", elapsed(start))
        }

        val relationships = getRelationships()
        if (!relationships.has(entityName.lowercase())) {
            return PluginResult.error("Relationship with '$entityName' not found. Use add first.", elapsed(start))
        }

        val interactions = getInteractions()
        val entityKey = entityName.lowercase()
        val entityInteractions = if (interactions.has(entityKey)) {
            interactions.getJSONArray(entityKey)
        } else {
            JSONArray().also { interactions.put(entityKey, it) }
        }

        val interaction = JSONObject().apply {
            put("quality", quality)
            put("context", context)
            put("timestamp", System.currentTimeMillis())
        }
        entityInteractions.put(interaction)
        saveInteractions(interactions)

        return PluginResult.success(
            "Recorded interaction with '$entityName': quality=$quality, context='$context' (${entityInteractions.length()} total)",
            elapsed(start)
        )
    }

    private fun showProfile(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: profile|entity_name", elapsed(start))
        }
        val entityName = parts[1].trim()
        val entityKey = entityName.lowercase()
        val relationships = getRelationships()

        if (!relationships.has(entityKey)) {
            return PluginResult.error("Relationship with '$entityName' not found.", elapsed(start))
        }

        val rel = relationships.getJSONObject(entityKey)
        val interactions = getInteractions()
        val entityInteractions = if (interactions.has(entityKey)) interactions.getJSONArray(entityKey) else JSONArray()

        val sb = StringBuilder("Relationship Profile: ${rel.getString("name")}\n")
        sb.append("  Type: ${rel.getString("type")}\n")
        sb.append("  Since: ${rel.getLong("created")}\n")
        sb.append("  Interactions: ${entityInteractions.length()}\n")

        if (entityInteractions.length() > 0) {
            var totalQuality = 0
            for (i in 0 until entityInteractions.length()) {
                totalQuality += entityInteractions.getJSONObject(i).getInt("quality")
            }
            val avgQuality = totalQuality.toDouble() / entityInteractions.length()
            val health = when {
                avgQuality >= 4.0 -> "excellent"
                avgQuality >= 3.0 -> "good"
                avgQuality >= 2.0 -> "fair"
                else -> "poor"
            }
            sb.append("  Average quality: ${"%.1f".format(avgQuality)}/5\n")
            sb.append("  Health: $health")
        } else {
            sb.append("  No interactions recorded yet.")
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun listRelationships(start: Long): PluginResult {
        val relationships = getRelationships()
        if (relationships.length() == 0) {
            return PluginResult.success("No relationships defined.", elapsed(start))
        }

        val interactions = getInteractions()
        val sb = StringBuilder("Relationships (${relationships.length()}):\n")
        val keys = relationships.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val rel = relationships.getJSONObject(key)
            val interactionCount = if (interactions.has(key)) interactions.getJSONArray(key).length() else 0
            sb.append("  - ${rel.getString("name")} (${rel.getString("type")}): $interactionCount interaction(s)\n")
        }
        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun showHistory(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: history|entity_name|count", elapsed(start))
        }
        val entityName = parts[1].trim()
        val count = if (parts.size >= 3) {
            parts[2].trim().toIntOrNull() ?: 5
        } else {
            5
        }

        val entityKey = entityName.lowercase()
        val relationships = getRelationships()

        if (!relationships.has(entityKey)) {
            return PluginResult.error("Relationship with '$entityName' not found.", elapsed(start))
        }

        val interactions = getInteractions()
        val entityInteractions = if (interactions.has(entityKey)) interactions.getJSONArray(entityKey) else JSONArray()

        if (entityInteractions.length() == 0) {
            return PluginResult.success("No interactions recorded with '$entityName'.", elapsed(start))
        }

        val displayCount = count.coerceAtMost(entityInteractions.length())
        val startIdx = entityInteractions.length() - displayCount

        val sb = StringBuilder("Interaction history with '$entityName' (last $displayCount):\n")
        for (i in startIdx until entityInteractions.length()) {
            val interaction = entityInteractions.getJSONObject(i)
            sb.append("  ${i + 1}. Quality: ${interaction.getInt("quality")}/5 - ${interaction.getString("context")}\n")
        }
        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun updateType(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 3 || parts[1].trim().isEmpty() || parts[2].trim().isEmpty()) {
            return PluginResult.error("Usage: update_type|entity_name|new_type", elapsed(start))
        }
        val entityName = parts[1].trim()
        val newType = parts[2].trim()
        val entityKey = entityName.lowercase()
        val relationships = getRelationships()

        if (!relationships.has(entityKey)) {
            return PluginResult.error("Relationship with '$entityName' not found.", elapsed(start))
        }

        val rel = relationships.getJSONObject(entityKey)
        val oldType = rel.getString("type")
        rel.put("type", newType)
        saveRelationships(relationships)

        return PluginResult.success(
            "Updated '$entityName' type from '$oldType' to '$newType'",
            elapsed(start)
        )
    }

    private fun getRelationships(): JSONObject {
        val raw = preferences.getString(KEY_RELATIONSHIPS, "{}")
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun saveRelationships(relationships: JSONObject) {
        preferences.edit().putString(KEY_RELATIONSHIPS, relationships.toString()).apply()
    }

    private fun getInteractions(): JSONObject {
        val raw = preferences.getString(KEY_INTERACTIONS, "{}")
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun saveInteractions(interactions: JSONObject) {
        preferences.edit().putString(KEY_INTERACTIONS, interactions.toString()).apply()
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun destroy() {
        // No-op
    }
}

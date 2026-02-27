package com.tronprotocol.app.persona

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Bidirectional converter for TavernAI v1/v2 character card JSON format.
 *
 * Ported from ToolNeuron's PersonaCardConverter. Supports:
 * - Export: [Persona] → TavernAI v2 JSON (spec = "chara_card_v2")
 * - Import: TavernAI v1/v2 JSON → [Persona] (auto-detects version)
 * - Extensions block preserves sampling profile and control vectors
 */
object PersonaCardConverter {

    private const val TAG = "PersonaCardConverter"
    private const val SPEC_V2 = "chara_card_v2"
    private const val SPEC_V1_DETECT = "name"
    private val gson = Gson()

    /**
     * Export a [Persona] to TavernAI v2 character card JSON.
     */
    fun exportToTavernV2(persona: Persona): String {
        val data = JsonObject().apply {
            addProperty("name", persona.name)
            addProperty("description", persona.description)
            addProperty("personality", persona.personality)
            addProperty("scenario", persona.scenario)
            addProperty("first_mes", persona.greeting)
            addProperty("mes_example", persona.exampleMessages)
            addProperty("system_prompt", persona.systemPrompt)
            addProperty("post_history_instructions", persona.postHistoryInstructions)
            addProperty("creator_notes", persona.creatorNotes)

            val altGreetings = com.google.gson.JsonArray()
            persona.alternateGreetings.forEach { altGreetings.add(it) }
            add("alternate_greetings", altGreetings)

            val tagsArr = com.google.gson.JsonArray()
            persona.tags.forEach { tagsArr.add(it) }
            add("tags", tagsArr)

            // Extensions block for TronProtocol-specific data
            val extensions = JsonObject().apply {
                addProperty("tronprotocol_sampling_profile", persona.samplingProfileJson)
                addProperty("tronprotocol_control_vectors", persona.controlVectorsJson)
                addProperty("tronprotocol_persona_id", persona.id)
            }
            add("extensions", extensions)
        }

        val root = JsonObject().apply {
            addProperty("spec", SPEC_V2)
            addProperty("spec_version", "2.0")
            add("data", data)
        }

        return gson.toJson(root)
    }

    /**
     * Import a TavernAI v1 or v2 character card from JSON.
     * Auto-detects the version from the JSON structure.
     *
     * @param json The character card JSON string
     * @param idOverride Optional ID override (generated if null)
     * @return Parsed [Persona], or null if parsing failed
     */
    fun importFromJson(json: String, idOverride: String? = null): Persona? {
        return try {
            val root = JsonParser.parseString(json).asJsonObject

            val spec = root.get("spec")?.asString
            if (spec == SPEC_V2) {
                importV2(root, idOverride)
            } else if (root.has(SPEC_V1_DETECT) && !root.has("data")) {
                importV1(root, idOverride)
            } else {
                Log.w(TAG, "Unknown card format, attempting v2 import")
                importV2(root, idOverride)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import character card: ${e.message}", e)
            null
        }
    }

    private fun importV2(root: JsonObject, idOverride: String?): Persona {
        val data = root.getAsJsonObject("data") ?: root

        val name = data.get("name")?.asString ?: "Unnamed"
        val id = idOverride ?: generateId(name)

        val altGreetings = data.getAsJsonArray("alternate_greetings")
            ?.map { it.asString }
            ?: emptyList()

        val tags = data.getAsJsonArray("tags")
            ?.map { it.asString }
            ?: emptyList()

        // Extract TronProtocol extensions
        val extensions = data.getAsJsonObject("extensions")
        val samplingJson = extensions?.get("tronprotocol_sampling_profile")?.asString ?: "{}"
        val controlJson = extensions?.get("tronprotocol_control_vectors")?.asString ?: "{}"

        return Persona(
            id = id,
            name = name,
            systemPrompt = data.get("system_prompt")?.asString ?: "",
            greeting = data.get("first_mes")?.asString ?: "",
            description = data.get("description")?.asString ?: "",
            personality = data.get("personality")?.asString ?: "",
            scenario = data.get("scenario")?.asString ?: "",
            exampleMessages = data.get("mes_example")?.asString ?: "",
            alternateGreetings = altGreetings,
            tags = tags,
            creatorNotes = data.get("creator_notes")?.asString ?: "",
            postHistoryInstructions = data.get("post_history_instructions")?.asString ?: "",
            samplingProfileJson = samplingJson,
            controlVectorsJson = controlJson
        )
    }

    private fun importV1(root: JsonObject, idOverride: String?): Persona {
        val name = root.get("name")?.asString ?: "Unnamed"
        val id = idOverride ?: generateId(name)

        return Persona(
            id = id,
            name = name,
            systemPrompt = root.get("system_prompt")?.asString
                ?: root.get("char_persona")?.asString ?: "",
            greeting = root.get("first_mes")?.asString
                ?: root.get("char_greeting")?.asString ?: "",
            description = root.get("description")?.asString ?: "",
            personality = root.get("personality")?.asString ?: "",
            scenario = root.get("scenario")?.asString
                ?: root.get("world_scenario")?.asString ?: "",
            exampleMessages = root.get("mes_example")?.asString
                ?: root.get("example_dialogue")?.asString ?: ""
        )
    }

    private fun generateId(name: String): String {
        val safeName = name.lowercase()
            .replace(Regex("[^a-z0-9]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return "imported_${safeName}_${System.currentTimeMillis() % 100000}"
    }
}

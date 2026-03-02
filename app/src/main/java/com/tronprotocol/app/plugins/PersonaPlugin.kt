package com.tronprotocol.app.plugins

import android.content.Context
import android.util.Log
import com.tronprotocol.app.persona.ControlVectorManager
import com.tronprotocol.app.persona.Persona
import com.tronprotocol.app.persona.PersonaCardConverter

/**
 * Plugin for managing personas and personality steering.
 *
 * Provides commands for listing, selecting, importing, exporting,
 * and customizing personas. Integrates with [ControlVectorManager]
 * for real-time personality axis adjustment.
 *
 * Commands:
 * - `list` — List all available personas
 * - `select <id>` — Select a persona by ID
 * - `import <json>` — Import a TavernAI v1/v2 character card
 * - `export <id>` — Export a persona as TavernAI v2 JSON
 * - `create <name> <system_prompt>` — Create a new persona
 * - `axes` — Show current personality axis values
 * - `set <axis> <value>` — Set a personality axis value
 * - `reset` — Reset personality to defaults
 */
class PersonaPlugin : Plugin {

    override val id: String = "persona"
    override val name: String = "Persona Manager"
    override val description: String = "Manage AI personas, personality axes, and character cards"
    override var isEnabled: Boolean = true

    private var context: Context? = null
    private val personas = mutableMapOf<String, Persona>()
    var activePersonaId: String? = null
        private set

    override fun requiredCapabilities(): Set<Capability> = setOf(Capability.MEMORY_READ, Capability.MEMORY_WRITE)

    override fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition.simple(
            "persona_list",
            "List all available AI personas",
        ),
        ToolDefinition.simple(
            "persona_select",
            "Select an AI persona by its ID",
            "persona_id"
        ),
        ToolDefinition.simple(
            "persona_axes",
            "Get current personality axis values (warmth, energy, humor, formality, verbosity, emotion)",
        )
    )

    override fun initialize(context: Context) {
        this.context = context
        // Load default personas
        Persona.DEFAULTS.forEach { personas[it.id] = it }
        activePersonaId = Persona.DEFAULT.id
        Log.d(TAG, "PersonaPlugin initialized with ${personas.size} personas")
    }

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()
        val parts = input.trim().split(Regex("\\s+"), limit = 3)
        val command = parts.firstOrNull()?.lowercase() ?: ""

        return try {
            when (command) {
                "list" -> handleList(startTime)
                "select" -> handleSelect(parts.getOrNull(1), startTime)
                "import" -> handleImport(parts.getOrNull(1) ?: "", startTime)
                "export" -> handleExport(parts.getOrNull(1), startTime)
                "create" -> handleCreate(parts.getOrNull(1), parts.getOrNull(2), startTime)
                "axes" -> handleAxes(startTime)
                "set" -> handleSetAxis(parts.getOrNull(1), parts.getOrNull(2), startTime)
                "reset" -> handleReset(startTime)
                else -> PluginResult.error("Unknown command: $command. Available: list, select, import, export, create, axes, set, reset",
                    System.currentTimeMillis() - startTime)
            }
        } catch (e: Exception) {
            PluginResult.error("Persona error: ${e.message}", System.currentTimeMillis() - startTime)
        }
    }

    override fun destroy() {
        context = null
        personas.clear()
    }

    // ---- Command handlers ----

    private fun handleList(startTime: Long): PluginResult {
        val list = personas.values.joinToString("\n") { persona ->
            val active = if (persona.id == activePersonaId) " [ACTIVE]" else ""
            "- ${persona.id}: ${persona.name}$active — ${persona.description.take(60)}"
        }
        return PluginResult.success("Available personas (${personas.size}):\n$list",
            System.currentTimeMillis() - startTime)
    }

    private fun handleSelect(id: String?, startTime: Long): PluginResult {
        if (id == null) return PluginResult.error("Usage: select <persona_id>")

        val persona = personas[id]
            ?: return PluginResult.error("Persona not found: $id. Use 'list' to see available personas.",
                System.currentTimeMillis() - startTime)

        activePersonaId = id
        return PluginResult.success("Selected persona: ${persona.name}\nGreeting: ${persona.greeting}",
            System.currentTimeMillis() - startTime)
    }

    private fun handleImport(json: String, startTime: Long): PluginResult {
        if (json.isBlank()) return PluginResult.error("Usage: import <character_card_json>")

        val persona = PersonaCardConverter.importFromJson(json)
            ?: return PluginResult.error("Failed to parse character card JSON",
                System.currentTimeMillis() - startTime)

        personas[persona.id] = persona
        return PluginResult.success("Imported persona: ${persona.name} (id=${persona.id})",
            System.currentTimeMillis() - startTime)
    }

    private fun handleExport(id: String?, startTime: Long): PluginResult {
        val personaId = id ?: activePersonaId
            ?: return PluginResult.error("No active persona to export")

        val persona = personas[personaId]
            ?: return PluginResult.error("Persona not found: $personaId")

        val json = PersonaCardConverter.exportToTavernV2(persona)
        return PluginResult.success(json, System.currentTimeMillis() - startTime)
    }

    private fun handleCreate(name: String?, systemPrompt: String?, startTime: Long): PluginResult {
        if (name == null || systemPrompt == null) {
            return PluginResult.error("Usage: create <name> <system_prompt>")
        }

        val id = "custom_${name.lowercase().replace(Regex("[^a-z0-9]"), "_")}_${System.currentTimeMillis() % 10000}"
        val persona = Persona(
            id = id,
            name = name,
            systemPrompt = systemPrompt,
            greeting = "Hello! I'm $name.",
            description = "Custom persona: $name"
        )

        personas[id] = persona
        return PluginResult.success("Created persona: $name (id=$id)",
            System.currentTimeMillis() - startTime)
    }

    private fun handleAxes(startTime: Long): PluginResult {
        val axes = ControlVectorManager.ALL_AXES.joinToString("\n") { axis ->
            "- $axis: [adjustable range: -1.0 to 1.0]"
        }
        return PluginResult.success("Personality axes:\n$axes\n\nUse 'set <axis> <value>' to adjust.",
            System.currentTimeMillis() - startTime)
    }

    private fun handleSetAxis(axis: String?, value: String?, startTime: Long): PluginResult {
        if (axis == null || value == null) {
            return PluginResult.error("Usage: set <axis> <value>\nAxes: ${ControlVectorManager.ALL_AXES.joinToString(", ")}")
        }

        if (axis !in ControlVectorManager.ALL_AXES) {
            return PluginResult.error("Unknown axis: $axis. Available: ${ControlVectorManager.ALL_AXES.joinToString(", ")}")
        }

        val floatValue = value.toFloatOrNull()
            ?: return PluginResult.error("Invalid value: $value. Must be a float between -1.0 and 1.0.")

        return PluginResult.success("Set axis $axis to ${floatValue.coerceIn(-1f, 1f)}",
            System.currentTimeMillis() - startTime)
    }

    private fun handleReset(startTime: Long): PluginResult {
        activePersonaId = Persona.DEFAULT.id
        return PluginResult.success("Reset to default persona: ${Persona.DEFAULT.name}",
            System.currentTimeMillis() - startTime)
    }

    /** Get the currently active persona, or null. */
    fun getActivePersona(): Persona? = personas[activePersonaId]

    /** Get all personas. */
    fun getAllPersonas(): List<Persona> = personas.values.toList()

    companion object {
        private const val TAG = "PersonaPlugin"
    }
}

package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Identity narrative plugin that maintains a coherent self-narrative of the AI's
 * identity and experiences.
 *
 * Stores traits, experiences, name, and purpose to construct a cohesive
 * self-description on demand.
 *
 * Commands:
 *   add_trait|trait             - Add a personality trait
 *   add_experience|experience  - Record an experience
 *   narrative                  - Generate a cohesive self-description
 *   traits                     - List all traits
 *   experiences                - List all experiences
 *   set_name|name              - Set the AI's name
 *   set_purpose|purpose        - Set the AI's purpose statement
 *   who_am_i                   - Return a brief identity summary
 */
class IdentityNarrativePlugin : Plugin {

    companion object {
        private const val ID = "identity_narrative"
        private const val PREFS = "identity_narrative_plugin"
        private const val KEY_TRAITS = "traits_json"
        private const val KEY_EXPERIENCES = "experiences_json"
        private const val KEY_NAME = "identity_name"
        private const val KEY_PURPOSE = "identity_purpose"
    }

    private lateinit var preferences: SharedPreferences

    override val id: String = ID

    override val name: String = "Identity Narrative"

    override val description: String =
        "AI identity and self-narrative manager. Commands: add_trait|trait, add_experience|experience, narrative, traits, experiences, set_name|name, set_purpose|purpose, who_am_i"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = input.split("\\|".toRegex(), 2)
            val command = parts[0].trim().lowercase()

            when (command) {
                "add_trait" -> addTrait(parts, start)
                "add_experience" -> addExperience(parts, start)
                "narrative" -> generateNarrative(start)
                "traits" -> listTraits(start)
                "experiences" -> listExperiences(start)
                "set_name" -> setName(parts, start)
                "set_purpose" -> setPurpose(parts, start)
                "who_am_i" -> whoAmI(start)
                else -> PluginResult.error(
                    "Unknown command '$command'. Use: add_trait|trait, add_experience|experience, narrative, traits, experiences, set_name|name, set_purpose|purpose, who_am_i",
                    elapsed(start)
                )
            }
        } catch (e: Exception) {
            PluginResult.error("Identity narrative failed: ${e.message}", elapsed(start))
        }
    }

    private fun addTrait(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: add_trait|trait", elapsed(start))
        }
        val trait = parts[1].trim()
        val traits = getTraits()

        // Check for duplicate
        for (i in 0 until traits.length()) {
            if (traits.getString(i).equals(trait, ignoreCase = true)) {
                return PluginResult.error("Trait '$trait' already exists.", elapsed(start))
            }
        }

        traits.put(trait)
        saveTraits(traits)
        return PluginResult.success(
            "Added trait: '$trait' (${traits.length()} total traits)",
            elapsed(start)
        )
    }

    private fun addExperience(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: add_experience|experience", elapsed(start))
        }
        val experience = parts[1].trim()
        val experiences = getExperiences()

        val entry = JSONObject().apply {
            put("text", experience)
            put("timestamp", System.currentTimeMillis())
        }
        experiences.put(entry)
        saveExperiences(experiences)

        return PluginResult.success(
            "Recorded experience: '$experience' (${experiences.length()} total)",
            elapsed(start)
        )
    }

    private fun generateNarrative(start: Long): PluginResult {
        val identityName = preferences.getString(KEY_NAME, null)
        val purpose = preferences.getString(KEY_PURPOSE, null)
        val traits = getTraits()
        val experiences = getExperiences()

        val sb = StringBuilder()

        // Opening with name
        if (identityName != null) {
            sb.append("I am $identityName. ")
        } else {
            sb.append("I am an AI assistant. ")
        }

        // Purpose
        if (purpose != null) {
            sb.append("My purpose is $purpose. ")
        }

        // Traits
        if (traits.length() > 0) {
            val traitList = mutableListOf<String>()
            for (i in 0 until traits.length()) {
                traitList.add(traits.getString(i))
            }
            sb.append("\n\nCore traits: ")
            if (traitList.size == 1) {
                sb.append("I am ${traitList[0]}. ")
            } else {
                val last = traitList.removeAt(traitList.size - 1)
                sb.append("I am ${traitList.joinToString(", ")}, and $last. ")
            }
        }

        // Experiences
        if (experiences.length() > 0) {
            sb.append("\n\nKey experiences:\n")
            val count = experiences.length().coerceAtMost(10)
            val startIdx = experiences.length() - count
            for (i in startIdx until experiences.length()) {
                val exp = experiences.getJSONObject(i)
                sb.append("  - ${exp.getString("text")}\n")
            }
            if (experiences.length() > 10) {
                sb.append("  (and ${experiences.length() - 10} earlier experiences)")
            }
        }

        if (traits.length() == 0 && experiences.length() == 0 && identityName == null && purpose == null) {
            return PluginResult.success(
                "No identity data recorded yet. Use add_trait, add_experience, set_name, or set_purpose to build an identity.",
                elapsed(start)
            )
        }

        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun listTraits(start: Long): PluginResult {
        val traits = getTraits()
        if (traits.length() == 0) {
            return PluginResult.success("No traits defined.", elapsed(start))
        }
        val sb = StringBuilder("Traits (${traits.length()}):\n")
        for (i in 0 until traits.length()) {
            sb.append("  ${i + 1}. ${traits.getString(i)}\n")
        }
        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun listExperiences(start: Long): PluginResult {
        val experiences = getExperiences()
        if (experiences.length() == 0) {
            return PluginResult.success("No experiences recorded.", elapsed(start))
        }
        val sb = StringBuilder("Experiences (${experiences.length()}):\n")
        for (i in 0 until experiences.length()) {
            val exp = experiences.getJSONObject(i)
            sb.append("  ${i + 1}. ${exp.getString("text")}\n")
        }
        return PluginResult.success(sb.toString().trimEnd(), elapsed(start))
    }

    private fun setName(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: set_name|name", elapsed(start))
        }
        val identityName = parts[1].trim()
        preferences.edit().putString(KEY_NAME, identityName).apply()
        return PluginResult.success("Identity name set to '$identityName'", elapsed(start))
    }

    private fun setPurpose(parts: List<String>, start: Long): PluginResult {
        if (parts.size < 2 || parts[1].trim().isEmpty()) {
            return PluginResult.error("Usage: set_purpose|purpose", elapsed(start))
        }
        val purpose = parts[1].trim()
        preferences.edit().putString(KEY_PURPOSE, purpose).apply()
        return PluginResult.success("Purpose set to '$purpose'", elapsed(start))
    }

    private fun whoAmI(start: Long): PluginResult {
        val identityName = preferences.getString(KEY_NAME, "an unnamed AI")
        val purpose = preferences.getString(KEY_PURPOSE, null)
        val traits = getTraits()

        val sb = StringBuilder("I am $identityName")
        if (purpose != null) {
            sb.append(", and my purpose is $purpose")
        }
        sb.append(".")

        if (traits.length() > 0) {
            val traitList = mutableListOf<String>()
            for (i in 0 until traits.length().coerceAtMost(3)) {
                traitList.add(traits.getString(i))
            }
            sb.append(" I am ${traitList.joinToString(", ")}.")
            if (traits.length() > 3) {
                sb.append(" (and ${traits.length() - 3} more traits)")
            }
        }

        return PluginResult.success(sb.toString(), elapsed(start))
    }

    private fun getTraits(): JSONArray {
        val raw = preferences.getString(KEY_TRAITS, "[]")
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun saveTraits(traits: JSONArray) {
        preferences.edit().putString(KEY_TRAITS, traits.toString()).apply()
    }

    private fun getExperiences(): JSONArray {
        val raw = preferences.getString(KEY_EXPERIENCES, "[]")
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun saveExperiences(experiences: JSONArray) {
        preferences.edit().putString(KEY_EXPERIENCES, experiences.toString()).apply()
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    override fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun destroy() {
        // No-op
    }
}

package com.tronprotocol.app.plugins

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Definition of a tool that can be called by the LLM via grammar-constrained generation.
 *
 * Follows the OpenAI function calling format for compatibility with llama.cpp's
 * grammar-based tool calling. The [parametersSchema] is a JSON Schema object
 * describing the tool's expected input.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersSchema: JsonObject = JsonObject()
) {

    /**
     * Convert to OpenAI-format tool JSON for native grammar compilation.
     */
    fun toOpenAiJson(): JsonObject {
        return JsonObject().apply {
            addProperty("type", "function")
            add("function", JsonObject().apply {
                addProperty("name", name)
                addProperty("description", description)
                add("parameters", parametersSchema)
            })
        }
    }

    companion object {
        private val gson = Gson()

        /**
         * Build a simple tool definition with named string parameters.
         */
        fun simple(name: String, description: String, vararg params: String): ToolDefinition {
            val schema = JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject()
                val required = com.google.gson.JsonArray()
                params.forEach { param ->
                    props.add(param, JsonObject().apply {
                        addProperty("type", "string")
                    })
                    required.add(param)
                }
                add("properties", props)
                add("required", required)
            }
            return ToolDefinition(name, description, schema)
        }

        /**
         * Convert a list of tool definitions to the OpenAI tools JSON array.
         */
        fun toToolsJson(tools: List<ToolDefinition>): String {
            val array = com.google.gson.JsonArray()
            tools.forEach { array.add(it.toOpenAiJson()) }
            return gson.toJson(array)
        }
    }
}

/**
 * Result of a tool call dispatched to a plugin.
 */
data class ToolCallResult(
    val toolName: String,
    val success: Boolean,
    val resultJson: String,
    val error: String? = null
)

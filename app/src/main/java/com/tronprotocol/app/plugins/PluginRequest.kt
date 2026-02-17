package com.tronprotocol.app.plugins

/**
 * Standardized plugin request envelope.
 */
data class PluginRequest(
    val command: String,
    val args: Map<String, Any?> = emptyMap(),
    val rawInput: String = command,
    val metadata: Map<String, String> = emptyMap()
) {
    companion object {
        @JvmStatic
        fun fromLegacyInput(input: String): PluginRequest {
            val parts = input.split("|")
            val command = parts.firstOrNull()?.trim().orEmpty()
            val args = parts.drop(1).mapIndexed { index, value -> "arg$index" to value }.toMap()
            return PluginRequest(command = command, args = args, rawInput = input)
        }
    }
}

package com.tronprotocol.app.plugins

/**
 * Standardized plugin response envelope.
 */
data class PluginResponse(
    val success: Boolean,
    val message: String?,
    val executionTimeMs: Long,
    val error: String? = null
) {
    fun toPluginResult(): PluginResult {
        return if (success) {
            PluginResult.success(message ?: "", executionTimeMs)
        } else {
            PluginResult.error(error ?: "Unknown plugin error", executionTimeMs)
        }
    }

    companion object {
        @JvmStatic
        fun success(message: String, executionTimeMs: Long): PluginResponse {
            return PluginResponse(success = true, message = message, executionTimeMs = executionTimeMs)
        }

        @JvmStatic
        fun error(error: String, executionTimeMs: Long): PluginResponse {
            return PluginResponse(success = false, message = null, executionTimeMs = executionTimeMs, error = error)
        }

        @JvmStatic
        fun fromResult(result: PluginResult): PluginResponse {
            return if (result.isSuccess) {
                success(result.data ?: "", result.executionTimeMs)
            } else {
                error(result.errorMessage ?: "Unknown plugin error", result.executionTimeMs)
            }
        }
    }
}


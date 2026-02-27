package com.tronprotocol.app.plugins

/**
 * Represents the result of a plugin execution
 *
 * Inspired by ToolNeuron's plugin execution metrics
 */
class PluginResult(
    val isSuccess: Boolean,
    val data: String?,
    val executionTimeMs: Long
) {
    var errorMessage: String? = null
        private set

    override fun toString(): String {
        return "PluginResult{success=$isSuccess, executionTimeMs=$executionTimeMs" +
                (if (isSuccess) ", data='$data'" else ", error='$errorMessage'") +
                "}"
    }

    companion object {
        @JvmStatic
        fun success(data: String, executionTimeMs: Long = 0L): PluginResult {
            return PluginResult(true, data, executionTimeMs)
        }

        @JvmStatic
        fun error(errorMessage: String?, executionTimeMs: Long = 0L): PluginResult {
            return PluginResult(false, null, executionTimeMs).also {
                it.errorMessage = errorMessage
            }
        }
    }
}

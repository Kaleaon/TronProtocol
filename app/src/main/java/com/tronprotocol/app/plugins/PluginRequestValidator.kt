package com.tronprotocol.app.plugins

import java.net.URI

/**
 * Shared request schema validation helpers for plugins.
 */
object PluginRequestValidator {
    private const val DEFAULT_MAX_STRING_SIZE = 16 * 1024

    fun requireFields(request: PluginRequest, vararg fields: String): String? {
        for (field in fields) {
            val value = request.args[field]
            if (value == null || (value is String && value.isBlank())) {
                return "Missing required field: $field"
            }
        }
        return null
    }

    fun enforceSizeLimit(value: String, maxSize: Int = DEFAULT_MAX_STRING_SIZE, field: String = "payload"): String? {
        if (value.length > maxSize) {
            return "$field exceeds maximum size of $maxSize"
        }
        return null
    }

    fun requireAllowedUri(uriValue: String, allowedSchemes: Set<String>, allowedHosts: Set<String> = emptySet()): String? {
        return try {
            val uri = URI(uriValue)
            val scheme = uri.scheme?.lowercase()
            if (scheme.isNullOrBlank() || !allowedSchemes.contains(scheme)) {
                return "URI scheme not allowed"
            }
            if (allowedHosts.isNotEmpty()) {
                val host = uri.host?.lowercase()
                if (host.isNullOrBlank() || !allowedHosts.contains(host)) {
                    return "URI host not allowlisted"
                }
            }
            null
        } catch (_: Exception) {
            "Invalid URI"
        }
    }

    fun requireString(request: PluginRequest, field: String): Pair<String?, String?> {
        val value = request.args[field] ?: return null to "Missing required field: $field"
        return if (value is String) {
            value to null
        } else {
            null to "Field '$field' must be a string"
        }
    }

    fun requireInt(request: PluginRequest, field: String): Pair<Int?, String?> {
        val value = request.args[field] ?: return null to "Missing required field: $field"
        return when (value) {
            is Int -> value to null
            is Number -> value.toInt() to null
            is String -> value.toIntOrNull()?.let { it to null } ?: (null to "Field '$field' must be an integer")
            else -> null to "Field '$field' must be an integer"
        }
    }
}


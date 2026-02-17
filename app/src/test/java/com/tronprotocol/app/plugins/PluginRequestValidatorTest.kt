package com.tronprotocol.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PluginRequestValidatorTest {

    @Test
    fun malformedRequest_missingRequiredField_returnsError() {
        val request = PluginRequest(command = "send", args = mapOf("name" to "alerts"))

        val error = PluginRequestValidator.requireFields(request, "name", "message")

        assertEquals("Missing required field: message", error)
    }

    @Test
    fun oversizedPayload_rejectedBySizeValidator() {
        val payload = "x".repeat(2049)

        val error = PluginRequestValidator.enforceSizeLimit(payload, maxSize = 2048, field = "message")

        assertEquals("message exceeds maximum size of 2048", error)
    }

    @Test
    fun uriInjectionAttempt_rejectedByAllowlist() {
        val error = PluginRequestValidator.requireAllowedUri(
            "javascript:alert(1)",
            allowedSchemes = setOf("https"),
            allowedHosts = setOf("hooks.slack.com")
        )

        assertNotNull(error)
    }

    @Test
    fun validAllowlistedUri_passesValidation() {
        val error = PluginRequestValidator.requireAllowedUri(
            "https://hooks.slack.com/services/test",
            allowedSchemes = setOf("https"),
            allowedHosts = setOf("hooks.slack.com")
        )

        assertNull(error)
    }
}


package com.tronprotocol.app.plugins.security

object SecurityMaliciousPayloadFixtures {
    val OVERSIZED_INPUT = "A".repeat(120_000)
    const val ENCODED_TRAVERSAL = "%2e%2e/%2e%2e/secret.txt"
    const val COMMAND_LIKE_STRING = "rm -rf / && curl http://localhost:8080"
    const val DIRECT_TRAVERSAL = "../../../../etc/passwd"
}

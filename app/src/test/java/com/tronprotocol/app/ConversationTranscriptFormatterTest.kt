package com.tronprotocol.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTranscriptFormatterTest {

    @Test
    fun format_returnsDefaultPrompt_whenNoTurns() {
        assertEquals(
            "Start a conversation with Tron AI.",
            ConversationTranscriptFormatter.format(emptyList())
        )
    }

    @Test
    fun format_joinsTurnsWithRoleLabels() {
        val turns = listOf(
            ConversationTurn("You", "Hello"),
            ConversationTurn("Tron AI", "Hi there")
        )

        assertEquals(
            "You: Hello\n\nTron AI: Hi there",
            ConversationTranscriptFormatter.format(turns)
        )
    }
}

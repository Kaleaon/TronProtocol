package com.tronprotocol.app

object ConversationTranscriptFormatter {
    fun format(turns: List<ConversationTurn>): String {
        if (turns.isEmpty()) {
            return "Start a conversation with Tron AI."
        }
        return turns.joinToString("\n\n") { turn ->
            "${turn.role}: ${turn.message}"
        }
    }
}

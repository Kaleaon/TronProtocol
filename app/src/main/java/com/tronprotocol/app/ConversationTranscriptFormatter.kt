package com.tronprotocol.app

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

object ConversationTranscriptFormatter {

    private val USER_COLOR = 0xFF64B5F6.toInt()   // Material blue 300
    private val AI_COLOR = 0xFFCE93D8.toInt()      // Material purple 200
    private const val SEPARATOR_COLOR = 0x33FFFFFF  // Subtle white separator

    /**
     * Returns a rich SpannableString transcript with colored role names,
     * bold labels, and visual spacing between turns.
     */
    fun formatSpannable(turns: List<ConversationTurn>): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        turns.forEachIndexed { index, turn ->
            val roleColor = if (turn.role == "You") USER_COLOR else AI_COLOR

            // Role label (bold + colored)
            val roleStart = builder.length
            builder.append(turn.role)
            val roleEnd = builder.length
            builder.setSpan(StyleSpan(Typeface.BOLD), roleStart, roleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(ForegroundColorSpan(roleColor), roleStart, roleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(RelativeSizeSpan(0.85f), roleStart, roleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            builder.append("\n")

            // Message body
            builder.append(turn.message)

            // Separator between turns
            if (index < turns.size - 1) {
                builder.append("\n")
                val sepStart = builder.length
                builder.append("  \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500")
                val sepEnd = builder.length
                builder.setSpan(ForegroundColorSpan(SEPARATOR_COLOR), sepStart, sepEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(RelativeSizeSpan(0.7f), sepStart, sepEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.append("\n")
            }
        }
        return builder
    }

    /**
     * Plain-text fallback for non-UI contexts (tests, logging).
     */
    fun format(turns: List<ConversationTurn>): String {
        if (turns.isEmpty()) {
            return "Start a conversation with Tron AI."
        }
        return turns.joinToString("\n\n") { turn ->
            "${turn.role}: ${turn.message}"
        }
    }
}

package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Send Policy — outbound message rate limiting and mode control.
 *
 * Inspired by OpenClaw's per-session send policies that control when/how
 * the AI can proactively send messages. TronProtocol previously had no policy
 * governing outbound sends — any plugin with network access could send freely.
 *
 * Modes:
 * - DISABLED: No outbound messages allowed
 * - REPLY_ONLY: Only respond to inbound messages (no proactive sends)
 * - RATE_LIMITED: Allow proactive sends with configurable rate limits
 * - UNRESTRICTED: No outbound restrictions (default for backward compat)
 *
 * Rate limits (in RATE_LIMITED mode):
 * - Max 5 proactive messages per hour per chat
 * - Max 20 proactive messages per hour total
 * - Min 60-second gap between proactive messages to same chat
 * - Replies (responding to user-initiated messages) are unlimited
 */
class SendPolicy(private val context: Context) {

    /** Operating modes for outbound message control. */
    enum class SendMode {
        /** No outbound messages allowed at all. */
        DISABLED,
        /** Only respond to inbound messages — no proactive sends. */
        REPLY_ONLY,
        /** Allow proactive sends with rate limits. */
        RATE_LIMITED,
        /** No outbound restrictions. */
        UNRESTRICTED
    }

    /** Result of a send evaluation. */
    data class SendDecision(
        val allowed: Boolean,
        val reason: String,
        val cooldownRemainingMs: Long = 0
    )

    // Per-chat send history: chatId -> list of send timestamps
    private val perChatHistory = ConcurrentHashMap<String, MutableList<Long>>()
    // Global send history (all chats)
    private val globalHistory = mutableListOf<Long>()
    // Per-chat last send time
    private val lastSendTime = ConcurrentHashMap<String, Long>()

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Stats
    private var totalAllowed = 0L
    private var totalDenied = 0L

    /** Set the active send mode. */
    fun setMode(mode: SendMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
        Log.d(TAG, "Send policy mode set to ${mode.name}")
    }

    /** Get the current send mode. Defaults to UNRESTRICTED for backward compat. */
    fun getMode(): SendMode {
        val stored = preferences.getString(KEY_MODE, SendMode.UNRESTRICTED.name)
        return try {
            SendMode.valueOf(stored ?: SendMode.UNRESTRICTED.name)
        } catch (e: IllegalArgumentException) {
            SendMode.UNRESTRICTED
        }
    }

    /**
     * Evaluate whether an outbound message is allowed.
     *
     * @param pluginId The plugin attempting to send
     * @param targetChatId The target chat/channel
     * @param isReply true if this is a direct response to a user-initiated message
     * @return SendDecision with the outcome and reason
     */
    fun evaluateSend(pluginId: String, targetChatId: String, isReply: Boolean): SendDecision {
        return when (getMode()) {
            SendMode.DISABLED -> {
                totalDenied++
                SendDecision(
                    allowed = false,
                    reason = "Send policy is DISABLED — all outbound messages blocked"
                )
            }

            SendMode.REPLY_ONLY -> {
                if (isReply) {
                    totalAllowed++
                    SendDecision(allowed = true, reason = "Reply allowed in REPLY_ONLY mode")
                } else {
                    totalDenied++
                    SendDecision(
                        allowed = false,
                        reason = "Proactive sends blocked in REPLY_ONLY mode"
                    )
                }
            }

            SendMode.RATE_LIMITED -> evaluateRateLimited(pluginId, targetChatId, isReply)

            SendMode.UNRESTRICTED -> {
                totalAllowed++
                SendDecision(allowed = true, reason = "Send policy is UNRESTRICTED")
            }
        }
    }

    /**
     * Record a successful send for rate limiting tracking.
     */
    fun recordSend(pluginId: String, targetChatId: String) {
        val now = System.currentTimeMillis()

        // Per-chat history
        perChatHistory.getOrPut(targetChatId) { mutableListOf() }.add(now)
        lastSendTime[targetChatId] = now

        // Global history
        synchronized(globalHistory) {
            globalHistory.add(now)
        }

        // Prune old entries (older than 1 hour)
        pruneOldEntries()
    }

    /** Get send policy statistics. */
    fun getStats(): Map<String, Any> = mapOf(
        "mode" to getMode().name,
        "total_allowed" to totalAllowed,
        "total_denied" to totalDenied,
        "active_chats" to perChatHistory.size,
        "global_sends_last_hour" to countGlobalSendsInWindow(),
        "per_chat_sends" to perChatHistory.mapValues { it.value.size }
    )

    // -- Internal --

    private fun evaluateRateLimited(
        pluginId: String,
        targetChatId: String,
        isReply: Boolean
    ): SendDecision {
        // Replies are unlimited in rate-limited mode
        if (isReply) {
            totalAllowed++
            return SendDecision(allowed = true, reason = "Reply allowed (unlimited in RATE_LIMITED mode)")
        }

        val now = System.currentTimeMillis()
        val windowStart = now - RATE_WINDOW_MS

        // Check per-chat cooldown
        val lastSend = lastSendTime[targetChatId] ?: 0L
        val cooldownRemaining = (lastSend + MIN_GAP_MS) - now
        if (cooldownRemaining > 0) {
            totalDenied++
            return SendDecision(
                allowed = false,
                reason = "Cooldown: ${cooldownRemaining}ms remaining before next proactive message to $targetChatId",
                cooldownRemainingMs = cooldownRemaining
            )
        }

        // Check per-chat rate limit
        val chatSends = perChatHistory[targetChatId]?.count { it > windowStart } ?: 0
        if (chatSends >= MAX_PER_CHAT_PER_HOUR) {
            totalDenied++
            return SendDecision(
                allowed = false,
                reason = "Per-chat rate limit: $chatSends/$MAX_PER_CHAT_PER_HOUR proactive messages to $targetChatId in the last hour"
            )
        }

        // Check global rate limit
        val globalSends = countGlobalSendsInWindow()
        if (globalSends >= MAX_GLOBAL_PER_HOUR) {
            totalDenied++
            return SendDecision(
                allowed = false,
                reason = "Global rate limit: $globalSends/$MAX_GLOBAL_PER_HOUR proactive messages in the last hour"
            )
        }

        totalAllowed++
        return SendDecision(
            allowed = true,
            reason = "Rate limited: allowed (chat: $chatSends/$MAX_PER_CHAT_PER_HOUR, global: $globalSends/$MAX_GLOBAL_PER_HOUR)"
        )
    }

    private fun countGlobalSendsInWindow(): Int {
        val windowStart = System.currentTimeMillis() - RATE_WINDOW_MS
        synchronized(globalHistory) {
            return globalHistory.count { it > windowStart }
        }
    }

    private fun pruneOldEntries() {
        val windowStart = System.currentTimeMillis() - RATE_WINDOW_MS

        // Prune per-chat histories
        for ((_, history) in perChatHistory) {
            history.removeAll { it < windowStart }
        }
        // Remove empty chat entries
        perChatHistory.entries.removeAll { it.value.isEmpty() }

        // Prune global history
        synchronized(globalHistory) {
            globalHistory.removeAll { it < windowStart }
        }
    }

    companion object {
        private const val TAG = "SendPolicy"
        private const val PREFS_NAME = "send_policy"
        private const val KEY_MODE = "send_mode"

        const val MAX_PER_CHAT_PER_HOUR = 5
        const val MAX_GLOBAL_PER_HOUR = 20
        const val MIN_GAP_MS = 60_000L          // 60 seconds between proactive sends
        const val RATE_WINDOW_MS = 3_600_000L    // 1 hour window
    }
}

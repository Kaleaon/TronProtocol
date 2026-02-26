package com.tronprotocol.app.plugins

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * DM Pairing Policy — approval-code-based access control for unknown Telegram senders.
 *
 * Inspired by OpenClaw's dm-policy-shared.ts:
 * - 3 modes: DISABLED (block all unknowns), OPEN (allow all), PAIRING (require code)
 * - In PAIRING mode, unknown senders receive a pairing code they must present
 * - The device operator approves/denies pairing requests from a queue
 * - Approved senders are added to the Telegram bridge allowlist
 * - Codes expire after 1 hour; max 3 pending requests at any time
 *
 * This replaces the simple allow/deny chat ID model with an interactive pairing flow.
 */
class DmPairingPolicy(private val context: Context) {

    /** Operating modes for DM access control. */
    enum class PolicyMode {
        /** Block all messages from unknown senders (no pairing possible). */
        DISABLED,
        /** Allow all messages from any sender (no pairing required). */
        OPEN,
        /** Unknown senders receive a pairing code; operator must approve. */
        PAIRING
    }

    /** Access decision for an incoming message. */
    data class AccessDecision(
        val allowed: Boolean,
        val reason: String,
        val pairingCode: String? = null
    )

    /** A pending pairing request from an unknown sender. */
    data class PairingRequest(
        val code: String,
        val chatId: String,
        val username: String,
        val requestedAt: Long,
        val expiresAt: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt

        fun toJson(): JSONObject = JSONObject().apply {
            put("code", code)
            put("chatId", chatId)
            put("username", username)
            put("requestedAt", requestedAt)
            put("expiresAt", expiresAt)
        }

        companion object {
            fun fromJson(obj: JSONObject): PairingRequest = PairingRequest(
                code = obj.getString("code"),
                chatId = obj.getString("chatId"),
                username = obj.optString("username", "unknown"),
                requestedAt = obj.getLong("requestedAt"),
                expiresAt = obj.getLong("expiresAt")
            )
        }
    }

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val pendingRequests = mutableListOf<PairingRequest>()

    init {
        loadPendingRequests()
    }

    /** Set the active policy mode. */
    fun setMode(mode: PolicyMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
        Log.d(TAG, "DM pairing mode set to ${mode.name}")
    }

    /** Get the current policy mode. Defaults to PAIRING. */
    fun getMode(): PolicyMode {
        val stored = preferences.getString(KEY_MODE, PolicyMode.PAIRING.name)
        return try {
            PolicyMode.valueOf(stored ?: PolicyMode.PAIRING.name)
        } catch (e: IllegalArgumentException) {
            PolicyMode.PAIRING
        }
    }

    /**
     * Evaluate an incoming message from a Telegram chat.
     *
     * @param chatId The Telegram chat ID
     * @param username Display name of the sender
     * @return AccessDecision indicating whether the message should be processed
     */
    fun evaluateIncoming(chatId: String, username: String): AccessDecision {
        return when (getMode()) {
            PolicyMode.DISABLED -> AccessDecision(
                allowed = false,
                reason = "DM policy is DISABLED — all unknown senders blocked"
            )

            PolicyMode.OPEN -> AccessDecision(
                allowed = true,
                reason = "DM policy is OPEN — all senders allowed"
            )

            PolicyMode.PAIRING -> {
                // Check if there's already a pending request for this chat
                pruneExpired()
                val existing = pendingRequests.find { it.chatId == chatId && !it.isExpired() }
                if (existing != null) {
                    return AccessDecision(
                        allowed = false,
                        reason = "Pairing request already pending for chat $chatId (code: ${existing.code})",
                        pairingCode = existing.code
                    )
                }

                // Generate a new pairing request (if under limit)
                if (pendingRequests.size >= MAX_PENDING_REQUESTS) {
                    return AccessDecision(
                        allowed = false,
                        reason = "Maximum pending pairing requests reached ($MAX_PENDING_REQUESTS)"
                    )
                }

                val request = generatePairingCode(chatId, username)
                AccessDecision(
                    allowed = false,
                    reason = "Pairing code generated for unknown sender: ${request.code}",
                    pairingCode = request.code
                )
            }
        }
    }

    /**
     * Generate a pairing code for an unknown sender.
     */
    fun generatePairingCode(chatId: String, username: String): PairingRequest {
        val code = buildString {
            repeat(CODE_LENGTH) {
                append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)])
            }
        }

        val now = System.currentTimeMillis()
        val request = PairingRequest(
            code = code,
            chatId = chatId,
            username = username,
            requestedAt = now,
            expiresAt = now + CODE_EXPIRY_MS
        )

        pendingRequests.add(request)
        persistPendingRequests()

        Log.d(TAG, "Generated pairing code $code for chat $chatId ($username)")
        return request
    }

    /**
     * Approve a pairing request by code.
     *
     * @return true if the code was found and the chat was added to the allowlist
     */
    fun approvePairing(code: String): Boolean {
        val request = pendingRequests.find { it.code.equals(code, ignoreCase = true) }
        if (request == null) {
            Log.w(TAG, "Pairing code not found: $code")
            return false
        }

        if (request.isExpired()) {
            pendingRequests.remove(request)
            persistPendingRequests()
            Log.w(TAG, "Pairing code expired: $code")
            return false
        }

        // Atomic: remove from pending + add to allowlist
        pendingRequests.remove(request)
        persistPendingRequests()

        // Add to Telegram bridge allowed chats
        addToAllowedChats(request.chatId)

        Log.d(TAG, "Pairing APPROVED: chat ${request.chatId} (${request.username}) via code $code")
        return true
    }

    /**
     * Deny a pairing request by code.
     *
     * @return true if the code was found and removed
     */
    fun denyPairing(code: String): Boolean {
        val request = pendingRequests.find { it.code.equals(code, ignoreCase = true) }
        if (request == null) {
            Log.w(TAG, "Pairing code not found for denial: $code")
            return false
        }

        pendingRequests.remove(request)
        persistPendingRequests()

        Log.d(TAG, "Pairing DENIED: chat ${request.chatId} (${request.username}) via code $code")
        return true
    }

    /** Get all non-expired pending pairing requests. */
    fun getPendingRequests(): List<PairingRequest> {
        pruneExpired()
        return pendingRequests.toList()
    }

    /** Remove expired pairing requests. */
    fun pruneExpired() {
        val before = pendingRequests.size
        pendingRequests.removeAll { it.isExpired() }
        if (pendingRequests.size != before) {
            persistPendingRequests()
            Log.d(TAG, "Pruned ${before - pendingRequests.size} expired pairing requests")
        }
    }

    // -- Persistence --

    private fun persistPendingRequests() {
        try {
            val arr = JSONArray()
            for (request in pendingRequests) {
                arr.put(request.toJson())
            }
            preferences.edit().putString(KEY_PENDING, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist pending pairing requests", e)
        }
    }

    private fun loadPendingRequests() {
        try {
            val data = preferences.getString(KEY_PENDING, null) ?: return
            val arr = JSONArray(data)
            for (i in 0 until arr.length()) {
                val request = PairingRequest.fromJson(arr.getJSONObject(i))
                if (!request.isExpired()) {
                    pendingRequests.add(request)
                }
            }
            Log.d(TAG, "Loaded ${pendingRequests.size} pending pairing requests")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pending pairing requests", e)
        }
    }

    private fun addToAllowedChats(chatId: String) {
        val telegramPrefs = context.getSharedPreferences(TELEGRAM_PREFS, Context.MODE_PRIVATE)
        val existing = HashSet(telegramPrefs.getStringSet(KEY_ALLOWED_CHATS, emptySet()) ?: emptySet())
        existing.add(chatId)
        telegramPrefs.edit().putStringSet(KEY_ALLOWED_CHATS, existing).apply()
    }

    companion object {
        private const val TAG = "DmPairingPolicy"
        private const val PREFS_NAME = "dm_pairing_policy"
        private const val KEY_MODE = "policy_mode"
        private const val KEY_PENDING = "pending_requests"

        // Telegram bridge SharedPreferences keys (must match TelegramBridgePlugin)
        private const val TELEGRAM_PREFS = "telegram_bridge_plugin"
        private const val KEY_ALLOWED_CHATS = "allowed_chats"

        const val CODE_LENGTH = 8
        const val CODE_EXPIRY_MS = 3_600_000L  // 1 hour
        const val MAX_PENDING_REQUESTS = 3

        // Unambiguous character set (no 0/O/1/I/l)
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}

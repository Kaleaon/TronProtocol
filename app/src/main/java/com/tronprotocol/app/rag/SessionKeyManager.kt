package com.tronprotocol.app.rag

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import com.tronprotocol.app.security.SecureStorage

/**
 * Session Key Manager — hierarchical session keys for RAG memory organization.
 *
 * Inspired by OpenClaw's session key architecture:
 *   agent:{agentId}:{channel}:{scope}:{identifier}
 *
 * Adapted for TronProtocol's on-device context:
 *   {aiId}:{channel}:{scope}:{identifier}
 *
 * Examples:
 *   tronprotocol_ai:telegram:dm:user123
 *   tronprotocol_ai:local:plugin:calculator
 *   tronprotocol_ai:service:heartbeat:session_20240101
 *   tronprotocol_ai:guidance:cloud:opus
 *
 * This enables:
 * - Session-scoped memory (conversations don't bleed into each other)
 * - Channel-aware retrieval (Telegram context stays separate from local)
 * - Scope-based access control (DM memories vs plugin memories)
 * - Session archival and cleanup
 * - Per-session token tracking for auto-compaction
 */
class SessionKeyManager(private val context: Context) {

    /** Parsed session key. */
    data class SessionKey(
        val aiId: String,
        val channel: Channel,
        val scope: Scope,
        val identifier: String
    ) {
        val key: String get() = "$aiId:${channel.value}:${scope.value}:$identifier"

        override fun toString(): String = key
    }

    enum class Channel(val value: String) {
        LOCAL("local"),          // On-device interactions
        TELEGRAM("telegram"),    // Telegram bridge
        SERVICE("service"),      // Background service
        GUIDANCE("guidance"),    // Cloud guidance
        WEBHOOK("webhook"),      // Communication hub webhooks
        SUBAGENT("subagent")     // Sub-agent sessions
    }

    enum class Scope(val value: String) {
        DM("dm"),               // Direct message / 1:1
        GROUP("group"),         // Group context
        PLUGIN("plugin"),       // Plugin execution context
        HEARTBEAT("heartbeat"), // Service heartbeat
        CONSOLIDATION("consolidation"), // Memory consolidation
        CLOUD("cloud"),         // Cloud API session
        SYSTEM("system")        // System-level operations
    }

    /** Session metadata. */
    data class SessionInfo(
        val sessionKey: SessionKey,
        val createdAt: Long,
        val lastActiveAt: Long,
        val chunkCount: Int,
        val totalTokens: Int,
        val archived: Boolean
    )

    // Active sessions and their metadata
    private val sessions = mutableMapOf<String, SessionMetadata>()
    private val storage: SecureStorage = SecureStorage(context)

    init {
        loadSessions()
    }

    /**
     * Create a new session key.
     */
    fun createSession(
        aiId: String,
        channel: Channel,
        scope: Scope,
        identifier: String
    ): SessionKey {
        val key = SessionKey(aiId, channel, scope, identifier)
        val keyStr = key.key

        if (keyStr !in sessions) {
            sessions[keyStr] = SessionMetadata(
                createdAt = System.currentTimeMillis(),
                lastActiveAt = System.currentTimeMillis()
            )
            persistSessions()
            Log.d(TAG, "Created session: $keyStr")
        }

        return key
    }

    /**
     * Create a session key for a sub-agent (auto-generates unique identifier).
     */
    fun createSubAgentSession(aiId: String, agentId: String): SessionKey {
        return createSession(aiId, Channel.SUBAGENT, Scope.PLUGIN, agentId)
    }

    /**
     * Create a session key for a plugin execution.
     */
    fun createPluginSession(aiId: String, pluginId: String): SessionKey {
        return createSession(aiId, Channel.LOCAL, Scope.PLUGIN, pluginId)
    }

    /**
     * Create a session key for the heartbeat loop.
     */
    fun createHeartbeatSession(aiId: String, sessionTag: String): SessionKey {
        return createSession(aiId, Channel.SERVICE, Scope.HEARTBEAT, sessionTag)
    }

    /**
     * Create a session key for guidance (cloud API).
     */
    fun createGuidanceSession(aiId: String, model: String): SessionKey {
        return createSession(aiId, Channel.GUIDANCE, Scope.CLOUD, model)
    }

    /**
     * Touch a session (update lastActiveAt).
     */
    fun touchSession(sessionKey: SessionKey) {
        sessions[sessionKey.key]?.let {
            it.lastActiveAt = System.currentTimeMillis()
            it.accessCount++
        }
    }

    /**
     * Record token usage for a session.
     */
    fun recordTokenUsage(sessionKey: SessionKey, tokens: Int) {
        sessions[sessionKey.key]?.let {
            it.totalTokens += tokens
            it.chunkCount++
        }
    }

    /**
     * Get a RAG store key scoped to a session.
     * This is used to namespace RAGStore chunks per session.
     */
    fun getRAGStoreKey(sessionKey: SessionKey): String {
        return "rag_${sessionKey.key.replace(":", "_")}"
    }

    /**
     * List all active (non-archived) sessions.
     */
    fun getActiveSessions(): List<SessionInfo> {
        return sessions.filter { !it.value.archived }.map { (key, meta) ->
            val parsed = parseKey(key) ?: return@map null
            SessionInfo(
                sessionKey = parsed,
                createdAt = meta.createdAt,
                lastActiveAt = meta.lastActiveAt,
                chunkCount = meta.chunkCount,
                totalTokens = meta.totalTokens,
                archived = meta.archived
            )
        }.filterNotNull()
    }

    /**
     * List sessions by channel.
     */
    fun getSessionsByChannel(channel: Channel): List<SessionInfo> {
        return getActiveSessions().filter { it.sessionKey.channel == channel }
    }

    /**
     * Archive old sessions (past the configurable timeout).
     */
    fun archiveExpiredSessions(maxAgeMs: Long = DEFAULT_SESSION_MAX_AGE_MS): Int {
        val now = System.currentTimeMillis()
        var archived = 0

        for ((key, meta) in sessions) {
            if (!meta.archived && now - meta.lastActiveAt > maxAgeMs) {
                meta.archived = true
                archived++
                Log.d(TAG, "Archived session: $key (inactive for ${(now - meta.lastActiveAt) / 60000}min)")
            }
        }

        if (archived > 0) {
            persistSessions()
        }

        return archived
    }

    /**
     * Clean up (delete) archived sessions.
     */
    fun cleanupArchivedSessions(): Int {
        val toRemove = sessions.filter { it.value.archived }.keys.toList()
        for (key in toRemove) {
            sessions.remove(key)
        }
        if (toRemove.isNotEmpty()) {
            persistSessions()
        }
        return toRemove.size
    }

    /**
     * Parse a string key back into a SessionKey.
     */
    fun parseKey(key: String): SessionKey? {
        val parts = key.split(":")
        if (parts.size < 4) return null

        val channel = Channel.entries.find { it.value == parts[1] } ?: return null
        val scope = Scope.entries.find { it.value == parts[2] } ?: return null

        return SessionKey(
            aiId = parts[0],
            channel = channel,
            scope = scope,
            identifier = parts.drop(3).joinToString(":")
        )
    }

    /**
     * Get aggregate statistics.
     */
    fun getStats(): Map<String, Any> {
        val all = sessions.toMap()
        return mapOf(
            "total_sessions" to all.size,
            "active_sessions" to all.count { !it.value.archived },
            "archived_sessions" to all.count { it.value.archived },
            "by_channel" to Channel.entries.associate { ch ->
                ch.value to all.count { it.key.split(":").getOrNull(1) == ch.value }
            },
            "total_tokens_tracked" to all.values.sumOf { it.totalTokens },
            "total_chunks_tracked" to all.values.sumOf { it.chunkCount }
        )
    }

    // -- Persistence --

    private fun persistSessions() {
        try {
            val json = JSONObject()
            for ((key, meta) in sessions) {
                json.put(key, JSONObject().apply {
                    put("createdAt", meta.createdAt)
                    put("lastActiveAt", meta.lastActiveAt)
                    put("chunkCount", meta.chunkCount)
                    put("totalTokens", meta.totalTokens)
                    put("accessCount", meta.accessCount)
                    put("archived", meta.archived)
                })
            }
            storage.store(STORAGE_KEY, json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist sessions", e)
        }
    }

    private fun loadSessions() {
        try {
            val data = storage.retrieve(STORAGE_KEY) ?: return
            val json = JSONObject(data)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = json.getJSONObject(key)
                sessions[key] = SessionMetadata(
                    createdAt = obj.getLong("createdAt"),
                    lastActiveAt = obj.getLong("lastActiveAt"),
                    chunkCount = obj.optInt("chunkCount", 0),
                    totalTokens = obj.optInt("totalTokens", 0),
                    accessCount = obj.optInt("accessCount", 0),
                    archived = obj.optBoolean("archived", false)
                )
            }
            Log.d(TAG, "Loaded ${sessions.size} sessions from storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions", e)
        }
    }

    /** Internal metadata for session tracking. */
    private data class SessionMetadata(
        val createdAt: Long,
        var lastActiveAt: Long = createdAt,
        var chunkCount: Int = 0,
        var totalTokens: Int = 0,
        var accessCount: Int = 0,
        var archived: Boolean = false
    )

    companion object {
        private const val TAG = "SessionKeyManager"
        private const val STORAGE_KEY = "session_keys"
        const val DEFAULT_SESSION_MAX_AGE_MS = 3_600_000L // 1 hour
    }
}

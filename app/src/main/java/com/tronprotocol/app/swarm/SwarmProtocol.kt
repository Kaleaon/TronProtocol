package com.tronprotocol.app.swarm

import org.json.JSONObject
import java.util.UUID

/**
 * Wire protocol for agent swarm communication between TronProtocol and PicoClaw nodes.
 *
 * All messages are JSON over HTTP. Each message has a type, source, target,
 * and a payload. Messages are idempotent — nodes can safely replay them.
 */
object SwarmProtocol {

    const val PROTOCOL_VERSION = "1.0.0"
    const val HEADER_SWARM_ID = "X-Swarm-Id"
    const val HEADER_NODE_ID = "X-Node-Id"
    const val HEADER_PROTOCOL_VERSION = "X-Swarm-Protocol"

    enum class MessageType {
        // Discovery & health
        PING,
        PONG,
        REGISTER,
        REGISTER_ACK,
        HEARTBEAT,
        STATUS_REQUEST,
        STATUS_RESPONSE,

        // Task dispatch
        TASK_DISPATCH,
        TASK_RESULT,
        TASK_CANCEL,

        // Inference
        INFERENCE_REQUEST,
        INFERENCE_RESPONSE,

        // Memory sync
        MEMORY_PUSH,
        MEMORY_PULL,
        MEMORY_SYNC_ACK,

        // Gateway forwarding
        GATEWAY_MESSAGE,
        GATEWAY_RELAY,

        // Skill execution
        SKILL_INVOKE,
        SKILL_RESULT,
        SKILL_LIST_REQUEST,
        SKILL_LIST_RESPONSE,

        // Voice
        VOICE_TRANSCRIBE_REQUEST,
        VOICE_TRANSCRIBE_RESPONSE,

        // Swarm coordination
        SWARM_TOPOLOGY,
        NODE_DEREGISTER
    }

    data class SwarmMessage(
        val messageId: String = UUID.randomUUID().toString(),
        val type: MessageType,
        val sourceNodeId: String,
        val targetNodeId: String?,
        val timestamp: Long = System.currentTimeMillis(),
        val payload: JSONObject = JSONObject(),
        val correlationId: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("message_id", messageId)
            put("type", type.name)
            put("source_node_id", sourceNodeId)
            put("target_node_id", targetNodeId ?: JSONObject.NULL)
            put("timestamp", timestamp)
            put("payload", payload)
            put("correlation_id", correlationId ?: JSONObject.NULL)
            put("protocol_version", PROTOCOL_VERSION)
        }

        companion object {
            fun fromJson(json: JSONObject): SwarmMessage {
                val typeStr = json.getString("type")
                val type = try {
                    MessageType.valueOf(typeStr)
                } catch (_: Exception) {
                    throw IllegalArgumentException("Unknown message type: $typeStr")
                }

                return SwarmMessage(
                    messageId = json.optString("message_id", UUID.randomUUID().toString()),
                    type = type,
                    sourceNodeId = json.getString("source_node_id"),
                    targetNodeId = json.optString("target_node_id", null),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    payload = json.optJSONObject("payload") ?: JSONObject(),
                    correlationId = json.optString("correlation_id", null)
                )
            }
        }
    }

    // ========================================================================
    // Factory methods for common messages
    // ========================================================================

    fun ping(sourceId: String): SwarmMessage = SwarmMessage(
        type = MessageType.PING,
        sourceNodeId = sourceId,
        targetNodeId = null
    )

    fun pong(sourceId: String, pingId: String): SwarmMessage = SwarmMessage(
        type = MessageType.PONG,
        sourceNodeId = sourceId,
        targetNodeId = null,
        correlationId = pingId
    )

    fun register(sourceId: String, nodeInfo: JSONObject): SwarmMessage = SwarmMessage(
        type = MessageType.REGISTER,
        sourceNodeId = sourceId,
        targetNodeId = null,
        payload = nodeInfo
    )

    fun taskDispatch(
        sourceId: String,
        targetId: String,
        taskType: String,
        taskPayload: JSONObject
    ): SwarmMessage = SwarmMessage(
        type = MessageType.TASK_DISPATCH,
        sourceNodeId = sourceId,
        targetNodeId = targetId,
        payload = JSONObject().apply {
            put("task_type", taskType)
            put("task_payload", taskPayload)
        }
    )

    fun inferenceRequest(
        sourceId: String,
        targetId: String,
        prompt: String,
        model: String? = null,
        maxTokens: Int = 600
    ): SwarmMessage = SwarmMessage(
        type = MessageType.INFERENCE_REQUEST,
        sourceNodeId = sourceId,
        targetNodeId = targetId,
        payload = JSONObject().apply {
            put("prompt", prompt)
            if (model != null) put("model", model)
            put("max_tokens", maxTokens)
        }
    )

    fun memoryPush(
        sourceId: String,
        targetId: String,
        memoryContent: String,
        relevance: Float
    ): SwarmMessage = SwarmMessage(
        type = MessageType.MEMORY_PUSH,
        sourceNodeId = sourceId,
        targetNodeId = targetId,
        payload = JSONObject().apply {
            put("content", memoryContent)
            put("relevance", relevance.toDouble())
        }
    )

    fun voiceTranscribeRequest(
        sourceId: String,
        targetId: String,
        audioUrl: String,
        language: String = "en"
    ): SwarmMessage = SwarmMessage(
        type = MessageType.VOICE_TRANSCRIBE_REQUEST,
        sourceNodeId = sourceId,
        targetNodeId = targetId,
        payload = JSONObject().apply {
            put("audio_url", audioUrl)
            put("language", language)
        }
    )

    fun skillInvoke(
        sourceId: String,
        targetId: String,
        skillName: String,
        skillArgs: String
    ): SwarmMessage = SwarmMessage(
        type = MessageType.SKILL_INVOKE,
        sourceNodeId = sourceId,
        targetNodeId = targetId,
        payload = JSONObject().apply {
            put("skill_name", skillName)
            put("args", skillArgs)
        }
    )

    fun gatewayMessage(
        sourceId: String,
        targetId: String,
        platform: String,
        chatId: String,
        text: String
    ): SwarmMessage = SwarmMessage(
        type = MessageType.GATEWAY_MESSAGE,
        sourceNodeId = sourceId,
        targetNodeId = targetId,
        payload = JSONObject().apply {
            put("platform", platform)
            put("chat_id", chatId)
            put("text", text)
        }
    )
}
